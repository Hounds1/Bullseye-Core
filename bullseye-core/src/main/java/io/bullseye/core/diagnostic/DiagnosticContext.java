package io.bullseye.core.diagnostic;

import io.bullseye.common.diagnostic.DiagnosticSnapshot;
import io.bullseye.common.diagnostic.ResourceType;
import io.bullseye.core.metric.MetricWindow;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

public record DiagnosticContext(
        MetricWindow metrics,
        DiagnosticSnapshot currentState,
        Map<ResourceType, DiagnosticDecision> resourceStates,
        long evaluatedAt) {

    public DiagnosticContext {
        Objects.requireNonNull(metrics, "metrics");
        Objects.requireNonNull(currentState, "currentState");
        resourceStates = Map.copyOf(Objects.requireNonNull(resourceStates, "resourceStates"));
        if (evaluatedAt < 0) {
            throw new IllegalArgumentException("evaluatedAt must not be negative");
        }
    }

    public DiagnosticContext(
            MetricWindow metrics, DiagnosticSnapshot currentState, long evaluatedAt) {
        this(metrics, currentState, initialStates(currentState), evaluatedAt);
    }

    public DiagnosticDecision resourceState(ResourceType resource) {
        return resourceStates.getOrDefault(
                resource, DiagnosticDecision.normal(resource, resource + " status nominal."));
    }

    private static Map<ResourceType, DiagnosticDecision> initialStates(
            DiagnosticSnapshot snapshot) {
        EnumMap<ResourceType, DiagnosticDecision> states = new EnumMap<>(ResourceType.class);
        if (snapshot.resource() != ResourceType.UNKNOWN) {
            states.put(
                    snapshot.resource(),
                    new DiagnosticDecision(
                            snapshot.resource(),
                            snapshot.state(),
                            snapshot.severity(),
                            "Diagnostic state retained.",
                            snapshot.evidence(),
                            0));
        }
        return states;
    }
}
