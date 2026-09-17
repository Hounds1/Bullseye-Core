package io.bullseye.core.diagnostic;

import io.bullseye.common.diagnostic.DiagnosticEvent;
import io.bullseye.common.diagnostic.DiagnosticSnapshot;
import io.bullseye.common.diagnostic.ResourceState;
import io.bullseye.common.diagnostic.ResourceType;
import io.bullseye.common.diagnostic.Severity;
import io.bullseye.common.workload.WorkloadAttribution;
import io.bullseye.core.linux.cgroup.CgroupResourceSnapshot;
import io.bullseye.core.metric.MetricWindow;
import io.bullseye.core.state.DiagnosticStateRepository;

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
    private final Supplier<List<CgroupResourceSnapshot>> cgroupSnapshots;
    private final PressureAttributor attributor;
    private final EnumMap<ResourceType, DiagnosticDecision> resourceStates =
            new EnumMap<>(ResourceType.class);

    public DiagnosticCoordinator(
            MetricWindow metricWindow,
            DiagnosticEngine engine,
            DiagnosticStateRepository stateRepository,
            Supplier<String> eventIdSupplier) {
        this(metricWindow, engine, stateRepository, eventIdSupplier, List::of, null);
    }

    public DiagnosticCoordinator(
            MetricWindow metricWindow,
            DiagnosticEngine engine,
            DiagnosticStateRepository stateRepository,
            Supplier<String> eventIdSupplier,
            Supplier<List<CgroupResourceSnapshot>> cgroupSnapshots,
            PressureAttributor attributor) {
        this.metricWindow = Objects.requireNonNull(metricWindow, "metricWindow");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.stateRepository = Objects.requireNonNull(stateRepository, "stateRepository");
        this.eventIdSupplier = Objects.requireNonNull(eventIdSupplier, "eventIdSupplier");
        this.cgroupSnapshots = Objects.requireNonNull(cgroupSnapshots, "cgroupSnapshots");
        this.attributor = attributor;
        DiagnosticSnapshot current = stateRepository.current();
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
    }

    public synchronized Optional<DiagnosticTransition> evaluate(long evaluatedAt) {
        DiagnosticSnapshot previous = stateRepository.current();
        DiagnosticEngine.Evaluation evaluation =
                engine.evaluate(
                        new DiagnosticContext(
                                metricWindow, previous, Map.copyOf(resourceStates), evaluatedAt));
        resourceStates.clear();
        resourceStates.putAll(evaluation.resourceStates());

        DiagnosticDecision primary = evaluation.primary();
        ResourceType snapshotResource =
                primary.severity() == Severity.NORMAL ? ResourceType.UNKNOWN : primary.resource();
        ResourceState snapshotState =
                primary.severity() == Severity.NORMAL ? ResourceState.NORMAL : primary.state();
        WorkloadAttribution attribution =
                primary.severity().ordinal() >= Severity.HIGH.ordinal() && attributor != null
                        ? attributor.attribute(
                                primary.resource(), cgroupSnapshots.get(), evaluatedAt)
                        : WorkloadAttribution.unresolved();

        if (sameState(previous, primary, snapshotResource, snapshotState, attribution)) {
            return Optional.empty();
        }

        DiagnosticSnapshot current =
                new DiagnosticSnapshot(
                        previous.host(),
                        previous.application(),
                        primary.severity(),
                        snapshotResource,
                        snapshotState,
                        attribution,
                        primary.evidence(),
                        evaluatedAt,
                        previous.version() + 1);
        stateRepository.update(current);
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

    private static boolean sameState(
            DiagnosticSnapshot previous,
            DiagnosticDecision primary,
            ResourceType resource,
            ResourceState state,
            WorkloadAttribution attribution) {
        return previous.severity() == primary.severity()
                && previous.resource() == resource
                && previous.state() == state
                && previous.attribution().workload().equals(attribution.workload());
    }
}
