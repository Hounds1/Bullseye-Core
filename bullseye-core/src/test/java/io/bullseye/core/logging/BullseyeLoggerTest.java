package io.bullseye.core.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.bullseye.common.diagnostic.DiagnosticEvent;
import io.bullseye.common.diagnostic.DiagnosticEvidence;
import io.bullseye.common.diagnostic.DiagnosticSnapshot;
import io.bullseye.common.diagnostic.DiagnosticSnapshot.ResourceDiagnosis;
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
import java.util.Map;

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

    @Test
    void announcesSimultaneousResourceChangesWithTheirOwnPreviousSeverities() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        DiagnosticSnapshot current = resourceSnapshot(ResourceType.HOST_CPU, 1_000, Map.of(
                ResourceType.HOST_CPU, diagnosis(Severity.CRITICAL,
                        "Host resource capacity compromised.",
                        source("worker", WorkloadAttribution.Confidence.HIGH), 1_000),
                ResourceType.HOST_MEMORY, diagnosis(Severity.HIGH,
                        "Critical memory pressure detected.",
                        source("cache", WorkloadAttribution.Confidence.MEDIUM), 1_000),
                ResourceType.DISK_IO, diagnosis(Severity.HIGH,
                        "Severe resource contention detected.",
                        source("writer", WorkloadAttribution.Confidence.LOW), 1_000)));

        logger(output).transition(resourceTransition(
                DiagnosticSnapshot.initial("host", "orders", 0), current));

        String logs = output.toString(StandardCharsets.UTF_8);
        assertEquals(6, logs.lines().count());
        assertTrue(logs.contains("previous=NORMAL current=CRITICAL resource=HOST_CPU"));
        assertTrue(logs.contains("previous=NORMAL current=HIGH resource=HOST_MEMORY"));
        assertTrue(logs.contains("previous=NORMAL current=HIGH resource=DISK_IO"));
        assertTrue(logs.contains("Critical workload identified. resource=HOST_CPU workload=worker.service"));
        assertTrue(logs.contains("Pressure source identified. resource=HOST_MEMORY workload=cache.service"));
        assertTrue(logs.contains("Pressure source identified. resource=DISK_IO workload=writer.service"));
        assertFalse(logs.contains("Primary resource reassigned."));
    }

    @Test
    void announcesSecondaryRecoveryWithoutRepeatingThePrimaryDiagnosis() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ResourceDiagnosis cpu = diagnosis(Severity.CRITICAL, "Host resource capacity compromised.",
                source("worker", WorkloadAttribution.Confidence.HIGH), 1_000);
        DiagnosticSnapshot previous = resourceSnapshot(ResourceType.HOST_CPU, 1_000, Map.of(
                ResourceType.HOST_CPU, cpu,
                ResourceType.HOST_MEMORY, diagnosis(Severity.HIGH, "Critical memory pressure detected.",
                        source("cache", WorkloadAttribution.Confidence.MEDIUM), 1_000)));
        DiagnosticSnapshot current = resourceSnapshot(ResourceType.HOST_CPU, 2_000, Map.of(
                ResourceType.HOST_CPU, cpu,
                ResourceType.HOST_MEMORY, diagnosis(Severity.NORMAL, "Memory pressure normalized.",
                        WorkloadAttribution.unresolved(), 2_000)));

        logger(output).transition(resourceTransition(previous, current));

        assertEquals("[BULLSEYE] Memory pressure normalized. previous=HIGH current=NORMAL "
                + "resource=HOST_MEMORY" + System.lineSeparator(),
                output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void announcesSecondarySourceReplacementWithoutRepeatingPressure() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ResourceDiagnosis cpu = diagnosis(Severity.CRITICAL, "Host resource capacity compromised.",
                source("worker", WorkloadAttribution.Confidence.HIGH), 1_000);
        DiagnosticSnapshot previous = resourceSnapshot(ResourceType.HOST_CPU, 1_000, Map.of(
                ResourceType.HOST_CPU, cpu,
                ResourceType.HOST_MEMORY, diagnosis(Severity.HIGH, "Critical memory pressure detected.",
                        source("alpha", WorkloadAttribution.Confidence.MEDIUM), 1_000)));
        DiagnosticSnapshot current = resourceSnapshot(ResourceType.HOST_CPU, 2_000, Map.of(
                ResourceType.HOST_CPU, cpu,
                ResourceType.HOST_MEMORY, diagnosis(Severity.HIGH, "Critical memory pressure detected.",
                        source("beta", WorkloadAttribution.Confidence.MEDIUM), 2_000)));

        logger(output).transition(resourceTransition(previous, current));

        String logs = output.toString(StandardCharsets.UTF_8);
        assertEquals(1, logs.lines().count());
        assertTrue(logs.contains("Pressure source identified. resource=HOST_MEMORY workload=beta.service"));
        assertFalse(logs.contains("Critical memory pressure detected."));
        assertFalse(logs.contains("resource=HOST_CPU"));
    }

    @Test
    void announcesSecondaryAttributionFailureWithItsOwnCoverage() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ResourceDiagnosis cpu = diagnosis(Severity.CRITICAL, "Host resource capacity compromised.",
                source("worker", WorkloadAttribution.Confidence.HIGH), 1_000);
        DiagnosticSnapshot previous = resourceSnapshot(ResourceType.HOST_CPU, 1_000, Map.of(
                ResourceType.HOST_CPU, cpu,
                ResourceType.DISK_IO, diagnosis(Severity.HIGH, "Severe resource contention detected.",
                        source("writer", WorkloadAttribution.Confidence.LOW), 1_000)));
        WorkloadAttribution unavailable = WorkloadAttribution.unavailable(
                WorkloadAttribution.Status.ACCESS_DENIED,
                new WorkloadAttribution.Coverage(0, 1, false, false), 2_000L);
        DiagnosticSnapshot current = resourceSnapshot(ResourceType.HOST_CPU, 2_000, Map.of(
                ResourceType.HOST_CPU, cpu,
                ResourceType.DISK_IO, diagnosis(Severity.HIGH, "Severe resource contention detected.",
                        unavailable, 2_000)));

        logger(output).transition(resourceTransition(previous, current));

        String logs = output.toString(StandardCharsets.UTF_8);
        assertEquals(1, logs.lines().count());
        assertTrue(logs.contains("Pressure source attribution unavailable. resource=DISK_IO "
                + "reason=ACCESS_DENIED coverage=PARTIAL collected=0 failed=1"));
    }

    @Test
    void resourceEvidenceScoresCoverageAndObservationFreshnessDoNotRepeatAnnouncements() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        WorkloadIdentity workload = WorkloadIdentity.fromCgroupPath("/system.slice/worker.service");
        WorkloadAttribution first = WorkloadAttribution.identified(
                workload, 50, WorkloadAttribution.Confidence.LOW,
                List.of(new DiagnosticEvidence("host.share", 50, "%")),
                new WorkloadAttribution.Coverage(2, 1, false, false), 1_000);
        WorkloadAttribution second = WorkloadAttribution.identified(
                workload, 60, WorkloadAttribution.Confidence.LOW,
                List.of(new DiagnosticEvidence("host.share", 60, "%")),
                new WorkloadAttribution.Coverage(3, 0, false, false), 2_000);
        DiagnosticSnapshot previous = resourceSnapshot(ResourceType.HOST_CPU, 1_000, Map.of(
                ResourceType.HOST_CPU, diagnosis(Severity.HIGH, "Heavily loaded CPU detected.", first, 1_000)));
        ResourceDiagnosis nextDiagnosis = new ResourceDiagnosis(
                Severity.HIGH, ResourceState.SATURATION_RISK,
                "Heavily loaded CPU detected. usage=89.0%",
                List.of(new DiagnosticEvidence("usage", 89, "%")), second, 1_000);
        DiagnosticSnapshot current = new DiagnosticSnapshot(
                previous.host(), previous.application(), previous.severity(), previous.resource(),
                previous.state(), second, nextDiagnosis.evidence(), previous.since(), previous.version(),
                2_000, DiagnosticSnapshot.ObservationStatus.STALE, Map.of(),
                Map.of(ResourceType.HOST_CPU, nextDiagnosis));

        logger(output).transition(resourceTransition(previous, current));

        assertEquals("", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void announcesOnlyPrimaryReassignmentWhenExistingResourceStatesDoNotChange() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Map<ResourceType, ResourceDiagnosis> resources = Map.of(
                ResourceType.HOST_CPU, diagnosis(Severity.HIGH, "Heavily loaded CPU detected.",
                        source("worker", WorkloadAttribution.Confidence.HIGH), 1_000),
                ResourceType.HOST_MEMORY, diagnosis(Severity.HIGH, "Critical memory pressure detected.",
                        source("cache", WorkloadAttribution.Confidence.MEDIUM), 1_000));
        DiagnosticSnapshot previous = resourceSnapshot(ResourceType.HOST_CPU, 1_000, resources);
        DiagnosticSnapshot current = resourceSnapshot(ResourceType.HOST_MEMORY, 2_000, resources);

        logger(output).transition(resourceTransition(previous, current));

        assertEquals("[BULLSEYE] Primary resource reassigned. previous=HOST_CPU "
                + "current=HOST_MEMORY severity=HIGH" + System.lineSeparator(),
                output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void newPrimaryEscalationDoesNotAddADuplicateReassignmentAnnouncement() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ResourceDiagnosis cpu = diagnosis(Severity.HIGH, "Heavily loaded CPU detected.",
                source("worker", WorkloadAttribution.Confidence.HIGH), 1_000);
        WorkloadAttribution memorySource = source("cache", WorkloadAttribution.Confidence.HIGH);
        DiagnosticSnapshot previous = resourceSnapshot(ResourceType.HOST_CPU, 1_000, Map.of(
                ResourceType.HOST_CPU, cpu,
                ResourceType.HOST_MEMORY, diagnosis(Severity.HIGH, "Critical memory pressure detected.",
                        memorySource, 1_000)));
        DiagnosticSnapshot current = resourceSnapshot(ResourceType.HOST_MEMORY, 2_000, Map.of(
                ResourceType.HOST_CPU, cpu,
                ResourceType.HOST_MEMORY, diagnosis(Severity.CRITICAL, "Host resource capacity compromised.",
                        memorySource, 2_000)));

        logger(output).transition(resourceTransition(previous, current));

        String logs = output.toString(StandardCharsets.UTF_8);
        assertEquals(1, logs.lines().count());
        assertTrue(logs.contains("previous=HIGH current=CRITICAL resource=HOST_MEMORY"));
        assertFalse(logs.contains("Primary resource reassigned."));
    }

    @Test
    void primaryRecoveryReassignsToTheAlreadyDiagnosedResourceWithoutReannouncingItsSource() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ResourceDiagnosis memory = diagnosis(Severity.HIGH, "Critical memory pressure detected.",
                source("cache", WorkloadAttribution.Confidence.MEDIUM), 1_000);
        DiagnosticSnapshot previous = resourceSnapshot(ResourceType.HOST_CPU, 1_000, Map.of(
                ResourceType.HOST_CPU, diagnosis(Severity.CRITICAL, "Host resource capacity compromised.",
                        source("worker", WorkloadAttribution.Confidence.HIGH), 1_000),
                ResourceType.HOST_MEMORY, memory));
        DiagnosticSnapshot current = resourceSnapshot(ResourceType.HOST_MEMORY, 2_000, Map.of(
                ResourceType.HOST_CPU, diagnosis(Severity.NORMAL, "CPU pressure normalized.",
                        WorkloadAttribution.unresolved(), 2_000),
                ResourceType.HOST_MEMORY, memory));

        logger(output).transition(resourceTransition(previous, current));

        String logs = output.toString(StandardCharsets.UTF_8);
        assertEquals(2, logs.lines().count());
        assertTrue(logs.contains("CPU pressure normalized. previous=CRITICAL current=NORMAL resource=HOST_CPU"));
        assertTrue(logs.contains("Primary resource reassigned. previous=HOST_CPU current=HOST_MEMORY severity=HIGH"));
        assertFalse(logs.contains("Pressure source identified."));
    }

    @Test
    void completeRecoveryAnnouncesEachResourceWithoutReassigningToUnknown() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        DiagnosticSnapshot previous = resourceSnapshot(ResourceType.HOST_CPU, 1_000, Map.of(
                ResourceType.HOST_CPU, diagnosis(Severity.HIGH, "Heavily loaded CPU detected.",
                        source("worker", WorkloadAttribution.Confidence.HIGH), 1_000),
                ResourceType.DISK_IO, diagnosis(Severity.HIGH, "Severe resource contention detected.",
                        source("writer", WorkloadAttribution.Confidence.LOW), 1_000)));
        DiagnosticSnapshot current = resourceSnapshot(ResourceType.UNKNOWN, 2_000, Map.of(
                ResourceType.HOST_CPU, diagnosis(Severity.NORMAL, "CPU pressure normalized.",
                        WorkloadAttribution.unresolved(), 2_000),
                ResourceType.DISK_IO, diagnosis(Severity.NORMAL, "I/O pressure normalized.",
                        WorkloadAttribution.unresolved(), 2_000)));

        logger(output).transition(resourceTransition(previous, current));

        String logs = output.toString(StandardCharsets.UTF_8);
        assertEquals(2, logs.lines().count());
        assertTrue(logs.contains("current=NORMAL resource=HOST_CPU"));
        assertTrue(logs.contains("current=NORMAL resource=DISK_IO"));
        assertFalse(logs.contains("Primary resource reassigned."));
    }

    @Test
    void materializingResourcesPreservesTheLegacyPrimaryWithoutRepeatingItsAnnouncement() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        WorkloadAttribution source = source("worker", WorkloadAttribution.Confidence.HIGH);
        DiagnosticSnapshot previous = new DiagnosticSnapshot(
                "host", "orders", Severity.CRITICAL, ResourceType.HOST_CPU,
                ResourceState.SATURATED, source, List.of(), 1_000, 2);
        DiagnosticSnapshot current = resourceSnapshot(ResourceType.HOST_CPU, 2_000, Map.of(
                ResourceType.HOST_CPU, diagnosis(Severity.CRITICAL, "Host resource capacity compromised.",
                        source, 1_000),
                ResourceType.HOST_MEMORY, diagnosis(Severity.NORMAL, "Memory condition nominal.",
                        WorkloadAttribution.unresolved(), 1_000)));

        logger(output).transition(resourceTransition(previous, current));

        assertEquals("", output.toString(StandardCharsets.UTF_8));
    }

    private static ResourceDiagnosis diagnosis(
            Severity severity, String reason, WorkloadAttribution attribution, long since) {
        ResourceState state = switch (severity) {
            case NORMAL -> ResourceState.NORMAL;
            case ELEVATED -> ResourceState.PRESSURE;
            case HIGH -> ResourceState.SATURATION_RISK;
            case CRITICAL -> ResourceState.SATURATED;
        };
        return new ResourceDiagnosis(severity, state, reason, List.of(), attribution, since);
    }

    private static WorkloadAttribution source(String name, WorkloadAttribution.Confidence confidence) {
        return WorkloadAttribution.identified(
                WorkloadIdentity.fromCgroupPath("/system.slice/" + name + ".service"), 60, confidence);
    }

    private static DiagnosticSnapshot resourceSnapshot(
            ResourceType primary, long evaluatedAt, Map<ResourceType, ResourceDiagnosis> resources) {
        ResourceDiagnosis selected = primary == ResourceType.UNKNOWN
                ? diagnosis(Severity.NORMAL, "System status nominal.",
                        WorkloadAttribution.unresolved(), evaluatedAt)
                : resources.get(primary);
        return new DiagnosticSnapshot(
                "host", "orders", selected.severity(), primary, selected.state(),
                selected.attribution(), selected.evidence(), selected.since(), evaluatedAt / 1_000 + 1,
                evaluatedAt, DiagnosticSnapshot.ObservationStatus.FRESH, Map.of(), resources);
    }

    private static DiagnosticTransition resourceTransition(
            DiagnosticSnapshot previous, DiagnosticSnapshot current) {
        DiagnosticEvent event = new DiagnosticEvent(
                "event", current.host(), current.application(), current.resource(), current.state(),
                current.severity(), current.evaluatedAt(), "Representative event only.");
        return new DiagnosticTransition(previous, current, event);
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
