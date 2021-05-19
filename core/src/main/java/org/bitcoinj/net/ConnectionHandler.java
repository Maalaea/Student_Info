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

package org.bitcoinj.net;

import org.bitcoinj.core.Message;
import org.bitcoinj.utils.Threading;
import com.google.common.base.Throwables;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

// TODO: The locking in all this class is horrible and not really necessary. We should just run all network stuff on one thread.

/**
 * A simple NIO MessageWriteTarget which handles all the business logic of a connection (reading+writing bytes).
 * Used only by the NioClient and NioServer classes
 */
class ConnectionHandler implements MessageWriteTarget {
    private static final org.slf4j.Logger log = LoggerFactory.getLogger(ConnectionHandler.class);

    private static final int BUFFER_SIZE_LOWER_BOUND = 4096;
    private static final int BUFFER_SIZE_UPPER_BOUND = 65536;

    private static final int OUTBOUND_BUFFER_BYTE_COUNT = Message.MAX_SIZE + 24; // 24 byte message header

    // We lock when touching local flags and when writing data, but NEVER when calling any methods which leave this
    // class into non-Java classes.
    private final ReentrantLock lock = Threading.lock("nioConnectionHandler");
    @GuardedBy("lock") private final ByteBuffer readBuff;
    @GuardedBy("lock") private final SocketChannel channel;
    @GuardedBy("lock") private final SelectionKey key;
    @GuardedBy("lock") StreamConnection connection;
    @GuardedBy("lock") private boolean closeCalled = false;

    @GuardedBy("lock") private long bytesToWriteRemaining = 0;
    @GuardedBy("lock") private final LinkedList<ByteBuffer> bytesToWrite = new LinkedList<>();

    private Set<ConnectionHandler> connectedHandlers;

    public ConnectionHandler(StreamConnectionFactory connectionFactory, SelectionKey key) throws IOException {
        this(connectionFactory.getNewConnection(((SocketChannel) key.channel()).socket().getInetAddress(), ((SocketChannel) key.channel()).socket().getPort()), key);
        if (connection == null)
            throw new IOException("Parser factory.getNewConnection returned null");
    }

    private ConnectionHandler(@Nullable StreamConnection connection, SelectionKey key) {
        this.key = key;
        this.channel = checkNotNull(((SocketChannel)key.channel()));
        if (connection == null) {
            readBuff = null;
            return;
        }
        this.connection = connection;
        readBuff = ByteBuffer.allocateDirect(Math.min(Math.max(connection.getMaxMessageSize(), BUFFER_SIZE_LOWER_BOUND), BUFFER_SIZE_UPPER_BOUND));
        connection.setWriteTarget(this); // May callback into us (eg closeConnection() now)
        connectedHandlers = null;
    }

    public Connec