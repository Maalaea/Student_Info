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
            Dete