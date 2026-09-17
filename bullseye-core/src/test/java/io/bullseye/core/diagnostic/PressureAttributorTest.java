package io.bullseye.core.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.bullseye.common.diagnostic.ResourceType;
import io.bullseye.common.workload.WorkloadAttribution;
import io.bullseye.common.workload.WorkloadIdentity;
import io.bullseye.core.linux.cgroup.CgroupResourceSnapshot;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

class PressureAttributorTest {

    private final PressureAttributor attributor =
            new PressureAttributor(5, 1.2, Duration.ofSeconds(15));

    @Test
    void attributesHostCpuPressureToDominantCpuCgroup() {
        var result =
                attributor.attribute(
                        ResourceType.HOST_CPU,
                        List.of(
                                snapshot("order-api.service", 200, 20, 0, 0, 0, 0, 1_000),
                                snapshot("batch.service", 50, 2, 0, 0, 0, 0, 1_000)),
                        1_000);

        assertEquals("order-api.service", result.workload().name());
        assertEquals(WorkloadAttribution.Confidence.HIGH, result.confidence());
    }

    @Test
    void attributesMemoryPressureUsingSameResourceSignals() {
        var result =
                attributor.attribute(
                        ResourceType.HOST_MEMORY,
                        List.of(
                                snapshot("order-api.service", 500, 0, 30, 8, 0, 0, 1_000),
                                snapshot("cache.service", 500, 0, 3, 0, 0, 0, 1_000)),
                        1_000);

        assertEquals("order-api.service", result.workload().name());
        assertTrue(result.resolved());
    }

    @Test
    void attributesIoPressureUsingIoPsiOnly() {
        var result =
                attributor.attribute(
                        ResourceType.DISK_IO,
                        List.of(
                                snapshot("batch.service", 500, 50, 50, 10, 40, 12, 1_000),
                                snapshot("web.service", 5, 0, 0, 0, 2, 0, 1_000)),
                        1_000);

        assertEquals("batch.service", result.workload().name());
    }

    @Test
    void closeCandidatesLowerConfidence() {
        var result =
                attributor.attribute(
                        ResourceType.DISK_IO,
                        List.of(
                                snapshot("first.service", 0, 0, 0, 0, 15, 0, 1_000),
                                snapshot("second.service", 0, 0, 0, 0, 10, 0, 1_000)),
                        1_000);

        assertTrue(result.resolved());
        assertFalse(result.confidence() == WorkloadAttribution.Confidence.HIGH);
    }

    @Test
    void leavesAttributionUnknownWhenCandidatesAreIndistinguishable() {
        var result =
                attributor.attribute(
                        ResourceType.HOST_MEMORY,
                        List.of(
                                snapshot("first.service", 0, 0, 10, 1, 0, 0, 1_000),
                                snapshot("second.service", 0, 0, 9.8, 1, 0, 0, 1_000)),
                        1_000);

        assertFalse(result.resolved());
        assertEquals("UNKNOWN", result.workload().name());
    }

    @Test
    void staleCgroupSamplesCannotBeAttributed() {
        var result =
                attributor.attribute(
                        ResourceType.HOST_CPU,
                        List.of(snapshot("old.service", 300, 50, 0, 0, 0, 0, 1_000)),
                        20_000);

        assertFalse(result.resolved());
    }

    @Test
    void memoryOccupancyWithoutLocalPressureIsNotBlamed() {
        var result =
                attributor.attribute(
                        ResourceType.HOST_MEMORY,
                        List.of(snapshot("cache.service", 0, 0, 0, 0, 0, 0, 1_000)),
                        1_000);

        assertFalse(result.resolved());
    }

    private static CgroupResourceSnapshot snapshot(
            String service,
            double cpu,
            double cpuPsi,
            double memoryPsi,
            double memoryFull,
            double ioPsi,
            double ioFull,
            long timestamp) {
        return new CgroupResourceSnapshot(
                WorkloadIdentity.fromCgroupPath("/system.slice/" + service),
                cpu,
                800,
                1_000,
                cpuPsi,
                memoryPsi,
                memoryFull,
                ioPsi,
                ioFull,
                timestamp);
    }
}
