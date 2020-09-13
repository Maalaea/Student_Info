/*
 * Copyright 2011 Google Inc.
 * Copyright 2014 Andreas Schildbach
 * Copyright 2015 Ross Nicoll
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.bitcoinj.core;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

/**
 * Generic interface for classes which serialize/deserialize messages. Implementing
 * classes should be immutable.
 */
public abstract class MessageSerializer {

    /**
     * Reads a message from the given ByteBuffer and returns it.
     */
    public abstract Message deserialize(ByteBuffer in) throws ProtocolException, IOException, UnsupportedOperationException;

    /**
     * Deserializes only the header in case packet meta data is needed before decoding
     * the payload. This method assumes you have already called seekPastMagicBytes()
     */
    public abstract BitcoinSerializer.BitcoinPacketHeader deserializeHeader(ByteBuffer in) throws ProtocolException, IOException, UnsupportedOperationException;

    /**
     * Deserialize payload only.  You must provide a header, typically obtained by calling
     * {@link BitcoinSerializer#deserializeHeader}.
     */
    public abstract Message deserializePayload(BitcoinSerializer.BitcoinPacketHeader header, ByteBuffer in) throws ProtocolException, BufferUnderflowException, UnsupportedOperationException;

    /**
     * Whether the serializer will produce cached mode Messages
     */
    public abstract boolean isParseRetainMode();

    /**
     * Make an address message from the payload. Extension point for alternative
     * serialization format support.
     */
    public abstract AddressMessage makeAddressMessage(byte[] payloadBytes, int length) throws ProtocolException, UnsupportedOperationException;

    /**
     * Make