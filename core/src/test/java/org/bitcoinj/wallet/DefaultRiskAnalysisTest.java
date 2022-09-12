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
        tx.getConfidence().setSource(TransactionConfidence.Source.SELF);
        {
            // Is no longer risky.
            DefaultRiskAnalysis analysis = DefaultRiskAnalysis.FACTORY.create(wallet, tx, NO_DEPS);
            assertEquals(RiskAnalysis.Result.OK, analysis.analyze());
        }
    }

    @Test
    public void nonFinalDependency() {
        // Final tx has a dependency that is non-final.
        Transaction tx1 = new Transaction(PARAMS);
        tx1.addInput(PARAMS.getGenesisBlock().getTransactions().get(0).getOutput(0)).setSequenceNumber(1);
        TransactionOutput output = tx1.addOutput(COIN, key1);
        tx1.setLockTime(TIMESTAMP + 86400);
        Transaction tx2 = new Transaction(PARAMS);
        tx2.addInput(output);
        tx2.addOutput(COIN, new ECKey());

        DefaultRiskAnalysis analysis = DefaultRiskAnalysis.FACTORY.create(wallet, tx2, ImmutableList.of(tx1));
        assertEquals(RiskAnalysis.Result.NON_FINAL, analysis.analyze());
        assertEquals(tx1, analysis.getNonFinal());
    }

    @Test
    public void nonStandardDust() {
        Transaction standardTx = new Transaction(PARAMS);
        standardTx.addInput(PARAMS.getGenesisBlock().getTransactions().get(0).getOutput(0));
        standardTx.addOutput(COIN, key1);
        assertEquals(RiskAnalysis.Result.OK, DefaultRiskAnalysis.FACTORY.create(wallet, standardTx, NO_DEPS).analyze());

        Transaction dustTx = new Transaction(PARAMS);
        dustTx.addInput(PARAMS.getGenesisBlock().getTransactions().get(0).getOutput(0));
        dustTx.addOutput(Coin.SATOSHI, key1); // 1 Satoshi
        assertEquals(RiskAnalysis.Result.NON_STANDARD, DefaultRiskAnalysis.FACTORY.create(wallet, dustTx, NO_DEPS).analyze());

        Transaction edgeCaseTx = new Transaction(PARAMS);
        edgeCaseTx.addInput(PARAMS.getGenesisBlock().getTransactions().get(0).getOutput(0));
        edgeCaseTx.addOutput(DefaultRiskAnalysis.MIN_ANALYSIS_NONDUST_OUTPUT, key1); // Dust threshold
        assertEquals(RiskAnalysis.Result.OK, DefaultRiskAnalysis.FACTORY.create(wallet, edgeCaseTx, NO_DEPS).analyze());
    }

    @Test
    public void nonShortestPossiblePushData() {
        ScriptChunk nonStandardChunk = new ScriptChunk(OP_PUSHDATA1, new byte[75]);
        byte[] nonStandardScript = new ScriptBuilder().addChunk(nonStandardChunk).build().getProgram();
        // Test non-standard script as an input.
        Transaction tx = new Transaction(PARAMS);
        assertEquals(DefaultRiskAnalysis.RuleViolation.NONE, DefaultRiskAnalysis.isStandard(tx));
        tx.addInput(new TransactionInput(PARAMS, null, nonStandardScript));
        assertEquals(DefaultRiskAnalysis.RuleViolation.SHORTEST_POSSIBLE_PUSHDATA, DefaultRiskAnalysis.isStandard(tx));
        // Test non-standard script as an output.
        tx.clearInputs();
        assertEquals(DefaultRiskAnalysis.RuleViolation.NONE, DefaultRiskAnalysis.isStandard(tx));
        tx.addOutput(new TransactionOutput(PARAMS, null, COIN, nonStandardScript));
        assertEquals(DefaultRiskAnalysis.RuleViolation.SHORTEST_POSSIBLE_PUSHDATA, DefaultRiskAnalysis.isStandard(tx));
    }

    @Test
    public void canonicalSignature() {
        TransactionSignature sig = TransactionSignature.dummy();
        Script scriptOk = ScriptBuilder.createInputScript(sig);
        assertEquals(RuleViolation.NONE,
                DefaultRiskAnalysis.isInputStandard(new TransactionInput(PARAMS, null, scriptOk.getProgram())));

        byte[] sigBytes = sig.encodeToBitcoin();
        // Appending a zero byte makes the signature uncanonical without violating DER encoding.
        Script scriptUncanonicalEncoding = new ScriptBuilder().data(Arrays.copyOf(sigBytes, sigBytes.length + 1))
                .build();
        assertEquals(RuleViolation.SIGNATURE_CANONICAL_ENCODING,
                DefaultRiskAnalysis.isInputStandard(new TransactionInput(PARAMS, null, scriptUncanonicalEncoding
                        .getProgram())));
    }

    @Test
    public void canonicalSignatureLowS() {
        // First, a synthetic test.
        TransactionSignature sig = TransactionSignature.dummy();
        Script scriptHighS = ScriptBuilder
                .createInputScript(new TransactionSignature(sig.r, ECKey.CURVE.getN().subtract(sig.s)));
        assertEquals(RuleViolation.SIGNATURE_CANONICAL_ENCODING,
                DefaultRiskAnalysis.isInputStandard(new Transacti