package com.example.sshddemo.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;

import org.apache.sshd.client.future.OpenFuture;
import org.apache.sshd.common.Closeable;
import org.apache.sshd.common.SshConstants;
import org.apache.sshd.common.channel.ChannelAsyncOutputStream;
import org.apache.sshd.common.channel.LocalWindow;
import org.apache.sshd.common.io.IoWriteFuture;
import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.common.util.buffer.ByteArrayBuffer;
import org.apache.sshd.server.channel.AbstractServerChannel;

/**
 * Server-side {@code direct-tcpip} channel that never opens an outbound socket. The host/port/originator fields
 * carried by the {@code SSH_MSG_CHANNEL_OPEN} request are consumed (so the buffer stays well-formed) but otherwise
 * ignored - instead of forwarding anywhere, every line of channel data received from the peer is echoed straight
 * back to it with a literal {@code !} prepended to the start of each line, regardless of how the underlying SSH
 * transport chunks the byte stream across {@code doWriteData} calls.
 */
public final class EchoServerChannel extends AbstractServerChannel {

    private ChannelAsyncOutputStream asyncOut;

    // Tracks whether the next byte to be written begins a new line, so the '!' marker is inserted once per line
    // rather than once per received chunk - a line may span many doWriteData() calls (the SSH transport chunks
    // data at packet-size/window boundaries with no regard for line content), so this state must persist across
    // calls. Keyed off the '\n' byte alone (0x0A): this covers both Unix ("\n") and Windows ("\r\n") line endings
    // without special-casing, including a "\r\n" split across two chunks, since only the '\n' half triggers the
    // next byte to start a new line.
    private boolean atLineStart = true;

    // ChannelAsyncOutputStream.writeBuffer() forbids overlapping writes - it throws WritePendingException if called
    // again before the previous write's future has completed. doWriteData() can be invoked repeatedly by the
    // session's reader thread faster than a large echo finishes writing (e.g. a multi-megabyte line split across
    // many SSH_MSG_CHANNEL_DATA chunks), so pending echoes are queued here and written out strictly one at a time,
    // advancing to the next only once the previous write's future completes (via its listener) - this preserves
    // both the write-serialization contract and the received data's ordering (and thus the per-line '!' state).
    private final Deque<ByteArrayBuffer> pendingEchoes = new ArrayDeque<>();
    private boolean echoWriteInProgress;

    public EchoServerChannel() {
        super("", Collections.emptyList(), null);
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

        System.out.printf(
                "[server] direct-tcpip channel opened (requested target=%s:%d, originator=%s:%d) - echoing back to client, no forwarding%n",
                hostToConnect, portToConnect, originatorIpAddress, originatorPort);

        // AbstractServerChannel's default doInit() immediately signals a successful channel open.
        OpenFuture future = super.doInit(buffer);
        asyncOut = new ChannelAsyncOutputStream(this, SshConstants.SSH_MSG_CHANNEL_DATA);
        return future;
    }

    @Override
    protected void doWriteData(byte[] data, int off, long len) throws IOException {
        int length = (int) len;
        ByteArrayOutputStream echo = new ByteArrayOutputStream(length + 1);
        for (int i = 0; i < length; i++) {
            byte b = data[off + i];
            if (atLineStart) {
                echo.write('!');
                atLineStart = false;
            }
            echo.write(b);
            if (b == '\n') {
                atLineStart = true;
            }
        }
        enqueueEcho(new ByteArrayBuffer(echo.toByteArray()));

        // Replenish the local window so the peer keeps sending; mirrors what every other channel implementation
        // (e.g. ChannelSession) does once it has consumed the bytes handed to it.
        LocalWindow localWindow = getLocalWindow();
        localWindow.release(len);
    }

    private void enqueueEcho(ByteArrayBuffer buffer) throws IOException {
        synchronized (pendingEchoes) {
            if (echoWriteInProgress) {
                pendingEchoes.add(buffer);
                return;
            }
            echoWriteInProgress = true;
        }
        writeNextEcho(buffer);
    }

    private void writeNextEcho(ByteArrayBuffer buffer) throws IOException {
        IoWriteFuture future = asyncOut.writeBuffer(buffer);
        future.addListener(f -> {
            ByteArrayBuffer next;
            synchronized (pendingEchoes) {
                next = pendingEchoes.poll();
                if (next == null) {
                    echoWriteInProgress = false;
                }
            }
            if (next != null) {
                try {
                    writeNextEcho(next);
                } catch (IOException e) {
                    // Channel is most likely closing/closed; nothing sensible to do with a queued echo at this point.
                }
            }
        });
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
