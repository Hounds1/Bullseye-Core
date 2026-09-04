package io.bullseye.core.diagnostic;

import io.bullseye.common.ResourceState;
import io.bullseye.common.ResourceType;
import io.bullseye.common.Severity;

import java.util.List;
import java.util.Objects;

public final class DiagnosticEngine {

    private final List<DiagnosticRule> rules;

    public DiagnosticEngine(List<DiagnosticRule> rules) {
        this.rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
    }

    public DiagnosticDecision evaluate(DiagnosticContext context) {
        DiagnosticDecision selected = null;
        for (DiagnosticRule rule : rules) {
            DiagnosticDecision candidate = rule.evaluate(context).orElse(null);
            if (candidate != null
                    && (selected == null || candidate.severity().isHigherThan(selected.severity()))) {
                selected = candidate;
            }
        }

        if (selected != null) {
            return selected;
        }
        if (context.currentState().severity() != Severity.NORMAL) {
            return new DiagnosticDecision(
                    context.currentState().resource(),
                    context.currentState().state(),
                    context.currentState().severity(),
                    "Diagnostic state retained."
            );
        }
        return new DiagnosticDecision(
                ResourceType.UNKNOWN,
                ResourceState.NORMAL,
                Severity.NORMAL,
                "System status nominal."
        );
    }
}
