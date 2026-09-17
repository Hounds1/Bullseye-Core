package io.bullseye.core.diagnostic.rule;

import io.bullseye.common.diagnostic.ResourceType;
import io.bullseye.core.diagnostic.DiagnosticContext;
import io.bullseye.core.diagnostic.DiagnosticDecision;

import java.util.Optional;

public interface DiagnosticRule {

    ResourceType resource();

    Optional<DiagnosticDecision> evaluate(DiagnosticContext context);
}
