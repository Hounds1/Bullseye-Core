package io.bullseye.common.workload;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class WorkloadIdentityTest {

    @Test
    void identifiesSystemdService() {
        var identity = WorkloadIdentity.fromCgroupPath("/system.slice/order-api.service");

        assertEquals("order-api.service", identity.name());
        assertEquals(WorkloadIdentity.Type.SYSTEMD_SERVICE, identity.type());
    }

    @Test
    void identifiesDockerContainerWithoutExternalApi() {
        var identity =
                WorkloadIdentity.fromCgroupPath(
                        "/system.slice/docker-0123456789abcdef0123456789abcdef.scope");

        assertEquals("0123456789ab", identity.name());
        assertEquals(WorkloadIdentity.Type.DOCKER_CONTAINER, identity.type());
    }

    @Test
    void preservesGenericCgroup() {
        var identity = WorkloadIdentity.fromCgroupPath("/tenant/batch-workers");

        assertEquals("batch-workers", identity.name());
        assertEquals(WorkloadIdentity.Type.GENERIC_CGROUP, identity.type());
    }
}
