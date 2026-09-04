package io.bullseye.core.collection;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class NioProcFileSource implements ProcFileSource {

    @Override
    public String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.US_ASCII);
    }
}
