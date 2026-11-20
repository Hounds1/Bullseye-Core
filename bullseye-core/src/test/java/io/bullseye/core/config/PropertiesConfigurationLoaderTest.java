package io.bullseye.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    private static final String ENABLED_CCC_PROPERTIES = """
            bullseye.ccc.enabled=true
            bullseye.ccc.host=ccc.example.org
            bullseye.ccc.port=9443
            bullseye.ccc.node-id=node-01
            """;

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
                bullseye.cgroup.attribution.confidence.minimum-observations=4
                bullseye.cgroup.attribution.confidence.minimum-duration=15s
                bullseye.cgroup.attribution.confidence.maximum-confirmation-collections=5
                bullseye.cgroup.attribution.confidence.minimum-margin=7.5
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
        assertEquals(
                new BullseyeConfiguration.AttributionConfidence(4, Duration.ofSeconds(15), 5, 7.5),
                configuration.cgroup().attributionConfidence());
    }

    @Test
    void leavesCccDisabledWithoutOptIn() throws Exception {
        PropertiesConfigurationLoader loader = new PropertiesConfigurationLoader();

        var defaults = loader.load(temporaryDirectory.resolve("missing.properties"));
        var disabled = loader.load(writeConfiguration("""
                bullseye.ccc.enabled=false
                bullseye.ccc.port=unused
                """));

        assertEquals(BullseyeConfiguration.Ccc.disabled(), defaults.ccc());
        assertEquals(BullseyeConfiguration.Ccc.disabled(), disabled.ccc());
    }

    @Test
    void loadsCccEndpointAndNodeIdentityWhenEnabled() throws Exception {
        var configuration = new PropertiesConfigurationLoader()
                .load(writeConfiguration(ENABLED_CCC_PROPERTIES));

        assertEquals(
                new BullseyeConfiguration.Ccc(true, "ccc.example.org", 9443, "node-01"),
                configuration.ccc());
        assertFalse(configuration.ccc().tccEnabled());
    }

    @Test
    void optsIntoTccSnapshotForwardingWhenCccIsEnabled() throws Exception {
        var configuration = new PropertiesConfigurationLoader()
                .load(writeConfiguration(ENABLED_CCC_PROPERTIES + "bullseye.tcc.enabled=true"));

        assertEquals(
                new BullseyeConfiguration.Ccc(true, "ccc.example.org", 9443, "node-01", true),
                configuration.ccc());
    }

    @Test
    void rejectsTccSnapshotForwardingWithoutCcc() throws Exception {
        var loader = new PropertiesConfigurationLoader();
        for (String cccSetting : List.of("", "bullseye.ccc.enabled=false\n")) {
            Path path = writeConfiguration(cccSetting + "bullseye.tcc.enabled=true");

            var failure = assertThrows(IllegalArgumentException.class, () -> loader.load(path));
            assertEquals(
                    "bullseye.tcc.enabled requires bullseye.ccc.enabled=true",
                    failure.getMessage());
        }
    }

    @Test
    void rejectsMalformedTccBooleanValues() throws Exception {
        var loader = new PropertiesConfigurationLoader();
        for (String value : List.of("", "yes", "1")) {
            Path path = writeConfiguration(ENABLED_CCC_PROPERTIES + "bullseye.tcc.enabled=" + value);

            var failure = assertThrows(IllegalArgumentException.class, () -> loader.load(path));
            assertEquals("bullseye.tcc.enabled must be true or false", failure.getMessage());
        }
    }

    @Test
    void rejectsInvalidCccSettings() throws Exception {
        PropertiesConfigurationLoader loader = new PropertiesConfigurationLoader();
        for (String setting : List.of(
                "enabled=yes", "enabled=", "host=", "port=", "port=0", "port=65536",
                "port=unused", "node-id=")) {
            Path path = writeConfiguration(ENABLED_CCC_PROPERTIES + "bullseye.ccc." + setting);

            var failure = assertThrows(IllegalArgumentException.class, () -> loader.load(path));
            String key = setting.substring(0, setting.indexOf('='));
            assertTrue(failure.getMessage().contains("bullseye.ccc." + key), setting);
        }
    }

    @Test
    void defaultsAttributionRefreshAndConfidence() throws Exception {
        BullseyeConfiguration configuration = new PropertiesConfigurationLoader()
                .load(temporaryDirectory.resolve("missing.properties"));

        assertEquals(Duration.ofSeconds(30), configuration.cgroup().attributionRefreshInterval());
        assertEquals(Duration.ofSeconds(30),
                BullseyeConfiguration.Cgroup.defaults().attributionRefreshInterval());
        var confidence = new BullseyeConfiguration.AttributionConfidence(
                3, Duration.ofSeconds(10), 3, 5);
        assertEquals(confidence, configuration.cgroup().attributionConfidence());
        assertEquals(confidence, BullseyeConfiguration.AttributionConfidence.defaults());
        assertEquals(confidence, BullseyeConfiguration.Cgroup.defaults().attributionConfidence());
        assertEquals(confidence, new BullseyeConfiguration.Cgroup(
                true, Path.of("/sys/fs/cgroup"), Duration.ofSeconds(60),
                8, 512, 5, 1.2, Duration.ofSeconds(15)).attributionConfidence());
    }

    @Test
    void acceptsConfidenceBoundariesAndDisablingExtraCollections() throws Exception {
        for (double margin : List.of(0.0, 100.0)) {
            Path configurationPath = writeConfiguration("""
                    bullseye.cgroup.attribution.confidence.minimum-observations=2
                    bullseye.cgroup.attribution.confidence.minimum-duration=1ms
                    bullseye.cgroup.attribution.confidence.maximum-confirmation-collections=0
                    bullseye.cgroup.attribution.confidence.minimum-margin=%s
                    """.formatted(margin));

            var configuration = new PropertiesConfigurationLoader().load(configurationPath);

            assertEquals(new BullseyeConfiguration.AttributionConfidence(
                    2, Duration.ofMillis(1), 0, margin), configuration.cgroup().attributionConfidence());
        }
    }

    @Test
    void rejectsInvalidAttributionConfidenceProperties() throws Exception {
        for (String setting : List.of(
                "minimum-observations=1",
                "minimum-observations=-1",
                "minimum-observations=1.5",
                "minimum-duration=0ms",
                "maximum-confirmation-collections=-1",
                "minimum-margin=-0.1",
                "minimum-margin=100.1",
                "minimum-margin=NaN",
                "minimum-margin=Infinity")) {
            Path configurationPath = writeConfiguration(
                    "bullseye.cgroup.attribution.confidence." + setting);

            assertThrows(IllegalArgumentException.class,
                    () -> new PropertiesConfigurationLoader().load(configurationPath), setting);
        }
    }

    @Test
    void rejectsConfidenceDurationsThatCannotRepresentPositiveMilliseconds() {
        for (Duration duration : List.of(
                Duration.ZERO,
                Duration.ofNanos(1),
                Duration.ofMillis(-1),
                Duration.ofSeconds(Long.MAX_VALUE))) {
            assertThrows(IllegalArgumentException.class,
                    () -> new BullseyeConfiguration.AttributionConfidence(3, duration, 3, 5),
                    duration.toString());
        }
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
