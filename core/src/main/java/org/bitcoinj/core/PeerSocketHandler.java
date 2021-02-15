/*
 * Copyright 2013 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.bitcoinj.core;

import org.bitcoinj.net.AbstractTimeoutHandler;
import org.bitcoinj.net.MessageWriteTarget;
import org.bitcoinj.net.StreamConnection;
import org.bitcoinj.utils.Threading;
import com.google.common.annotations.VisibleForTesting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.channels.NotYetConnectedException;
import java.util.concurrent.locks.Lock;

import static com.google.common.base.Preconditions.*;

/**
 * Handles high-level message (de)serialization for peers, acting as the bridge between the
 * {@link org.bitcoinj.net} classes and {@link Peer}.
 */
public abstract class PeerSocketHandler extends AbstractTimeoutHandler implements StreamConnection {
    private static final Logger log = LoggerFactory.getLogger(PeerSocketHandler.class);

    private final MessageSerializer serializer;
    protected PeerAddress peerAddress;
    // If we close() before we know our writeTarget, set this to true to call writeTarget.closeConnection() right away.
    private boolean closePending = false;
    // writeTarget will be thread-safe, and may call into PeerGroup, which calls us, so we should call it unlocked
    @VisibleForTesting protected MessageWriteTarget writeTarget = null;

    // The ByteBuffers passed to us from the writeTarget are static in size, and usually smaller than some messages we
    // will receive. For SPV clients, this should be rare (ie we're mostly dealing with small transactions), but for
    // messages which are larger than the read buffer, we have to keep a temporary buffer with its bytes.
    private byte[] largeReadBuffer;
    private int largeReadBufferPos;
    private BitcoinSerializer.BitcoinPacketHeader header;

    private Lock lock = Threading.lock("PeerSocketHandler");

    public PeerSocketHandler(NetworkParameters params, InetSocketAddress remoteIp) {
        checkNotNull(params);
        serializer = params.getDefaultSerializer();
        this.peerAddress = new PeerAddress(params, remoteIp);
    }

    public PeerSocketHandler(NetworkParameters params, PeerAddress peerAddress) {
        checkNotNull(params);
        serializer = params.getDefaultSerializer();
        this.peerAddress = checkNotNull(peerAddress);
    }

    /**
     * Sends the given message to the peer. Due to the asynchronousness of network programming, there is no guarantee
     * the peer will have received it. Throws NotYetConnectedException if we are not yet connected to the remote peer.
     * TODO: Maybe use something other than the unchecked NotYetConnectedException here
     */
    public void sendMessage(Message message) throws NotYetConnectedException {
        lock.lock();
        try {
            if (writeTarget == null)
                throw new NotYetConnectedException();
        } finally {
            lock.unlock();
        }
        // TODO: Some round-tripping could be avoided here
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            serializer.serialize(message, out);
            writeTarget.writeBytes(out.toByteArray());
        } catch (IOException e) {
            exceptionCaught(e);
        }
    }

    /**
     * Closes the connection to the peer if one exists, or immediately closes the connection as soon as it opens
     */
    public void close() {
        lock.lock();
        try {
            if (writeTarget == null) {
                closePending = true;
                return;
            }
        } finally {
            lock.unlock();
        }
        writeTarget.closeConnection();
    }

    @Override
    protected void timeoutOccurred() {
        log.info("{}: Timed out", getAddress());
        close();
    }

    /**
     * Called every time a message is received from the network
     */
    protected abstract void processMessage(Message m) throws Exception;

    @Override
    public int receiveBytes(ByteBuffer buff) {
        checkArgument(buff.position() == 0 &&
                buff.capacity() >= BitcoinSerializer.BitcoinPacketHeader.HEADER_LENGTH + 4);
        try {
            // Repeatedly try to deserialize messages until we hit a BufferUnderflowException
            boolean firstMessage = true;
            while (true) {
                // If we are in the middle of reading a message, try to fill that one first, before we expect another
                if (largeReadBuffer != null) {
                    // This can only happen in the first iteration
                    checkState(firstMessage);
                    // Read new bytes into the largeReadBuffer
                    int bytesToGet = Math.min(buff.remaining(), largeReadBuffer.length - largeReadBufferPos);
                    buff.get(largeReadBuffer, largeReadBufferPos, bytesToGet);
                    largeReadBufferPos += bytesToGet;
                    // Check the largeReadBuffer's status
                    if (largeReadBufferPos == largeReadBuffer.length) {
                        // ...processing a message if one is available
                        processMessage(serializer.deserializePayload(header, ByteBuffer.wrap(largeReadBuffer)));
                        largeReadBuffer = null;
                        header = null;
                        firstMessage = false;
                    } else // ...or just returning if we don't have enough bytes yet
                        return buff.position();
                }
                // Now try to deserialize any messages left in buff
                Message message;
                int preSerializePosition = buff.position();
                try {
                    message = serializer.deserialize(buff);
                } catch (BufferUnderflowException e) {
                    // If we went through the whole buffer without a full message, we need to use the largeReadBuffer
                    if (firstMessage && buff.limit() == buff.capacity()) {
                        // ...so reposition the buffer to 0 and read the next message header
                        buff.position(0);
                        try {
                            serializer.seekPastMagicBytes(buff);
                            header = serializer.deserializeHeader(buff);
                            // Initialize the largeReadBuffer with the next message'