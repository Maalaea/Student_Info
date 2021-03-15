/*
 * Copyright 2011 Google Inc.
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

import com.google.common.base.Objects;
import com.google.common.net.InetAddresses;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * <p>A VersionMessage holds information exchanged during connection setup with another peer. Most of the fields are not
 * particularly interesting. The subVer field, since BIP 14, acts as a User-Agent string would. You can and should
 * append to or change the subVer for your own software so other implementations can identify it, and you can look at
 * the subVer field received from other nodes to see what they are running.</p>
 *
 * <p>After creating yourself a VersionMessage, you can pass it to {@link PeerGroup#setVersionMessage(VersionMessage)}
 * to ensure it will be used for each new connection.</p>
 *
 * <p>Instances of this class are not safe for use by multiple threads.</p>
 */
public class VersionMessage extends Message {

    /** The version of this library release, as a string. */
    public static final String BITCOINJ_VERSION = "0.15-SNAPSHOT";
    /** The value that is prepended to the subVer field of this application. */
    public static final String LIBRARY_SUBVER = "/bitcoinj:" + BITCOINJ_VERSION + "/";

    /** A services flag that denotes whether the peer has a copy of the block chain or not. */
    public static final int NODE_NETWORK = 1 << 0;
    /** A flag that denotes whether the peer supports the getutxos message or not. */
    public static final int NODE_GETUTXOS = 1 << 1;
    /** A service bit used by Bitcoin-ABC to announce Bitcoin Cash nodes. */
    public static final int NODE_BITCOIN_CASH = 1 << 5;
    /** A service bit used by BTC1 to announce Segwit2x nodes. */
    public static final int NODE_SEGWIT2X = 1 << 7;
    /** Indicates that a node can be asked for blocks and transactions including witness data. */
    public static final int NODE_WITNESS = 1 << 3;

    /**
     * The version number of the protocol spoken.
     */
    public int clientVersion;
    /**
     * Flags defining what optional services are supported.
     */
    public long localServices;
    /**
     * What the other side believes the current time to be, in seconds.
     */
    public long time;
    /**
     * What the other side believes the address of this program is. Not used.
     */
    public PeerAddress myAddr;
    /**
     * What the other side believes their own address is. Not used.
     */
    public PeerAddress theirAddr;
    /**
     * User-Agent as defined in <a href="https://github.com/bitcoin/bips/blob/master/bip-0014.mediawiki">BIP 14</a>.
     * Bitcoin Core sets it to something like "/Satoshi:0.9.1/".
     */
    public String subVer;
    /**
     * How many blocks are in the chain, according to the other side.
     */
    public long bestHeight;
    /**
     * Whether or not to relay tx invs before a filter is received.
     * See <a href="https://github.com/bitcoin/bips/blob/master/bip-0037.mediawiki#extensions-to-existing-messages">BIP 37</a>.
     */
    public boolean relayTxesBeforeFilter;

    public VersionMessage(NetworkParameters params, byte[] payload) throws ProtocolException {
        super(params, payload, 0);
    }

    // It doesn't really make sense to ever lazily parse a version message or to retain the backing bytes.
    // If you're receiving this on the wire you need to check the protocol version and it will never need to be sent
    // back down the wire.

    public VersionMessage(NetworkParameters params, int newBestHeight) {
        super(params);
        clientVersion = params.getProtocolVersionNum(NetworkParameters.ProtocolVersion.CURRENT);
        localServices = 0;
        time = System.currentTimeMillis() / 1000;
        // Note that the Bitcoin Core doesn't do anything with these, and finding out your own external IP address
        // is kind of tricky anyway, so we just put nonsense here for now.
        InetAddress localhost = InetAddresses.forString("127.0.0.1");
        myAddr = new PeerAddress(params, localhost, params.getPort(), 0, BigInteger.ZERO);
        theirAddr = new PeerAddress(params, localhost, params.getPort(), 0, BigInteger.ZERO);
        subVer = LIBRARY_SUBVER;
        bestHeight = newBestHeight;
        relayTxesBeforeFilter = true;

        length = 85;
        if (protocolVersion > 31402)
            length += 8;
        length += VarInt.sizeOf(subVer.length()) + subVer.length();
    }

    @Override
    protected void parse() throws ProtocolException {
        clientVersion = (int) readUint32();
        localServices = readUint64().longValue();
        time = readUint64().longValue();
        myAddr = new PeerAddress(params, payload, cursor, 0);
        cursor += myAddr.getMessageSize();
        theirAddr = new PeerAddress(params, payload, cursor, 0);
        cursor += theirAddr.getMessageSize();
        // uint64 localHostNonce  (random data)
        // We don't care about the localhost nonce. It's used to detect connecting back to yourself in cases where
        // there are NATs and proxies in the way. However we don't listen for inbound connections so it's irrelevant.
        readUint64();
        try {
            // Initialize default values for flags which may not be sent by old nodes
            subVer = "";
            bestHeight = 0;
            relayTxesBeforeFilter = true;
            if (!hasMoreBytes())
                return;
            //   string subVer  (currently "")
            subVer = readStr();
            if (!hasMoreBytes())
                return;
            //   int bestHeight (size of known block chain).
            bestHeight = readUint32();
            if (!hasMoreBytes())
                return;
            relayTxesBeforeFilter = readBytes(1)[0] != 0;
        } finally {
            length = cursor - offset;
        }
    }

    @Override
    public void bitcoinSerializeToStream(OutputStream buf) throws IOException {
        Utils.uint32ToByteStreamLE(clientVersion, buf);
        Utils.uint32ToByteStreamLE(localServices, buf);
        Utils.uint32ToByteStreamLE(localServices >> 32, buf);
        Utils.uint32ToByteStreamLE(time, buf);
        Utils.uint32T