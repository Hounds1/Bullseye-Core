package io.bullseye.common.diagnostic;

import io.bullseye.common.workload.WorkloadAttribution;

import java.util.List;
import java.util.Map;
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
        long version,
        long evaluatedAt,
        ObservationStatus observationStatus,
        Map<ResourceType, Observation> observations) {

    public enum ObservationStatus {
        INITIALIZING,
        FRESH,
        STALE
    }

    public record Observation(
            ObservationStatus status, Long lastSampleAt, List<DiagnosticEvidence> metrics) {

        public Observation {
            Objects.requireNonNull(status, "status");
            if (lastSampleAt != null && lastSampleAt < 0) {
                throw new IllegalArgumentException("lastSampleAt must not be negative");
            }
            metrics = List.copyOf(Objects.requireNonNull(metrics, "metrics"));
        }
    }

    public DiagnosticSnapshot {
        requireText(host, "host");
        requireText(application, "application");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(attribution, "attribution");
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        Objects.requireNonNull(observationStatus, "observationStatus");
        observations = Map.copyOf(Objects.requireNonNull(observations, "observations"));
        if (since < 0) {
            throw new IllegalArgumentException("since must not be negative");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        if (evaluatedAt < since) {
            throw new IllegalArgumentException("evaluatedAt must not precede since");
        }
    }

    public DiagnosticSnapshot(
            String host,
            String application,
            Severity severity,
            ResourceType resource,
            ResourceState state,
            WorkloadAttribution attribution,
            List<DiagnosticEvidence> evidence,
            long since,
            long version) {
        this(
                host,
                application,
                severity,
                resource,
                state,
                attribution,
                evidence,
                since,
                version,
                since,
                ObservationStatus.INITIALIZING,
                Map.of());
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
