package io.bullseye.core.diagnostic;

import io.bullseye.common.diagnostic.DiagnosticEvent;
import io.bullseye.common.diagnostic.DiagnosticEvidence;
import io.bullseye.common.diagnostic.DiagnosticSnapshot;
import io.bullseye.common.diagnostic.DiagnosticSnapshot.Observation;
import io.bullseye.common.diagnostic.DiagnosticSnapshot.ObservationStatus;
import io.bullseye.common.diagnostic.ResourceState;
import io.bullseye.common.diagnostic.ResourceType;
import io.bullseye.common.diagnostic.Severity;
import io.bullseye.common.metric.MetricSample;
import io.bullseye.common.metric.MetricType;
import io.bullseye.common.workload.WorkloadAttribution;
import io.bullseye.core.metric.MetricWindow;
import io.bullseye.core.state.DiagnosticStateRepository;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public final class DiagnosticCoordinator {

    private final MetricWindow metricWindow;
    private final DiagnosticEngine engine;
    private final DiagnosticStateRepository stateRepository;
    private final Supplier<String> eventIdSupplier;
    private final MemoryPressureAttributor memoryAttributor;
    private final CpuPressureAttributor cpuAttributor;
    private final Duration maximumSampleAge;
    private final long attributionRefreshMillis;
    private final long startedAt;
    private final EnumMap<ResourceType, DiagnosticDecision> resourceStates =
            new EnumMap<>(ResourceType.class);
    private WorkloadAttribution memoryAttribution = WorkloadAttribution.unresolved();
    private WorkloadAttribution cpuAttribution = WorkloadAttribution.unresolved();
    private Long lastMemoryAttributionAt;
    private Long lastCpuAttributionAt;
    private Long lastCpuInputAt;
    private int previousHostCpuCount;

    public DiagnosticCoordinator(
            MetricWindow metricWindow,
            DiagnosticEngine engine,
            DiagnosticStateRepository stateRepository,
            Supplier<String> eventIdSupplier) {
        this(metricWindow, engine, stateRepository, eventIdSupplier, null);
    }

    public DiagnosticCoordinator(
            MetricWindow metricWindow,
            DiagnosticEngine engine,
            DiagnosticStateRepository stateRepository,
            Supplier<String> eventIdSupplier,
            MemoryPressureAttributor memoryAttributor) {
        this(
                metricWindow,
                engine,
                stateRepository,
                eventIdSupplier,
                memoryAttributor,
                Duration.ofSeconds(10));
    }

    public DiagnosticCoordinator(
            MetricWindow metricWindow,
            DiagnosticEngine engine,
            DiagnosticStateRepository stateRepository,
            Supplier<String> eventIdSupplier,
            MemoryPressureAttributor memoryAttributor,
            Duration maximumSampleAge) {
        this(
                metricWindow,
                engine,
                stateRepository,
                eventIdSupplier,
                memoryAttributor,
                maximumSampleAge,
                Duration.ofSeconds(30));
    }

    public DiagnosticCoordinator(
            MetricWindow metricWindow,
            DiagnosticEngine engine,
            DiagnosticStateRepository stateRepository,
            Supplier<String> eventIdSupplier,
            MemoryPressureAttributor memoryAttributor,
            Duration maximumSampleAge,
            Duration attributionRefreshInterval) {
        this(
                metricWindow,
                engine,
                stateRepository,
                eventIdSupplier,
                memoryAttributor,
                null,
                maximumSampleAge,
                attributionRefreshInterval);
    }

    public DiagnosticCoordinator(
            MetricWindow metricWindow,
            DiagnosticEngine engine,
            DiagnosticStateRepository stateRepository,
            Supplier<String> eventIdSupplier,
            MemoryPressureAttributor memoryAttributor,
            CpuPressureAttributor cpuAttributor,
            Duration maximumSampleAge,
            Duration attributionRefreshInterval) {
        this.metricWindow = Objects.requireNonNull(metricWindow, "metricWindow");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.stateRepository = Objects.requireNonNull(stateRepository, "stateRepository");
        this.eventIdSupplier = Objects.requireNonNull(eventIdSupplier, "eventIdSupplier");
        this.memoryAttributor = memoryAttributor;
        this.cpuAttributor = cpuAttributor;
        this.maximumSampleAge = Objects.requireNonNull(maximumSampleAge, "maximumSampleAge");
        if (maximumSampleAge.toMillis() < 1) {
            throw new IllegalArgumentException("maximumSampleAge must be positive");
        }
        Objects.requireNonNull(attributionRefreshInterval, "attributionRefreshInterval");
        this.attributionRefreshMillis = attributionRefreshInterval.toMillis();
        if (attributionRefreshMillis < 1) {
            throw new IllegalArgumentException("attributionRefreshInterval must be at least 1ms");
        }
        DiagnosticSnapshot current = stateRepository.current();
        this.startedAt = current.evaluatedAt();
        if (current.resource() != ResourceType.UNKNOWN) {
            resourceStates.put(
                    current.resource(),
                    new DiagnosticDecision(
                            current.resource(),
                            current.state(),
                            current.severity(),
                            "Diagnostic state retained.",
                            current.evidence(),
                            0));
        }
        if (current.resource() == ResourceType.HOST_MEMORY) {
            memoryAttribution = current.attribution();
        } else if (current.resource() == ResourceType.HOST_CPU) {
            cpuAttribution = current.attribution();
        }
    }

    public synchronized Optional<DiagnosticTransition> evaluate(long evaluatedAt) {
        DiagnosticSnapshot previous = stateRepository.current();
        if (evaluatedAt < previous.evaluatedAt()) {
            throw new IllegalArgumentException("evaluation time must not move backwards");
        }
        DiagnosticDecision previousMemory = memoryState(resourceStates);
        DiagnosticDecision previousCpu = cpuState(resourceStates);
        DiagnosticEngine.Evaluation evaluation =
                engine.evaluate(
                        new DiagnosticContext(
                                metricWindow, previous, Map.copyOf(resourceStates), evaluatedAt));
        DiagnosticDecision currentMemory = memoryState(evaluation.resourceStates());
        DiagnosticDecision currentCpu = cpuState(evaluation.resourceStates());
        resourceStates.clear();
        resourceStates.putAll(evaluation.resourceStates());

        DiagnosticDecision primary = evaluation.primary();
        ResourceType snapshotResource =
                primary.severity() == Severity.NORMAL ? ResourceType.UNKNOWN : primary.resource();
        ResourceState snapshotState =
                primary.severity() == Severity.NORMAL ? ResourceState.NORMAL : primary.state();
        updateMemoryAttribution(previousMemory, currentMemory, evaluatedAt);
        updateCpuAttribution(previousCpu, currentCpu, evaluatedAt);
        WorkloadAttribution attribution = switch (snapshotResource) {
            case HOST_MEMORY -> memoryAttribution;
            case HOST_CPU -> cpuAttribution;
            default -> WorkloadAttribution.unresolved();
        };

        boolean changed =
                !sameState(previous, primary, snapshotResource, snapshotState, attribution);
        Map<ResourceType, Observation> observations = observations(evaluatedAt);

        DiagnosticSnapshot current =
                new DiagnosticSnapshot(
                        previous.host(),
                        previous.application(),
                        primary.severity(),
                        snapshotResource,
                        snapshotState,
                        attribution,
                        primary.evidence(),
                        changed ? evaluatedAt : previous.since(),
                        changed ? previous.version() + 1 : previous.version(),
                        evaluatedAt,
                        observationStatus(observations),
                        observations);
        stateRepository.update(current);
        if (!changed) {
            return Optional.empty();
        }
        DiagnosticEvent event =
                new DiagnosticEvent(
                        eventIdSupplier.get(),
                        current.host(),
                        current.application(),
                        primary.resource(),
                        primary.state(),
                        primary.severity(),
                        evaluatedAt,
                        primary.reason());
        return Optional.of(new DiagnosticTransition(previous, current, event));
    }

    public synchronized Map<ResourceType, DiagnosticDecision> resourceStates() {
        return Map.copyOf(resourceStates);
    }

    private Map<ResourceType, Observation> observations(long now) {
        EnumMap<ResourceType, Observation> result = new EnumMap<>(ResourceType.class);
        for (ResourceType resource : engine.resources()) {
            result.put(resource, observe(resource, now));
        }
        return result;
    }

    private Observation observe(ResourceType resource, long now) {
        List<MetricType> required = requiredMetrics(resource);
        List<MetricType> optional = optionalMetrics(resource);
        List<DiagnosticEvidence> metrics = new ArrayList<>();
        Long lastSampleAt = null;
        boolean missing = required.isEmpty();
        boolean expired = false;
        for (MetricType type : MetricType.values()) {
            if (!required.contains(type) && !optional.contains(type)) {
                continue;
            }
            List<MetricSample> samples = metricWindow.get(type);
            if (samples.isEmpty()) {
                missing |= required.contains(type);
                continue;
            }
            MetricSample sample = samples.getLast();
            boolean fresh =
                    sample.timestamp() <= now
                            && now - sample.timestamp() <= maximumSampleAge.toMillis();
            expired |= !fresh;
            lastSampleAt =
                    lastSampleAt == null
                            ? sample.timestamp()
                            : Math.min(lastSampleAt, sample.timestamp());
            // Expired values are deliberately omitted from current telemetry.
            if (fresh) {
                String unit = type == MetricType.HOST_CPU_LOGICAL_COUNT ? "count" : "%";
                metrics.add(new DiagnosticEvidence(type.name(), sample.value(), unit));
            }
        }
        ObservationStatus status;
        if (!missing && !expired) {
            status = ObservationStatus.FRESH;
        } else if (!expired && now - startedAt <= maximumSampleAge.toMillis()) {
            status = ObservationStatus.INITIALIZING;
        } else {
            status = ObservationStatus.STALE;
        }
        return new Observation(status, missing ? null : lastSampleAt, metrics);
    }

    private static List<MetricType> requiredMetrics(ResourceType resource) {
        return switch (resource) {
            case HOST_CPU -> List.of(MetricType.HOST_CPU_USAGE);
            case HOST_MEMORY ->
                    List.of(MetricType.HOST_MEMORY_USAGE, MetricType.HOST_MEMORY_PSI_SOME_AVG10);
            case DISK_IO -> List.of(MetricType.HOST_IO_PSI_SOME_AVG10);
            default -> List.of();
        };
    }

    private static List<MetricType> optionalMetrics(ResourceType resource) {
        return switch (resource) {
            case HOST_CPU ->
                    List.of(MetricType.HOST_CPU_PSI_SOME_AVG10, MetricType.HOST_CPU_LOGICAL_COUNT);
            case HOST_MEMORY -> List.of(MetricType.HOST_MEMORY_PSI_FULL_AVG10);
            case DISK_IO -> List.of(MetricType.HOST_IO_PSI_FULL_AVG10);
            default -> List.of();
        };
    }

    private static ObservationStatus observationStatus(
            Map<ResourceType, Observation> observations) {
        if (observations.values().stream()
                .anyMatch(observation -> observation.status() == ObservationStatus.STALE)) {
            return ObservationStatus.STALE;
        }
        if (observations.isEmpty()
                || observations.values().stream()
                        .anyMatch(o -> o.status() == ObservationStatus.INITIALIZING)) {
            return ObservationStatus.INITIALIZING;
        }
        return ObservationStatus.FRESH;
    }

    private void updateMemoryAttribution(
            DiagnosticDecision previousMemory,
            DiagnosticDecision currentMemory,
            long evaluatedAt) {
        if (memoryAttributor == null
                || currentMemory.severity().ordinal() < Severity.HIGH.ordinal()) {
            if (memoryAttributor != null
                    && previousMemory.severity().ordinal() >= Severity.HIGH.ordinal()) {
                memoryAttributor.reset();
            }
            memoryAttribution = WorkloadAttribution.unresolved();
            lastMemoryAttributionAt = null;
            return;
        }

        boolean highEntry =
                currentMemory.severity() == Severity.HIGH
                        && previousMemory.severity() != Severity.HIGH;
        boolean critical = currentMemory.severity() == Severity.CRITICAL;
        boolean refreshDue =
                lastMemoryAttributionAt == null
                        || evaluatedAt - lastMemoryAttributionAt >= attributionRefreshMillis;
        boolean fresh =
                requiredMetrics(ResourceType.HOST_MEMORY).stream()
                        .allMatch(
                                type -> MetricConditions.latest(
                                                metricWindow, type, evaluatedAt, maximumSampleAge)
                                        .isPresent())
                        && !MetricConditions.hasExpired(
                                metricWindow,
                                MetricType.HOST_MEMORY_PSI_FULL_AVG10,
                                evaluatedAt,
                                maximumSampleAge);
        if ((highEntry || critical || refreshDue) && fresh) {
            lastMemoryAttributionAt = evaluatedAt;
            memoryAttribution = memoryAttributor.attribute(latestHostMemoryTotal(evaluatedAt));
        }
    }

    private void updateCpuAttribution(
            DiagnosticDecision previousCpu,
            DiagnosticDecision currentCpu,
            long evaluatedAt) {
        if (cpuAttributor == null || currentCpu.severity().ordinal() < Severity.HIGH.ordinal()) {
            if (cpuAttributor != null
                    && previousCpu.severity().ordinal() >= Severity.HIGH.ordinal()) {
                resetCpuHistory();
            }
            cpuAttribution = WorkloadAttribution.unresolved();
            return;
        }

        Optional<MetricSample> usage = MetricConditions.latest(
                metricWindow, MetricType.HOST_CPU_USAGE, evaluatedAt, maximumSampleAge);
        int hostCpuCount = latestHostCpuCount(evaluatedAt);
        if (usage.isEmpty()
                || hostCpuCount == 0
                || MetricConditions.hasExpired(
                        metricWindow,
                        MetricType.HOST_CPU_PSI_SOME_AVG10,
                        evaluatedAt,
                        maximumSampleAge)) {
            // Keep the last explanation while telemetry is stale, but never bridge the gap.
            if (lastCpuInputAt != null) {
                resetCpuHistory();
            }
            return;
        }
        long inputAt = usage.orElseThrow().timestamp();
        boolean inputGap =
                lastCpuInputAt != null
                        && inputAt - lastCpuInputAt > maximumSampleAge.toMillis();
        boolean capacityChanged = previousHostCpuCount != 0 && previousHostCpuCount != hostCpuCount;
        if (inputGap || capacityChanged) {
            resetCpuHistory();
        }
        boolean newInput = lastCpuInputAt == null || inputAt > lastCpuInputAt;
        lastCpuInputAt = inputAt;
        previousHostCpuCount = hostCpuCount;

        boolean highEntry = currentCpu.severity() == Severity.HIGH
                && previousCpu.severity() != Severity.HIGH;
        boolean critical = currentCpu.severity() == Severity.CRITICAL;
        boolean refreshDue = lastCpuAttributionAt == null
                || evaluatedAt - lastCpuAttributionAt >= attributionRefreshMillis;
        boolean baselineNeeded =
                cpuAttribution.status() == WorkloadAttribution.Status.INSUFFICIENT_DATA;
        if (newInput && (highEntry || critical || refreshDue || baselineNeeded)) {
            lastCpuAttributionAt = evaluatedAt;
            cpuAttribution = cpuAttributor.attribute(hostCpuCount);
        }
    }

    private void resetCpuHistory() {
        cpuAttributor.reset();
        lastCpuAttributionAt = null;
        lastCpuInputAt = null;
        previousHostCpuCount = 0;
    }

    private int latestHostCpuCount(long evaluatedAt) {
        return MetricConditions.latest(
                        metricWindow,
                        MetricType.HOST_CPU_LOGICAL_COUNT,
                        evaluatedAt,
                        maximumSampleAge)
                .filter(sample -> sample.value() >= 1
                        && sample.value() <= Integer.MAX_VALUE
                        && sample.value() == Math.rint(sample.value()))
                .map(sample -> (int) sample.value())
                .orElse(0);
    }

    private long latestHostMemoryTotal(long evaluatedAt) {
        return MetricConditions.latest(
                        metricWindow,
                        MetricType.HOST_MEMORY_TOTAL_BYTES,
                        evaluatedAt,
                        maximumSampleAge)
                .map(sample -> (long) sample.value())
                .orElse(-1L);
    }

    private static DiagnosticDecision memoryState(Map<ResourceType, DiagnosticDecision> states) {
        return states.getOrDefault(
                ResourceType.HOST_MEMORY,
                DiagnosticDecision.normal(ResourceType.HOST_MEMORY, "Memory condition nominal."));
    }

    private static DiagnosticDecision cpuState(Map<ResourceType, DiagnosticDecision> states) {
        return states.getOrDefault(
                ResourceType.HOST_CPU,
                DiagnosticDecision.normal(ResourceType.HOST_CPU, "CPU condition nominal."));
    }

    private static boolean sameState(
            DiagnosticSnapshot previous,
            DiagnosticDecision primary,
            ResourceType resource,
            ResourceState state,
            WorkloadAttribution attribution) {
        return previous.severity() == primary.severity()
                && previous.resource() == resource
                && previous.state() == state
                && previous.attribution().workload().equals(attribution.workload())
                && previous.attribution().confidence() == attribution.confidence()
                && previous.attribution().status() == attribution.status();
    }
}
