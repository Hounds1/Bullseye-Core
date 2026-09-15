package io.bullseye.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

class PropertiesConfigurationLoaderTest {

    @TempDir Path temporaryDirectory;

    @Test
    void loadsConfiguredValues() throws Exception {
        Path configurationPath = temporaryDirectory.resolve("bullseye.properties");
        Files.writeString(
                configurationPath,
                """
                bullseye.host=was-01
                bullseye.application=orders
                bullseye.sampling.interval=2s
                bullseye.window.duration=10m
                bullseye.state.output=/tmp/bullseye-state.json
                bullseye.diagnostics.cpu.high.minimum-rise=3.5
                bullseye.diagnostics.memory.high.psi-some=22
                bullseye.diagnostics.io.critical.psi-full=12
                bullseye.cgroup.discovery-interval=90s
                bullseye.cgroup.maximum-groups=128
                """);

        BullseyeConfiguration configuration =
                new PropertiesConfigurationLoader().load(configurationPath);

        assertEquals("was-01", configuration.host());
        assertEquals("orders", configuration.application());
        assertEquals(Duration.ofSeconds(2), configuration.samplingInterval());
        assertEquals(Duration.ofMinutes(10), configuration.windowDuration());
        assertEquals(3.5, configuration.cpuDiagnostics().highMinimumRise());
        assertEquals(22, configuration.memoryDiagnostics().highPsiSome());
        assertEquals(12, configuration.ioDiagnostics().criticalPsiFull());
        assertEquals(Duration.ofSeconds(90), configuration.cgroup().discoveryInterval());
        assertEquals(128, configuration.cgroup().maximumGroups());
    }

    @Test
    void rejectsUnsupportedDuration() {
        assertThrows(
                IllegalArgumentException.class,
                () -> PropertiesConfigurationLoader.parseDuration("five seconds"));
    }
}
