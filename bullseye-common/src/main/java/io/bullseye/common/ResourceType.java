package io.bullseye.common;

public enum ResourceType {
    HOST_CPU,
    HOST_MEMORY,
    JVM_HEAP,
    JVM_GC,
    THREAD_POOL,
    DB_CONNECTION_POOL,
    REQUEST_QUEUE,
    NETWORK,
    DISK_IO,
    UNKNOWN
}
