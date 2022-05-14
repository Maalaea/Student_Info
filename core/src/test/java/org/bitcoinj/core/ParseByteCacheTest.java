/*
 * Copyright 2011 Steve Coughlan.
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
import org.bitcoinj.params.UnitTestParams;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.MemoryBlockStore;
import org.bitcoinj.wallet.Wallet;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.bitcoinj.core.Coin.*;
import static org.bitcoinj.core.Utils.HEX;
import static org.bitcoinj.testing.FakeTxBuilder.createFakeBlock;
import static org.bitcoinj.testing.FakeTxBuilder.createFakeTx;
import static org.junit.Assert.*;

public class ParseByteCacheTest {
    private static final int BLOCK_HEIGHT_GENESIS = 0;

    private final byte[] txMessage = HEX.withSeparator(" ", 2).decode(
            "f9 be b4 d9 74 78 00 00  00 00 00 00 00 00 00 00" +
            "02 01 00 00 e2 93 cd be  01 00 00 00 01 6d bd db" +
            "08 5b 1d 8a f7 51 84 f0  bc 01 fa d5 8d 12 66 e9" +
            "b6 3b 50 88 19 90 e4 b4  0d 6a ee 36 29 00 00 00" +
            "00 8b 48 30 45 02 21 00  f3 58 1e 19 72 ae 8a c7" +
            "c7 36 7a 7a 25 3b c1 13  52 23 ad b9 a4 68 bb 3a" +
            "59 23 3f 45 bc 57 83 80  02 20 59 af 01 ca 17 d0" +
            "0e 41 83 7a 1d 58 e9 7a  a3 1b ae 58 4e de c2 8d" +
            "35 bd 96 92 36 90 91 3b  ae 9a 01 41 04 9c 02 bf" +
            "c9 7e f2 36 ce 6d 8f e5  d9 40 13 c7 21 e9 15 98" +
            "2a cd 2b 12 b6 5d 9b 7d  59 e2 0a 84 20 05 f8 fc" +
            "4e 02 53 2e 87 3d 37 b9  6f 09 d6 d4 51 1a da 8f" +
            "14 04 2f 46 61 4a 4c 70  c0 f1 4b ef f5 ff ff ff" +
            "ff 02 40 4b 4c 00 00 00  00 00 19 76 a9 14 1a a0" +
            "cd 1c be a6 e7 45 8a 7a  ba d5 12 a9 d9 ea 1a fb" +
            "22 5e 88 ac 80 fa e9 c7  00 00 00 00 19 76 a9 14" +
            "0e ab 5b ea 43 6a 04 84  cf ab 12 48 5e fd a0 b7" +
            "8b 4e cc 52 88 ac 00 00  00 00");
    
    private final byte[] txMessagePart = HEX.withSeparator(" ", 2).decode(
            "08 5b 1d 8a f7 51 84 f0  bc 01 fa d5 8d 12 66 e9" +
            "b6 3b 50 88 19 90 e4 b4  0d 6a ee 36 29 00 00 00" +
            "00 8b 48 30 45 02 21 00  f3 58 1e 19 72 ae 8a c7" +
            "c7 36 7a 7a 25 3b c1 13  52 23 ad b9 a4 68 bb 3a");

    private BlockStore blockStore;
    private static final NetworkParameters PARAMS = UnitTestParams.get();
    
    private byte[] b1Bytes;
    private byte[] b1BytesWithHeader;
    
    private byte[] tx1Bytes;
    private byte[] tx1BytesWithHeader;
    
    private byte[] tx2Bytes;
    private byte[] tx2BytesWithHeader;

    private void resetBlockStore() {
        blockStore = new MemoryBlockStore(PARAMS);
    }
    
    @Before
    public void setUp() throws Exception {
        Context context = new Context(PARAMS);
        Wallet wallet = new Wallet(context);
        wallet.freshReceiveKey();

        resetBlockStore();
        
        Transaction tx1 = createFakeTx(PARAMS,
                valueOf(2, 0),
                wallet.currentReceiveKey().toAddress(PARAMS));
        
        // add a second input so can test granularity of byte cache.
        Transaction prevTx = new Transaction(PARAMS);
        TransactionOutput prevOut = new TransactionOutput(PARAMS, prevTx, COIN, wallet.currentReceiveKey().toAddress(PARAMS));
        prevTx.addOutput(prevOut);
        // Connect it.
        tx1.addInput(prevOut);
        
        Transaction tx2 = createFakeTx(PARAMS, COIN,
                new ECKey().toAddress(PARAMS));

        Block b1 = createFakeBlock(blockStore, BLOCK_HEIGHT_GENESIS, tx1, tx2).block;

        MessageSerializer bs = PARAMS.getDefaultSerializer();
        
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bs.serialize(tx1, bos);
        tx1BytesWithHeader = bos.toByteArray();
        tx1Bytes = tx1.bitcoinSerialize();
        
        bos.reset();
        bs.serialize(tx2, bos);
        tx2BytesWithHeader = bos.toByteArray();
        tx2Bytes = tx2.bitcoinSerialize();
        
        bos.reset();
        bs.serialize(b1, bos);
        b1BytesWithHeader = bos.toByteArray();
        b1Bytes = b1.bitcoinSerialize();
    }
    
    @Test
    public void validateSetup() {
        byte[] b1 = {1, 1, 1, 2, 3, 4, 5, 6, 7};
        byte[] b2 = {1, 2, 3};
        assertTrue(arrayContains(b1, b2));
        assertTrue(arrayContains(txMessage, txMessagePart));
        assertTrue(arrayContains(tx1BytesWithHeader, tx1Bytes));
        assertTrue(arrayContains(tx2BytesWithHeader, tx2Bytes));
        assertTrue(arrayContains(b1BytesWithHeader, b1Bytes));
        assertTrue(arrayContains(b1BytesWithHeader, tx1Bytes));
        assertTrue(arrayContains(b1BytesWithHeader, tx2Bytes));
        assertFalse(arrayContains(tx1BytesWithHeader, b1Bytes));
    }
    
    @Test
    public void testTransactionsRetain() throws Exception {
        testTransaction(MainNetParams.get(), txMessage, false, true);
        testTransaction(PARAMS, tx1BytesWithHeader, false, true);
        testTransaction(PARAMS, tx2BytesWithHeader, false, true);
    }
    
    @Test
    public void testTransactionsNoRetain() throws Exception {
        testTransaction(MainNetParams.get(), txMessage, false, false);
        testTransaction(PARAMS, tx1BytesWithHeader, false, false);
        testTransaction(PARAMS, tx2BytesWithHeader, false, false);
    }

    @Test
    public void testBlockAll() throws Exception {
        testBlock(b1BytesWithHeader, false, false);
        testBlock(b1BytesWithHeader, false, true);
    }

    public void testBlock(byte[] blockBytes, boolean isChild, boolean retain) throws Exception {
        // reference serializer to produce comparison serialization output after changes to
        // message structure.
        MessageSerializer bsRef = PARAMS.getSerializer(false);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        
        BitcoinSerializer bs = PARAMS.getSerializer(retain);
        Block b1;
        Block bRef;
        b1 = (Block) bs.deserialize(ByteBuffer.wrap(blockBytes));
        bRef = (Block) bsRef.deserialize(ByteBuffer.wrap(blockBytes));
        
        // verify our reference BitcoinSerializer produces matching byte array.
        bos.reset();
        bsRef.serialize(bRef, bos);
        assertTrue(Arrays.equals(bos.toByteArray(), blockBytes));
        
        // check retain status survive both before and after a serialization
        assertEquals(retain, b1.isHeaderBytesValid());
        assertEquals(retain, b1.isTransactionBytesValid());
        
        serDeser(bs, b1, blockBytes, null, null);
        
        assertEquals(retain, b1.isHeaderBytesValid());
        assertEquals(retain, b1.isTransactionBytesValid());
        
        // compare to ref block
        bos.reset();
        bsRef.serialize(bRef, bos);
        serDeser(bs, b1, bos.toByteArray(), null, null);
        
        // retrieve a value from a child
        b1.getTransactions();
        if (b1.getTransactions().size() > 0) {
            Transaction tx1 = b1.getTransactions().get(0);
            
            // this will always be true for all children of a block once they are retrieved.
            // the tx child inputs/outputs may not be parsed however.
            
            assertEquals(retain, tx1.isCached());
            
            // does it still match ref block?
            serDeser(bs, b1, bos.toByteArray(), null, null);
        }
        
        // refresh block
        b1 = (Block) bs.deserialize(ByteBuffer.wrap(blockBytes));
        bRef = (Block) bsRef.deserialize(ByteBuffer.wrap(blockBytes));
        
        // retrieve a value from header
        b1.getDifficultyTarget();
        
        // does it still match ref block?
        serDeser(bs, b1, bos.toByteArray(), null, null);
        
        // refresh block
        b1 = (Block) bs.deserialize(ByteBuffer.wrap(blockBytes));
        bRef = (Block) bsRef.deserialize(ByteBuffer.wrap(blockBytes));
        
        // retrieve a value from a child and header
        b1.getDifficultyTarget();

        b1.getTransactions();
        if (b1.getTransactions().size() > 0) {
            Transaction tx1 = b1.getTransactions().get(0);
            
            assertEquals(retain, tx1.isCached());
        }
        // does it still match ref block?
        serDeser(bs, b1, bos.toByteArray(), null, null);
        
        // refresh block
        b1 = (Block) bs.deserialize(ByteBuffer.wrap(blockBytes));
        bRef = (Block) bsRef.deserialize(ByteBuffer.wrap(blockBytes));

        // change a value in header
        b1.setNonce(23);
        bRef.setNonce(23);
        assertFalse(b1.isHeaderBytesValid());
        assertEquals(retain , b1.isTransactionBytesValid());
        // does it still match ref block?
        bos.reset();
        bsRef.serialize(bRef, bos);
        serDeser(bs, b1, bos.toByteArray(), null, null);
        
        // refresh block
        b1 = (Block) bs.deserialize(ByteBuffer.wrap(blockBytes));
        bRef = (Block) bsRef.deserialize(ByteBuffer.wrap(blockBytes));
        
        // retrieve a value from a child of a child
        b1.getTransactions();
        if (b1.getTransactions().size() > 0) {
            Transaction tx1 = b1.getTransactions().get(0);
            
            TransactionInput tin = tx1.getInputs().get(0);
            
            assertEquals(retain, tin.isCached());
            
            // does it still match ref tx?
            bos.reset();
            bsRef.serialize(bRef, bos);
            serDeser(bs, b1, bos.toByteArray(), null, null);
        }
        
        // refresh block
        b1 = (Block) bs.deserialize(ByteBuffer.wrap(blockBytes));
        bRef = (Block) bsRef.deserialize(ByteBuffer.wrap(blockBytes));
        
        // add an input
        b1.getTransactions();
        if (b1.getTransactions().size() > 0) {
            Transaction tx1 = b1.getTransactions().get(0);
            
            if (tx1.getInputs().size() > 0) {
                tx1.addInput(tx1.getInputs().get(0));
                // replicate on reference tx
                bRef.getTransactions().get(0).addInput(bRef.getTransactions().get(0).getInputs().get(0));
            