package io.bullseye.core.config;

import java.io.IOException;
import java.io.Reader;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PropertiesConfigurationLoader {

    private static final Pattern DURATION_PATTERN = Pattern.compile("^(\\d+)(ms|s|m|h)$");
    private static final String CPU_PREFIX = "bullseye.diagnostics.cpu.";
    private static final String MEMORY_PREFIX = "bullseye.diagnostics.memory.";
    private static final String IO_PREFIX = "bullseye.diagnostics.io.";
    private static final String CGROUP_PREFIX = "bullseye.cgroup.";

    public BullseyeConfiguration load(Path path) throws IOException {
        Properties properties = read(path);

        return new BullseyeConfiguration(
                text(properties, "bullseye.host", detectHostName()),
                text(properties, "bullseye.application", "host"),
                duration(properties, "bullseye.sampling.interval", "5s"),
                duration(properties, "bullseye.window.duration", "30m"),
                Path.of(text(properties, "bullseye.state.output", "/run/bullseye/state.json")),
                loadCpuDiagnostics(properties),
                loadMemoryDiagnostics(properties),
                loadIoDiagnostics(properties),
                loadCgroup(properties));
    }

    private static Properties read(Path path) throws IOException {
        Properties properties = new Properties();
        if (!Files.exists(path)) {
            return properties;
        }

        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    private static BullseyeConfiguration.CpuDiagnostics loadCpuDiagnostics(Properties properties) {
        return new BullseyeConfiguration.CpuDiagnostics(
                decimal(properties, CPU_PREFIX + "elevated.threshold", 70),
                duration(properties, CPU_PREFIX + "elevated.duration", "10s"),
                decimal(properties, CPU_PREFIX + "high.threshold", 80),
                duration(properties, CPU_PREFIX + "high.duration", "10s"),
                decimal(properties, CPU_PREFIX + "high.minimum-rise", 0),
                decimal(properties, CPU_PREFIX + "critical.threshold", 90),
                duration(properties, CPU_PREFIX + "critical.duration", "5s"),
                decimal(properties, CPU_PREFIX + "recovery.threshold", 65),
                duration(properties, CPU_PREFIX + "recovery.duration", "30s"),
                decimal(properties, CPU_PREFIX + "psi.corroboration-threshold", 5),
                decimal(properties, CPU_PREFIX + "psi.recovery-threshold", 1));
    }

    private static BullseyeConfiguration.MemoryDiagnostics loadMemoryDiagnostics(
            Properties properties) {
        return new BullseyeConfiguration.MemoryDiagnostics(
                decimal(properties, MEMORY_PREFIX + "elevated.usage", 80),
                decimal(properties, MEMORY_PREFIX + "elevated.psi-some", 10),
                duration(properties, MEMORY_PREFIX + "elevated.duration", "10s"),
                decimal(properties, MEMORY_PREFIX + "high.usage", 85),
                decimal(properties, MEMORY_PREFIX + "high.psi-some", 20),
                decimal(properties, MEMORY_PREFIX + "high.minimum-rise", 0),
                duration(properties, MEMORY_PREFIX + "high.duration", "10s"),
                decimal(properties, MEMORY_PREFIX + "critical.usage", 90),
                decimal(properties, MEMORY_PREFIX + "critical.psi-some", 40),
                decimal(properties, MEMORY_PREFIX + "critical.psi-full", 5),
                duration(properties, MEMORY_PREFIX + "critical.duration", "5s"),
                decimal(properties, MEMORY_PREFIX + "recovery.usage", 70),
                decimal(properties, MEMORY_PREFIX + "recovery.psi-some", 2),
                decimal(properties, MEMORY_PREFIX + "recovery.psi-full", 1),
                duration(properties, MEMORY_PREFIX + "recovery.duration", "30s"));
    }

    private static BullseyeConfiguration.IoDiagnostics loadIoDiagnostics(Properties properties) {
        return new BullseyeConfiguration.IoDiagnostics(
                decimal(properties, IO_PREFIX + "elevated.psi-some", 10),
                duration(properties, IO_PREFIX + "elevated.duration", "10s"),
                decimal(properties, IO_PREFIX + "high.psi-some", 25),
                decimal(properties, IO_PREFIX + "high.psi-full", 3),
                duration(properties, IO_PREFIX + "high.duration", "10s"),
                decimal(properties, IO_PREFIX + "critical.psi-full", 10),
                duration(properties, IO_PREFIX + "critical.duration", "5s"),
                decimal(properties, IO_PREFIX + "recovery.psi-some", 2),
                decimal(properties, IO_PREFIX + "recovery.psi-full", 1),
                duration(properties, IO_PREFIX + "recovery.duration", "30s"));
    }

    private static BullseyeConfiguration.Cgroup loadCgroup(Properties properties) {
        return new BullseyeConfiguration.Cgroup(
                bool(properties, CGROUP_PREFIX + "enabled", true),
                Path.of(text(properties, CGROUP_PREFIX + "root", "/sys/fs/cgroup")),
                duration(properties, CGROUP_PREFIX + "discovery-interval", "60s"),
                duration(properties, CGROUP_PREFIX + "sampling-interval", "5s"),
                integer(properties, CGROUP_PREFIX + "maximum-depth", 8),
                integer(properties, CGROUP_PREFIX + "maximum-groups", 512),
                decimal(properties, CGROUP_PREFIX + "attribution.minimum-score", 5),
                decimal(properties, CGROUP_PREFIX + "attribution.dominance-ratio", 1.2));
    }

    static Duration parseDuration(String raw) {
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        Matcher matcher = DURATION_PATTERN.matcher(normalized);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Unsupported duration: " + raw);
        }

        long amount = Long.parseLong(matcher.group(1));
        return switch (matcher.group(2)) {
            case "ms" -> Duration.ofMillis(amount);
            case "s" -> Duration.ofSeconds(amount);
            case "m" -> Duration.ofMinutes(amount);
            case "h" -> Duration.ofHours(amount);
            default -> throw new IllegalStateException("Unexpected duration unit");
        };
    }

    private static String text(Properties properties, String key, String defaultValue) {
        return properties.getProperty(key, defaultValue).trim();
    }

    private static double decimal(Properties properties, String key, double defaultValue) {
        String raw = properties.getProperty(key);
        return raw == null ? defaultValue : Double.parseDouble(raw.trim());
    }

    private static int integer(Properties properties, String key, int defaultValue) {
        String raw = properties.getProperty(key);
        return raw == null ? defaultValue : Integer.parseInt(raw.trim());
    }

    private static boolean bool(Properties properties, String key, boolean defaultValue) {
        String raw = properties.getProperty(key);
        return raw == null ? defaultValue : Boolean.parseBoolean(raw.trim());
    }

    private static Duration duration(Properties properties, String key, String defaultValue) {
        return parseDuration(properties.getProperty(key, defaultValue));
    }

    private static String detectHostName() {
        String environmentHost = System.getenv("HOSTNAME");
        if (environmentHost == null || environmentHost.isBlank()) {
            environmentHost = System.getenv("COMPUTERNAME");
        }
        if (environmentHost != null && !environmentHost.isBlank()) {
            return environmentHost;
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {
            return "unknown-host";
        }
    }
}
