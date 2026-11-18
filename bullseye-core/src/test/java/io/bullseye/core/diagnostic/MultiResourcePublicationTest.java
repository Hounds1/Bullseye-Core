package io.bullseye.core.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.bullseye.common.diagnostic.DiagnosticEvidence;
import io.bullseye.common.diagnostic.DiagnosticSnapshot;
import io.bullseye.common.diagnostic.DiagnosticSnapshot.ObservationStatus;
import io.bullseye.common.diagnostic.DiagnosticSnapshot.ResourceDiagnosis;
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
import io.bullseye.core.state.publish.AtomicFileStatePublisher;
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
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

class MultiResourcePublicationTest {

    private static final List<ResourceType> RESOURCES =
            List.of(ResourceType.HOST_CPU, ResourceType.HOST_MEMORY, ResourceType.DISK_IO);

    @TempDir
    Path root;

    @Test
    void publishesThreeDiagnosesAndDistinctWorkloadsInTheSameAtomicSnapshot() throws Exception {
        Scenario scenario = new Scenario();
        scenario.activate();

        DiagnosticSnapshot snapshot = scenario.current();
        assertEquals(ResourceType.HOST_CPU, snapshot.resource());
        assertEquals(Severity.CRITICAL, snapshot.severity());
        assertEquals("alpha.service", snapshot.attribution().workload().name());
        assertEquals(Set.copyOf(RESOURCES), snapshot.resources().keySet());
        assertDiagnosis(snapshot, ResourceType.HOST_CPU, Severity.CRITICAL, "alpha.service");
        assertDiagnosis(snapshot, ResourceType.HOST_MEMORY, Severity.HIGH, "beta.service");
        assertDiagnosis(snapshot, ResourceType.DISK_IO, Severity.HIGH, "gamma.service");
        assertEquals(2_000, snapshot.diagnosis(ResourceType.HOST_CPU).since());
        assertEquals(1_000, snapshot.diagnosis(ResourceType.HOST_MEMORY).since());
        assertEquals(2_000, snapshot.diagnosis(ResourceType.DISK_IO).since());

        Path target = root.resolve("publication/state.json");
        DiagnosticSnapshotJsonEncoder encoder = new DiagnosticSnapshotJsonEncoder();
        new AtomicFileStatePublisher(target, encoder).publish(snapshot);
        String json = Files.readString(target);
        assertEquals(encoder.encode(snapshot), json);
        assertFalse(Files.exists(target.resolveSibling("state.json.tmp")));
        String resources = object(json, "resources");
        String cpu = object(resources, "HOST_CPU");
        String memory = object(resources, "HOST_MEMORY");
        String io = object(resources, "DISK_IO");
        assertTrue(cpu.contains("\"severity\": \"CRITICAL\""));
        assertTrue(cpu.contains("\"primaryWorkload\": \"alpha.service\""));
        assertTrue(cpu.contains("cpu.usage.cores"));
        assertTrue(memory.contains("\"severity\": \"HIGH\""));
        assertTrue(memory.contains("\"primaryWorkload\": \"beta.service\""));
        assertTrue(memory.contains("memory.current"));
        assertTrue(io.contains("\"primaryWorkload\": \"gamma.service\""));
        assertTrue(io.contains("\"attributionConfidence\": \"LOW\""));
        assertTrue(io.contains("device.8:0.observed.byte.share"));
        for (ResourceType resource : RESOURCES) {
            assertTrue(object(resources, resource.name()).contains("\"attributionCoverage\""));
            assertTrue(object(resources, resource.name()).contains("\"reason\""));
            assertNotNull(snapshot.observations().get(resource));
        }
    }

    @Test
    void normalResourceInitializationDoesNotCreateAnIncidentOrAdvanceVersion() throws Exception {
        Scenario scenario = new Scenario();

        assertTrue(scenario.evaluateOnly(1_000).isEmpty());

        DiagnosticSnapshot snapshot = scenario.current();
        assertEquals(1, snapshot.version());
        assertEquals(0, snapshot.since());
        assertEquals(ResourceType.UNKNOWN, snapshot.resource());
        assertEquals(ObservationStatus.INITIALIZING, snapshot.observationStatus());
        assertEquals(Set.copyOf(RESOURCES), snapshot.resources().keySet());
        for (ResourceDiagnosis diagnosis : snapshot.resources().values()) {
            assertEquals(Severity.NORMAL, diagnosis.severity());
            assertEquals(ResourceState.NORMAL, diagnosis.state());
            assertEquals(0, diagnosis.since());
            assertEquals(WorkloadAttribution.Status.NOT_ATTEMPTED, diagnosis.attribution().status());
        }
        assertEquals(0, scenario.events.get());
        assertTrue(scenario.log.toString().isEmpty());
    }

    @Test
    void secondaryEscalationAndRecoveryAdvanceVersionWithoutResettingPrimarySince() throws Exception {
        Scenario scenario = new Scenario();
        scenario.cpu.severity = Severity.CRITICAL;
        scenario.tick(1_000);
        scenario.tick(2_000);
        long version = scenario.current().version();
        long primarySince = scenario.current().since();
        scenario.log.reset();

        scenario.memory.severity = Severity.HIGH;
        DiagnosticTransition escalation = scenario.tick(3_000).orElseThrow();

        assertEquals(ResourceType.HOST_MEMORY, escalation.event().resource());
        assertEquals(ResourceType.HOST_CPU, scenario.current().resource());
        assertEquals(primarySince, scenario.current().since());
        assertEquals(version + 1, scenario.current().version());
        assertDiagnosis(scenario.current(), ResourceType.HOST_MEMORY, Severity.HIGH, "beta.service");
        assertEquals(3_000, scenario.current().diagnosis(ResourceType.HOST_MEMORY).since());
        assertTrue(scenario.log.toString().contains(
                "previous=NORMAL current=HIGH resource=HOST_MEMORY"));

        scenario.memory.severity = Severity.NORMAL;
        DiagnosticTransition recovery = scenario.tick(4_000).orElseThrow();

        assertEquals(ResourceType.HOST_MEMORY, recovery.event().resource());
        assertEquals(primarySince, scenario.current().since());
        assertEquals(version + 2, scenario.current().version());
        assertEquals(Severity.NORMAL, scenario.current().diagnosis(ResourceType.HOST_MEMORY).severity());
        assertEquals(4_000, scenario.current().diagnosis(ResourceType.HOST_MEMORY).since());
        assertEquals(WorkloadAttribution.Status.NOT_ATTEMPTED,
                scenario.current().diagnosis(ResourceType.HOST_MEMORY).attribution().status());
        assertTrue(scenario.log.toString().contains(
                "previous=HIGH current=NORMAL resource=HOST_MEMORY"));
    }

    @Test
    void periodicSecondarySourceReplacementPreservesThePrimary() throws Exception {
        // Isolate periodic refresh from the additional confidence confirmation collections.
        Scenario scenario = new Scenario(new BullseyeConfiguration.AttributionConfidence(
                3, Duration.ofSeconds(10), 0, 5));
        scenario.activate();
        DiagnosticSnapshot before = scenario.current();
        scenario.betaMemory = 10;
        scenario.gammaMemory = 800;
        scenario.log.reset();
        for (long now = 3_000; now <= 5_000; now += 1_000) {
            assertTrue(scenario.tick(now).isEmpty());
        }

        DiagnosticTransition transition = scenario.tick(6_000).orElseThrow();

        assertEquals(ResourceType.HOST_MEMORY, transition.event().resource());
        assertEquals(before.version() + 1, scenario.current().version());
        assertEquals(before.since(), scenario.current().since());
        assertEquals(before.attribution().workload(), scenario.current().attribution().workload());
        assertDiagnosis(scenario.current(), ResourceType.HOST_MEMORY, Severity.HIGH, "gamma.service");
        assertEquals(6_000, scenario.current().diagnosis(ResourceType.HOST_MEMORY).since());
        String logs = scenario.log.toString();
        assertTrue(logs.contains("resource=HOST_MEMORY workload=gamma.service"));
        assertFalse(logs.contains("resource=HOST_CPU"));
        assertFalse(logs.contains("previous=HIGH current=HIGH"));
    }

    @Test
    void confirmationCollectionPublishesSecondarySourceReplacementBeforePeriodicRefresh()
            throws Exception {
        Scenario scenario = new Scenario();
        scenario.activate();
        DiagnosticSnapshot before = scenario.current();
        scenario.betaMemory = 10;
        scenario.gammaMemory = 800;
        scenario.log.reset();

        DiagnosticTransition transition = scenario.tick(3_000).orElseThrow();

        DiagnosticSnapshot after = scenario.current();
        ResourceDiagnosis memory = after.diagnosis(ResourceType.HOST_MEMORY);
        assertEquals(ResourceType.HOST_MEMORY, transition.event().resource());
        assertEquals(ResourceType.HOST_CPU, after.resource());
        assertEquals(before.version() + 1, after.version());
        assertEquals(before.since(), after.since());
        assertTrue(before.attribution().sameSourceAs(after.attribution()));
        assertDiagnosis(after, ResourceType.HOST_MEMORY, Severity.HIGH, "gamma.service");
        assertEquals(3_000, memory.since());
        assertEquals(3_000, memory.attribution().collectedAt());
        assertEquals(WorkloadAttribution.Confidence.LOW, memory.attribution().confidence());
        String logs = scenario.log.toString();
        assertTrue(logs.contains("resource=HOST_MEMORY workload=gamma.service confidence=LOW"));
        assertFalse(logs.contains("resource=HOST_CPU"));
        assertFalse(logs.contains("previous="));
        String publishedMemory = object(
                object(new DiagnosticSnapshotJsonEncoder().encode(after), "resources"),
                "HOST_MEMORY");
        assertTrue(publishedMemory.contains("\"primaryWorkload\": \"gamma.service\""));
        assertTrue(publishedMemory.contains("\"attributionConfidence\": \"LOW\""));

        scenario.log.reset();
        assertTrue(scenario.tick(4_000).isEmpty());
        assertEquals(after.version(), scenario.current().version());
        assertTrue(scenario.log.toString().isEmpty());
    }

    @Test
    void secondaryConfidencePromotionUpdatesPublicationWithoutResettingPrimarySince()
            throws Exception {
        Scenario scenario = new Scenario();
        scenario.activate();
        for (long now = 3_000; now <= 13_000; now += 1_000) {
            scenario.tick(now);
        }
        // CPU confirms at 12s; memory's bounded burst resumes on periodic refresh at 9s and 14s.
        DiagnosticSnapshot before = scenario.current();
        assertEquals(WorkloadAttribution.Confidence.HIGH, before.attribution().confidence());
        assertEquals(WorkloadAttribution.Confidence.LOW,
                before.diagnosis(ResourceType.HOST_MEMORY).attribution().confidence());
        assertEquals(9_000,
                before.diagnosis(ResourceType.HOST_MEMORY).attribution().collectedAt());
        scenario.log.reset();

        DiagnosticTransition transition = scenario.tick(14_000).orElseThrow();

        DiagnosticSnapshot after = scenario.current();
        ResourceDiagnosis memory = after.diagnosis(ResourceType.HOST_MEMORY);
        assertEquals(ResourceType.HOST_MEMORY, transition.event().resource());
        assertEquals(ResourceType.HOST_CPU, after.resource());
        assertEquals(before.version() + 1, after.version());
        assertEquals(before.since(), after.since());
        assertTrue(before.attribution().sameSourceAs(after.attribution()));
        assertDiagnosis(after, ResourceType.HOST_MEMORY, Severity.HIGH, "beta.service");
        assertEquals(14_000, memory.since());
        assertEquals(WorkloadAttribution.Confidence.HIGH, memory.attribution().confidence());
        assertTrue(memory.attribution().evidence().contains(
                new DiagnosticEvidence("confidence.observations", 6, "count")));
        assertTrue(memory.attribution().evidence().contains(
                new DiagnosticEvidence("confidence.duration", 13, "s")));
        assertEquals(WorkloadAttribution.Confidence.LOW,
                after.diagnosis(ResourceType.DISK_IO).attribution().confidence());
        String logs = scenario.log.toString();
        assertTrue(logs.contains("resource=HOST_MEMORY workload=beta.service confidence=HIGH"));
        assertFalse(logs.contains("resource=HOST_CPU"));
        assertFalse(logs.contains("previous="));
        String publishedMemory = object(
                object(new DiagnosticSnapshotJsonEncoder().encode(after), "resources"),
                "HOST_MEMORY");
        assertTrue(publishedMemory.contains("\"attributionConfidence\": \"HIGH\""));
        assertTrue(publishedMemory.contains("confidence.observations"));
        assertTrue(publishedMemory.contains("confidence.duration"));

        scenario.log.reset();
        for (long now = 15_000; now <= 19_000; now += 1_000) {
            assertTrue(scenario.tick(now).isEmpty());
        }
        assertEquals(after.version(), scenario.current().version());
        assertEquals(memory.since(), scenario.current().diagnosis(ResourceType.HOST_MEMORY).since());
        assertEquals(19_000,
                scenario.current().diagnosis(ResourceType.HOST_MEMORY).attribution().collectedAt());
        assertTrue(scenario.log.toString().isEmpty());
    }

    @Test
    void scoreEvidenceAndCoverageRefreshOnASecondaryResourceDoNotCreateTransitions() throws Exception {
        Scenario scenario = new Scenario();
        scenario.cpu.severity = Severity.CRITICAL;
        scenario.memory.severity = Severity.HIGH;
        scenario.io.severity = Severity.CRITICAL;
        scenario.tick(1_000);
        scenario.tick(2_000);
        DiagnosticSnapshot before = scenario.current();
        scenario.io.metric = 41;
        scenario.failAlphaIo = true;
        scenario.log.reset();

        assertTrue(scenario.tick(3_000).isEmpty());

        DiagnosticSnapshot after = scenario.current();
        ResourceDiagnosis io = after.diagnosis(ResourceType.DISK_IO);
        assertEquals(before.version(), after.version());
        assertEquals(before.since(), after.since());
        assertEquals(before.diagnosis(ResourceType.DISK_IO).since(), io.since());
        assertEquals(41, io.evidence().getFirst().value());
        assertEquals("gamma.service", io.attribution().workload().name());
        assertEquals(100, io.attribution().score(), 0.001);
        assertEquals(3_000, io.attribution().collectedAt());
        assertEquals(1, io.attribution().coverage().failedGroups());
        assertEquals(WorkloadAttribution.Confidence.LOW, io.attribution().confidence());
        assertFalse(scenario.log.toString().contains("Pressure source identified."));
        assertFalse(scenario.log.toString().contains("previous="));
        String publishedIo = object(object(new DiagnosticSnapshotJsonEncoder().encode(after), "resources"),
                "DISK_IO");
        assertTrue(publishedIo.contains("\"attributionScore\": 100.0"));
        assertTrue(publishedIo.contains("\"failedGroups\": 1"));
    }

    @Test
    void staleObservationsPreserveEveryDiagnosisAndSourceWithoutAdvancingVersion() throws Exception {
        Scenario scenario = new Scenario();
        scenario.activate();
        DiagnosticSnapshot before = scenario.current();
        scenario.log.reset();

        assertTrue(scenario.evaluateOnly(5_001).isEmpty());

        DiagnosticSnapshot stale = scenario.current();
        assertEquals(before.version(), stale.version());
        assertEquals(before.since(), stale.since());
        assertEquals(before.resources(), stale.resources());
        assertEquals(ObservationStatus.STALE, stale.observationStatus());
        for (ResourceType resource : RESOURCES) {
            assertEquals(ObservationStatus.STALE, stale.observations().get(resource).status());
        }
        assertTrue(scenario.log.toString().isEmpty());
    }

    @Test
    void selectingAnotherPrimaryDoesNotRestartTheExistingResourceDiagnoses() throws Exception {
        Scenario scenario = new Scenario();
        scenario.cpu.severity = Severity.HIGH;
        scenario.memory.severity = Severity.HIGH;
        scenario.io.severity = Severity.HIGH;
        scenario.cpu.pressure = 200;
        scenario.tick(1_000);
        scenario.tick(2_000);
        DiagnosticSnapshot before = scenario.current();
        scenario.memory.pressure = 400;
        scenario.log.reset();

        DiagnosticTransition transition = scenario.tick(3_000).orElseThrow();

        DiagnosticSnapshot after = scenario.current();
        assertEquals(ResourceType.HOST_MEMORY, transition.event().resource());
        assertEquals(ResourceType.HOST_MEMORY, after.resource());
        assertEquals(before.version() + 1, after.version());
        assertEquals(3_000, after.since());
        assertEquals("beta.service", after.attribution().workload().name());
        for (ResourceType resource : RESOURCES) {
            assertEquals(before.diagnosis(resource).since(), after.diagnosis(resource).since());
        }
        assertTrue(scenario.log.toString().contains(
                "Primary resource reassigned. previous=HOST_CPU current=HOST_MEMORY"));
        assertFalse(scenario.log.toString().contains("Pressure source identified."));
    }

    @Test
    void simultaneousRecoveriesAdvanceVersionOnceAndKeepAllNormalResourceEntries() throws Exception {
        Scenario scenario = new Scenario();
        scenario.activate();
        long previousVersion = scenario.current().version();
        scenario.cpu.severity = Severity.NORMAL;
        scenario.memory.severity = Severity.NORMAL;
        scenario.io.severity = Severity.NORMAL;
        scenario.log.reset();

        scenario.tick(3_000).orElseThrow();

        DiagnosticSnapshot snapshot = scenario.current();
        assertEquals(previousVersion + 1, snapshot.version());
        assertEquals(3_000, snapshot.since());
        assertEquals(ResourceType.UNKNOWN, snapshot.resource());
        assertEquals(Severity.NORMAL, snapshot.severity());
        assertEquals(Set.copyOf(RESOURCES), snapshot.resources().keySet());
        for (ResourceType resource : RESOURCES) {
            ResourceDiagnosis diagnosis = snapshot.diagnosis(resource);
            assertEquals(Severity.NORMAL, diagnosis.severity());
            assertEquals(3_000, diagnosis.since());
            assertEquals(WorkloadAttribution.Status.NOT_ATTEMPTED, diagnosis.attribution().status());
            assertTrue(scenario.log.toString().contains("current=NORMAL resource=" + resource));
        }
        assertEquals(3, scenario.log.toString().lines().filter(line -> line.contains("previous=")).count());
    }

    @Test
    void coordinatorRestoresNonPrimaryDiagnosesAndSourcesFromAnExistingSnapshot() throws Exception {
        Scenario scenario = new Scenario();
        scenario.activate();
        DiagnosticSnapshot before = scenario.current();
        scenario.cpu.retain = true;
        scenario.memory.retain = true;
        scenario.io.retain = true;
        scenario.coordinator = scenario.coordinator();
        for (ResourceType resource : RESOURCES) {
            assertEquals(before.diagnosis(resource).severity(),
                    scenario.coordinator.resourceStates().get(resource).severity());
        }
        scenario.log.reset();

        assertTrue(scenario.evaluateOnly(5_001).isEmpty());

        DiagnosticSnapshot restored = scenario.current();
        assertEquals(before.version(), restored.version());
        assertEquals(before.since(), restored.since());
        assertEquals(before.resources(), restored.resources());
        assertTrue(scenario.log.toString().isEmpty());
    }

    private static void assertDiagnosis(
            DiagnosticSnapshot snapshot, ResourceType resource, Severity severity, String workload) {
        ResourceDiagnosis diagnosis = snapshot.resources().get(resource);
        assertNotNull(diagnosis);
        assertEquals(severity, diagnosis.severity());
        assertEquals(workload, diagnosis.attribution().workload().name());
        assertEquals(WorkloadAttribution.Status.IDENTIFIED, diagnosis.attribution().status());
        assertFalse(diagnosis.evidence().isEmpty());
        assertFalse(diagnosis.attribution().evidence().isEmpty());
    }

    private static String object(String json, String key) {
        int field = json.indexOf('"' + key + '"' + ':');
        assertTrue(field >= 0, "Missing JSON object: " + key);
        int start = json.indexOf('{', field);
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int index = start; index < json.length(); index++) {
            char character = json.charAt(index);
            if (quoted) {
                if (escaped) {
                    escaped = false;
                } else if (character == '\\') {
                    escaped = true;
                } else if (character == '"') {
                    quoted = false;
                }
            } else if (character == '"') {
                quoted = true;
            } else if (character == '{') {
                depth++;
            } else if (character == '}' && --depth == 0) {
                return json.substring(start, index + 1);
            }
        }
        throw new AssertionError("Unclosed JSON object: " + key);
    }

    private final class Scenario {
        private final MutableClock clock = new MutableClock();
        private final ByteArrayOutputStream log = new ByteArrayOutputStream();
        private final BullseyeLogger logger =
                new BullseyeLogger(new PrintStream(log), new PrintStream(log));
        private final AtomicInteger events = new AtomicInteger();
        private final InMemoryRollingMetricWindow window =
                new InMemoryRollingMetricWindow(Duration.ofMinutes(1), Duration.ofSeconds(1));
        private final InMemoryDiagnosticStateRepository repository =
                new InMemoryDiagnosticStateRepository(DiagnosticSnapshot.initial("host", "test", 0));
        private final MutableRule cpu = new MutableRule(ResourceType.HOST_CPU, 100);
        private final MutableRule memory = new MutableRule(ResourceType.HOST_MEMORY, 80);
        private final MutableRule io = new MutableRule(ResourceType.DISK_IO, 60);
        private final CgroupMonitor monitor;
        private final BullseyeConfiguration.AttributionConfidence memoryConfidence;
        private DiagnosticCoordinator coordinator;
        private long betaMemory = 800;
        private long gammaMemory = 10;
        private boolean failAlphaIo;

        private Scenario() throws IOException {
            this(BullseyeConfiguration.AttributionConfidence.defaults());
        }

        private Scenario(BullseyeConfiguration.AttributionConfidence memoryConfidence)
                throws IOException {
            this.memoryConfidence = memoryConfidence;
            Files.writeString(root.resolve("cgroup.controllers"), "cpu memory io");
            for (String group : List.of("alpha.service", "beta.service", "gamma.service")) {
                Path directory = Files.createDirectories(root.resolve(group));
                Files.writeString(directory.resolve("io.max"), "");
            }
            monitor = new CgroupMonitor(
                    new BullseyeConfiguration.Cgroup(
                            true, root, Duration.ofSeconds(60), 8, 512, 5, 1.2),
                    CgroupMonitor.nioFileAccess(), clock, logger);
            coordinator = coordinator();
        }

        private DiagnosticCoordinator coordinator() {
            return new DiagnosticCoordinator(
                    window,
                    new DiagnosticEngine(List.of(cpu, memory, io)),
                    repository,
                    () -> "event-" + events.incrementAndGet(),
                    new MemoryPressureAttributor(
                            monitor, 5, 1.2, Duration.ofSeconds(7), memoryConfidence),
                    new CpuPressureAttributor(monitor, 5, 1.2, Duration.ofSeconds(7)),
                    new IoPressureAttributor(monitor, 5, 1.2, Duration.ofSeconds(7)),
                    Duration.ofSeconds(2),
                    Duration.ofSeconds(5));
        }

        private void activate() throws IOException {
            cpu.severity = Severity.CRITICAL;
            memory.severity = Severity.HIGH;
            io.severity = Severity.HIGH;
            tick(1_000);
            tick(2_000);
        }

        private Optional<DiagnosticTransition> tick(long now) throws IOException {
            writeGroup("alpha.service", now * 3_000, 10, now / 10);
            writeGroup("beta.service", now * 100, betaMemory, 0);
            writeGroup("gamma.service", 0, gammaMemory, now * 9 / 10);
            if (failAlphaIo) {
                Files.writeString(root.resolve("alpha.service/io.stat"), "broken");
            }
            window.append(new MetricSample(MetricType.HOST_CPU_USAGE, 95, now));
            window.append(new MetricSample(MetricType.HOST_CPU_LOGICAL_COUNT, 4, now));
            window.append(new MetricSample(MetricType.HOST_MEMORY_USAGE, 90, now));
            window.append(new MetricSample(MetricType.HOST_MEMORY_TOTAL_BYTES, 1_000, now));
            window.append(new MetricSample(MetricType.HOST_MEMORY_PSI_SOME_AVG10, 30, now));
            window.append(new MetricSample(MetricType.HOST_IO_PSI_SOME_AVG10, 30, now));
            return evaluateOnly(now);
        }

        private Optional<DiagnosticTransition> evaluateOnly(long now) {
            clock.now = now;
            Optional<DiagnosticTransition> transition = coordinator.evaluate(now);
            transition.ifPresent(logger::transition);
            return transition;
        }

        private void writeGroup(String name, long cpuUsec, long memoryBytes, long ioOperations)
                throws IOException {
            Path directory = root.resolve(name);
            Files.writeString(directory.resolve("cpu.stat"),
                    "usage_usec " + cpuUsec + "\nuser_usec " + cpuUsec + "\nsystem_usec 0\n");
            Files.writeString(directory.resolve("memory.current"), Long.toString(memoryBytes));
            Files.writeString(directory.resolve("io.stat"),
                    "8:0 rbytes=" + ioOperations * 4_096 + " wbytes=0 rios=" + ioOperations + " wios=0\n");
        }

        private DiagnosticSnapshot current() {
            return repository.current();
        }
    }

    private static final class MutableRule implements DiagnosticRule {
        private final ResourceType resource;
        private Severity severity = Severity.NORMAL;
        private double pressure;
        private double metric = 30;
        private boolean retain;

        private MutableRule(ResourceType resource, double pressure) {
            this.resource = resource;
            this.pressure = pressure;
        }

        @Override
        public ResourceType resource() {
            return resource;
        }

        @Override
        public Optional<DiagnosticDecision> evaluate(DiagnosticContext context) {
            if (retain) {
                return Optional.empty();
            }
            ResourceState state = switch (severity) {
                case NORMAL -> ResourceState.NORMAL;
                case ELEVATED -> ResourceState.PRESSURE;
                case HIGH -> ResourceState.SATURATION_RISK;
                case CRITICAL -> ResourceState.SATURATED;
            };
            String reason = severity == Severity.NORMAL
                    ? "Resource pressure normalized." : "Resource saturation detected.";
            return Optional.of(new DiagnosticDecision(
                    resource, state, severity, reason,
                    List.of(new DiagnosticEvidence("observed", metric, "%")), pressure));
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
