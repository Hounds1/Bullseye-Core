package io.bullseye.core.collection;

import java.io.IOException;
import java.nio.file.Path;

@FunctionalInterface
public interface ProcFileSource {

    String read(Path path) throws IOException;
}
