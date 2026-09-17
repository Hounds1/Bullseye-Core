package io.bullseye.common.diagnostic;

import io.bullseye.common.workload.WorkloadAttribution;

import java.util.List;
import java.util.Objects;

public record DiagnosticSnapshot(
        String host,
        String application,
        Severity severity,
        ResourceType resource,
        ResourceState state,
        WorkloadAttribution attribution,
        List<DiagnosticEvidence> evidence,
        long since,
        long version) {

    public DiagnosticSnapshot {
        requireText(host, "host");
        requireText(application, "application");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(attribution, "attribution");
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        if (since < 0) {
            throw new IllegalArgumentException("since must not be negative");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
    }

    public DiagnosticSnapshot(
            String host,
            String application,
            Severity severity,
            ResourceType resource,
            ResourceState state,
            long since,
            long version) {
        this(
                host,
                application,
                severity,
                resource,
                state,
                WorkloadAttribution.unresolved(),
                List.of(),
                since,
                version);
    }

    public static DiagnosticSnapshot initial(String host, String application, long now) {
        return new DiagnosticSnapshot(
                host,
                application,
                Severity.NORMAL,
                ResourceType.UNKNOWN,
                ResourceState.NORMAL,
                WorkloadAttribution.unresolved(),
                List.of(),
                now,
                1);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
