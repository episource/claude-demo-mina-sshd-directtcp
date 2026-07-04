package com.example.sshddemo;

import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.example.sshddemo.server.DemoServer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Proves that {@link DemoServer}'s public-key authenticator accepts only the hardcoded demo key and rejects
 * anything else, including password authentication (which is not configured at all anymore).
 */
class PublicKeyAuthenticationTest {

    private static final String USERNAME = "demo";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Test
    void authorizedKeyIsAccepted(@TempDir Path tempDir) throws Exception {
        KeyPair authorizedKeyPair = App.loadClientKeyPair();

        try (DemoServer server = startServer(tempDir, authorizedKeyPair);
                SshClient client = SshClient.setUpDefaultClient()) {
            client.start();

            try (ClientSession session = connect(client, server.getPort())) {
                session.addPublicKeyIdentity(authorizedKeyPair);
                assertDoesNotThrow(() -> session.auth().verify(TIMEOUT));
            }

            client.stop();
        }
    }

    @Test
    void unauthorizedKeyIsRejected(@TempDir Path tempDir) throws Exception {
        KeyPair authorizedKeyPair = App.loadClientKeyPair();
        KeyPair otherKeyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();

        try (DemoServer server = startServer(tempDir, authorizedKeyPair);
                SshClient client = SshClient.setUpDefaultClient()) {
            client.start();

            try (ClientSession session = connect(client, server.getPort())) {
                session.addPublicKeyIdentity(otherKeyPair);
                assertThrows(Exception.class, () -> session.auth().verify(TIMEOUT));
            }

            client.stop();
        }
    }

    @Test
    void passwordAuthenticationIsRejected(@TempDir Path tempDir) throws Exception {
        KeyPair authorizedKeyPair = App.loadClientKeyPair();

        try (DemoServer server = startServer(tempDir, authorizedKeyPair);
                SshClient client = SshClient.setUpDefaultClient()) {
            client.start();

            try (ClientSession session = connect(client, server.getPort())) {
                session.addPasswordIdentity(USERNAME);
                assertThrows(Exception.class, () -> session.auth().verify(TIMEOUT));
            }

            client.stop();
        }
    }

    private static DemoServer startServer(Path tempDir, KeyPair authorizedKeyPair) throws Exception {
        return DemoServer.start(0, tempDir.resolve("hostkey.ser"), System.out, authorizedKeyPair.getPublic());
    }

    private static ClientSession connect(SshClient client, int port) throws Exception {
        return client.connect(USERNAME, "127.0.0.1", port).verify(TIMEOUT).getSession();
    }
}
