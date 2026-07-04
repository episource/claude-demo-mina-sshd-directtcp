package com.example.sshddemo.server;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import org.apache.sshd.client.future.OpenFuture;
import org.apache.sshd.common.Closeable;
import org.apache.sshd.common.SshConstants;
import org.apache.sshd.common.channel.ChannelAsyncOutputStream;
import org.apache.sshd.common.channel.LocalWindow;
import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.common.util.buffer.ByteArrayBuffer;
import org.apache.sshd.server.channel.AbstractServerChannel;

/**
 * Server-side {@code direct-tcpip} channel that never opens an outbound socket. The host/port/originator fields
 * carried by the {@code SSH_MSG_CHANNEL_OPEN} request are consumed (so the buffer stays well-formed) but otherwise
 * ignored - instead of forwarding anywhere, every chunk of channel data received from the peer is echoed straight
 * back to it with a literal {@code !} prepended.
 */
public final class EchoServerChannel extends AbstractServerChannel {

    private final PrintStream sink;

    private ChannelAsyncOutputStream asyncOut;

    public EchoServerChannel(PrintStream sink) {
        super("", Collections.emptyList(), null);
        this.sink = sink;
    }

    @Override
    protected OpenFuture doInit(Buffer buffer) {
        // Standard direct-tcpip channel-open payload: host-to-connect, port-to-connect, originator IP, originator
        // port. We read (and discard) them purely to keep the parser conventions of a real direct-tcpip channel;
        // no connection is ever made to the requested target.
        String hostToConnect = buffer.getString();
        int portToConnect = buffer.getInt();
        String originatorIpAddress = buffer.getString();
        int originatorPort = buffer.getInt();

        sink.printf(
                "[server] direct-tcpip channel opened (requested target=%s:%d, originator=%s:%d) - echoing back to client, no forwarding%n",
                hostToConnect, portToConnect, originatorIpAddress, originatorPort);

        // AbstractServerChannel's default doInit() immediately signals a successful channel open.
        OpenFuture future = super.doInit(buffer);
        asyncOut = new ChannelAsyncOutputStream(this, SshConstants.SSH_MSG_CHANNEL_DATA);
        return future;
    }

    @Override
    protected void doWriteData(byte[] data, int off, long len) throws IOException {
        String received = new String(data, off, (int) len, StandardCharsets.UTF_8);
        byte[] echoBytes = ("!" + received).getBytes(StandardCharsets.UTF_8);
        asyncOut.writeBuffer(new ByteArrayBuffer(echoBytes));

        // Replenish the local window so the peer keeps sending; mirrors what every other channel implementation
        // (e.g. ChannelSession) does once it has consumed the bytes handed to it.
        LocalWindow localWindow = getLocalWindow();
        localWindow.release(len);
    }

    @Override
    protected void doWriteExtendedData(byte[] data, int off, long len) throws IOException {
        throw new UnsupportedOperationException("direct-tcpip channel does not support extended data");
    }

    @Override
    public void handleWindowAdjust(Buffer buffer) throws IOException {
        super.handleWindowAdjust(buffer);
        asyncOut.onWindowExpanded();
    }

    @Override
    protected Closeable getInnerCloseable() {
        return builder().close(asyncOut).close(super.getInnerCloseable()).build();
    }
}
