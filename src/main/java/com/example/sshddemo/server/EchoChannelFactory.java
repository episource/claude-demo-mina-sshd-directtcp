package com.example.sshddemo.server;

import java.io.IOException;

import org.apache.sshd.common.channel.Channel;
import org.apache.sshd.common.channel.ChannelFactory;
import org.apache.sshd.common.session.Session;

/**
 * Server-side factory for the SSH {@code direct-tcpip} channel type. Instead of the standard behaviour (opening a
 * socket to the host/port requested by the client and relaying bytes to/from it), every channel produced here echoes
 * received data straight back to the client with a {@code !} prepended. No target socket is ever opened.
 */
public final class EchoChannelFactory implements ChannelFactory {

    /** The channel type name used in the SSH {@code SSH_MSG_CHANNEL_OPEN} request. */
    public static final String DIRECT_TCPIP_CHANNEL_TYPE = "direct-tcpip";

    @Override
    public String getName() {
        return DIRECT_TCPIP_CHANNEL_TYPE;
    }

    @Override
    public Channel createChannel(Session session) throws IOException {
        return new EchoServerChannel();
    }
}
