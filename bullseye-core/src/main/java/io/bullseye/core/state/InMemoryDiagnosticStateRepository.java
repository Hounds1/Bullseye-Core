package io.bullseye.core.state;

import io.bullseye.common.diagnostic.DiagnosticSnapshot;
import io.bullseye.common.diagnostic.ResourceType;

import java.util.EnumSet;
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
                    if (snapshot.version() < previous.version()
                            || snapshot.evaluatedAt() < previous.evaluatedAt()) {
                        throw new IllegalArgumentException("Snapshot must not move backwards");
                    }
                    if (snapshot.version() == previous.version()
                            && !sameDiagnosis(previous, snapshot)) {
                        throw new IllegalArgumentException(
                                "Diagnostic changes require a new version");
                    }
                    return snapshot;
                });
    }

    private static boolean sameDiagnosis(DiagnosticSnapshot previous, DiagnosticSnapshot current) {
        return previous.host().equals(current.host())
                && previous.application().equals(current.application())
                && previous.severity() == current.severity()
                && previous.resource() == current.resource()
                && previous.state() == current.state()
                && previous.since() == current.since()
                && previous.attribution().sameSourceAs(current.attribution())
                && sameResourceDiagnoses(previous, current);
    }

    private static boolean sameResourceDiagnoses(
            DiagnosticSnapshot previous, DiagnosticSnapshot current) {
        EnumSet<ResourceType> resources = EnumSet.noneOf(ResourceType.class);
        resources.addAll(previous.resources().keySet());
        resources.addAll(current.resources().keySet());
        for (ResourceType resource : resources) {
            var before = previous.diagnosis(resource);
            var after = current.diagnosis(resource);
            if (!before.sameStateAs(after) || before.since() != after.since()) {
                return false;
            }
        }
        return true;
    }
}
