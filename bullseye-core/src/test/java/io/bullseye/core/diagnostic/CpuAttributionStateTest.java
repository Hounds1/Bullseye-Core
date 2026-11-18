package io.bullseye.core.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.bullseye.common.diagnostic.DiagnosticSnapshot;
import io.bullseye.common.diagnostic.ResourceState;
import io.bullseye.common.diagnostic.ResourceType;
import io.bullseye.common.diagnostic.Severity;
import io.bullseye.common.metric.MetricSample;
import io.bullseye.common.metric.MetricType;
import io.bullseye.common.workload.WorkloadAttribution;
import io.bullseye.core.config.BullseyeConfiguration;
import io.bullseye.core.diagnostic.rule.DiagnosticRule;
import io.bullseye.core.linux.cgroup.CgroupMonitor;
import io.bullseye.core.logging.BullseyeLogger;
import io.bullseye.core.metric.InMemoryRollingMetricWindow;
import io.bullseye.core.state.InMemoryDiagnosticStateRepository;
import io.bullseye.core.state.publish.DiagnosticSnapshotJsonEncoder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

class CpuAttributionStateTest {

    @TempDir
    Path root;

    @Test
    void warmsUpOnNextSampleAndRefreshesWithoutRepeatedSourceAnnouncements() throws Exception {
        Scenario scenario = new Scenario();
        scenario.tick(1_000, 0, 0);
        assertEquals(WorkloadAttribution.Status.INSUFFICIENT_DATA, scenario.attribution().status());

        scenario.tick(2_000, 3_000_000, 100_000);
        assertEquals("alpha.service", scenario.attribution().workload().name());
        long version = scenario.repository.current().version();
        long since = scenario.repository.current().since();

        scenario.tick(3_000, 6_000_000, 200_000);
        scenario.tick(4_000, 9_000_000, 300_000);
        scenario.tick(5_000, 12_000_000, 400_000);
        scenario.tick(6_000, 15_000_000, 500_000);
        assertEquals(2_000, scenario.attribution().collectedAt());
        scenario.tick(7_000, 17_000_000, 600_000);

        assertEquals(7_000, scenario.attribution().collectedAt());
        assertEquals(version, scenario.repository.current().version());
        assertEquals(since, scenario.repository.current().since());
        assertEquals(1, scenario.log.toString().lines()
                .filter(line -> line.contains("Pressure source identified.")).count());
        String json = new DiagnosticSnapshotJsonEncoder().encode(scenario.repository.current());
        assertTrue(json.contains("cpu.usage.cores"));
        assertTrue(json.contains("HOST_CPU_LOGICAL_COUNT"));
        assertEquals("count", scenario.repository.current().observations()
                .get(ResourceType.HOST_CPU).metrics().stream()
                .filter(metric -> metric.metric().equals("HOST_CPU_LOGICAL_COUNT"))
                .findFirst().orElseThrow().unit());
        assertTrue(json.contains("\"unit\": \"count\""));
    }

    @Test
    void sustainedHighFindsReplacementOnlyWhenRefreshIsDue() throws Exception {
        Scenario scenario = new Scenario();
        scenario.tick(1_000, 0, 0);
        scenario.tick(2_000, 3_000_000, 100_000);
        long version = scenario.repository.current().version();
        for (int second = 3; second <= 6; second++) {
            scenario.tick(second * 1_000L, 3_000_000, (second - 2) * 3_000_000L + 100_000);
            assertEquals("alpha.service", scenario.attribution().workload().name());
        }

        scenario.tick(7_000, 3_000_000, 15_100_000);
        assertEquals("beta.service", scenario.attribution().workload().name());
        assertEquals(version + 1, scenario.repository.current().version());
        assertEquals(Severity.HIGH, scenario.repository.current().severity());
    }

    @Test
    void failedHighCollectionWaitsForRetryThenWarmsUpOnTheFollowingTick() throws Exception {
        Scenario scenario = new Scenario();
        scenario.writeCounters(0, 0);
        Files.writeString(root.resolve("alpha.service/cpu.stat"), "broken");
        Files.writeString(root.resolve("beta.service/cpu.stat"), "broken");
        scenario.observe(1_000);
        assertEquals(2, scenario.attribution().coverage().failedGroups());
        for (int second = 2; second <= 5; second++) {
            scenario.tick(second * 1_000L, second * 3_000_000L, second * 100_000L);
            assertEquals(1_000, scenario.attribution().collectedAt());
        }

        scenario.tick(6_000, 18_000_000, 600_000);
        assertEquals(WorkloadAttribution.Status.INSUFFICIENT_DATA, scenario.attribution().status());
        scenario.tick(7_000, 21_000_000, 700_000);
        assertEquals("alpha.service", scenario.attribution().workload().name());
    }

    @Test
    void criticalReevaluatesOnEveryNewSampleAndChangesSource() throws Exception {
        Scenario scenario = new Scenario();
        scenario.cpu.severity = Severity.CRITICAL;
        scenario.tick(1_000, 0, 0);
        scenario.tick(2_000, 3_000_000, 100_000);
        long version = scenario.repository.current().version();

        scenario.clock.now = 2_500;
        assertTrue(scenario.coordinator.evaluate(2_500).isEmpty());
        assertEquals(2_000, scenario.attribution().collectedAt());

        scenario.tick(3_000, 3_100_000, 3_100_000);
        assertEquals("beta.service", scenario.attribution().workload().name());
        assertEquals(version + 1, scenario.repository.current().version());
        assertTrue(scenario.log.toString().contains("Critical workload identified."));
    }

    @Test
    void staleTelemetryRetainsExplanationButRequiresANewBaselineOnRecovery() throws Exception {
        Scenario scenario = new Scenario();
        scenario.tick(1_000, 0, 0);
        scenario.tick(2_000, 3_000_000, 100_000);

        scenario.clock.now = 5_000;
        scenario.coordinator.evaluate(5_000);
        assertEquals(DiagnosticSnapshot.ObservationStatus.STALE,
                scenario.repository.current().observationStatus());
        assertEquals("alpha.service", scenario.attribution().workload().name());
        assertEquals(2_000, scenario.attribution().collectedAt());

        scenario.tick(6_000, 100_000_000, 2_000_000);
        assertEquals(WorkloadAttribution.Status.INSUFFICIENT_DATA, scenario.attribution().status());
        scenario.tick(7_000, 100_100_000, 5_000_000);
        assertEquals("beta.service", scenario.attribution().workload().name());
    }

    @Test
    void lostCpuPsiSuspendsAttributionAndRestartsItsBaselineAfterReturn() throws Exception {
        Scenario scenario = new Scenario();
        scenario.cpu.severity = Severity.CRITICAL;
        scenario.window.append(new MetricSample(MetricType.HOST_CPU_PSI_SOME_AVG10, 10, 1_000));
        scenario.tick(1_000, 0, 0);
        scenario.window.append(new MetricSample(MetricType.HOST_CPU_PSI_SOME_AVG10, 10, 2_000));
        scenario.tick(2_000, 3_000_000, 100_000);
        long version = scenario.repository.current().version();

        scenario.tick(5_000, 12_000_000, 400_000);
        assertEquals(2_000, scenario.attribution().collectedAt());
        assertEquals(version, scenario.repository.current().version());
        assertEquals(DiagnosticSnapshot.ObservationStatus.STALE,
                scenario.repository.current().observationStatus());

        scenario.window.append(new MetricSample(MetricType.HOST_CPU_PSI_SOME_AVG10, 10, 6_000));
        scenario.tick(6_000, 15_000_000, 500_000);
        assertEquals(WorkloadAttribution.Status.INSUFFICIENT_DATA, scenario.attribution().status());
        scenario.window.append(new MetricSample(MetricType.HOST_CPU_PSI_SOME_AVG10, 10, 7_000));
        scenario.tick(7_000, 18_000_000, 600_000);
        assertTrue(scenario.attribution().resolved());
    }

    @Test
    void missedDiagnosticTicksAndCapacityChangesCannotReuseOldCpuIntervals() throws Exception {
        Scenario scenario = new Scenario();
        scenario.tick(1_000, 0, 0);
        scenario.tick(2_000, 3_000_000, 100_000);
        scenario.tick(8_000, 20_000_000, 200_000);
        assertEquals(WorkloadAttribution.Status.INSUFFICIENT_DATA, scenario.attribution().status());
        scenario.tick(9_000, 23_000_000, 300_000);
        assertTrue(scenario.attribution().resolved());

        scenario.hostCpuCount = 8;
        scenario.tick(10_000, 26_000_000, 400_000);
        assertEquals(WorkloadAttribution.Status.INSUFFICIENT_DATA, scenario.attribution().status());
        scenario.tick(11_000, 29_000_000, 500_000);
        assertEquals(37.5, scenario.attribution().score(), 0.001);
    }

    @Test
    void recoveringOneResourceDoesNotClearTheOthersHistoryOrRefreshTimer() throws Exception {
        Scenario scenario = new Scenario();
        scenario.cpu.severity = Severity.CRITICAL;
        scenario.memory.severity = Severity.HIGH;
        scenario.tick(1_000, 0, 0);
        scenario.tick(2_000, 3_000_000, 100_000);

        scenario.memory.severity = Severity.NORMAL;
        scenario.tick(3_000, 6_000_000, 200_000);
        assertTrue(scenario.attribution().resolved());
        assertEquals(75, scenario.attribution().score(), 0.001);

        scenario.memory.severity = Severity.HIGH;
        scenario.tick(4_000, 9_000_000, 300_000);
        scenario.cpu.severity = Severity.NORMAL;
        scenario.tick(5_000, 12_000_000, 400_000);
        assertEquals(ResourceType.HOST_MEMORY, scenario.repository.current().resource());
        assertEquals(4_000, scenario.attribution().collectedAt());

        scenario.cpu.severity = Severity.CRITICAL;
        scenario.tick(6_000, 15_000_000, 500_000);
        assertEquals(WorkloadAttribution.Status.INSUFFICIENT_DATA, scenario.attribution().status());
        scenario.tick(7_000, 18_000_000, 600_000);
        assertTrue(scenario.attribution().resolved());
    }

    @Test
    void cpuKeepsSamplingEvenWhenMemoryIsPrimary() throws Exception {
        Scenario scenario = new Scenario();
        scenario.memory.severity = Severity.CRITICAL;
        scenario.tick(1_000, 0, 0);
        scenario.tick(2_000, 3_000_000, 100_000);
        assertEquals(ResourceType.HOST_MEMORY, scenario.repository.current().resource());

        scenario.memory.severity = Severity.NORMAL;
        scenario.tick(3_000, 6_000_000, 200_000);
        assertEquals(ResourceType.HOST_CPU, scenario.repository.current().resource());
        assertEquals("alpha.service", scenario.attribution().workload().name());
        assertEquals(2_000, scenario.attribution().collectedAt());
    }

    @Test
    void partialCollectionAndRestorationChangeConfidence() throws Exception {
        Scenario scenario = new Scenario();
        scenario.cpu.severity = Severity.CRITICAL;
        scenario.tick(1_000, 0, 0);
        scenario.tick(2_000, 3_000_000, 100_000);
        assertEquals(WorkloadAttribution.Confidence.HIGH, scenario.attribution().confidence());

        scenario.writeCounters(6_000_000, 200_000);
        Files.writeString(root.resolve("beta.service/cpu.stat"), "broken");
        scenario.observe(3_000);
        assertEquals(WorkloadAttribution.Confidence.LOW, scenario.attribution().confidence());
        assertFalse(scenario.attribution().coverage().complete());
        assertTrue(scenario.log.toString().contains("coverage=PARTIAL"));

        scenario.tick(4_000, 9_000_000, 300_000);
        assertEquals(WorkloadAttribution.Confidence.LOW, scenario.attribution().confidence());
        scenario.tick(5_000, 12_000_000, 400_000);
        assertEquals(WorkloadAttribution.Confidence.HIGH, scenario.attribution().confidence());
    }

    @Test
    void missingCapacityDefersCollectionUntilRealHostCountIsAvailable() throws Exception {
        Scenario scenario = new Scenario();
        scenario.hostCpuCount = 0;
        scenario.tick(1_000, 0, 0);
        assertEquals(WorkloadAttribution.Status.NOT_ATTEMPTED, scenario.attribution().status());

        scenario.hostCpuCount = 4;
        scenario.tick(2_000, 3_000_000, 100_000);
        assertEquals(WorkloadAttribution.Status.INSUFFICIENT_DATA, scenario.attribution().status());
        scenario.tick(3_000, 6_000_000, 200_000);
        assertTrue(scenario.attribution().resolved());
    }

    private final class Scenario {
        private final MutableClock clock = new MutableClock();
        private final ByteArrayOutputStream log = new ByteArrayOutputStream();
        private final BullseyeLogger logger =
                new BullseyeLogger(new PrintStream(log), new PrintStream(log));
        private final InMemoryRollingMetricWindow window =
                new InMemoryRollingMetricWindow(Duration.ofMinutes(1), Duration.ofSeconds(1));
        private final InMemoryDiagnosticStateRepository repository =
                new InMemoryDiagnosticStateRepository(
                        DiagnosticSnapshot.initial("host", "test", 0));
        private final MutableRule cpu = new MutableRule(ResourceType.HOST_CPU, Severity.HIGH);
        private final MutableRule memory =
                new MutableRule(ResourceType.HOST_MEMORY, Severity.NORMAL);
        private final DiagnosticCoordinator coordinator;
        private int hostCpuCount = 4;

        private Scenario() throws IOException {
            Files.writeString(root.resolve("cgroup.controllers"), "cpu memory");
            for (String group : List.of("alpha.service", "beta.service")) {
                Path directory = Files.createDirectories(root.resolve(group));
                Files.writeString(directory.resolve("memory.current"),
                        group.startsWith("alpha") ? "800" : "1");
            }
            CgroupMonitor monitor = new CgroupMonitor(
                    new BullseyeConfiguration.Cgroup(
                            true, root, Duration.ofSeconds(60), 8, 512, 5, 1.2),
                    CgroupMonitor.nioFileAccess(), clock, logger);
            coordinator = new DiagnosticCoordinator(
                    window,
                    new DiagnosticEngine(List.of(cpu, memory)),
                    repository,
                    () -> "event",
                    new MemoryPressureAttributor(monitor, 5, 1.2),
                    new CpuPressureAttributor(monitor, 5, 1.2, Duration.ofSeconds(7)),
                    Duration.ofSeconds(2),
                    Duration.ofSeconds(5));
        }

        private void tick(long now, long alpha, long beta) throws IOException {
            writeCounters(alpha, beta);
            observe(now);
        }

        private void observe(long now) {
            clock.now = now;
            window.append(new MetricSample(MetricType.HOST_CPU_USAGE, 95, now));
            if (hostCpuCount > 0) {
                window.append(new MetricSample(
                        MetricType.HOST_CPU_LOGICAL_COUNT, hostCpuCount, now));
            }
            window.append(new MetricSample(MetricType.HOST_MEMORY_USAGE, 90, now));
            window.append(new MetricSample(MetricType.HOST_MEMORY_TOTAL_BYTES, 1_000, now));
            window.append(new MetricSample(MetricType.HOST_MEMORY_PSI_SOME_AVG10, 30, now));
            coordinator.evaluate(now).ifPresent(logger::transition);
        }

        private void writeCounters(long alpha, long beta) throws IOException {
            writeCpu("alpha.service", alpha);
            writeCpu("beta.service", beta);
        }

        private void writeCpu(String group, long usage) throws IOException {
            Files.writeString(root.resolve(group).resolve("cpu.stat"),
                    "usage_usec " + usage + "\nuser_usec " + usage + "\nsystem_usec 0\n");
        }

        private WorkloadAttribution attribution() {
            return repository.current().attribution();
        }
    }

    private static final class MutableRule implements DiagnosticRule {
        private final ResourceType resource;
        private Severity severity;

        private MutableRule(ResourceType resource, Severity severity) {
            this.resource = resource;
            this.severity = severity;
        }

        @Override
        public ResourceType resource() {
            return resource;
        }

        @Override
        public Optional<DiagnosticDecision> evaluate(DiagnosticContext context) {
            ResourceState state = switch (severity) {
                case NORMAL -> ResourceState.NORMAL;
                case ELEVATED -> ResourceState.PRESSURE;
                case HIGH -> ResourceState.SATURATION_RISK;
                case CRITICAL -> ResourceState.SATURATED;
            };
            return Optional.of(new DiagnosticDecision(
                    resource, state, severity, "Resource saturation detected.", List.of(), 100));
        }
    }

    private static final class MutableClock extends Clock {
        private long now;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(now);
        }
    }
}
