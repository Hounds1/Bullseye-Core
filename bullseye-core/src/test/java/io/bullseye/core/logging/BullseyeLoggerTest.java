package io.bullseye.core.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.bullseye.common.diagnostic.DiagnosticEvent;
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
                                        + "workload=order-api.service confidence=HIGH score=91.0"));
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
}
