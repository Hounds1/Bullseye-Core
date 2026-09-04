package io.bullseye.core.diagnostic;

import io.bullseye.common.DiagnosticEvent;
import io.bullseye.common.DiagnosticSnapshot;
import io.bullseye.core.state.DiagnosticStateRepository;
import io.bullseye.core.store.MetricWindow;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public final class DiagnosticCoordinator {

    private final MetricWindow metricWindow;
    private final DiagnosticEngine engine;
    private final SeverityEvaluator severityEvaluator;
    private final DiagnosticStateRepository stateRepository;
    private final Supplier<String> eventIdSupplier;

    public DiagnosticCoordinator(
            MetricWindow metricWindow,
            DiagnosticEngine engine,
            SeverityEvaluator severityEvaluator,
            DiagnosticStateRepository stateRepository,
            Supplier<String> eventIdSupplier
    ) {
        this.metricWindow = Objects.requireNonNull(metricWindow, "metricWindow");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.severityEvaluator = Objects.requireNonNull(severityEvaluator, "severityEvaluator");
        this.stateRepository = Objects.requireNonNull(stateRepository, "stateRepository");
        this.eventIdSupplier = Objects.requireNonNull(eventIdSupplier, "eventIdSupplier");
    }

    public synchronized Optional<DiagnosticTransition> evaluate(long evaluatedAt) {
        DiagnosticSnapshot previous = stateRepository.current();
        DiagnosticDecision decision = engine.evaluate(new DiagnosticContext(
                metricWindow,
                previous,
                evaluatedAt
        ));
        DiagnosticSnapshot current = severityEvaluator.evaluate(previous, decision, evaluatedAt);
        if (current == previous) {
            return Optional.empty();
        }

        stateRepository.update(current);
        DiagnosticEvent event = new DiagnosticEvent(
                eventIdSupplier.get(),
                current.host(),
                current.application(),
                decision.resource(),
                decision.state(),
                decision.severity(),
                evaluatedAt,
                decision.reason()
        );
        return Optional.of(new DiagnosticTransition(previous, current, event));
    }
}
