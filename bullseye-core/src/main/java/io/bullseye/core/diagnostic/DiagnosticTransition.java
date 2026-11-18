package io.bullseye.core.diagnostic;

import io.bullseye.common.diagnostic.DiagnosticEvent;
import io.bullseye.common.diagnostic.DiagnosticSnapshot;

import java.util.Objects;

// The event represents the primary change, or the first changed resource when the primary
// is unchanged. Consumers inspect the snapshot resource maps for all changes in the evaluation.
public record DiagnosticTransition(
        DiagnosticSnapshot previous, DiagnosticSnapshot current, DiagnosticEvent event) {

    public DiagnosticTransition {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(event, "event");
    }
}
