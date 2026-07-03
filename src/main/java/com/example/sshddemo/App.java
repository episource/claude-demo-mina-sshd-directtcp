package com.example.sshddemo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelDirectTcpip;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.util.net.SshdSocketAddress;

import com.example.sshddemo.server.DemoServer;

/**
 * Self-contained demo: starts an embedded Mina SSHD server and an Mina SSHD client in the same JVM, has the client
 * connect and open a {@code direct-tcpip} channel, then streams console input over that channel to the server, which
 * prints whatever it receives.
 *
 * <p>Neither side touches an extra network socket for the tunneled data: the client feeds bytes into the channel via
 * {@link ChannelDirectTcpip#getInvertedIn()} (no local port is bound) and the server writes received bytes directly
 * to {@link System#out} from within its custom channel implementation (no outbound connection is made).
 */
public final class App {

    private static final int SERVER_PORT = 2222;
    private static final String USERNAME = "demo";
    private static final String PASSWORD = "demo";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    public static void main(String[] args) throws Exception {
        Path hostKeyPath = Paths.get("demo-hostkey.ser");

        System.out.println("Starting embedded SSH server on port " + SERVER_PORT + " ...");
        try (DemoServer server = DemoServer.start(SERVER_PORT, hostKeyPath, System.out)) {
            System.out.println("Server listening on port " + server.getPort());

            try (SshClient client = SshClient.setUpDefaultClient()) {
                client.start();
                runClient(client, server.getPort());
                client.stop();
            }
        }
    }

    private static void runClient(SshClient client, int port) throws IOException {
        System.out.println("Connecting SSH client to 127.0.0.1:" + port + " ...");
        try (ClientSession session = client.connect(USERNAME, "127.0.0.1", port)
                .verify(TIMEOUT)
                .getSession()) {
            session.addPasswordIdentity(PASSWORD);
            session.auth().verify(TIMEOUT);
            System.out.println("Client authenticated.");

            // These addresses are never used to open a real socket on either end: the server captures the data
            // itself instead of forwarding it to "targetHost:targetPort", and the client never binds a local port
            // for "localHost:localPort" - it feeds data into the channel directly, see pumpConsoleToChannel().
            SshdSocketAddress localAddress = new SshdSocketAddress(InetAddress.getLoopbackAddress().getHostAddress(), 0);
            SshdSocketAddress targetAddress = new SshdSocketAddress("console-capture", 0);

            try (ChannelDirectTcpip channel = session.createDirectTcpipChannel(localAddress, targetAddress)) {
                channel.open().verify(TIMEOUT);
                System.out.println("direct-tcpip channel open.");
                System.out.println("Type text and press Enter to send it to the server; type 'exit' to quit.");

                pumpConsoleToChannel(channel);
            }
        }
    }

    /**
     * Reads lines from the console and writes them straight into the channel's outbound stream - no local socket,
     * server socket, or port forwarding is involved anywhere in this path.
     */
    private static void pumpConsoleToChannel(ChannelDirectTcpip channel) throws IOException {
        OutputStream toServer = channel.getInvertedIn();
        try (BufferedReader console = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = console.readLine()) != null) {
                if ("exit".equalsIgnoreCase(line.trim())) {
                    break;
                }
                byte[] bytes = (line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
                toServer.write(bytes);
                toServer.flush();
            }
        }
    }
}
