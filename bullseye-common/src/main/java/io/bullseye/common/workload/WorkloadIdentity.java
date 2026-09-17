package io.bullseye.common.workload;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record WorkloadIdentity(String id, String name, String cgroupPath, Type type) {

    private static final Pattern CONTAINER_ID =
            Pattern.compile("(?:docker-|cri-containerd-|crio-)?([0-9a-f]{12,64})(?:\\.scope)?");
    private static final Pattern KUBERNETES_POD = Pattern.compile("pod([0-9a-fA-F_-]{8,})");

    public enum Type {
        SYSTEMD_SERVICE,
        DOCKER_CONTAINER,
        KUBERNETES_POD,
        GENERIC_CGROUP,
        UNKNOWN
    }

    public WorkloadIdentity {
        requireText(id, "id");
        requireText(name, "name");
        requireText(cgroupPath, "cgroupPath");
        Objects.requireNonNull(type, "type");
    }

    public static WorkloadIdentity unknown() {
        return new WorkloadIdentity("UNKNOWN", "UNKNOWN", "/", Type.UNKNOWN);
    }

    public static WorkloadIdentity fromCgroupPath(String rawPath) {
        if (rawPath == null || rawPath.isBlank() || "/".equals(rawPath)) {
            return unknown();
        }
        String path = rawPath.replace('\\', '/');
        String[] parts = path.split("/");
        String leaf = parts[parts.length - 1];
        String lower = path.toLowerCase(Locale.ROOT);

        Matcher pod = KUBERNETES_POD.matcher(path);
        if (lower.contains("kubepods") && pod.find()) {
            String id = pod.group(1).replace('_', '-');
            return new WorkloadIdentity(id, "pod-" + id, path, Type.KUBERNETES_POD);
        }

        Matcher container = CONTAINER_ID.matcher(leaf);
        if ((lower.contains("docker") || lower.contains("containerd") || lower.contains("crio"))
                && container.find()) {
            String id = container.group(1);
            return new WorkloadIdentity(id, shortId(id), path, Type.DOCKER_CONTAINER);
        }

        if (leaf.endsWith(".service")) {
            return new WorkloadIdentity(leaf, leaf, path, Type.SYSTEMD_SERVICE);
        }
        return new WorkloadIdentity(path, leaf.isBlank() ? path : leaf, path, Type.GENERIC_CGROUP);
    }

    private static String shortId(String id) {
        return id.substring(0, Math.min(12, id.length()));
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
