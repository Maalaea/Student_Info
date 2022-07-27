
/*
 * Copyright 2011 Google Inc.
 * Copyright 2016 Andreas Schildbach
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

package org.bitcoinj.testing;

import org.bitcoinj.core.*;
import org.bitcoinj.crypto.TransactionSignature;
import org.bitcoinj.script.ScriptBuilder;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Random;

import static org.bitcoinj.core.Coin.*;
import static com.google.common.base.Preconditions.checkState;

public class FakeTxBuilder {
    /** Create a fake transaction, without change. */
    public static Transaction createFakeTx(final NetworkParameters params) {
        return createFakeTxWithoutChangeAddress(params, Coin.COIN, new ECKey().toAddress(params));
    }

    /** Create a fake transaction, without change. */
    public static Transaction createFakeTxWithoutChange(final NetworkParameters params, final TransactionOutput output) {
        Transaction prevTx = FakeTxBuilder.createFakeTx(params, Coin.COIN, new ECKey().toAddress(params));
        Transaction tx = new Transaction(params);
        tx.addOutput(output);
        tx.addInput(prevTx.getOutput(0));
        return tx;
    }

    /** Create a fake coinbase transaction. */
    public static Transaction createFakeCoinbaseTx(final NetworkParameters params) {
        TransactionOutPoint outpoint = new TransactionOutPoint(params, -1, Sha256Hash.ZERO_HASH);
        TransactionInput input = new TransactionInput(params, null, new byte[0], outpoint);
        Transaction tx = new Transaction(params);
        tx.addInput(input);
        TransactionOutput outputToMe = new TransactionOutput(params, tx, Coin.FIFTY_COINS,
                new ECKey().toAddress(params));
        tx.addOutput(outputToMe);

        checkState(tx.isCoinBase());
        return tx;
    }

    /**
     * Create a fake TX of sufficient realism to exercise the unit tests. Two outputs, one to us, one to somewhere
     * else to simulate change. There is one random input.
     */
    public static Transaction createFakeTxWithChangeAddress(NetworkParameters params, Coin value, Address to, Address changeOutput) {
        Transaction t = new Transaction(params);
        TransactionOutput outputToMe = new TransactionOutput(params, t, value, to);
        t.addOutput(outputToMe);
        TransactionOutput change = new TransactionOutput(params, t, valueOf(1, 11), changeOutput);
        t.addOutput(change);
        // Make a previous tx simply to send us sufficient coins. This prev tx is not really valid but it doesn't
        // matter for our purposes.
        Transaction prevTx = new Transaction(params);
        TransactionOutput prevOut = new TransactionOutput(params, prevTx, value, to);
        prevTx.addOutput(prevOut);
        // Connect it.
        t.addInput(prevOut).setScriptSig(ScriptBuilder.createInputScript(TransactionSignature.dummy()));
        // Fake signature.
        // Serialize/deserialize to ensure internal state is stripped, as if it had been read from the wire.
        return roundTripTransaction(params, t);
    }

    /**
     * Create a fake TX for unit tests, for use with unit tests that need greater control. One outputs, 2 random inputs,
     * split randomly to create randomness.
     */
    public static Transaction createFakeTxWithoutChangeAddress(NetworkParameters params, Coin value, Address to) {
        Transaction t = new Transaction(params);
        TransactionOutput outputToMe = new TransactionOutput(params, t, value, to);
        t.addOutput(outputToMe);

        // Make a random split in the output value so we get a distinct hash when we call this multiple times with same args
        long split = new Random().nextLong();
        if (split < 0) { split *= -1; }
        if (split == 0) { split = 15; }
        while (split > value.getValue()) {
            split /= 2;
        }

        // Make a previous tx simply to send us sufficient coins. This prev tx is not really valid but it doesn't
        // matter for our purposes.
        Transaction prevTx1 = new Transaction(params);
        TransactionOutput prevOut1 = new TransactionOutput(params, prevTx1, Coin.valueOf(split), to);
        prevTx1.addOutput(prevOut1);
        // Connect it.
        t.addInput(prevOut1).setScriptSig(ScriptBuilder.createInputScript(TransactionSignature.dummy()));
        // Fake signature.

        // Do it again
        Transaction prevTx2 = new Transaction(params);
        TransactionOutput prevOut2 = new TransactionOutput(params, prevTx2, Coin.valueOf(value.getValue() - split), to);
        prevTx2.addOutput(prevOut2);
        t.addInput(prevOut2).setScriptSig(ScriptBuilder.createInputScript(TransactionSignature.dummy()));

        // Serialize/deserialize to ensure internal state is stripped, as if it had been read from the wire.
        return roundTripTransaction(params, t);
    }

    /**
     * Create a fake TX of sufficient realism to exercise the unit tests. Two outputs, one to us, one to somewhere
     * else to simulate change. There is one random input.
     */
    public static Transaction createFakeTx(NetworkParameters params, Coin value, Address to) {
        return createFakeTxWithChangeAddress(params, value, to, new ECKey().toAddress(params));
    }

    /**
     * Create a fake TX of sufficient realism to exercise the unit tests. Two outputs, one to us, one to somewhere
     * else to simulate change. There is one random input.
     */
    public static Transaction createFakeTx(NetworkParameters params, Coin value, ECKey to) {
        Transaction t = new Transaction(params);
        TransactionOutput outputToMe = new TransactionOutput(params, t, value, to);
        t.addOutput(outputToMe);
        TransactionOutput change = new TransactionOutput(params, t, valueOf(1, 11), new ECKey());
        t.addOutput(change);