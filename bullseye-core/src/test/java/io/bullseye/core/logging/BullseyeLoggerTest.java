package io.bullseye.core.logging;

import io.bullseye.common.DiagnosticEvent;
import io.bullseye.common.DiagnosticSnapshot;
import io.bullseye.common.ResourceState;
import io.bullseye.common.ResourceType;
import io.bullseye.common.Severity;
import io.bullseye.core.diagnostic.DiagnosticTransition;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BullseyeLoggerTest {

    @Test
    void rendersShortOperationalStateAnnouncement() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        BullseyeLogger logger = new BullseyeLogger(
                new PrintStream(output, true, StandardCharsets.UTF_8),
                System.err
        );
        DiagnosticSnapshot previous = DiagnosticSnapshot.initial("was-01", "orders", 0);
        DiagnosticSnapshot current = new DiagnosticSnapshot(
                "was-01",
                "orders",
                Severity.ELEVATED,
                ResourceType.HOST_CPU,
                ResourceState.PRESSURE,
                10_000,
                2
        );
        DiagnosticEvent event = new DiagnosticEvent(
                "event-1",
                "was-01",
                "orders",
                ResourceType.HOST_CPU,
                ResourceState.PRESSURE,
                Severity.ELEVATED,
                10_000,
                "CPU pressure detected. usage=74.0% sustained=10s"
        );

        logger.transition(new DiagnosticTransition(previous, current, event));

        assertEquals(
                "[BULLSEYE] CPU pressure detected. usage=74.0% sustained=10s "
                        + "previous=NORMAL current=ELEVATED "
                        + "resource=HOST_CPU" + System.lineSeparator(),
                output.toString(StandardCharsets.UTF_8)
        );
    }
}
