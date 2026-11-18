package io.bullseye.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.bullseye.common.metric.MetricSample;
import io.bullseye.common.metric.MetricType;
import io.bullseye.core.diagnostic.MetricConditions;
import io.bullseye.core.metric.InMemoryRollingMetricWindow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

class PropertiesConfigurationLoaderTest {

    @TempDir Path temporaryDirectory;

    @Test
    void loadsConfiguredValues() throws Exception {
        Path configurationPath = temporaryDirectory.resolve("bullseye.properties");
        Files.writeString(
                configurationPath,
                """
                bullseye.host=was-01
                bullseye.application=orders
                bullseye.sampling.interval=2s
                bullseye.window.duration=10m
                bullseye.state.output=/tmp/bullseye-state.json
                bullseye.diagnostics.cpu.high.minimum-rise=3.5
                bullseye.diagnostics.memory.high.psi-some=22
                bullseye.diagnostics.io.critical.psi-full=12
                bullseye.cgroup.discovery-interval=90s
                bullseye.cgroup.maximum-groups=128
                bullseye.cgroup.attribution.refresh-interval=15s
                """);

        BullseyeConfiguration configuration =
                new PropertiesConfigurationLoader().load(configurationPath);

        assertEquals("was-01", configuration.host());
        assertEquals("orders", configuration.application());
        assertEquals(Duration.ofSeconds(2), configuration.samplingInterval());
        assertEquals(Duration.ofMinutes(10), configuration.windowDuration());
        assertEquals(3.5, configuration.cpuDiagnostics().highMinimumRise());
        assertEquals(22, configuration.memoryDiagnostics().highPsiSome());
        assertEquals(12, configuration.ioDiagnostics().criticalPsiFull());
        assertEquals(Duration.ofSeconds(90), configuration.cgroup().discoveryInterval());
        assertEquals(128, configuration.cgroup().maximumGroups());
        assertEquals(Duration.ofSeconds(15), configuration.cgroup().attributionRefreshInterval());
    }

    @Test
    void defaultsAttributionRefreshToThirtySeconds() throws Exception {
        BullseyeConfiguration configuration = new PropertiesConfigurationLoader()
                .load(temporaryDirectory.resolve("missing.properties"));

        assertEquals(Duration.ofSeconds(30), configuration.cgroup().attributionRefreshInterval());
        assertEquals(Duration.ofSeconds(30),
                BullseyeConfiguration.Cgroup.defaults().attributionRefreshInterval());
    }

    @Test
    void rejectsZeroAttributionRefreshInterval() throws Exception {
        Path configurationPath = temporaryDirectory.resolve("bullseye.properties");
        Files.writeString(configurationPath, "bullseye.cgroup.attribution.refresh-interval=0ms");

        assertThrows(IllegalArgumentException.class,
                () -> new PropertiesConfigurationLoader().load(configurationPath));
    }

    @Test
    void rejectsSubMillisecondAttributionRefreshInterval() {
        assertThrows(IllegalArgumentException.class,
                () -> new BullseyeConfiguration.Cgroup(
                        true, Path.of("/sys/fs/cgroup"), Duration.ofSeconds(60),
                        8, 512, 5, 1.2, Duration.ofNanos(1)));
    }

    @Test
    void rejectsWindowShorterThanRecoveryAndExplainsRequiredMargin() throws Exception {
        Path configurationPath = writeConfiguration("bullseye.window.duration=10s");

        var failure = assertThrows(IllegalArgumentException.class,
                () -> new PropertiesConfigurationLoader().load(configurationPath));

        assertTrue(failure.getMessage().contains("bullseye.window.duration=PT10S"));
        assertTrue(failure.getMessage().contains("must be at least PT40S"));
        assertTrue(failure.getMessage()
                .contains("bullseye.diagnostics.cpu.recovery.duration=PT30S"));
        assertTrue(failure.getMessage().contains("maximum sample gap (PT10S)"));
    }

    @Test
    void acceptsExactWindowMarginAndRejectsOneMillisecondLess() throws Exception {
        var accepted = new PropertiesConfigurationLoader()
                .load(writeConfiguration("bullseye.window.duration=40s"));
        assertEquals(Duration.ofSeconds(40), accepted.windowDuration());

        Path tooShort = writeConfiguration("bullseye.window.duration=39999ms");
        assertThrows(IllegalArgumentException.class,
                () -> new PropertiesConfigurationLoader().load(tooShort));
    }

    @Test
    void validatesEveryResourceAndEveryDecisionDuration() throws Exception {
        for (String resource : List.of("cpu", "memory", "io")) {
            for (String phase : List.of("elevated", "high", "critical", "recovery")) {
                String key = "bullseye.diagnostics." + resource + "." + phase + ".duration";
                Path configurationPath = writeConfiguration(
                        "bullseye.window.duration=40s\n" + key + "=31s");

                var failure = assertThrows(IllegalArgumentException.class,
                        () -> new PropertiesConfigurationLoader().load(configurationPath), key);

                assertTrue(failure.getMessage().contains(key + "=PT31S"), key);
                assertTrue(failure.getMessage().contains("must be at least PT41S"), key);
            }
        }
    }

    @Test
    void validatedWindowRetainsTheBoundarySampleAcrossAllowedJitter() throws Exception {
        var configuration = new PropertiesConfigurationLoader()
                .load(writeConfiguration("bullseye.window.duration=40s"));
        var window = new InMemoryRollingMetricWindow(
                configuration.windowDuration(), configuration.samplingInterval());
        for (long timestamp = 0; timestamp <= 39_996; timestamp += 9_999) {
            window.append(new MetricSample(MetricType.HOST_CPU_USAGE, 20, timestamp));
        }

        assertTrue(MetricConditions.sustained(
                window,
                MetricType.HOST_CPU_USAGE,
                39_996,
                configuration.cpuDiagnostics().recoveryDuration(),
                configuration.samplingInterval().multipliedBy(2),
                value -> value <= configuration.cpuDiagnostics().recoveryThreshold()));
    }

    @Test
    void permitsDecisionDurationsShorterThanTheSamplingInterval() throws Exception {
        var properties = new StringBuilder("bullseye.window.duration=10001ms\n");
        for (String resource : List.of("cpu", "memory", "io")) {
            for (String phase : List.of("elevated", "high", "critical", "recovery")) {
                properties.append("bullseye.diagnostics.").append(resource).append('.')
                        .append(phase).append(".duration=1ms\n");
            }
        }

        var configuration = new PropertiesConfigurationLoader()
                .load(writeConfiguration(properties.toString()));

        assertEquals(Duration.ofMillis(1), configuration.cpuDiagnostics().highDuration());
        assertEquals(Duration.ofSeconds(5), configuration.samplingInterval());
    }

    @Test
    void rejectsSubMillisecondSamplingAndDiagnosticDurations() {
        var cpu = cpuDiagnostics(Duration.ofSeconds(10));
        assertThrows(IllegalArgumentException.class,
                () -> new BullseyeConfiguration(
                        "host", "test", Duration.ofNanos(1), Duration.ofMinutes(1),
                        Path.of("state.json"), cpu));
        assertThrows(IllegalArgumentException.class,
                () -> cpuDiagnostics(Duration.ofNanos(1)));
    }

    @Test
    void rejectsDurationsThatCannotBeRepresentedInMilliseconds() {
        assertThrows(IllegalArgumentException.class,
                () -> cpuDiagnostics(Duration.ofSeconds(Long.MAX_VALUE)));
    }

    @Test
    void rejectsUnsupportedDuration() {
        assertThrows(
                IllegalArgumentException.class,
                () -> PropertiesConfigurationLoader.parseDuration("five seconds"));
    }

    private Path writeConfiguration(String properties) throws Exception {
        Path configurationPath = temporaryDirectory.resolve("bullseye.properties");
        Files.writeString(configurationPath, properties);
        return configurationPath;
    }

    private static BullseyeConfiguration.CpuDiagnostics cpuDiagnostics(Duration highDuration) {
        return new BullseyeConfiguration.CpuDiagnostics(
                70, Duration.ofSeconds(10), 80, highDuration, 0,
                90, Duration.ofSeconds(5), 65, Duration.ofSeconds(30));
    }
}
