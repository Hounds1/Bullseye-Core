package io.bullseye.core.diagnostic;

import java.util.Optional;

public interface DiagnosticRule {

    Optional<DiagnosticDecision> evaluate(DiagnosticContext context);
}
