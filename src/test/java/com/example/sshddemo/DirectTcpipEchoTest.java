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
import java.util.Arrays;

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
    private static final Duration HUGE_LINES_AWAIT_TIMEOUT = Duration.ofSeconds(60);
    private static final int HUGE_LINE_COUNT = 10;
    private static final int HUGE_LINE_UNIT_BYTES = 1_000_000;

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

    /**
     * Sends 10 huge lines (1MB, 2MB, ..., 10MB, decimal megabytes) over the same echo round trip and checks the
     * response. {@link com.example.sshddemo.server.EchoServerChannel} prepends exactly one {@code !} per logical
     * line regardless of how many {@code SSH_MSG_CHANNEL_DATA} chunks a huge line is split across in transit, so
     * each line's echo is expected to be a single {@code !} followed by the original content and line separator -
     * the same shape as the small-line assertions above, just at megabyte scale.
     */
    @Test
    void serverEchoesTenIncreasinglyHugeLines(@TempDir Path tempDir) throws Exception {
        KeyPair clientKeyPair = App.loadClientKeyPair();

        ByteArrayOutputStream capturedOutput = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(capturedOutput, true, StandardCharsets.UTF_8);
        PipedOutputStream consoleInput = new PipedOutputStream();
        PipedInputStream in = new PipedInputStream(consoleInput, 1 << 16);

        StringBuilder expectedStream = new StringBuilder();

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
                    "run-client-under-test-huge-lines");
            runClientThread.start();

            for (int i = 1; i <= HUGE_LINE_COUNT; i++) {
                String hugeLine = generateHugeLine(i);
                sendLine(consoleInput, hugeLine);
                expectedStream.append('!').append(hugeLine).append(System.lineSeparator());
                awaitOutputContains(capturedOutput, expectedStream.toString(), HUGE_LINES_AWAIT_TIMEOUT);
            }

            sendLine(consoleInput, "exit");

            runClientThread.join(TIMEOUT.toMillis());
            assertTrue(!runClientThread.isAlive(), "runClient did not finish after 'exit'");

            client.stop();
        }

        String output = capturedOutput.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains(expectedStream.toString()),
                "expected all " + HUGE_LINE_COUNT + " huge lines, each with a single '!' prefix, in order");
    }

    /** Generates a deterministic line of exactly {@code index * HUGE_LINE_UNIT_BYTES} ASCII bytes. */
    private static String generateHugeLine(int index) {
        char marker = (char) ('a' + (index - 1));
        char[] chars = new char[index * HUGE_LINE_UNIT_BYTES];
        Arrays.fill(chars, marker);
        return new String(chars);
    }

    private static void sendLine(PipedOutputStream consoleInput, String line) throws Exception {
        consoleInput.write((line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
        consoleInput.flush();
    }

    private static void awaitOutputContains(ByteArrayOutputStream capturedOutput, String expected) throws InterruptedException {
        awaitOutputContains(capturedOutput, expected, AWAIT_TIMEOUT);
    }

    /**
     * Waits until {@code expected} appears in the captured output. Cheaply gates on the raw captured byte count
     * first (no decoding) since that count can only reach {@code expected}'s length once all of it has actually
     * arrived; only once that gate passes does it pay for the UTF-8 decode needed for an exact containment check.
     * This keeps polling cheap for the megabyte-scale huge-line assertions, not just the small ones.
     */
    private static void awaitOutputContains(ByteArrayOutputStream capturedOutput, String expected, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (true) {
            if (capturedOutput.size() >= expected.length() && capturedOutput.toString(StandardCharsets.UTF_8).contains(expected)) {
                return;
            }
            assertTrue(Instant.now().isBefore(deadline),
                    "timed out waiting for expected content (length=" + expected.length() + ") in output; captured "
                            + capturedOutput.size() + " raw bytes so far");
            Thread.sleep(50);
        }
    }
}
