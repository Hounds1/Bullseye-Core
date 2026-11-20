package io.bullseye.core.uog.ccc;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.bullseye.common.diagnostic.DiagnosticSnapshot;
import io.bullseye.core.logging.BullseyeLogger;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.Socket;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

class TccSnapshotSenderTest {
    @Test
    void timeoutClosesTcpEvenWhenTheWriterDoesNotReturn() throws Exception {
        var writeStarted = new CountDownLatch(1);
        var transportClosed = new CountDownLatch(1);
        var socket = new Socket() {
            @Override
            public void close() {
                transportClosed.countDown();
            }
        };
        var output = new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                writeStarted.countDown();
                try {
                    if (!transportClosed.await(10, TimeUnit.SECONDS)) {
                        throw new AssertionError("The transport stayed open while writing");
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                }
                throw new IOException("Transport closed");
            }
        };

        try (var logs = new PrintStream(OutputStream.nullOutputStream())) {
            var sender = new TccSnapshotSender(
                    "edge-01", UUID.randomUUID(), new BullseyeLogger(logs, logs));
            sender.offer(DiagnosticSnapshot.initial("host", "core", 1_000));
            try (var session = sender.open(socket, output, 400, 10_000)) {
                assertTrue(writeStarted.await(5, TimeUnit.SECONDS));
                assertTrue(transportClosed.await(5, TimeUnit.SECONDS));
            }
        }
    }
}
