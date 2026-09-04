package io.bullseye.nativehost;

public interface NativeHostRuntime {

    boolean available();

    HostNativeSnapshot snapshot();
}
