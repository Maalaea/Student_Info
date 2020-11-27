/*
 * Copyright 2011 Google Inc.
 * Copyright 2015 Andreas Schildbach
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

import org.bitcoinj.params.MainNetParams;
import com.google.common.base.Objects;
import com.google.common.net.InetAddresses;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

import static org.bitcoinj.core.Utils.uint32ToByteStreamLE;
import static org.bitcoinj.core.Utils.uint64ToByteStreamLE;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * <p>A PeerAddress holds an IP address and port number representing the network location of
 * a peer in the Bitcoin P2P network. It exists primarily for serialization purposes.</p>
 * 
 * <p>Instances of this class are not safe for use by multiple threads.</p>
 */
public class PeerAddress extends ChildMessage {

    static final int MESSAGE_SIZE = 30;

    private InetAddress addr;
    private String hostname; // Used for .onion addresses
    private int port;
    private BigInteger services;
    private long time;

    /**
     * Construct a peer address from a serialized payload.
     */
    public PeerAddress(NetworkParameters params, byte[] payload, int offset, int protocolVersion) throws ProtocolException {
        super(params, payload, offset, protocolVersion);
    }

    /**
     * Construct a peer address from a serialized payload.
     * @param params NetworkParameters object.
     * @param payload Bitcoin protocol formatted byte array containing message content.
     * @param offset The location of the first payload byte within the array.
     * @param protocolVersion Bitcoin protocol version.
     * @param serializer the serializer to use for this message.
     * @throws ProtocolException
     */
    public PeerAddress(NetworkParameters params, byte[] payload, int offset, int protocolVersion, Message parent, MessageSerializer serializer) throws ProtocolException {
        super(params, payload, offset, protocolVersion, parent, serializer, UNKNOWN_LENGTH);
    }

    /**
     * Construct a peer address from a memorized or hardcoded address.
     */
    public PeerAddress(NetworkParameters params, InetAddress addr, int port, int protocolVersion, BigInteger services) {
        super(params);
        this.addr = checkNotNull(addr);
        this.port = port;
        this.protocolVersion = protocolVersion;
        this.services = services;
        length = protocolVersion > 31402 ? MESSAGE_SIZE : MESSAGE_SIZE - 4;
    }

    /**
     * Constructs a peer address from the given IP address and port. Version number is default for the given parameters.
     */
    public PeerAddress(NetworkParameters params, InetAddress addr, int port) {
        this(params, addr, port, params.getProtocolVersionNum(NetworkParameters.ProtocolVersion.CURRENT),
                BigInteger.ZERO);
    }

    /**
     * Constructs a peer address from the given IP address. Port and version number are default for the given
     * parameters.
     */
    public PeerAddress(NetworkParameters params, InetAddress addr) {
        this(params, addr, MainNetParams.get().getPort());
    }

    /**
     * Constructs a peer address from an {@link InetSocketAddress}. An InetSocketAddress can take in as parameters an
     *