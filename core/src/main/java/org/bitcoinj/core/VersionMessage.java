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
    /** Indicates that a