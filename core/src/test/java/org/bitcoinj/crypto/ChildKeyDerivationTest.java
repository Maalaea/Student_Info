/*
 * Copyright 2013 Matija Mazi.
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

package org.bitcoinj.crypto;

import org.bitcoinj.core.*;
import org.bitcoinj.params.*;
import org.junit.*;
import org.bouncycastle.crypto.params.*;

import static org.bitcoinj.core.Utils.*;
import static org.junit.Assert.*;

import java.util.List;
import java.util.ArrayList;

/**
 * This test is adapted from Armory's BIP 32 tests.
 */
public class ChildKeyDerivationTest {
    private static final int HDW_CHAIN_EXTERNAL = 0;
    private static final int HDW_CHAIN_INTERNAL = 1;

    @Test
    public void testChildKeyDerivation() throws Exception {
        String[] ckdTestVectors = {
                // test case 1:
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "04" +  "6a04ab98d9e4774ad806e302dddeb63b" +
                        "ea16b5cb5f223ee77478e861bb583eb3" +
                        "36b6fbcb60b5b3d4f1551ac45e5ffc49" +
                        "36466e7d98f6c7c0ec736539f74691a6",
                "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",

                // test case 2:
                "be05d9ded0a73f81b814c93792f753b35c575fe446760005d44e0be13ba8935a",
                "02" +  "b530da16bbff1428c33020e87fc9e699" +
                        "cc9c753a63b8678ce647b7457397acef",
                "7012bc411228495f25d666d55fdce3f10a93908b5f9b9b7baa6e7573603a7bda"
        };

        for(int i = 0; i < 1; i++) {
            byte[] priv  = HEX.decode(ckdTestVectors[3 * i]);
            byte[] pub   = HEX.decode(ckdTestVectors[3 * i + 1]);
            byte[] chain = HEX.decode(ckdTestVectors[3 * i + 2]); // chain code

            //////////////////////////////////////////////////////////////////////////
            // Start with an extended PRIVATE key
            DeterministicKey ekprv = HDKeyDerivation.createMasterPrivKeyFromBytes(priv, chain);

            // Create two accounts
            DeterministicKey ekprv_0 = HDKeyDerivation.deriveChildKey(ekprv, 0);
            DeterministicKey ekprv_1 = HDKeyDerivation.deriveChildKey(ekprv, 1);

            // Create internal and external chain on Account 0
            DeterministicKey ekprv_0_EX = HDKeyDerivation.deriveChildKey(ekprv_0, HDW_CHAIN_EXTERNAL);
            DeterministicKey ekprv_0_IN = HDKeyDerivation.deriveChildKey(ekprv_0, HDW_CHAIN_INTERNAL);

            // Create three addresses on external chain
            DeterministicKey ekprv_0_EX_0 = HDKeyDerivation.deriveChildKey(ekprv_0_EX, 0);
            DeterministicKey ekprv_0_EX_1 = HDKeyDerivation.deriveChildKey(ekprv_0_EX, 1);
            DeterministicKey ekprv_0_EX_2 = HDKeyDerivation.deriveChildKey(ekprv_0_EX, 2);

            // Create three addresses on internal chain
            DeterministicKey ekprv_0_IN_0 = HDKeyDerivation.deriveChildKey(ekprv_0_IN, 0);
            DeterministicKey ekprv_0_IN_1 = HDKeyDerivation.deriveChildKey(ekprv_0_IN, 1);
            DeterministicKey ekprv_0_IN_2 = HDKeyDerivation.deriveChildKey(ekprv_0_IN, 2);

            // Now add a few more addresses with very large indices
            DeterministicKey ekprv_1_IN = HDKeyDerivation.deriveChildKey(ekprv_1, HDW_CHAIN_INTERNAL);
            DeterministicKey ekprv_1_IN_4095 = HDKeyDerivation.deriveChildKey(ekprv_1_IN, 4095);
//            ExtendedHierarchicKey ekprv_1_IN_4bil = HDKeyDerivation.deriveChildKey(ekprv_1_IN, 4294967295L);

            //////////////////////////////////////////////////////////////////////////
            // Repeat the above with PUBLIC key
            DeterministicKey ekpub = HDKeyDerivation.createMasterPubKeyFromBytes(HDUtils.toCompressed(pub), chain);

            // Create two accounts
            DeterministicKey ekpub_0 = HDKeyDerivation.deriveChildKey(ekpub, 0);
            DeterministicKey ekpub_1 = HDKeyDerivation.deriveChildKey(ekpub, 1);

            // Create internal and external chain on Account 0
            DeterministicKey ekpub_0_EX = HDKeyDerivation.deriveChildKey(ekpub_0, HDW_CHAIN_EXTERNAL);
            DeterministicKey ekpub_0_IN = HDKeyDerivation.deriveChildKey(ekpub_0, HDW_CHAIN_INTERNAL);

            // Create three addresses on external chain
            DeterministicKey ekpub_0_EX_0 = HDKeyDerivation.deriveChildKey(ekpub_0_EX, 0);
            DeterministicKey ekpub_0_EX_1 = HDKeyDerivation.deriveChildKey(ekpub_0_EX, 1);
            DeterministicKey ekpub_0_EX_2 = HDKeyDerivation.deriveChildKey(ekpub_0_EX, 2);

            // Create three addresses on internal chain
            DeterministicKey ekpub_0_IN_0 = HDKeyDerivation.deriveChildKey(ekpub_0_IN, 0);
            DeterministicKey ekpub_0_IN_1 = HDKeyDerivation.deriveChildKey(ekpub_0_IN, 1);
            DeterministicKey ekpub_0_IN_2 = HDKeyDerivation.deriveChildKey(ekpub_0_IN, 2);

            // Now add a few more addresses with very large indices
            DeterministicKey ekpub_1_IN = HDKeyDerivation.deriveChildKey(ekpub_1, HDW_CHAIN_INTERNAL);
            DeterministicKey ekpub_1_IN_4095 = HDKeyDerivation.deriveChildKey(ekpub_1_IN, 4095);
//            ExtendedHierarchicKey ekpub_1_IN_4bil = HDKeyDerivation.deriveChildKey(ekpub_1_IN, 4294967295L);

            assertEquals(hexEncodePub(ekprv.dropPrivateBytes().dropParent()), hexEncodePub(ekpub));
            assertEquals(hexEncodePub(ekprv_0.dropPrivateBytes().dropParent()), hexEncodePub(ekpub_0));
            assertEquals(hexEncodePub(ekprv_1.dropPrivateBytes().dropParent()), hexEncodePub(ekpub_1));
            assertEquals(hexEncodePub(ekprv_0_IN.dropPrivateBytes().dropParent()), hexEncodePub(ekpub_0_IN));
            assertEquals(hexEncodePub(ekprv_0_IN_0.dropPrivateBytes().dropParent()), hexEncodePub(ekpub_0_IN_0));
            assertEquals(hexEncodePub(ekprv_0_IN_1.dropPrivateBytes().dropParent()), hexEncodePub(ekpub_0_IN_1));
            assertEquals(hexEncodePub(ekprv_0_IN_2.dropPrivateBytes().dropParent()), hexEncodePub(ekpub_0_IN_2));
            assertEquals(hexEncodePub(ekprv_0_EX_0.dropPrivateBytes().dropParent()), hexEncodePub(ekpub_0_EX_0));
            assertEquals(hexEncodePub(ekprv_0_EX_1.dropPrivateBytes().dropParent()), hexEncodePub(ekpub_0_EX_1));
            assertEquals(hexEncodePub(ekprv_0_EX_2.dropPrivateBytes().dropParent()), hexEncodePub(ekpub_0_EX_2));
            assertEquals(hexEncodePub(ekprv_1_IN.dropPrivateBytes().dropParent()), hexEncodePub(ekpub_1_IN));
            assertEquals(hexEncodePub(ekprv_1_IN_4095.dropPrivateBytes().dropParent()), hexEncodePub(ekpub_1_IN_4095));
            //assertEquals(hexEncodePub(ekprv_1_IN_4bil.dropPrivateBytes()), hexEncodePub(ekpub_1_IN_4bil));
        }
    }

    @Test
    public void inverseEqualsNormal() throws Exception {
        DeterministicKey key1 = HDKeyDerivation.createMasterPrivateKey("Wired / Aug 13th 2014 / Snowden: I Left the NSA Clues, But They Couldn't Find Them".getBytes());
        HDKeyDerivation.RawKeyBytes key2 = HDKeyDerivation.deriveChildKeyBytesFromPublic(key1.dropPrivateBytes().dropParent(), ChildNumber.ZERO, HDKeyDerivation.PublicDeriveMode.NORMAL);
        HDKeyDerivation.RawKeyBytes key3 = HDKeyDerivation.deriveChildKeyBytesFromPublic(key1.dropPrivateBytes().dropParent(), ChildNumber.ZERO, HDKeyDerivation.PublicDeriveMode.WITH_INVERSION);
        assertArrayEquals(key2.keyBytes, key3.keyBytes);
        assertArrayEquals(key2.chainCode, key3.chainCode);
    }

    @Test
    public void encryptedDerivation() throws Exception {
        // Check that encrypting a parent key in the heirarchy and then deriving from it yields a DeterministicKey
        // with no private key component, and that the private key bytes are derived on demand.
        KeyCrypter scrypter = new KeyCrypterScrypt();
        KeyParameter aesKey = scrypter.deriveKey("we never went to the moon");

        DeterministicKey key1 = HDKeyDerivation.createMasterPrivateKey("it was all a hoax".getBytes());
        DeterministicKey encryptedKey1 = key1.encrypt(scrypter, aesKey, null);
        DeterministicKey decryptedKey1 = encryptedKey1.decrypt(aesKey);
        assertEquals(key1, decryptedKey1);

        DeterministicKey key2 = HDKeyDerivation.deriveChildKey(key1, ChildNumber.ZERO);
        DeterministicKey derivedKey2 = HDKeyDerivation.deriveChildKey(encryptedKey1, ChildNumber.ZERO);
        assertTrue(derivedKey2.isEncrypted());   // parent is encrypted.
        DeterministicKey decryptedKey2 = derivedKey2.decrypt(aesKey);
        assertFalse(decryptedKey2.isEncrypted());
        assertEquals(key2, decryptedKey2);

        Sha256Hash hash = Sha256Hash.of("the mainstream media won't cover it. why is that?".getBytes());
        try {
            derivedKey2.sign(hash);
            fail();
        } catch (ECKey.KeyIsEncryptedException e) {
            // Ignored.
        }
        ECKey.ECDSASignature signature = derivedKey2.sign(hash, aesKey);
        assertTrue(derivedKey2.verify(hash, signature));
    }

    @Test
    public void pubOnlyDerivation() throws Exception {
        DeterministicKey key1 = HDKeyDerivation.createMasterPrivateKey("satoshi lives!".getBytes());
        assertFalse(key1.isPubKeyOnly());
        DeterministicKey key2 = HDKeyDerivation.deriveChildKey(key1, ChildNumber.ZERO_HARDENED);
        assertFalse(key2.isPubKeyOnly());
        DeterministicKey key3 = HDKeyDerivation.deriveChildKey(key2, ChildNumber.ZERO);
        assertFalse(key3.isPubKeyOnly());

        key2 = key2.dropPrivateBytes();
        assertFalse(key2.isPubKeyOnly());   // still got private key bytes from the parents!

        // pubkey2 got its cached private key bytes (if any) dropped, and now it'll lose its parent too, so now it
        // becomes a true pubkey-only object.
        DeterministicKey pubkey2 = key2.dropParent();

        DeterministicKey pubkey3 = HDKeyDerivation.deriveChildKey(pubkey2, ChildNumber.ZERO);
        assertTrue(pubkey3.isPubKeyOnly());
        assertEquals(key3.getPubKeyPoint(), pubkey3.getPubKeyPoint());
    }

    @Test
    public void testSerializationMainAndTestNetworks() {
        DeterministicKey key1 = HDKeyDerivation.createMasterPrivateKey("satoshi lives!".getBytes());
        NetworkParameters params = MainNetParams.get();
        String pub58 = key1.serializePubB58(params);
        String priv58 = key1.serializePrivB58(params);
        assertEquals("xpub661MyMwAqRbcF7mq7Aejj5xZNzFfgi3ABamE9FedDHVmViSzSxYTgAQGcATDo2J821q7Y9EAagjg5EP3L7uBZk11PxZU3hikL59dexfLkz3", pub58);
        assertEquals("xprv9s21ZrQH143K2dhN197jMx1ppxRBHFKJpMqdLsF1ewxncv7quRED8N5nksxphju3W7naj1arF56L5PUEWfuSk8h73Sb2uh7bSwyXNrjzhAZ", priv58);
        params = TestNet3Params.get();
        pub58 = key1.serializePubB58(params);
        priv58 = key1.serializePrivB58(params);
        assertEquals("tpubD6NzVbkrYhZ4WuxgZMdpw1Hvi7MKg6YDjDMXVohmZCFfF17hXBPYpc56rCY1KXFMovN29ik37nZimQseiykRTBTJTZJmjENyv2k3R12BJ1M", pub58);
        assertEquals("tprv8ZgxMBicQKsPdSvtfhyEXbdp95qPWmMK9ukkDHfU8vTGQWrvtnZxe7TEg48Ui7HMsZKMj7CcQRg8YF1ydtFPZBxha5oLa3qeN3iwpYhHPVZ", priv58);
        {
        List<String> words = new ArrayList<String>();
        words.add("dwarf");
        words.add("cheese");
        words.add("material");
        words.add("blind");
        words.add("rain");
        words.add("elder");
        words.add("train");
        words.add("tribe");
        words.add("deposit");
        words.add("exist");
        words.add("better");
        words.add("organ");
        byte[] hd_seed = MnemonicCode.toSeed(words, "test");
        key1 = HDKeyDerivation.createMasterPrivateKey(hd_seed);
        // BIP49
        DeterministicKey key2 = HDKeyDerivation.deriveChildKey(key1, 49|ChildNumber.HARDENED_BIT);
        DeterministicKey key3 = HDKeyDerivation.deriveChildKey(key2, ChildNum