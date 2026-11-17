package io.bullseye.core.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.bullseye.common.diagnostic.DiagnosticEvent;
import io.bullseye.common.diagnostic.DiagnosticEvidence;
import io.bullseye.common.diagnostic.DiagnosticSnapshot;
import io.bullseye.common.diagnostic.ResourceState;
import io.bullseye.common.diagnostic.ResourceType;
import io.bullseye.common.diagnostic.Severity;
import io.bullseye.common.workload.WorkloadAttribution;
import io.bullseye.common.workload.WorkloadIdentity;
import io.bullseye.core.diagnostic.DiagnosticTransition;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

class BullseyeLoggerTest {

    @Test
    void rendersShortOperationalStateAnnouncement() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        BullseyeLogger logger =
                new BullseyeLogger(
                        new PrintStream(output, true, StandardCharsets.UTF_8), System.err);
        DiagnosticSnapshot previous = DiagnosticSnapshot.initial("was-01", "orders", 0);
        DiagnosticSnapshot current =
                new DiagnosticSnapshot(
                        "was-01",
                        "orders",
                        Severity.ELEVATED,
                        ResourceType.HOST_CPU,
                        ResourceState.PRESSURE,
                        10_000,
                        2);
        DiagnosticEvent event =
                new DiagnosticEvent(
                        "event-1",
                        "was-01",
                        "orders",
                        ResourceType.HOST_CPU,
                        ResourceState.PRESSURE,
                        Severity.ELEVATED,
                        10_000,
                        "Heavily loaded CPU detected. usage=74.0% sustained=10s");

        logger.transition(new DiagnosticTransition(previous, current, event));

        assertEquals(
                "[BULLSEYE] Heavily loaded CPU detected. usage=74.0% sustained=10s "
                        + "previous=NORMAL current=ELEVATED "
                        + "resource=HOST_CPU"
                        + System.lineSeparator(),
                output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void suppressesRepeatedComponentFailuresUntilRecovery() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        BullseyeLogger logger =
                new BullseyeLogger(
                        new PrintStream(output, true, StandardCharsets.UTF_8),
                        new PrintStream(error, true, StandardCharsets.UTF_8));

        logger.componentInterrupted("psi-memory", new IllegalStateException("missing"));
        logger.componentInterrupted("psi-memory", new IllegalStateException("still missing"));
        logger.componentRestored("psi-memory");

        assertEquals(1, error.toString(StandardCharsets.UTF_8).lines().count());
        assertTrue(
                output.toString(StandardCharsets.UTF_8)
                        .contains("Telemetry component restored. component=psi-memory"));
    }

    @Test
    void announcesIdentifiedMemoryPressureSource() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        BullseyeLogger logger =
                new BullseyeLogger(
                        new PrintStream(output, true, StandardCharsets.UTF_8), System.err);
        DiagnosticSnapshot previous = DiagnosticSnapshot.initial("host", "orders", 0);
        DiagnosticSnapshot current =
                new DiagnosticSnapshot(
                        "host",
                        "orders",
                        Severity.HIGH,
                        ResourceType.HOST_MEMORY,
                        ResourceState.SATURATION_RISK,
                        WorkloadAttribution.identified(
                                WorkloadIdentity.fromCgroupPath("/system.slice/order-api.service"),
                                91,
                                WorkloadAttribution.Confidence.HIGH),
                        List.of(),
                        1_000,
                        2);
        DiagnosticEvent event =
                new DiagnosticEvent(
                        "event",
                        "host",
                        "orders",
                        ResourceType.HOST_MEMORY,
                        ResourceState.SATURATION_RISK,
                        Severity.HIGH,
                        1_000,
                        "Critical memory pressure detected.");

        logger.transition(new DiagnosticTransition(previous, current, event));

        assertTrue(
                output.toString(StandardCharsets.UTF_8)
                        .contains(
                                "[BULLSEYE] Pressure source identified. resource=HOST_MEMORY "
                                        + "workload=order-api.service "
                                        + "confidence=HIGH score=91.0"));
    }

    @Test
    void announcesExplicitAttributionFailureReason() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        BullseyeLogger logger =
                new BullseyeLogger(
                        new PrintStream(output, true, StandardCharsets.UTF_8), System.err);
        DiagnosticSnapshot previous = DiagnosticSnapshot.initial("host", "orders", 0);
        DiagnosticSnapshot current =
                new DiagnosticSnapshot(
                        "host",
                        "orders",
                        Severity.HIGH,
                        ResourceType.HOST_MEMORY,
                        ResourceState.SATURATION_RISK,
                        WorkloadAttribution.unavailable(WorkloadAttribution.Status.ACCESS_DENIED),
                        List.of(),
                        1_000,
                        2);
        DiagnosticEvent event =
                new DiagnosticEvent(
                        "event",
                        "host",
                        "orders",
                        ResourceType.HOST_MEMORY,
                        ResourceState.SATURATION_RISK,
                        Severity.HIGH,
                        1_000,
                        "Critical memory pressure detected.");

        logger.transition(new DiagnosticTransition(previous, current, event));

        assertTrue(
                output.toString(StandardCharsets.UTF_8)
                        .contains(
                                "Pressure source attribution unavailable. resource=HOST_MEMORY "
                                        + "reason=ACCESS_DENIED"));
    }

    @Test
    void announcesPartialCandidateWithoutClaimingCriticalWorkloadConfidence() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        BullseyeLogger logger = logger(output);
        WorkloadAttribution attribution =
                WorkloadAttribution.identified(
                        WorkloadIdentity.fromCgroupPath("/system.slice/order-api.service"),
                        91,
                        WorkloadAttribution.Confidence.LOW,
                        List.of(),
                        new WorkloadAttribution.Coverage(2, 1, false, true),
                        1_000);

        logger.transition(transition(
                DiagnosticSnapshot.initial("host", "orders", 0), attribution));

        String logs = output.toString(StandardCharsets.UTF_8);
        assertTrue(logs.contains("Pressure source identified."));
        assertTrue(logs.contains("confidence=LOW score=91.0"));
        assertTrue(logs.contains("coverage=PARTIAL collected=2 failed=1"));
        assertTrue(logs.contains("discoveryFailed=false discoveryLimited=true"));
        assertFalse(logs.contains("Critical workload identified."));
    }

    @Test
    void includesIncompleteCoverageWhenNoCandidateCanBeSelected() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        BullseyeLogger logger = logger(output);
        WorkloadAttribution attribution =
                WorkloadAttribution.unavailable(
                        WorkloadAttribution.Status.NO_CANDIDATE,
                        new WorkloadAttribution.Coverage(0, 1, true, false),
                        1_000L);

        logger.transition(transition(
                DiagnosticSnapshot.initial("host", "orders", 0), attribution));

        String logs = output.toString(StandardCharsets.UTF_8);
        assertTrue(logs.contains("reason=NO_CANDIDATE coverage=PARTIAL collected=0 failed=1"));
        assertTrue(logs.contains("discoveryFailed=true discoveryLimited=false"));
    }

    @Test
    void doesNotReannounceSourceForEvidenceTimestampOrCoverageCountChanges() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        BullseyeLogger logger = logger(output);
        WorkloadIdentity workload =
                WorkloadIdentity.fromCgroupPath("/system.slice/order-api.service");
        WorkloadAttribution first = WorkloadAttribution.identified(
                workload, 53, WorkloadAttribution.Confidence.LOW,
                List.of(new DiagnosticEvidence("score.growth", 18, "points")),
                new WorkloadAttribution.Coverage(2, 1, false, false), 1_000);
        DiagnosticSnapshot previous = transition(
                DiagnosticSnapshot.initial("host", "orders", 0), first).current();
        WorkloadAttribution next = WorkloadAttribution.identified(
                workload, 54, WorkloadAttribution.Confidence.LOW,
                List.of(new DiagnosticEvidence("score.growth", 19, "points")),
                new WorkloadAttribution.Coverage(3, 2, false, false), 2_000);

        logger.transition(transition(previous, next));

        String logs = output.toString(StandardCharsets.UTF_8);
        assertEquals(1, logs.lines().count());
        assertFalse(logs.contains("Pressure source identified."));
        assertFalse(logs.contains("Critical workload identified."));
    }

    private static BullseyeLogger logger(ByteArrayOutputStream output) {
        return new BullseyeLogger(
                new PrintStream(output, true, StandardCharsets.UTF_8), System.err);
    }

    private static DiagnosticTransition transition(
            DiagnosticSnapshot previous, WorkloadAttribution attribution) {
        DiagnosticSnapshot current = new DiagnosticSnapshot(
                "host", "orders", Severity.CRITICAL, ResourceType.HOST_MEMORY,
                ResourceState.SATURATED, attribution, List.of(), 2_000, previous.version() + 1);
        DiagnosticEvent event = new DiagnosticEvent(
                "event", "host", "orders", ResourceType.HOST_MEMORY, ResourceState.SATURATED,
                Severity.CRITICAL, 2_000, "Host resource capacity compromised.");
        return new DiagnosticTransition(previous, current, event);
    }
}
