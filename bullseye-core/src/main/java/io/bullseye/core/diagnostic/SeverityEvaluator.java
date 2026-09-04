package io.bullseye.core.diagnostic;

import io.bullseye.common.DiagnosticSnapshot;
import io.bullseye.common.ResourceState;
import io.bullseye.common.ResourceType;
import io.bullseye.common.Severity;

public final class SeverityEvaluator {

    public DiagnosticSnapshot evaluate(
            DiagnosticSnapshot previous,
            DiagnosticDecision decision,
            long evaluatedAt
    ) {
        ResourceType resource = decision.severity() == Severity.NORMAL
                ? ResourceType.UNKNOWN
                : decision.resource();
        ResourceState state = decision.severity() == Severity.NORMAL
                ? ResourceState.NORMAL
                : decision.state();

        if (previous.severity() == decision.severity()
                && previous.resource() == resource
                && previous.state() == state) {
            return previous;
        }

        return new DiagnosticSnapshot(
                previous.host(),
                previous.application(),
                decision.severity(),
                resource,
                state,
                evaluatedAt,
                previous.version() + 1
        );
    }
}
