package com.example.sshddemo;

import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;

import org.apache.sshd.client.SshClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.example.sshddemo.server.DemoServer;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the direct-tcpip round trip end to end: whatever the client sends over the channel comes back from
 * {@link DemoServer} with a literal {@code !} prepended, and {@link App#runClient} prints it to its console output.
 *
 * <p>Input is fed through a live pipe rather than a fixed buffer, and each line is only sent once the previous
 * line's echo has actually appeared in the captured output - otherwise the console-reading loop could race ahead
 * and close the channel before a pending echo arrives (reproduced manually while verifying the echo feature: a
 * fast, non-interactive input stream let the channel close while the server's last echo was still in flight).
 */
class DirectTcpipEchoTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(10);

    @Test
    void serverEchoesEachLineBackWithExclamationMarkPrefix(@TempDir Path tempDir) throws Exception {
        KeyPair clientKeyPair = App.loadClientKeyPair();

        ByteArrayOutputStream capturedOutput = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(capturedOutput, true, StandardCharsets.UTF_8);
        PipedOutputStream consoleInput = new PipedOutputStream();
        PipedInputStream in = new PipedInputStream(consoleInput);

        try (DemoServer server = DemoServer.start(0, tempDir.resolve("hostkey.ser"), clientKeyPair.getPublic());
                SshClient client = SshClient.setUpDefaultClient()) {
            client.start();

            Thread runClientThread = new Thread(
                    () -> {
                        try {
                            App.runClient(client, server.getPort(), clientKeyPair, in, out);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    },
                    "run-client-under-test");
            runClientThread.start();

            sendLine(consoleInput, "hello");
            awaitOutputContains(capturedOutput, "!hello");

            sendLine(consoleInput, "world");
            awaitOutputContains(capturedOutput, "!world");

            sendLine(consoleInput, "exit");

            runClientThread.join(TIMEOUT.toMillis());
            assertTrue(!runClientThread.isAlive(), "runClient did not finish after 'exit'");

            client.stop();
        }

        String output = capturedOutput.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("!hello"), "expected echoed \"!hello\" in output, got: " + output);
        assertTrue(output.contains("!world"), "expected echoed \"!world\" in output, got: " + output);
    }

    private static void sendLine(PipedOutputStream consoleInput, String line) throws Exception {
        consoleInput.write((line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
        consoleInput.flush();
    }

    private static void awaitOutputContains(ByteArrayOutputStream capturedOutput, String expected) throws InterruptedException {
        Instant deadline = Instant.now().plus(AWAIT_TIMEOUT);
        while (!capturedOutput.toString(StandardCharsets.UTF_8).contains(expected)) {
            assertTrue(Instant.now().isBefore(deadline),
                    "timed out waiting for \"" + expected + "\" in output, got: " + capturedOutput.toString(StandardCharsets.UTF_8));
            Thread.sleep(50);
        }
    }
}
