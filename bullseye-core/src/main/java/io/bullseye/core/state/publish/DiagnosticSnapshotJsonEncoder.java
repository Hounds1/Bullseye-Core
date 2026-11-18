package io.bullseye.core.state.publish;

import io.bullseye.common.diagnostic.DiagnosticEvidence;
import io.bullseye.common.diagnostic.DiagnosticSnapshot;
import io.bullseye.common.diagnostic.ResourceType;
import io.bullseye.common.workload.WorkloadAttribution;

import java.util.List;
import java.util.Objects;

public final class DiagnosticSnapshotJsonEncoder {

    public String encode(DiagnosticSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        StringBuilder json =
                new StringBuilder(384)
                        .append("{\n")
                        .append("  \"host\": \"")
                        .append(escape(snapshot.host()))
                        .append("\",\n")
                        .append("  \"application\": \"")
                        .append(escape(snapshot.application()))
                        .append("\",\n")
                        .append("  \"severity\": \"")
                        .append(snapshot.severity())
                        .append("\",\n")
                        .append("  \"resource\": \"")
                        .append(snapshot.resource())
                        .append("\",\n")
                        .append("  \"state\": \"")
                        .append(snapshot.state())
                        .append("\",\n");
        appendAttribution(json, snapshot.attribution(), "  ");
        return json.append(",\n")
                .append("  \"evidence\": ")
                .append(evidence(snapshot.evidence(), "  "))
                .append(",\n")
                .append("  \"evaluatedAt\": ")
                .append(snapshot.evaluatedAt())
                .append(",\n")
                .append("  \"observationStatus\": \"")
                .append(snapshot.observationStatus())
                .append("\",\n")
                .append("  \"observations\": ")
                .append(observations(snapshot))
                .append(",\n")
                .append("  \"resources\": ")
                .append(resources(snapshot))
                .append(",\n")
                .append("  \"since\": ")
                .append(snapshot.since())
                .append(",\n")
                .append("  \"version\": ")
                .append(snapshot.version())
                .append('\n')
                .append("}\n")
                .toString();
    }

    private static void appendAttribution(
            StringBuilder json, WorkloadAttribution attribution, String indent) {
        json.append(indent).append("\"primaryWorkload\": \"")
                .append(escape(attribution.workload().name()))
                .append("\",\n")
                .append(indent).append("\"workloadType\": \"")
                .append(attribution.workload().type())
                .append("\",\n")
                .append(indent).append("\"cgroupPath\": \"")
                .append(escape(attribution.workload().cgroupPath()))
                .append("\",\n")
                .append(indent).append("\"attributionConfidence\": \"")
                .append(attribution.confidence())
                .append("\",\n")
                .append(indent).append("\"attributionStatus\": \"")
                .append(attribution.status())
                .append("\",\n")
                .append(indent).append("\"attributionScore\": ")
                .append(attribution.score())
                .append(",\n")
                .append(indent).append("\"attributionCollectedAt\": ")
                .append(attribution.collectedAt())
                .append(",\n")
                .append(indent).append("\"attributionCoverage\": ")
                .append(coverage(attribution.coverage(), indent))
                .append(",\n")
                .append(indent).append("\"attributionEvidence\": ")
                .append(evidence(attribution.evidence(), indent));
    }

    private static String coverage(WorkloadAttribution.Coverage coverage, String indent) {
        if (coverage == null) {
            return "null";
        }
        return new StringBuilder("{\n")
                .append(indent).append("  \"complete\": ")
                .append(coverage.complete())
                .append(",\n")
                .append(indent).append("  \"collectedGroups\": ")
                .append(coverage.collectedGroups())
                .append(",\n")
                .append(indent).append("  \"failedGroups\": ")
                .append(coverage.failedGroups())
                .append(",\n")
                .append(indent).append("  \"discoveryFailed\": ")
                .append(coverage.discoveryFailed())
                .append(",\n")
                .append(indent).append("  \"discoveryLimited\": ")
                .append(coverage.discoveryLimited())
                .append('\n').append(indent).append('}')
                .toString();
    }

    private static String evidence(List<DiagnosticEvidence> values, String indent) {
        StringBuilder json = new StringBuilder("[");
        for (int index = 0; index < values.size(); index++) {
            DiagnosticEvidence value = values.get(index);
            if (index > 0) {
                json.append(',');
            }
            json.append('\n')
                    .append(indent)
                    .append("  {\"metric\": \"")
                    .append(escape(value.metric()))
                    .append("\", \"value\": ")
                    .append(value.value())
                    .append(", \"unit\": \"")
                    .append(escape(value.unit()))
                    .append("\"}");
        }
        if (!values.isEmpty()) {
            json.append('\n').append(indent);
        }
        return json.append(']').toString();
    }

    private static String observations(DiagnosticSnapshot snapshot) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (ResourceType resource : ResourceType.values()) {
            var observation = snapshot.observations().get(resource);
            if (observation == null) {
                continue;
            }
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append("\n    \"")
                    .append(resource)
                    .append("\": {\n")
                    .append("      \"status\": \"")
                    .append(observation.status())
                    .append("\",\n")
                    .append("      \"lastSampleAt\": ")
                    .append(observation.lastSampleAt())
                    .append(",\n")
                    .append("      \"metrics\": ")
                    .append(evidence(observation.metrics(), "      "))
                    .append("\n    }");
        }
        if (!first) {
            json.append('\n').append("  ");
        }
        return json.append('}').toString();
    }

    private static String resources(DiagnosticSnapshot snapshot) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (ResourceType resource : ResourceType.values()) {
            var diagnosis = snapshot.resources().get(resource);
            if (diagnosis == null) {
                continue;
            }
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append("\n    \"")
                    .append(resource)
                    .append("\": {\n")
                    .append("      \"severity\": \"")
                    .append(diagnosis.severity())
                    .append("\",\n")
                    .append("      \"state\": \"")
                    .append(diagnosis.state())
                    .append("\",\n")
                    .append("      \"reason\": \"")
                    .append(escape(diagnosis.reason()))
                    .append("\",\n")
                    .append("      \"since\": ")
                    .append(diagnosis.since())
                    .append(",\n")
                    .append("      \"evidence\": ")
                    .append(evidence(diagnosis.evidence(), "      "))
                    .append(",\n");
            appendAttribution(json, diagnosis.attribution(), "      ");
            json.append("\n    }");
        }
        if (!first) {
            json.append("\n  ");
        }
        return json.append('}').toString();
    }

    private static String escape(String value) {
        StringBuilder result = new StringBuilder(value.length() + 8);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\b' -> result.append("\\b");
                case '\f' -> result.append("\\f");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (character < 0x20) {
                        result.append(String.format("\\u%04x", (int) character));
                    } else {
                        result.append(character);
                    }
                }
            }
        }
        return result.toString();
    }
}
