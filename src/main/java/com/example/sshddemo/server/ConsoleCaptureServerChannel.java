package com.example.sshddemo.server;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Collections;

import org.apache.sshd.client.future.OpenFuture;
import org.apache.sshd.common.channel.LocalWindow;
import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.server.channel.AbstractServerChannel;

/**
 * Server-side {@code direct-tcpip} channel that never opens an outbound socket. The host/port/originator fields
 * carried by the {@code SSH_MSG_CHANNEL_OPEN} request are consumed (so the buffer stays well-formed) but otherwise
 * ignored - all channel data received from the peer is written directly to a local {@link PrintStream} instead of
 * being forwarded anywhere.
 */
public final class ConsoleCaptureServerChannel extends AbstractServerChannel {

    private final PrintStream sink;

    public ConsoleCaptureServerChannel(PrintStream sink) {
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
                "[server] direct-tcpip channel opened (requested target=%s:%d, originator=%s:%d) - capturing locally, no forwarding%n",
                hostToConnect, portToConnect, originatorIpAddress, originatorPort);

        // AbstractServerChannel's default doInit() immediately signals a successful channel open.
        return super.doInit(buffer);
    }

    @Override
    protected void doWriteData(byte[] data, int off, long len) throws IOException {
        sink.write(data, off, (int) len);
        sink.flush();

        // Replenish the local window so the peer keeps sending; mirrors what every other channel implementation
        // (e.g. ChannelSession) does once it has consumed the bytes handed to it.
        LocalWindow localWindow = getLocalWindow();
        localWindow.release(len);
    }

    @Override
    protected void doWriteExtendedData(byte[] data, int off, long len) throws IOException {
        throw new UnsupportedOperationException("direct-tcpip channel does not support extended data");
    }
}
