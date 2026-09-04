package io.bullseye.core.publish;

import io.bullseye.common.DiagnosticSnapshot;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

public final class AtomicFileStatePublisher implements StatePublisher {

    private final Path target;
    private final DiagnosticSnapshotJsonEncoder encoder;

    public AtomicFileStatePublisher(Path target, DiagnosticSnapshotJsonEncoder encoder) {
        this.target = Objects.requireNonNull(target, "target").toAbsolutePath();
        this.encoder = Objects.requireNonNull(encoder, "encoder");
    }

    @Override
    public synchronized void publish(DiagnosticSnapshot state) throws IOException {
        Path parent = target.getParent();
        if (parent == null) {
            throw new IOException("State output has no parent directory: " + target);
        }
        Files.createDirectories(parent);
        Path temporary = parent.resolve(target.getFileName() + ".tmp");
        byte[] content = encoder.encode(state).getBytes(StandardCharsets.UTF_8);

        try (FileChannel channel = FileChannel.open(
                temporary,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        )) {
            ByteBuffer buffer = ByteBuffer.wrap(content);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }

        try {
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException failure) {
            throw new IOException("Atomic state replacement is not supported for " + target, failure);
        }
        forceDirectoryBestEffort(parent);
    }

    private static void forceDirectoryBestEffort(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // The state file is already atomically visible. Some platforms cannot fsync directories.
        }
    }
}
