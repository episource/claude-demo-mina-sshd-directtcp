package com.example.sshddemo.server;

import java.io.IOException;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.Collections;

import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.auth.pubkey.KeySetPublickeyAuthenticator;
import org.apache.sshd.server.channel.ChannelSessionFactory;
import org.apache.sshd.server.forward.RejectAllForwardingFilter;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;

/**
 * Sets up and starts an embedded {@link SshServer} whose only meaningful channel type is {@code direct-tcpip},
 * handled by {@link EchoServerChannel} - i.e. echoed back to the client instead of being forwarded to a real
 * target host/port.
 */
public final class DemoServer implements AutoCloseable {

    private final SshServer sshServer;

    private DemoServer(SshServer sshServer) {
        this.sshServer = sshServer;
    }

    public static DemoServer start(int port, Path hostKeyPath, PublicKey authorizedClientKey) throws IOException {
        SshServer server = SshServer.setUpDefaultServer();
        server.setPort(port);

        KeyPairProvider hostKeyProvider = new SimpleGeneratorHostKeyProvider(hostKeyPath);
        server.setKeyPairProvider(hostKeyProvider);

        // Demo only: accepts exactly one hardcoded client key. Do not use in production.
        server.setPublickeyAuthenticator(
                new KeySetPublickeyAuthenticator("demo-client-key", Collections.singleton(authorizedClientKey)));

        // Explicit-forwarding ("remote port forwarding") is unrelated to what this demo does and is disabled;
        // only a "session" channel (unused here, kept for protocol completeness) and our custom "direct-tcpip"
        // echo channel are registered.
        server.setForwardingFilter(RejectAllForwardingFilter.INSTANCE);
        server.setChannelFactories(Arrays.asList(
                ChannelSessionFactory.INSTANCE,
                new EchoChannelFactory()));

        server.start();
        return new DemoServer(server);
    }

    public int getPort() {
        return sshServer.getPort();
    }

    @Override
    public void close() throws IOException {
        sshServer.stop();
    }
}
