package io.bullseye.core.linux.psi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.bullseye.common.metric.MetricType;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

class PsiParserTest {

    @Test
    void parsesCpuSomePressure() {
        var pressure = PsiParser.parse("some avg10=12.30 avg60=5.20 avg300=1.10 total=123456");

        assertEquals(12.3, pressure.some().avg10());
        assertEquals(5.2, pressure.some().avg60());
        assertEquals(1.1, pressure.some().avg300());
        assertEquals(123456, pressure.some().total());
    }

    @Test
    void parsesMemorySomeAndFullPressure() {
        var pressure =
                PsiParser.parse(
                        """
                        some avg10=10.00 avg60=8.00 avg300=3.00 total=100
                        full avg10=2.50 avg60=1.00 avg300=0.50 total=20
                        """);

        assertEquals(10, pressure.some().avg10());
        assertEquals(2.5, pressure.full().orElseThrow().avg10());
    }

    @Test
    void parsesIoSomeAndFullInAnyOrder() {
        var pressure =
                PsiParser.parse(
                        """
                        full avg10=8.40 avg60=4.00 avg300=2.00 total=90
                        some avg10=43.10 avg60=20.00 avg300=5.00 total=300
                        """);

        assertEquals(43.1, pressure.some().avg10());
        assertEquals(8.4, pressure.full().orElseThrow().avg10());
    }

    @Test
    void rejectsMalformedLine() {
        assertThrows(
                RuntimeException.class,
                () -> PsiParser.parse("some avg10=nope avg60=1 avg300=1 total=2"));
    }

    @Test
    void acceptsMissingFullLine() {
        var pressure = PsiParser.parse("some avg10=1 avg60=2 avg300=3 total=4");

        assertFalse(pressure.full().isPresent());
    }

    @Test
    void unsupportedPsiDoesNotFailCollector() {
        LinuxPsiMetricCollector collector =
                new LinuxPsiMetricCollector(
                        path -> {
                            if (path.equals(LinuxPsiMetricCollector.CPU)) {
                                return "some avg10=3 avg60=2 avg300=1 total=5";
                            }
                            throw new IOException("unsupported");
                        },
                        Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC));

        var samples = collector.collect();

        assertEquals(1, samples.size());
        assertTrue(
                samples.stream()
                        .anyMatch(
                                sample ->
                                        sample.type() == MetricType.HOST_CPU_PSI_SOME_AVG10
                                                && sample.value() == 3
                                                && sample.timestamp() == 1_000));
    }
}
