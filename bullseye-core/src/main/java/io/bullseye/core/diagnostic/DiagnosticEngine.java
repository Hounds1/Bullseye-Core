package io.bullseye.core.diagnostic;

import io.bullseye.common.diagnostic.ResourceType;
import io.bullseye.common.diagnostic.Severity;
import io.bullseye.core.diagnostic.rule.DiagnosticRule;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class DiagnosticEngine {

    private static final double PRIMARY_SWITCH_RATIO = 1.2;
    private final List<DiagnosticRule> rules;

    public DiagnosticEngine(List<DiagnosticRule> rules) {
        this.rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
    }

    public Evaluation evaluate(DiagnosticContext context) {
        EnumMap<ResourceType, DiagnosticDecision> states = new EnumMap<>(ResourceType.class);
        states.putAll(context.resourceStates());
        DiagnosticDecision recovery = null;
        for (DiagnosticRule rule : rules) {
            DiagnosticDecision before = context.resourceState(rule.resource());
            DiagnosticDecision candidate = rule.evaluate(context).orElse(before);
            if (candidate.resource() != rule.resource()) {
                throw new IllegalStateException("Diagnostic rule returned a different resource");
            }
            states.put(rule.resource(), candidate);
            if (before.severity() != Severity.NORMAL && candidate.severity() == Severity.NORMAL) {
                recovery = candidate;
            }
        }

        DiagnosticDecision primary = selectPrimary(states, context.currentState().resource());
        if (primary == null) {
            primary =
                    recovery != null
                            ? recovery
                            : DiagnosticDecision.normal("System status nominal.");
        }
        return new Evaluation(states, primary);
    }

    private static DiagnosticDecision selectPrimary(
            Map<ResourceType, DiagnosticDecision> states, ResourceType currentPrimary) {
        int highest =
                states.values().stream()
                        .mapToInt(decision -> decision.severity().ordinal())
                        .max()
                        .orElse(0);
        if (highest == Severity.NORMAL.ordinal()) {
            return null;
        }

        DiagnosticDecision strongest =
                states.values().stream()
                        .filter(decision -> decision.severity().ordinal() == highest)
                        .max(
                                (left, right) -> {
                                    int pressure =
                                            Double.compare(
                                                    left.pressureScore(), right.pressureScore());
                                    return pressure != 0
                                            ? pressure
                                            : Integer.compare(
                                                    right.resource().ordinal(),
                                                    left.resource().ordinal());
                                })
                        .orElse(null);
        DiagnosticDecision current = states.get(currentPrimary);
        if (current != null
                && current.severity().ordinal() == highest
                && current.pressureScore() * PRIMARY_SWITCH_RATIO >= strongest.pressureScore()) {
            return current;
        }
        return strongest;
    }

    public record Evaluation(
            Map<ResourceType, DiagnosticDecision> resourceStates, DiagnosticDecision primary) {

        public Evaluation {
            resourceStates = Map.copyOf(resourceStates);
            Objects.requireNonNull(primary, "primary");
        }
    }
}
