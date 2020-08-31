/*
 * Copyright 2011 Google Inc.
 * Copyright 2014 Andreas Schildbach
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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.math.BigInteger;
import java.util.Arrays;

import static com.google.common.base.Preconditions.checkState;

/**
 * <p>A Message is a data structure that can be serialized/deserialized using the Bitcoin serialization format.
 * Specific types of messages that are used both in the block chain, and on the wire, are derived from this
 * class.</p>
 * 
 * <p>Instances of this class are not safe for use by multiple threads.</p>
 */
public abstract class Message {
    private static final Logger log = LoggerFactory.getLogger(Message.class);

    public static final int MAX_SIZE = 0x02000000; // 32MB

    public static final int UNKNOWN_LENGTH = Integer.MIN_VALUE;

    // Useful to ensure serialize/deserialize are consistent with each other.
    private static final boolean SELF_CHECK = false;

    // The offset is how many bytes into the provided byte array this message payload starts at.
    protected int offset;
    // The cursor keeps track of where we are in the byte array as we parse it.
    // Note that it's relative to the start of the array NOT the start of the message payload.
    protected int cursor;

    protected int length = UNKNOWN_LENGTH;

    // The raw message payload bytes themselves.
    protected byte[] payload;

    protected boolean recached = false;
    protected MessageSerializer serializer;

    protected int protocolVersion;
    public int transactionOptions = TransactionOptions.ALL; // FIXME: Hacked for serialisation

    protected NetworkParameters params;

    protected Message() {
        serializer = DummySerializer.DEFAULT;
    }

    protected Message(NetworkParameters params) {
        this.params = params;
        serializer = params.getDefaultSerializer();
    }

    protected Message(NetworkParameters params, byte[] payload, int offset, int protocolVersion) throws ProtocolException {
        this(params, payload, offset, protocolVersion, params.getDefaultSerializer(), UNKNOWN_LENGTH);
    }

    /**
     * 
     * @param params NetworkParameters object.
     * @param payload Bitcoin protocol formatted byte array containing message content.
     * @param offset The location of the first payload byte within the array.
     * @param protocolVersion Bitcoin protocol version.
     * @param serializer the serializer to use for this message.
     * @param length The length of message payload if known.  Usually this is provided when deserializing of the wire
     * as the length will be provided as part of the header.  If unknown then set to Message.UNKNOWN_LENGTH
     * @throws ProtocolException
     */
    protected Message(NetworkParameters params, byte[] payload, int offset, int protocolVersion, MessageSerializer serializer, int length) throws ProtocolException {
        this.serializer = serializer;
        this.protocolVersion = protocolVersion;
        this.params = params;
        this.payload = payload;
        this.cursor = this.offset = offset;
        this.length = length;

        parse();

        if (this.length == UNKNOWN_LENGTH)
            checkState(false, "Length field has not been set in constructor for %s after parse.",
                       getClass().getSimpleName());
        
        if (SELF_CHECK) {
            selfCheck(payload, offset);
        }
        
        if (!serializer.isParseRetainMode())
            this.payload = null;
    }

    private void selfCheck(byte[] payload, int offset) {
        if (!(this instanceof VersionMessage)) {
            byte[] payloadBytes = new byte[cursor - offset];
            System.arraycopy(payload, offset, payloadBytes, 0, cursor - offset);
            byte[] reserialized = bitcoinSerialize();
            if (!Arrays.equals(reserialized, payloadBytes))
                throw new RuntimeException("Serialization is wrong: \n" +
                        Utils.HEX.encode(reserialized) + " vs \n" +
                        Utils.HEX.encode(payloadBytes));
        }
    }

    protected Message(NetworkParameters params, byte[] payload, int offset) throws ProtocolException {
        this(params, payload, offset, params.getProtocolVersionNum(NetworkParameters.ProtocolVersion.CURRENT),
             params.getDefaultSerializer(), UNKNOWN_LENGTH);
    }

    protected Message(NetworkParameters params, byte[] payload, int offset, MessageSerializer serializer, int length) throws ProtocolException {
        this(params, payload, offset, params.getProtocolVersionNum(NetworkParameters.ProtocolVersion.CURRENT),
             serializer, length);
    }

    // These methods handle the serialization/deserialization using the custom Bitcoin protocol.

    protected abstract void parse() throws ProtocolException;

    /**
     * <p>To be called before any change of internal values including any setters. This ensures any cached byte array is
     * removed.<p/>
     * <p>Child messages of this object(e.g. Transactions belonging to a Block) will not have their internal byte caches
     * invalidated unless they are also modified internally.</p>
     */
    protected void unCache() {
        payload = null;
        recached = false;
    }

    protected void adjustLength(int newArraySize, int adjustment) {
        if (length == UNKNOWN_LENGTH)
            ret