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
        assertTrue(group.hasKey(c));
        assertFalse(group.hasKey(d));
        ECKey result = group.findKeyFromPubKey(a.getPubKey());
        assertEquals(a, result);
        result = group.findKeyFromPubKey(b.getPubKey());
        assertEquals(b, result);
        result = group.findKeyFromPubHash(a.getPubKeyHash());
        assertEquals(a, result);
        result = group.findKeyFromPubHash(b.getPubKeyHash());
        assertEquals(b, result);
        result = group.findKeyFromPubKey(c.getPubKey());
        assertEquals(c, result);
        result = group.findKeyFromPubHash(c.getPubKeyHash());
        assertEquals(c, result);
        assertNull(group.findKeyFromPubKey(d.getPubKey()));
        assertNull(group.findKeyFromPubHash(d.getPubKeyHash()));
    }

    @Test
    public void currentP2SHAddress() throws Exception {
        group = createMarriedKeyChainGroup();
        Address a1 = group.currentAddress(KeyChain.KeyPurpose.RECEIVE_FUNDS);
        assertTrue(a1.isP2SHAddress());
        Address a2 = group.currentAddress(KeyChain.KeyPurpose.RECEIVE_FUNDS);
        assertEquals(a1, a2);
        Address a3 = group.currentAddress(KeyChain.KeyPurpose.CHANGE);
        assertNotEquals(a2, a3);
    }

    @Test
    public void freshAddress() throws Exception {
        group = createMarriedKeyChainGroup();
        Address a1 = group.freshAddress(KeyChain.KeyPurpose.RECEIVE_FUNDS);
        Address a2 = group.freshAddress(KeyChain.KeyPurpose.RECEIVE_FUNDS);
        assertTrue(a1.isP2SHAddress());
        assertNotEquals(a1, a2);
        group.getBloomFilterElementCount();
        assertEquals(((group.getLookaheadSize() + group.getLookaheadThreshold()) * 2)   // * 2 because of internal/external
                + (2 - group.getLookaheadThreshold())  // keys issued
                + group.getActiveKeyChain().getAccountPath().size() + 3  /* master, account, int, ext */, group.numKeys());

        Address a3 = group.currentAddress(KeyChain.KeyPurpose.RECEIVE_FUNDS);
        assertEquals(a2, a3);
    }

    @Test
    public void findRedeemData() throws Exception {
        group = createMarriedKeyChainGroup();

        // test script hash that we don't have
        assertNull(group.findRedeemDataFromScriptHash(new ECKey().getPubKey()));

        // test our script hash
        Address address = group.currentAddress(KeyChain.KeyPurpose.RECEIVE_FUNDS);
        RedeemData redeemData = group.findRedeemDataFromScriptHash(address.getHash160());
        assertNotNull(redeemData);
        assertNotNull(redeemData.redeemScript);
        assertEquals(2, redeemData.keys.size());
    }

    // Check encryption with and without a basic keychain.

    @Test
    public void encryptionWithoutImported() throws Exception {
        encryption(false);
    }

    @Test
    public void encryptionWithImported() throws Exception {
        encryption(true);
    }

    public void encryption(boolean withImported) throws Exception {
        Utils.rollMockClock(0);
        long now = Utils.currentTimeSeconds();
        ECKey a = group.freshKey(KeyChain.KeyPurpose.RECEIVE_FUNDS);
        assertEquals(now, group.getEarliestKeyCreationTime());
        Utils.rollMockClock(-86400);
        long yesterday = Utils.currentTimeSeconds();
        ECKey b = new ECKey();

        assertFalse(group.isEncrypted());
        try {
            group.checkPassword("foo");   // Cannot check password of an unencrypted group.
            fail();
        } catch (IllegalStateException e) {
        }
        if (withImported) {
            assertEquals(now, group.getEarliestKeyCreationTime());
            group.importKeys(b);
            assertEquals(yesterday, group.getEarliestKeyCreationTime());
        }
        KeyCrypterScrypt scrypt = new KeyCrypterScrypt(2);
        final KeyParameter aesKey = scrypt.deriveKey("password");
        group.encrypt(scrypt, aesKey);
        assertTrue(group.isEncrypted());
        assertTrue(group.checkPassword("password"));
        assertFalse(group.checkPassword("wrong password"));
        final ECKey ea = group.findKeyFromPubKey(a.getPubKey());
        assertTrue(checkNotNull(ea).isEncrypted());
        if (withImported) {
            assertTrue(checkNotNull(group.findKeyFromPubKey(b.getPubKey())).isEncrypted());
            assertEquals(yesterday, group.getEarliestKeyCreationTime());
        } else {
            assertEquals(now, group.getEarliestKeyCreationTime());
        }
        try {
            ea.sign(Sha256Hash.ZERO_HASH);
            fail();
        } catch (ECKey.KeyIsEncryptedException e) {
            // Ignored.
        }
        if (withImported) {
            ECKey c = new ECKey();
            try {
                group.importKeys(c);
                fail();
            } catch (KeyCrypterException e) {
            }
            group.importKeysAndEncrypt(ImmutableList.of(c), aesKey);
            ECKey ec = group.findKeyFromPubKey(c.getPubKey());
            try {
                group.importKeysAndEncrypt(ImmutableList.of(ec), aesKey);
                fail();
            } catch (IllegalArgumentException e) {
            }
        }

        try {
            group.decrypt(scrypt.deriveKey("WRONG PASSWORD"));
            fail();
        } catch (KeyCrypterException e) {
        }

        group.decrypt(aesKey);
        assertFalse(group.isEncrypted());
        assertFalse(checkNotNull(group.findKeyFromPubKey(a.getPubKey())).isEncrypted());
        if (withImported) {
            assertFalse(checkNotNull(group.findKeyFromPubKey(b.getPubKey())).isEncrypted());
            assertEquals(yesterday, group.getEarliestKeyCreationTime());
        } else {
            assertEquals(now, group.getEarliestKeyCreationTime());
        }
    }

    @Test
    public void encryptionWhilstEmpty() throws Exception {
        group = new KeyChainGroup(PARAMS);
        group.setLookaheadSize(5);
        KeyCrypterScrypt scrypt = new KeyCrypterScrypt(2);
        final KeyParameter aesKey = scrypt.deriveKey("password");
        group.encrypt(scrypt, aesKey);
        assertTrue(group.freshKey(KeyChain.KeyPurpose.RECEIVE_FUNDS).isEncrypted());
        final ECKey key = group.currentKey(KeyChain.KeyPurpose.RECEIVE_FUNDS);
        group.decrypt(aesKey);
        assertFalse(checkNotNull(group.findKeyFromPubKey(key.getPubKey())).isEncrypted());
    }

    @Test
    public void bloom() throws Exception {
        ECKey key1 = group.freshKey(KeyChain.KeyPurpose.RECEIVE_FUNDS);
        ECKey key2 = new ECKey();
        BloomFilter filter = group.getBloomFilter(group.getBloomFilterElementCount(), 0.001, (long)(Math.random() * Long.MAX_VALUE));
        assertTrue(filter.contains(key1.getPubKeyHash()));
        assertTrue(filter.contains(key1.getPubKey()));
        assertFalse(filter.contains(key2.getPubKey()));
        // Check that the filter contains the lookahead buffer and threshold zone.
        for (int i = 0; i < LOOKAHEAD_SIZE + group.getLookaheadThreshold(); i++) {
            ECKey k = group.freshKey(KeyChain.KeyPurpose.RECEIVE_FUNDS);
            assertTrue(filter.contains(k.getPubKeyHash()));
        }
        // We ran ahead of the lookahead buffer.
        assertFalse(filter.contains(group.freshKey(KeyChain.KeyPurpose.RECEIVE_FUNDS).getPubKey()));
        group.importKeys(key2);
        filter = group.getBloomFilter(group.getBloomFilterElementCount(), 0.001, (long) (Math.random() * Long.MAX_VALUE));
        assertTrue(filter.contains(key1.getPubKeyHash()));
        assertTrue(filter.contains(key1.getPubKey()));
        assertTrue(filter.contains(key2.getPubKey()));
    }

    @Test
    public void findRedeemScriptFromPubHash() throws Exception {
        group = createMarriedKeyChainGroup();
        Address address = group.freshAddress(KeyChain.KeyPurpose.RECEIVE_FUNDS);
        assertTrue(group.findRedeemDataFromScriptHash(address.getHash160()) != null);
        group.getBloomFilterElementCount();
        KeyChainGroup group2 = createMarriedKeyChainGroup();
        group2.freshAddress(KeyChain.KeyPurpose.RECEIVE_FUNDS);
        group2.getBloomFilterElementCount();  // Force lookahead.
        // test address from lookahead zone and lookahead threshold zone
        for (int i = 0; i < group.getLookaheadSize() + group.getLookaheadThreshold(); i++) {
            address = group.freshAddress(KeyChain.KeyPurpose.RECEIVE_FUNDS);
            assertTrue(group2.findRedeemDataFromScriptHash(address.getHash160()) != null);
        }
        assertFalse(group2.findRedeemDataFromScriptHash(group.freshAddress(KeyChain.KeyPurpose.RECEIVE_FUNDS).getHash160()) != null);
    }

    @Test
    public void bloomFilterForMarriedChains() throws Exception {
        group = createMarriedKeyChainGroup();
        int bufferSize = group.getLookaheadSize() + group.getLookaheadThreshold();
        int expected = bufferSize * 2 /* chains