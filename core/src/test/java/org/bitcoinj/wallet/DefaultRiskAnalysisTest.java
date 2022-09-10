/*
 * Copyright 2013 Google Inc.
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

package org.bitcoinj.wallet;

import com.google.common.collect.*;
import org.bitcoinj.core.*;
import org.bitcoinj.crypto.*;
import org.bitcoinj.params.*;
import org.bitcoinj.script.*;
import org.bitcoinj.testing.FakeTxBuilder;
import org.bitcoinj.wallet.DefaultRiskAnalysis.*;
import org.junit.*;

import java.util.*;

import static org.bitcoinj.core.Coin.*;
import static org.bitcoinj.script.ScriptOpCodes.*;
import static org.junit.Assert.*;

public class DefaultRiskAnalysisTest {
    // Uses mainnet because isStandard checks are disabled on testnet.
    private static final NetworkParameters PARAMS = MainNetParams.get();
    private Wallet wallet;
    private final int TIMESTAMP = 1384190189;
    private static final ECKey key1 = new ECKey();
    private final ImmutableList<Transaction> NO_DEPS = ImmutableList.of();

    @Before
    public void setup() {
        wallet = new Wallet(new Context(PARAMS)) {
            @Override
            public int getLastBlockSeenHeight() {
                return 1000;
            }

            @Override
            public long getLastBlockSeenTimeSecs() {
                return TIMESTAMP;
            }
        };
    }

    @Test(expected = IllegalStateException.class)
    public void analysisCantBeUsedTwice() {
        Transaction tx = new Transaction(PARAMS);
        DefaultRiskAnalysis analysis = DefaultRiskAnalysis.FACTORY.create(wallet, tx, NO_DEPS);
        assertEquals(RiskAnalysis.Result.OK, analysis.analyze());
        assertNull(analysis.getNonFinal());
        // Verify we can't re-use a used up risk analysis.
        analysis.analyze();
    }

    @Test
    public void nonFinal() throws Exception {
        // Verify that just having a lock time in the future is not enough to be considered risky (it's still final).
        Transaction tx = new Transaction(PARAMS);
        TransactionInput input = tx.addInput(PARAMS.getGenesisBlock().getTransactions().get(0).getOutput(0));
        tx.addOutput(COIN, key1);
        tx.setLockTime(TIMESTAMP + 86400);
        DefaultRiskAnalysis analysis = DefaultRiskAnalysis.FACTORY.create(wallet, tx, NO_DEPS);
        assertEquals(RiskAnalysis.Result.OK, analysis.analyze());
        assertNull(analysis.getNonFinal());

        // Set a sequence number on the input to make it genuinely non-final. Verify it's risky.
        input.setSequenceNumber(TransactionInput.NO_SEQUENCE - 1);
        analysis = DefaultRiskAnalysis.FACTORY.create(wallet, tx, NO_DEPS);
        assertEquals(RiskAnalysis.Result.NON_FINAL, analysis.analyze());
        assertEquals(tx, analysis.getNonFinal());

        // If the lock time is the current block, it's about to become final and we consider it non-risky.
        tx.setLockTime(1000);
        analysis = DefaultRiskAnalysis.FACTORY.create(wallet, tx, NO_DEPS);
        assertEquals(RiskAnalysis.Result.OK, analysis.analyze());
    }

    @Test
    public void selfCreatedAreNotRisky() {
        Transaction tx = new Transaction(PARAMS);
        tx.addInput(PARAMS.getGenesisBlock().getTransactions().get(0).getOutput(0)).setSequenceNumber(1);
        tx.addOutput(COIN, key1);
        tx.setLockTime(TIMESTAMP + 86400);

        {
            // Is risky ...
            DefaultRiskAnalysis analysis = DefaultRiskAnalysis.FACTORY.create(wallet, tx, NO_DEPS);
            assertEquals(RiskAnalysis.Result.NON_FINAL, analysis.analyze());
        }
        tx.getConfidence().setSource(Trans