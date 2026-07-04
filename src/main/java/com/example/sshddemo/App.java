package com.example.sshddemo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.time.Duration;
import java.util.Collection;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelDirectTcpip;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.config.keys.loader.pem.PEMResourceParserUtils;
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
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    // Demo only: a real deployment would never embed a private key in source. This RSA 4096
    // key pair is the sole accepted client identity, shared here since client and server both
    // run in this one JVM (parsed once, then its private half authenticates the client while
    // its derived public half is handed to the server as the only key it will accept).
    private static final String PRIVATE_KEY_PEM = """
            -----BEGIN PRIVATE KEY-----
            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
            ALOwbnVrlXc9vPaLkGO88VBDX5q1G+Q=
            -----END PRIVATE KEY-----
            """;

    public static void main(String[] args) throws Exception {
        Path hostKeyPath = Paths.get("demo-hostkey.ser");
        KeyPair clientKeyPair = loadClientKeyPair();

        System.out.println("Starting embedded SSH server on port " + SERVER_PORT + " ...");
        try (DemoServer server = DemoServer.start(SERVER_PORT, hostKeyPath, System.out, clientKeyPair.getPublic())) {
            System.out.println("Server listening on port " + server.getPort());

            try (SshClient client = SshClient.setUpDefaultClient()) {
                client.start();
                runClient(client, server.getPort(), clientKeyPair);
                client.stop();
            }
        }
    }

    private static KeyPair loadClientKeyPair() throws IOException, GeneralSecurityException {
        Collection<KeyPair> keyPairs = PEMResourceParserUtils.PROXY.loadKeyPairs(
                null, NamedResource.ofName("demo-client-key"), FilePasswordProvider.EMPTY,
                new StringReader(PRIVATE_KEY_PEM));
        return keyPairs.iterator().next();
    }

    private static void runClient(SshClient client, int port, KeyPair clientKeyPair) throws IOException {
        System.out.println("Connecting SSH client to 127.0.0.1:" + port + " ...");
        try (ClientSession session = client.connect(USERNAME, "127.0.0.1", port)
                .verify(TIMEOUT)
                .getSession()) {
            session.addPublicKeyIdentity(clientKeyPair);
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
