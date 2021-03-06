/*
 * Copyright 2014 the bitcoinj authors
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
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * <p>Message representing a list of unspent transaction outputs ("utxos"), returned in response to sending a
 * {@link GetUTXOsMessage} ("getutxos"). Note that both this message and the query that generates it are not
 * supported by Bitcoin Core. An implementation is available in <a href="https://github.com/bitcoinxt/bitcoinxt">Bitcoin XT</a>,
 * a patch set on top of Core. Thus if you want to use it, you must find some XT peers to connect to. This can be done
 * using a {@link org.bitcoinj.net.discovery.HttpDiscovery} class combined with an HTTP/Cartographer seed.</p>
 *
 * <p>The getutxos/utxos protocol is defined in <a href="https://github.com/bitcoin/bips/blob/master/bip-0065.mediawiki">BIP 65</a>.
 * In that document you can find a discussion of the security of this protocol (briefly, there is none). Because the
 * data found in this message is not authenticated it should be used carefully. Places where it can be useful are if
 * you're querying your own trusted node, if you're comparing answers from multiple nodes simultaneously and don't
 * believe there is a MITM on your connection, or if you're only using the returned data as a UI hint and it's OK
 * if the data is occasionally wrong. Bear in mind that the answer can be wrong even in the absence of malicious intent
 * just through the nature of querying an ever changing data source: the UTXO set may be updated by a new transaction
 * immediately after this message is returned.</p>
 * 
 * <p>Instances of this class are not safe for use by multiple threads.</p>
 */
public class UTXOsMessage extends Message {
    private long height;
    private Sha256Hash chainHead;
    private byte[] hits;   // little-endian bitset indicating whether an output was found or not.

    private List<TransactionOutput> outputs;
    private long[] heights;

    /** This is a special sentinel value that can appear in the heights field if the given tx is in the mempool. */
    public static long MEMPOOL_HEIGHT = 0x7FFFFFFFL;

    public UTXOsMessage(NetworkParameters params, byte[] payloadBytes) {
        super(params, payloadBytes, 0);
    }

    /**
     * Provide an array of output objects, with nulls indicating that the output was missing. The bitset will
     * be calculated from this.
     */
    public UTXOsMessage(NetworkParameters params, List<TransactionOutput> outputs, long[] heights, Sha256Hash chainHead, long height) {
        super(params);
        hits = new byte[(int) Math.ceil(outputs.size() / 8.0)];
        for (int i = 0; i < outputs.size(); i++) {
            if (outputs.get(i) != null)
                Utils.setBitLE(hits, i);
        }
        this.outputs = new ArrayList<>(outputs.size());
        for (TransactionOutput output 