package io.bullseye.core.diagnostic;

import io.bullseye.common.diagnostic.DiagnosticEvent;
import io.bullseye.common.diagnostic.DiagnosticEvidence;
import io.bullseye.common.diagnostic.DiagnosticSnapshot;
import io.bullseye.common.diagnostic.DiagnosticSnapshot.Observation;
import io.bullseye.common.diagnostic.DiagnosticSnapshot.ObservationStatus;
import io.bullseye.common.diagnostic.DiagnosticSnapshot.ResourceDiagnosis;
import io.bullseye.common.diagnostic.ResourceState;
import io.bullseye.common.diagnostic.ResourceType;
import io.bullseye.common.diagnostic.Severity;
import io.bullseye.common.metric.MetricSample;
import io.bullseye.common.metric.MetricType;
import io.bullseye.common.workload.WorkloadAttribution;
import io.bullseye.core.metric.MetricView;
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
    private final IoPressureAttributor ioAttributor;
    private final Duration maximumSampleAge;
    private final long attributionRefreshMillis;
    private final long startedAt;
    private final EnumMap<ResourceType, DiagnosticDecision> resourceStates =
            new EnumMap<>(ResourceType.class);
    private WorkloadAttribution memoryAttribution = WorkloadAttribution.unresolved();
    private WorkloadAttribution cpuAttribution = WorkloadAttribution.unresolved();
    private WorkloadAttribution ioAttribution = WorkloadAttribution.unresolved();
    private Long lastMemoryAttributionAt;
    private Long lastMemoryInputAt;
    private long previousHostMemoryTotal;
    private Long lastCpuAttributionAt;
    private Long lastCpuInputAt;
    private Long lastIoAttributionAt;
    private Long lastIoInputAt;
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
        this(
                metricWindow,
                engine,
                stateRepository,
                eventIdSupplier,
                memoryAttributor,
                cpuAttributor,
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
            IoPressureAttributor ioAttributor,
            Duration maximumSampleAge,
            Duration attributionRefreshInterval) {
        this.metricWindow = Objects.requireNonNull(metricWindow, "metricWindow");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.stateRepository = Objects.requireNonNull(stateRepository, "stateRepository");
        this.eventIdSupplier = Objects.requireNonNull(eventIdSupplier, "eventIdSupplier");
        this.memoryAttributor = memoryAttributor;
        this.cpuAttributor = cpuAttributor;
        this.ioAttributor = ioAttributor;
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
        resourceStates.putAll(DiagnosticContext.initialStates(current));
        resourceStates.keySet().retainAll(engine.resources());
        memoryAttribution = current.diagnosis(ResourceType.HOST_MEMORY).attribution();
        cpuAttribution = current.diagnosis(ResourceType.HOST_CPU).attribution();
        ioAttribution = current.diagnosis(ResourceType.DISK_IO).attribution();
    }

    public synchronized Optional<DiagnosticTransition> evaluate(long evaluatedAt) {
        return evaluate(evaluatedAt, metricWindow.snapshot(evaluatedAt));
    }

    public synchronized Optional<DiagnosticTransition> evaluate(long evaluatedAt, MetricView inputs) {
        return evaluate(evaluatedAt, evaluatedAt, inputs);
    }

    public synchronized Optional<DiagnosticTransition> evaluate(
            long evaluatedAt, long wallClockAt, MetricView inputs) {
        DiagnosticSnapshot previous = stateRepository.current();
        if (evaluatedAt < previous.evaluatedAt()) {
            throw new IllegalArgumentException("evaluation time must not move backwards");
        }
        DiagnosticDecision previousMemory = memoryState(resourceStates);
        DiagnosticDecision previousCpu = cpuState(resourceStates);
        DiagnosticDecision previousIo = ioState(resourceStates);
        DiagnosticEngine.Evaluation evaluation =
                engine.evaluate(
                        new DiagnosticContext(
                                inputs, previous, Map.copyOf(resourceStates), evaluatedAt));
        DiagnosticDecision currentMemory = memoryState(evaluation.resourceStates());
        DiagnosticDecision currentCpu = cpuState(evaluation.resourceStates());
        DiagnosticDecision currentIo = ioState(evaluation.resourceStates());
        resourceStates.clear();
        resourceStates.putAll(evaluation.resourceStates());

        DiagnosticDecision primary = evaluation.primary();
        ResourceType snapshotResource =
                primary.severity() == Severity.NORMAL ? ResourceType.UNKNOWN : primary.resource();
        ResourceState snapshotState =
                primary.severity() == Severity.NORMAL ? ResourceState.NORMAL : primary.state();
        updateMemoryAttribution(previousMemory, currentMemory, inputs, evaluatedAt);
        updateCpuAttribution(previousCpu, currentCpu, inputs, evaluatedAt);
        updateIoAttribution(previousIo, currentIo, inputs, evaluatedAt);
        WorkloadAttribution attribution = attributionFor(snapshotResource);
        boolean primaryChanged =
                !sameState(previous, primary, snapshotResource, snapshotState, attribution);
        Map<ResourceType, ResourceDiagnosis> resources = resourceDiagnoses(previous, evaluatedAt);
        List<ResourceType> changedResources = changedResources(previous, resources);
        boolean changed = primaryChanged || !changedResources.isEmpty();
        Map<ResourceType, Observation> observations = observations(inputs, evaluatedAt);

        DiagnosticSnapshot current =
                new DiagnosticSnapshot(
                        previous.host(),
                        previous.application(),
                        primary.severity(),
                        snapshotResource,
                        snapshotState,
                        attribution,
                        primary.evidence(),
                        primaryChanged ? evaluatedAt : previous.since(),
                        changed ? previous.version() + 1 : previous.version(),
                        evaluatedAt,
                        observationStatus(observations),
                        observations,
                        resources,
                        wallClockAt);
        stateRepository.update(current);
        if (!changed) {
            return Optional.empty();
        }
        DiagnosticDecision eventDecision = primaryChanged
                ? primary
                : resourceStates.getOrDefault(
                        changedResources.getFirst(),
                        DiagnosticDecision.normal(
                                changedResources.getFirst(), "Diagnostic monitoring withdrawn."));
        DiagnosticEvent event =
                new DiagnosticEvent(
                        eventIdSupplier.get(),
                        current.host(),
                        current.application(),
                        eventDecision.resource(),
                        eventDecision.state(),
                        eventDecision.severity(),
                        evaluatedAt,
                        eventDecision.reason());
        return Optional.of(new DiagnosticTransition(previous, current, event));
    }

    public synchronized Map<ResourceType, DiagnosticDecision> resourceStates() {
        return Map.copyOf(resourceStates);
    }

    private WorkloadAttribution attributionFor(ResourceType resource) {
        return switch (resource) {
            case HOST_MEMORY -> memoryAttribution;
            case HOST_CPU -> cpuAttribution;
            case DISK_IO -> ioAttribution;
            default -> WorkloadAttribution.unresolved();
        };
    }

    private Map<ResourceType, ResourceDiagnosis> resourceDiagnoses(
            DiagnosticSnapshot previous, long evaluatedAt) {
        EnumMap<ResourceType, ResourceDiagnosis> diagnoses = new EnumMap<>(ResourceType.class);
        for (ResourceType resource : engine.resources()) {
            DiagnosticDecision decision = resourceStates.get(resource);
            ResourceDiagnosis before = previous.diagnosis(resource);
            WorkloadAttribution attribution = attributionFor(resource);
            boolean unchanged = before.severity() == decision.severity()
                    && before.state() == decision.state()
                    && before.attribution().sameSourceAs(attribution);
            diagnoses.put(resource, new ResourceDiagnosis(
                    decision.severity(),
                    decision.state(),
                    decision.reason(),
                    decision.evidence(),
                    attribution,
                    unchanged ? before.since() : evaluatedAt));
        }
        return Map.copyOf(diagnoses);
    }

    private static List<ResourceType> changedResources(
            DiagnosticSnapshot previous, Map<ResourceType, ResourceDiagnosis> current) {
        List<ResourceType> changed = new ArrayList<>();
        for (ResourceType resource : ResourceType.values()) {
            ResourceDiagnosis diagnosis = current.get(resource);
            if (diagnosis != null) {
                if (!previous.diagnosis(resource).sameStateAs(diagnosis)) {
                    changed.add(resource);
                }
            } else if (previous.resources().containsKey(resource)) {
                changed.add(resource);
            }
        }
        return List.copyOf(changed);
    }

    private Map<ResourceType, Observation> observations(MetricView inputs, long now) {
        EnumMap<ResourceType, Observation> result = new EnumMap<>(ResourceType.class);
        for (ResourceType resource : engine.resources()) {
            result.put(resource, observe(resource, inputs, now));
        }
        return result;
    }

    private Observation observe(ResourceType resource, MetricView inputs, long now) {
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
            List<MetricSample> samples = inputs.get(type);
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
            MetricView inputs,
            long evaluatedAt) {
        if (memoryAttributor == null
                || currentMemory.severity().ordinal() < Severity.HIGH.ordinal()) {
            if (memoryAttributor != null
                    && previousMemory.severity().ordinal() >= Severity.HIGH.ordinal()) {
                resetMemoryHistory();
            }
            memoryAttribution = WorkloadAttribution.unresolved();
            lastMemoryAttributionAt = null;
            return;
        }

        Optional<MetricSample> usage = MetricConditions.latest(
                inputs, MetricType.HOST_MEMORY_USAGE, evaluatedAt, maximumSampleAge);
        boolean fresh =
                usage.isPresent()
                        && MetricConditions.latest(
                                        inputs,
                                        MetricType.HOST_MEMORY_PSI_SOME_AVG10,
                                        evaluatedAt,
                                        maximumSampleAge)
                                .isPresent()
                        && !MetricConditions.hasExpired(
                                inputs,
                                MetricType.HOST_MEMORY_PSI_FULL_AVG10,
                                evaluatedAt,
                                maximumSampleAge);
        if (!fresh) {
            if (lastMemoryInputAt != null) {
                resetMemoryHistory();
            }
            memoryAttribution = retainedLowConfidence(memoryAttribution);
            return;
        }

        long inputAt = usage.orElseThrow().timestamp();
        long hostMemoryTotal = latestHostMemoryTotal(inputs, evaluatedAt);
        boolean inputGap = lastMemoryInputAt != null
                && inputAt - lastMemoryInputAt > maximumSampleAge.toMillis();
        boolean capacityChanged = previousHostMemoryTotal > 0
                && previousHostMemoryTotal != hostMemoryTotal;
        if (inputGap || capacityChanged) {
            resetMemoryHistory();
            memoryAttribution = retainedLowConfidence(memoryAttribution);
        }
        boolean newInput = lastMemoryInputAt == null || inputAt > lastMemoryInputAt;
        lastMemoryInputAt = inputAt;
        previousHostMemoryTotal = hostMemoryTotal;

        boolean highEntry =
                currentMemory.severity() == Severity.HIGH
                        && previousMemory.severity() != Severity.HIGH;
        boolean critical = currentMemory.severity() == Severity.CRITICAL;
        boolean refreshDue =
                lastMemoryAttributionAt == null
                        || evaluatedAt - lastMemoryAttributionAt >= attributionRefreshMillis;
        boolean confirmationDue = newInput && memoryAttributor.needsConfirmation();
        if (highEntry || critical || refreshDue || confirmationDue) {
            lastMemoryAttributionAt = evaluatedAt;
            memoryAttribution = memoryAttributor.attribute(hostMemoryTotal);
        }
    }

    private void updateCpuAttribution(
            DiagnosticDecision previousCpu,
            DiagnosticDecision currentCpu,
            MetricView inputs,
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
                inputs, MetricType.HOST_CPU_USAGE, evaluatedAt, maximumSampleAge);
        int hostCpuCount = latestHostCpuCount(inputs, evaluatedAt);
        if (usage.isEmpty()
                || hostCpuCount == 0
                || MetricConditions.hasExpired(
                        inputs,
                        MetricType.HOST_CPU_PSI_SOME_AVG10,
                        evaluatedAt,
                        maximumSampleAge)) {
            // Keep the last explanation while telemetry is stale, but never bridge the gap.
            if (lastCpuInputAt != null) {
                resetCpuHistory();
            }
            cpuAttribution = retainedLowConfidence(cpuAttribution);
            return;
        }
        long inputAt = usage.orElseThrow().timestamp();
        boolean inputGap =
                lastCpuInputAt != null
                        && inputAt - lastCpuInputAt > maximumSampleAge.toMillis();
        boolean capacityChanged = previousHostCpuCount != 0 && previousHostCpuCount != hostCpuCount;
        if (inputGap || capacityChanged) {
            resetCpuHistory();
            cpuAttribution = retainedLowConfidence(cpuAttribution);
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
        if (newInput
                && (highEntry || critical || refreshDue || baselineNeeded
                        || cpuAttributor.needsConfirmation())) {
            lastCpuAttributionAt = evaluatedAt;
            cpuAttribution = cpuAttributor.attribute(hostCpuCount);
        }
    }

    private void updateIoAttribution(
            DiagnosticDecision previousIo,
            DiagnosticDecision currentIo,
            MetricView inputs,
            long evaluatedAt) {
        if (ioAttributor == null || currentIo.severity().ordinal() < Severity.HIGH.ordinal()) {
            if (ioAttributor != null
                    && previousIo.severity().ordinal() >= Severity.HIGH.ordinal()) {
                resetIoHistory();
            }
            ioAttribution = WorkloadAttribution.unresolved();
            return;
        }

        Optional<MetricSample> pressure = MetricConditions.latest(
                inputs, MetricType.HOST_IO_PSI_SOME_AVG10, evaluatedAt, maximumSampleAge);
        if (pressure.isEmpty()
                || MetricConditions.hasExpired(
                        inputs,
                        MetricType.HOST_IO_PSI_FULL_AVG10,
                        evaluatedAt,
                        maximumSampleAge)) {
            // Preserve the last explanation, but require a new baseline after missing telemetry.
            if (lastIoInputAt != null) {
                resetIoHistory();
            }
            return;
        }

        long inputAt = pressure.orElseThrow().timestamp();
        if (lastIoInputAt != null
                && inputAt - lastIoInputAt > maximumSampleAge.toMillis()) {
            resetIoHistory();
        }
        boolean newInput = lastIoInputAt == null || inputAt > lastIoInputAt;
        lastIoInputAt = inputAt;

        boolean highEntry = currentIo.severity() == Severity.HIGH
                && previousIo.severity() != Severity.HIGH;
        boolean critical = currentIo.severity() == Severity.CRITICAL;
        boolean refreshDue = lastIoAttributionAt == null
                || evaluatedAt - lastIoAttributionAt >= attributionRefreshMillis;
        boolean baselineNeeded =
                ioAttribution.status() == WorkloadAttribution.Status.INSUFFICIENT_DATA;
        if (newInput && (highEntry || critical || refreshDue || baselineNeeded)) {
            lastIoAttributionAt = evaluatedAt;
            ioAttribution = ioAttributor.attribute();
        }
    }

    private void resetIoHistory() {
        ioAttributor.reset();
        lastIoAttributionAt = null;
        lastIoInputAt = null;
    }

    private void resetMemoryHistory() {
        memoryAttributor.reset();
        lastMemoryAttributionAt = null;
        lastMemoryInputAt = null;
        previousHostMemoryTotal = 0;
    }

    private void resetCpuHistory() {
        cpuAttributor.reset();
        lastCpuAttributionAt = null;
        lastCpuInputAt = null;
        previousHostCpuCount = 0;
    }

    private static WorkloadAttribution retainedLowConfidence(WorkloadAttribution attribution) {
        if (!attribution.resolved()
                || attribution.confidence() == WorkloadAttribution.Confidence.LOW) {
            return attribution;
        }
        return new WorkloadAttribution(
                attribution.workload(),
                attribution.score(),
                WorkloadAttribution.Confidence.LOW,
                attribution.status(),
                attribution.evidence(),
                attribution.coverage(),
                attribution.collectedAt());
    }

    private int latestHostCpuCount(MetricView inputs, long evaluatedAt) {
        return MetricConditions.latest(
                        inputs,
                        MetricType.HOST_CPU_LOGICAL_COUNT,
                        evaluatedAt,
                        maximumSampleAge)
                .filter(sample -> sample.value() >= 1
                        && sample.value() <= Integer.MAX_VALUE
                        && sample.value() == Math.rint(sample.value()))
                .map(sample -> (int) sample.value())
                .orElse(0);
    }

    private long latestHostMemoryTotal(MetricView inputs, long evaluatedAt) {
        return MetricConditions.latest(
                        inputs,
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

    private static DiagnosticDecision ioState(Map<ResourceType, DiagnosticDecision> states) {
        return states.getOrDefault(
                ResourceType.DISK_IO,
                DiagnosticDecision.normal(ResourceType.DISK_IO, "IO condition nominal."));
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
                && previous.attribution().sameSourceAs(attribution);
    }
}
