package io.bullseye.core.diagnostic;

import io.bullseye.common.diagnostic.DiagnosticEvent;
import io.bullseye.common.diagnostic.DiagnosticSnapshot;

import java.util.Objects;

public record DiagnosticTransition(
        DiagnosticSnapshot previous, DiagnosticSnapshot current, DiagnosticEvent event) {

    public DiagnosticTransition {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(event, "event");
    }
}
