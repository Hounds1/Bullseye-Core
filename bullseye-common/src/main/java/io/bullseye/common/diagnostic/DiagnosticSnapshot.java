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
        Map<ResourceType, Observation> observations,
        Map<ResourceType, ResourceDiagnosis> resources,
        long wallClockAt) {

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

    public record ResourceDiagnosis(
            Severity severity,
            ResourceState state,
            String reason,
            List<DiagnosticEvidence> evidence,
            WorkloadAttribution attribution,
            long since) {

        public ResourceDiagnosis {
            Objects.requireNonNull(severity, "severity");
            Objects.requireNonNull(state, "state");
            requireText(reason, "reason");
            evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
            Objects.requireNonNull(attribution, "attribution");
            if (since < 0) {
                throw new IllegalArgumentException("since must not be negative");
            }
        }

        public boolean sameStateAs(ResourceDiagnosis other) {
            Objects.requireNonNull(other, "other");
            return severity == other.severity
                    && state == other.state
                    && attribution.sameSourceAs(other.attribution);
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
        resources = Map.copyOf(Objects.requireNonNull(resources, "resources"));
        if (since < 0) {
            throw new IllegalArgumentException("since must not be negative");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        if (evaluatedAt < since) {
            throw new IllegalArgumentException("evaluatedAt must not precede since");
        }
        for (ResourceDiagnosis diagnosis : resources.values()) {
            if (diagnosis.since() > evaluatedAt) {
                throw new IllegalArgumentException("Resource since must not follow evaluatedAt");
            }
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
            long version,
            long evaluatedAt,
            ObservationStatus observationStatus,
            Map<ResourceType, Observation> observations,
            Map<ResourceType, ResourceDiagnosis> resources) {
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
                evaluatedAt,
                observationStatus,
                observations,
                resources,
                evaluatedAt);
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
            long version,
            long evaluatedAt,
            ObservationStatus observationStatus,
            Map<ResourceType, Observation> observations) {
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
                evaluatedAt,
                observationStatus,
                observations,
                Map.of());
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
        return initial(host, application, now, now);
    }

    public static DiagnosticSnapshot initial(String host, String application, long now, long wallClockAt) {
        return new DiagnosticSnapshot(
                host,
                application,
                Severity.NORMAL,
                ResourceType.UNKNOWN,
                ResourceState.NORMAL,
                WorkloadAttribution.unresolved(),
                List.of(),
                now,
                1,
                now,
                ObservationStatus.INITIALIZING,
                Map.of(),
                Map.of(),
                wallClockAt);
    }

    public ResourceDiagnosis diagnosis(ResourceType requestedResource) {
        Objects.requireNonNull(requestedResource, "requestedResource");
        ResourceDiagnosis diagnosis = resources.get(requestedResource);
        if (diagnosis != null) {
            return diagnosis;
        }
        // Older snapshots contain only the primary diagnosis.
        if (resource == requestedResource) {
            return new ResourceDiagnosis(
                    severity,
                    state,
                    "Diagnostic state retained.",
                    evidence,
                    attribution,
                    since);
        }
        return new ResourceDiagnosis(
                Severity.NORMAL,
                ResourceState.NORMAL,
                "System status nominal.",
                List.of(),
                WorkloadAttribution.unresolved(),
                since);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
