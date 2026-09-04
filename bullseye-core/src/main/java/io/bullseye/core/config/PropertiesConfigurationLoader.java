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

    public BullseyeConfiguration load(Path path) throws IOException {
        Properties properties = new Properties();
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
        }

        String host = text(properties, "bullseye.host", detectHostName());
        String application = text(properties, "bullseye.application", "host");
        Duration samplingInterval = duration(properties, "bullseye.sampling.interval", "5s");
        Duration windowDuration = duration(properties, "bullseye.window.duration", "30m");
        Path stateOutput = Path.of(text(
                properties,
                "bullseye.state.output",
                "/run/bullseye/state.json"
        ));

        BullseyeConfiguration.CpuDiagnostics cpu = new BullseyeConfiguration.CpuDiagnostics(
                decimal(properties, "bullseye.diagnostics.cpu.elevated.threshold", 70),
                duration(properties, "bullseye.diagnostics.cpu.elevated.duration", "10s"),
                decimal(properties, "bullseye.diagnostics.cpu.high.threshold", 80),
                duration(properties, "bullseye.diagnostics.cpu.high.duration", "10s"),
                decimal(properties, "bullseye.diagnostics.cpu.high.minimum-rise", 0),
                decimal(properties, "bullseye.diagnostics.cpu.critical.threshold", 90),
                duration(properties, "bullseye.diagnostics.cpu.critical.duration", "5s"),
                decimal(properties, "bullseye.diagnostics.cpu.recovery.threshold", 65),
                duration(properties, "bullseye.diagnostics.cpu.recovery.duration", "30s")
        );

        return new BullseyeConfiguration(
                host,
                application,
                samplingInterval,
                windowDuration,
                stateOutput,
                cpu
        );
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
