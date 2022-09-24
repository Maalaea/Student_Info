/*
 * Copyright 2014 Mike Hearn
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

package org.bitcoinj.wallet;

import org.bitcoinj.core.*;
import org.bitcoinj.crypto.*;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.utils.BriefLogFormatter;
import org.bitcoinj.utils.Threading;
import org.bitcoinj.wallet.listeners.KeyChainEventListener;

import com.google.common.collect.ImmutableList;
import org.junit.Before;
import org.junit.Test;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.util.Arrays;

import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.junit.Assert.*;

public class KeyChainGroupTest {
    // Number of initial keys in this tests HD wallet, including interior keys.
    private static final int INITIAL_KEYS = 4;
    private static final int LOOKAHEAD_SIZE = 5;
    private static final NetworkParameters PARAMS = MainNetParams.get();
    private static final String XPUB = "xpub68KFnj3bqUx1s7mHejLDBPywCAKdJEu1b49uniEEn2WSbHmZ7xbLqFTjJbtx1LUcAt1DwhoqWHmo2s5WMJp6wi38CiF2hYD49qVViKVvAoi";
    private KeyChainGroup group;
    private DeterministicKey watchingAccountKey;

    @Before
    public void setup() {
        BriefLogFormatter.init();
        Utils.setMockClock();
        group = new KeyChainGroup(PARAMS);
        group.setLookaheadSize(LOOKAHEAD_SIZE);   // Don't want slow tests.
        group.getActiveKeyChain();  // Force create a chain.

        watchingAccountKey = DeterministicKey.deserializeB58(null, XPUB, PARAMS);
    }

    private KeyChainGroup createMarriedKeyChainGroup() {
        KeyChainGroup group = new KeyChainGroup(PARAMS);
        DeterministicKeyChain chain = createMarriedKeyChain();
        group.addAndActivateHDChain(chain);
        group.setLookaheadSize(LOOKAHEAD_SIZE);
        group.getActiveKeyChain();
        return group;
    }

    private MarriedKeyChain createMarriedKeyChain() {
        byte[] entropy = Sha256Hash.hash("don't use a seed like this in real life".getBytes());
        DeterministicSeed seed = new DeterministicSeed(entropy, "", MnemonicCode.BIP39_STANDARDISATION_TIME_SECS);
        MarriedKeyChain chain = MarriedKeyChain.builder()
                .seed(seed)
                .followingKeys(watchingAccountKey)
                .threshold(2).build();
        return chain;
    }

    @Test
    public void freshCurrentKeys() throws Exception {
        int numKeys = ((group.getLookaheadSize() + group.getLookaheadThreshold()) * 2)   // * 2 because of internal/external
                + 1  // keys issued
                + group.getActiveKeyChain().getAccountPath().size() + 2  /* account key + int/ext parent keys */;
        assertEquals(numKeys, group.numKeys());
        assertEquals(2 * numKeys, group.getBloomFilterElementCount());
        ECKey r1 = group.currentKey(KeyChain.KeyPurpose.RECEIVE_FUNDS);
        assertEquals(numKeys, group.numKeys());
        assertEquals(2 * numKeys, group.getBloomFilterElementCount());

        ECKey i1 = new ECKey();
        group.importKeys(i1);
        numKeys++;
        assertEquals(numKeys, group.numKeys());
        assertEquals(2 * numKeys, group.getBloomFilterElementCount());

        ECKey r2 = group.currentKey(KeyChain.KeyPurpose.RECEIVE_FUNDS);
        assertEquals(r1, r2);
        ECKey c1 = group.currentKey(KeyChain.KeyPurpose.CHANGE);
        assertNotEquals(r1, c1);
        ECKey r3 = group.freshKey(KeyChain.KeyPurpose.RECEIVE_FUNDS);
        assertNotEquals(r1, r3);
        ECKey c2 = group.freshKey(KeyChain.KeyPurpose.CHANGE);
        assertNotEquals(r3, c2);
        // Current key has not moved and will not under marked as used.
        ECKey r4 = group.currentKey(KeyChain.KeyPurpose.RECEIVE_FUNDS);
        assertEquals(r2, r4);
        ECKey c3 = group.currentKey(KeyChain.KeyPurpose.CHANGE);
        assertEquals(c1, c3);
        // Mark as used. Current key is now different.
        group.markPubKeyAsUsed(r4.getPubKey());
        ECKey r5 = group.currentKey(KeyChain.KeyPurpose.RECEIVE_FUNDS);
        assertNotEquals(r4, r5);
    }

    @Test
    public void freshCurrentKeysForMarriedKeychain() throws Exception {
        group = createMarriedKeyChainGroup();

        try {
            group.freshKey(KeyChain.KeyPurpose.RECEIVE_FUNDS);
            fail();
        } catch (UnsupportedOperationException e) {
        }

        try {
            group.currentKey(KeyChain.KeyPurpose.RECEIVE_FUNDS);
            fail();
        } catch (UnsupportedOperationException e) {
        }
    }

    @Test
    public void imports() throws Exception {
        ECKey key1 = new ECKey();
        int numKeys = group.numKeys();
        assertFalse(group.removeImportedKey(key1));
        assertEquals(1, group.importKeys(ImmutableList.of(key1)));
        assertEquals(numKeys + 1, group.numKeys());   // Lookahead is triggered by requesting a key, so none yet.
        group.removeImportedKey(key1);
        assertEquals(numKeys, group.numKeys());
    }

    @Test
    public void findKey() throws Exception {
        ECKey a = group.freshKey(KeyChain.KeyPurpose.RECEIVE_FUNDS);
        ECKey b = group.freshKey(KeyChain.KeyPurpose.CHANGE);
        ECKey c = new ECKey();
        ECKey d = new ECKey();   // Not imported.
        group.importKeys(c);
        assertTrue(group.hasKey(a));
        assertTrue(group.hasKey(b));
        asse