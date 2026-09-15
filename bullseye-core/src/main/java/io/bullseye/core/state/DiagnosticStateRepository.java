package io.bullseye.core.state;

import io.bullseye.common.diagnostic.DiagnosticSnapshot;

public interface DiagnosticStateRepository {

    DiagnosticSnapshot current();

    void update(DiagnosticSnapshot snapshot);
}
