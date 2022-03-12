/*
 * Copyright 2011 Google Inc.
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

package org.bitcoinj.core;

import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.params.TestNet2Params;
import org.bitcoinj.params.UnitTestParams;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.MemoryBlockStore;
import org.bitcoinj.testing.FakeTxBuilder;
import org.bitcoinj.utils.BriefLogFormatter;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.wallet.Wallet.BalanceType;

import com.google.common.util.concurrent.ListenableFuture;
import org.junit.rules.ExpectedException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.math.BigInteger;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import static org.bitcoinj.core.Coin.*;
import static org.bitcoinj.testing.FakeTxBuilder.createFakeBlock;
import static org.bitcoinj.testing.FakeTxBuilder.createFakeTx;
import static org.junit.Assert.*;

// Handling of chain splits/reorgs are in ChainSplitTests.

public class BlockChainTest {
    @Rule
    public ExpectedException thrown = ExpectedException.none();

    private BlockChain testNetChain;

    private Wallet wallet;
    private BlockChain chain;
    private BlockStore blockStore;
    private Address coinbaseTo;
    private static final NetworkParameters PARAMS = UnitTestParams.get();
    private final StoredBlock[] block = new StoredBlock[1];
    private Transaction coinbaseTransaction;

    private static class TweakableTestNet2Params extends TestNet2Params {
        public void setMaxTarget(BigInteger limit) {
            maxTarget = limit;
        }
    }
    private static final TweakableTestNet2Params testNet = new TweakableTestNet2Params();

    private void resetBlockStore() {
        blockStore = new MemoryBlockStore(PARAMS);
    }

    @Before
    public void setUp() throws Exception {
        BriefLogFormatter.initVerbose();
        Context.propagate(new Context(testNet, 100, Coin.ZERO, false));
        testNetChain = new BlockChain(testNet, new Wallet(testNet), new MemoryBlockStore(testNet));
        Context.propagate(new Context(PARAMS, 100, Coin.ZERO, false));
        wallet = new Wallet(PARAMS) {
            @Override
            public void receiveFromBlock(Transaction tx, StoredBlock block, BlockChain.NewBlockType blockType,
                                         int relativityOffset) throws VerificationException {
                super.receiveFromBlock(tx, block, blockType, relativityOffset);
                BlockChainTest.this.block[0] = block;
                if (isTransactionRelevant(tx) && tx.isCoinBase()) {
                    BlockChainTest.this.coinbaseTransaction = tx;
                }
            }
        };
        wallet.freshReceiveKey();

        resetBlockStore();
        chain = new BlockChain(PARAMS, wallet, blockStore);

        coinbaseTo = wallet.currentReceiveKey().toAddress(PARAMS);
    }

    @Test
    public void testBasicChaining() throws Exception {
        // Check that we can plug a few blocks together and the futures work.
        ListenableFuture<StoredBlock> future = testNetChain.getHeightFuture(2);
        // Block 1 from the testnet.
        Block b1 = getBlock1();
        assertTrue(testNetChain.add(b1));
        assertFalse(future.isDone());
        // Block 2 from the testnet.
        Block b2 = getBlock2();

        // Let's try adding an invalid block.
        long n = b2.getNonce();
        try {
            b2.setNonce(12345);
            testNetChain.add(b2);
            fail();
        } catch (VerificationException e) {
            b2.setNonce(n);
        }

        // Now it works because we reset the nonce.
        assertTrue(testNetChain.add(b2));
        assertTrue(future.isDone());
        assertEquals(2, future.get().getHeight());
    }

    @Test
    public void receiveCoins() throws Exception {
        int height = 1;
        // Quick check that we can actually receive coins.
        Transaction tx1 = createFakeTx(PARAMS,
                                       COIN,
                                       wallet.currentReceiveKey().toAddress(PARAMS));
        Block b1 = createFakeBlock(blockStore, height, tx1).block;
        chain.add(b1);
        assertTrue(wallet.getBalance().signum() > 0);
    }

    @Test
    public void unconnectedB