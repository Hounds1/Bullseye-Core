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

class IoAttributionStateTest {

    @TempDir
    Path root;

    @Test
    void warmsUpOnNextSampleAndPublishesScoreRefreshWithoutRepeatingAnnouncements() throws Exception {
        Scenario scenario = new Scenario();
        scenario.tick(1_000, 0, 0);
        assertEquals(WorkloadAttribution.Status.INSUFFICIENT_DATA, scenario.attribution().status());

        scenario.tick(2_000, 900, 100);
        assertEquals("alpha.service", scenario.attribution().workload().name());
        assertEquals(90, scenario.attribution().score(), 0.001);
        assertEquals(WorkloadAttribution.Confidence.LOW, scenario.attribution().confidence());
        long version = scenario.repository.current().version();
        long since = scenario.repository.current().since();

        for (int second = 3; second <= 6; second++) {
            scenario.tick(second * 1_000L, (second - 1) * 900L, (second - 1) * 100L);
            assertEquals(2_000, scenario.attribution().collectedAt());
        }
        scenario.tick(7_000, 5_000, 1_000);

        assertEquals(7_000, scenario.attribution().collectedAt());
        assertEquals(82, scenario.attribution().score(), 0.001);
        assertEquals(version, scenario.repository.current().version());
        assertEquals(since, scenario.repository.current().since());
        assertEquals(1, scenario.log.toString().lines()
                .filter(line -> line.contains("Pressure source identified.")).count());
        assertFalse(scenario.attribution().evidence().isEmpty());
        String json = new DiagnosticSnapshotJsonEncoder().encode(scenario.repository.current());
        assertTrue(json.contains("\"resource\": \"DISK_IO\""));
        assertTrue(json.contains("\"attributionCollectedAt\": 7000"));
        assertTrue(json.contains("\"attributionScore\": 82.0"));
        assertTrue(json.contains("HOST_IO_PSI_SOME_AVG10"));
        assertTrue(json.contains("device.8:0.read.bytes.per-second"));
        assertTrue(json.contains("device.8:0.observed.operation.share"));
    }

    @Test
    void sustainedHighFindsReplacementOnlyWhenRefreshIsDue() throws Exception {
        Scenario scenario = new Scenario();
        scenario.tick(1_000, 0, 0);
        scenario.tick(2_000, 900, 100);
        long version = scenario.repository.current().version();

        for (int second = 3; second <= 6; second++) {
            scenario.tick(second * 1_000L, 900, 100 + (second - 2) * 900L);
            assertEquals("alpha.service", scenario.attribution().workload().name());
        }
        scenario.tick(7_000, 900, 4_600);

        assertEquals("beta.service", scenario.attribution().workload().name());
        assertEquals(version + 1, scenario.repository.current().version());
        assertEquals(Severity.HIGH, scenario.repository.current().severity());
    }

    @Test
    void failedHighCollectionRetriesAtTheIntervalThenWarmsUpOnTheNextSample() throws Exception {
        Scenario scenario = new Scenario();
        scenario.writeCounters(0, 0);
        Files.writeString(root.resolve("alpha.service/io.stat"), "broken");
        Files.writeString(root.resolve("beta.service/io.stat"), "broken");
        scenario.observe(1_000);
        assertEquals(2, scenario.attribution().coverage().failedGroups());

        for (int second = 2; second <= 5; second++) {
            scenario.tick(second * 1_000L, second * 900L, second * 100L);
            assertEquals(1_000, scenario.attribution().collectedAt());
        }
        scenario.tick(6_000, 5_400, 600);
        assertEquals(WorkloadAttribution.Status.INSUFFICIENT_DATA, scenario.attribution().status());
        scenario.tick(7_000, 6_300, 700);
        assertEquals("alpha.service", scenario.attribution().workload().name());
    }

    @Test
    void criticalReevaluatesEveryNewInputButDoesNotCollectTheSameInputTwice() throws Exception {
        Scenario scenario = new Scenario();
        scenario.io.severity = Severity.CRITICAL;
        scenario.tick(1_000, 0, 0);
        scenario.tick(2_000, 900, 100);
        long version = scenario.repository.current().version();

        scenario.writeCounters(1_000, 1_000);
        scenario.clock.now = 2_500;
        assertTrue(scenario.coordinator.evaluate(2_500).isEmpty());
        assertEquals(2_000, scenario.attribution().collectedAt());
        assertEquals("alpha.service", scenario.attribution().workload().name());

        scenario.tick(3_000, 1_000, 1_000);
        assertEquals("beta.service", scenario.attribution().workload().name());
        assertEquals(version + 1, scenario.repository.current().version());
        assertEquals(WorkloadAttribution.Confidence.LOW, scenario.attribution().confidence());
        assertFalse(scenario.log.toString().contains("Critical workload identified."));
        assertEquals(2, scenario.log.toString().lines()
                .filter(line -> line.contains("Pressure source identified.")).count());
    }

    @Test
    void staleTelemetryRetainsItsExplanationAndRequiresANewBaselineOnRecovery() throws Exception {
        Scenario scenario = new Scenario();
        scenario.tick(1_000, 0, 0);
        scenario.tick(2_000, 900, 100);

        scenario.clock.now = 5_000;
        scenario.coordinator.evaluate(5_000);
        assertEquals(DiagnosticSnapshot.ObservationStatus.STALE,
                scenario.repository.current().observations().get(ResourceType.DISK_IO).status());
        assertEquals("alpha.service", scenario.attribution().workload().name());
        assertEquals(2_000, scenario.attribution().collectedAt());

        scenario.tick(6_000, 100_000, 20_000);
        assertEquals(WorkloadAttribution.Status.INSUFFICIENT_DATA, scenario.attribution().status());
        scenario.tick(7_000, 100_100, 20_900);
        assertEquals("beta.service", scenario.attribution().workload().name());
    }

    @Test
    void missedDiagnosticTicksDoNotBridgeAGapBetweenHostIoSamples() throws Exception {
        Scenario scenario = new Scenario();
        scenario.tick(1_000, 0, 0);
        scenario.tick(2_000, 900, 100);

        scenario.tick(8_000, 100_000, 20_000);
        assertEquals(WorkloadAttribution.Status.INSUFFICIENT_DATA, scenario.attribution().status());
        scenario.tick(9_000, 100_100, 20_900);
        assertEquals("beta.service", scenario.attribution().workload().name());
    }

    @Test
    void expiredPreviouslyObservedFullPsiSuspendsAttributionUntilItReturns() throws Exception {
        Scenario scenario = new Scenario();
        scenario.io.severity = Severity.CRITICAL;
        scenario.fullPressure(1_000);
        scenario.tick(1_000, 0, 0);
        scenario.fullPressure(2_000);
        scenario.tick(2_000, 900, 100);
        long version = scenario.repository.current().version();

        scenario.tick(5_000, 3_600, 400);
        assertEquals(2_000, scenario.attribution().collectedAt());
        assertEquals(version, scenario.repository.current().version());
        assertEquals(DiagnosticSnapshot.ObservationStatus.STALE,
                scenario.repository.current().observations().get(ResourceType.DISK_IO).status());

        scenario.fullPressure(6_000);
        scenario.tick(6_000, 4_500, 500);
        assertEquals(WorkloadAttribution.Status.INSUFFICIENT_DATA, scenario.attribution().status());
        scenario.fullPressure(7_000);
        scenario.tick(7_000, 4_600, 1_400);
        assertEquals("beta.service", scenario.attribution().workload().name());
    }

    @Test
    void freshSeverityBelowHighClearsAttributionAndTheNextHighStartsANewBaseline() throws Exception {
        Scenario scenario = new Scenario();
        scenario.io.severity = Severity.ELEVATED;
        scenario.tick(1_000, 0, 0);
        assertEquals(WorkloadAttribution.Status.NOT_ATTEMPTED, scenario.attribution().status());

        scenario.io.severity = Severity.HIGH;
        scenario.tick(2_000, 900, 100);
        assertEquals(WorkloadAttribution.Status.INSUFFICIENT_DATA, scenario.attribution().status());
        scenario.tick(3_000, 1_800, 200);
        assertTrue(scenario.attribution().resolved());

        scenario.io.severity = Severity.ELEVATED;
        scenario.tick(4_000, 2_700, 300);
        assertEquals(WorkloadAttribution.Status.NOT_ATTEMPTED, scenario.attribution().status());
        scenario.io.severity = Severity.HIGH;
        scenario.tick(5_000, 3_600, 400);
        assertEquals(WorkloadAttribution.Status.INSUFFICIENT_DATA, scenario.attribution().status());
        scenario.tick(6_000, 3_700, 1_300);
        assertEquals("beta.service", scenario.attribution().workload().name());
    }

    @Test
    void ioKeepsItsOwnBaselineAndRefreshTimerWhileCpuIsPrimary() throws Exception {
        Scenario scenario = new Scenario();
        scenario.cpu.severity = Severity.CRITICAL;
        scenario.tick(1_000, 0, 0);
        scenario.tick(2_000, 900, 100);
        assertEquals(ResourceType.HOST_CPU, scenario.repository.current().resource());

        scenario.cpu.severity = Severity.NORMAL;
        scenario.tick(3_000, 900, 1_000);
        assertEquals(ResourceType.DISK_IO, scenario.repository.current().resource());
        assertEquals("alpha.service", scenario.attribution().workload().name());
        assertEquals(2_000, scenario.attribution().collectedAt());
    }

    @Test
    void recoveringIoDoesNotClearCpuOrMemoryCollectionHistory() throws Exception {
        Scenario scenario = new Scenario();
        scenario.cpu.severity = Severity.CRITICAL;
        scenario.memory.severity = Severity.HIGH;
        scenario.tick(1_000, 0, 0);
        scenario.tick(2_000, 900, 100);

        scenario.io.severity = Severity.NORMAL;
        scenario.tick(3_000, 1_800, 200);
        scenario.tick(4_000, 2_700, 300);
        assertEquals(ResourceType.HOST_CPU, scenario.repository.current().resource());
        assertTrue(scenario.attribution().resolved());
        assertEquals(75, scenario.attribution().score(), 0.001);
        assertEquals(1_000, scenario.monitor.collectMemory().previousSnapshots()
                .get("/alpha.service").collectedAt());

        scenario.cpu.severity = Severity.NORMAL;
        scenario.memory.severity = Severity.NORMAL;
        scenario.io.severity = Severity.HIGH;
        scenario.tick(5_000, 3_600, 400);
        assertEquals(WorkloadAttribution.Status.INSUFFICIENT_DATA, scenario.attribution().status());
        scenario.tick(6_000, 4_500, 500);
        assertEquals("alpha.service", scenario.attribution().workload().name());
    }

    @Test
    void partialCollectionUpdatesPublishedCoverageWithoutInventingAStateTransition() throws Exception {
        Scenario scenario = new Scenario();
        scenario.io.severity = Severity.CRITICAL;
        scenario.tick(1_000, 0, 0);
        scenario.tick(2_000, 900, 100);
        long version = scenario.repository.current().version();

        scenario.writeCounters(1_800, 200);
        Files.writeString(root.resolve("beta.service/io.stat"), "broken");
        scenario.observe(3_000);

        assertEquals("alpha.service", scenario.attribution().workload().name());
        assertEquals(WorkloadAttribution.Confidence.LOW, scenario.attribution().confidence());
        assertFalse(scenario.attribution().coverage().complete());
        assertEquals(1, scenario.attribution().coverage().failedGroups());
        assertEquals(version, scenario.repository.current().version());
        String json = new DiagnosticSnapshotJsonEncoder().encode(scenario.repository.current());
        assertTrue(json.contains("\"complete\": false"));
        assertTrue(json.contains("\"failedGroups\": 1"));

        scenario.tick(4_000, 2_700, 300);
        scenario.tick(5_000, 3_600, 400);
        assertTrue(scenario.attribution().coverage().complete());
        assertEquals(version, scenario.repository.current().version());
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
        private final MutableRule cpu = new MutableRule(ResourceType.HOST_CPU, Severity.NORMAL);
        private final MutableRule memory =
                new MutableRule(ResourceType.HOST_MEMORY, Severity.NORMAL);
        private final MutableRule io = new MutableRule(ResourceType.DISK_IO, Severity.HIGH);
        private final CgroupMonitor monitor;
        private final DiagnosticCoordinator coordinator;

        private Scenario() throws IOException {
            Files.writeString(root.resolve("cgroup.controllers"), "cpu memory io");
            for (String group : List.of("alpha.service", "beta.service")) {
                Path directory = Files.createDirectories(root.resolve(group));
                Files.writeString(directory.resolve("memory.current"),
                        group.startsWith("alpha") ? "800" : "1");
                Files.writeString(directory.resolve("io.max"),
                        "8:0 rbps=max wbps=max riops=max wiops=max\n");
                Files.writeString(directory.resolve("io.pressure"),
                        "some avg10=30.00 avg60=10.00 avg300=5.00 total=100000\n"
                                + "full avg10=10.00 avg60=5.00 avg300=1.00 total=10000\n");
            }
            monitor = new CgroupMonitor(
                    new BullseyeConfiguration.Cgroup(
                            true, root, Duration.ofSeconds(60), 8, 512, 5, 1.2),
                    CgroupMonitor.nioFileAccess(), clock, logger);
            coordinator = new DiagnosticCoordinator(
                    window,
                    new DiagnosticEngine(List.of(cpu, memory, io)),
                    repository,
                    () -> "event",
                    new MemoryPressureAttributor(monitor, 5, 1.2),
                    new CpuPressureAttributor(monitor, 5, 1.2, Duration.ofSeconds(7)),
                    new IoPressureAttributor(monitor, 5, 1.2, Duration.ofSeconds(7)),
                    Duration.ofSeconds(2),
                    Duration.ofSeconds(5));
        }

        private void tick(long now, long alpha, long beta) throws IOException {
            writeCounters(alpha, beta);
            observe(now);
        }

        private void observe(long now) throws IOException {
            clock.now = now;
            writeCpu("alpha.service", now * 3_000);
            writeCpu("beta.service", now * 100);
            window.append(new MetricSample(MetricType.HOST_CPU_USAGE, 95, now));
            window.append(new MetricSample(MetricType.HOST_CPU_LOGICAL_COUNT, 4, now));
            window.append(new MetricSample(MetricType.HOST_MEMORY_USAGE, 90, now));
            window.append(new MetricSample(MetricType.HOST_MEMORY_TOTAL_BYTES, 1_000, now));
            window.append(new MetricSample(MetricType.HOST_MEMORY_PSI_SOME_AVG10, 30, now));
            window.append(new MetricSample(MetricType.HOST_IO_PSI_SOME_AVG10, 30, now));
            coordinator.evaluate(now).ifPresent(logger::transition);
        }

        private void fullPressure(long now) {
            window.append(new MetricSample(MetricType.HOST_IO_PSI_FULL_AVG10, 10, now));
        }

        private void writeCounters(long alpha, long beta) throws IOException {
            writeIo("alpha.service", alpha);
            writeIo("beta.service", beta);
        }

        private void writeIo(String group, long operations) throws IOException {
            Files.writeString(root.resolve(group).resolve("io.stat"),
                    "8:0 rbytes=" + operations * 4_096 + " wbytes=0 rios=" + operations
                            + " wios=0 dbytes=0 dios=0\n");
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
