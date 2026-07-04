package com.example.sshddemo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
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
 * echoes each chunk back with a literal {@code !} prepended; the client prints whatever it gets back to its console.
 *
 * <p>Neither side touches an extra network socket for the tunneled data: the client feeds bytes into the channel via
 * {@link ChannelDirectTcpip#getInvertedIn()} and reads echoed responses via {@link ChannelDirectTcpip#getInvertedOut()}
 * (no local port is bound), and the server echoes bytes back over the same channel from within its custom channel
 * implementation (no outbound connection is made on either side).
 */
public final class App {

    private static final int SERVER_PORT = 2222;
    private static final String USERNAME = "demo";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    // Demo only: a real deployment would never bundle a private key with the application. This
    // RSA 4096 key pair (src/main/resources/demo-client-key.pem) is the sole accepted client
    // identity, shared here since client and server both run in this one JVM (loaded once, then
    // its private half authenticates the client while its derived public half is handed to the
    // server as the only key it will accept).
    private static final String CLIENT_KEY_RESOURCE = "/demo-client-key.pem";

    public static void main(String[] args) throws Exception {
        Path hostKeyPath = Paths.get("demo-hostkey.ser");
        KeyPair clientKeyPair = loadClientKeyPair();

        System.out.println("Starting embedded SSH server on port " + SERVER_PORT + " ...");
        try (DemoServer server = DemoServer.start(SERVER_PORT, hostKeyPath, System.out, clientKeyPair.getPublic())) {
            System.out.println("Server listening on port " + server.getPort());

            try (SshClient client = SshClient.setUpDefaultClient()) {
                client.start();
                runClient(client, server.getPort(), clientKeyPair, System.in, System.out);
                client.stop();
            }
        }
    }

    static KeyPair loadClientKeyPair() throws IOException, GeneralSecurityException {
        try (InputStream keyStream = App.class.getResourceAsStream(CLIENT_KEY_RESOURCE)) {
            Collection<KeyPair> keyPairs = PEMResourceParserUtils.PROXY.loadKeyPairs(
                    null, NamedResource.ofName(CLIENT_KEY_RESOURCE), FilePasswordProvider.EMPTY, keyStream);
            return keyPairs.iterator().next();
        }
    }

    static void runClient(SshClient client, int port, KeyPair clientKeyPair, InputStream in, PrintStream out) throws IOException {
        out.println("Connecting SSH client to 127.0.0.1:" + port + " ...");
        try (ClientSession session = client.connect(USERNAME, "127.0.0.1", port)
                .verify(TIMEOUT)
                .getSession()) {
            session.addPublicKeyIdentity(clientKeyPair);
            session.auth().verify(TIMEOUT);
            out.println("Client authenticated.");

            // These addresses are never used to open a real socket on either end: the server captures the data
            // itself instead of forwarding it to "targetHost:targetPort", and the client never binds a local port
            // for "localHost:localPort" - it feeds data into the channel directly, see pumpConsoleToChannel().
            SshdSocketAddress localAddress = new SshdSocketAddress(InetAddress.getLoopbackAddress().getHostAddress(), 0);
            SshdSocketAddress targetAddress = new SshdSocketAddress("console-capture", 0);

            try (ChannelDirectTcpip channel = session.createDirectTcpipChannel(localAddress, targetAddress)) {
                channel.open().verify(TIMEOUT);
                out.println("direct-tcpip channel open.");
                out.println("Type text and press Enter to send it to the server; type 'exit' to quit.");

                // ChannelDirectTcpip.doWriteData() always feeds inbound bytes into its piped stream, ignoring
                // ClientChannel.setOut(); reading channel.getInvertedOut() is the only way to receive them. Run
                // that on a background thread since the main thread blocks reading console input for the other
                // direction of the channel.
                Thread responseReader = new Thread(() -> printChannelResponses(channel, out), "response-reader");
                responseReader.setDaemon(true);
                responseReader.start();

                pumpConsoleToChannel(channel, in);
            }
        }
    }

    /**
     * Reads whatever the server echoes back over the channel and prints it to the console, until the channel is
     * closed.
     */
    private static void printChannelResponses(ChannelDirectTcpip channel, PrintStream out) {
        try (InputStream fromServer = channel.getInvertedOut()) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = fromServer.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                out.flush();
            }
        } catch (IOException e) {
            // Expected once the channel is closed while this thread is blocked in read().
        }
    }

    /**
     * Reads lines from the console and writes them straight into the channel's outbound stream - no local socket,
     * server socket, or port forwarding is involved anywhere in this path.
     */
    private static void pumpConsoleToChannel(ChannelDirectTcpip channel, InputStream in) throws IOException {
        OutputStream toServer = channel.getInvertedIn();
        try (BufferedReader console = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
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
