package io.bullseye.core.state;

import io.bullseye.common.diagnostic.DiagnosticSnapshot;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class InMemoryDiagnosticStateRepository implements DiagnosticStateRepository {

    private final AtomicReference<DiagnosticSnapshot> current;

    public InMemoryDiagnosticStateRepository(DiagnosticSnapshot initialState) {
        current = new AtomicReference<>(Objects.requireNonNull(initialState, "initialState"));
    }

    @Override
    public DiagnosticSnapshot current() {
        return current.get();
    }

    @Override
    public void update(DiagnosticSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        current.updateAndGet(
                previous -> {
                    if (snapshot.version() <= previous.version()) {
                        throw new IllegalArgumentException("State version must increase");
                    }
                    return snapshot;
                });
    }
}
