
/*
 * Copyright by the original author or authors.
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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.bitcoinj.core.Transaction.SigHash;
import org.bitcoinj.crypto.TransactionSignature;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.bitcoinj.script.ScriptException;
import com.google.common.base.Preconditions;

import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.*;

import static org.bitcoinj.core.Coin.*;
import static org.bitcoinj.script.ScriptOpCodes.*;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

/**
 * YOU ARE READING THIS CODE BECAUSE EITHER...
 *
 * a) You are testing an alternative implementation with full validation rules. If you are doing this, you should go
 *    rethink your life. Seriously, why are you reimplementing Bitcoin consensus rules? Instead, go work on making
 *    Bitcoin Core consensus rules a shared library and use that. Seriously, you wont get it right, and starting with
 *    this tester as a way to try to do so will simply end in pain and lost coins. SERIOUSLY, JUST STOP!
 *
 * b) Bitcoin Core is failing some test in here and you're wondering what test is causing failure. Just stop. There is no
 *    hope trying to read this file and decipher it. Give up and ping BlueMatt. Seriously, this stuff is a huge mess.
 *
 * c) You are trying to add a new test. STOP! WHY THE HELL WOULD YOU EVEN DO THAT? GO REWRITE THIS TESTER!
 *
 * d) You are BlueMatt and you're trying to hack more crap onto this multi-headed lopsided Proof Of Stake. Why are you
 *    doing this? Seriously, why have you not rewritten this thing yet? WTF man...
 *
 * IN ANY CASE, STOP READING NOW. IT WILL SAVE YOU MUCH PAIN AND MISERY LATER
 */

class NewBlock {
    public Block block;
    private TransactionOutPointWithValue spendableOutput;
    public NewBlock(Block block, TransactionOutPointWithValue spendableOutput) {
        this.block = block; this.spendableOutput = spendableOutput;
    }
    // Wrappers to make it more block-like
    public Sha256Hash getHash() { return block.getHash(); }
    public void solve() { block.solve(); }
    public void addTransaction(Transaction tx) { block.addTransaction(tx); }

    public TransactionOutPointWithValue getCoinbaseOutput() {
        return new TransactionOutPointWithValue(block.getTransactions().get(0), 0);
    }

    public TransactionOutPointWithValue getSpendableOutput() {
        return spendableOutput;
    }
}

class TransactionOutPointWithValue {
    public TransactionOutPoint outpoint;
    public Coin value;
    public Script scriptPubKey;

    public TransactionOutPointWithValue(TransactionOutPoint outpoint, Coin value, Script scriptPubKey) {
        this.outpoint = outpoint;
        this.value = value;
        this.scriptPubKey = scriptPubKey;
    }

    public TransactionOutPointWithValue(Transaction tx, int output) {
        this(new TransactionOutPoint(tx.getParams(), output, tx.getHash()),
                tx.getOutput(output).getValue(), tx.getOutput(output).getScriptPubKey());
    }
}

/** An arbitrary rule which the testing client must match */
class Rule {
    String ruleName;
    Rule(String ruleName) {
        this.ruleName = ruleName;
    }
}

/**
 * A test which checks the mempool state (ie defined which transactions should be in memory pool
 */
class MemoryPoolState extends Rule {
    Set<InventoryItem> mempool;
    public MemoryPoolState(Set<InventoryItem> mempool, String ruleName) {
        super(ruleName);
        this.mempool = mempool;
    }
}

class UTXORule extends Rule {
    List<TransactionOutPoint> query;
    UTXOsMessage result;

    public UTXORule(String ruleName, TransactionOutPoint query, UTXOsMessage result) {
        super(ruleName);
        this.query = Collections.singletonList(query);
        this.result = result;
    }

    public UTXORule(String ruleName, List<TransactionOutPoint> query, UTXOsMessage result) {
        super(ruleName);
        this.query = query;
        this.result = result;
    }
}

class RuleList {
    public List<Rule> list;
    public int maximumReorgBlockCount;
    Map<Sha256Hash, Block> hashHeaderMap;
    public RuleList(List<Rule> list, Map<Sha256Hash, Block> hashHeaderMap, int maximumReorgBlockCount) {
        this.list = list;
        this.hashHeaderMap = hashHeaderMap;
        this.maximumReorgBlockCount = maximumReorgBlockCount;
    }
}

public class FullBlockTestGenerator {
    // Used by BitcoindComparisonTool and AbstractFullPrunedBlockChainTest to create test cases
    private NetworkParameters params;
    private ECKey coinbaseOutKey;
    private byte[] coinbaseOutKeyPubKey;

    // Used to double-check that we are always using the right next-height
    private Map<Sha256Hash, Integer> blockToHeightMap = new HashMap<>();

    private Map<Sha256Hash, Block> hashHeaderMap = new HashMap<>();
    private Map<Sha256Hash, Sha256Hash> coinbaseBlockMap = new HashMap<>();

    public FullBlockTestGenerator(NetworkParameters params) {
        this.params = params;
        coinbaseOutKey = new ECKey();
        coinbaseOutKeyPubKey = coinbaseOutKey.getPubKey();
        Utils.setMockClock();
    }

    public RuleList getBlocksToTest(boolean runBarelyExpensiveTests, boolean runExpensiveTests, File blockStorageFile) throws ScriptException, ProtocolException, IOException {
        final FileOutputStream outStream = blockStorageFile != null ? new FileOutputStream(blockStorageFile) : null;

        final Script OP_TRUE_SCRIPT = new ScriptBuilder().op(OP_TRUE).build();
        final Script OP_NOP_SCRIPT = new ScriptBuilder().op(OP_NOP).build();

        // TODO: Rename this variable.
        List<Rule> blocks = new LinkedList<Rule>() {
            @Override
            public boolean add(Rule element) {
                if (outStream != null && element instanceof BlockAndValidity) {
                    try {
                        outStream.write((int) (params.getPacketMagic() >>> 24));
                        outStream.write((int) (params.getPacketMagic() >>> 16));
                        outStream.write((int) (params.getPacketMagic() >>> 8));
                        outStream.write((int) params.getPacketMagic());
                        byte[] block = ((BlockAndValidity)element).block.bitcoinSerialize();
                        byte[] length = new byte[4];
                        Utils.uint32ToByteArrayBE(block.length, length, 0);
                        outStream.write(Utils.reverseBytes(length));
                        outStream.write(block);
                        ((BlockAndValidity)element).block = null;
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
                return super.add(element);
            }
        };
        RuleList ret = new RuleList(blocks, hashHeaderMap, 10);

        Queue<TransactionOutPointWithValue> spendableOutputs = new LinkedList<>();

        int chainHeadHeight = 1;
        Block chainHead = params.getGenesisBlock().createNextBlockWithCoinbase(Block.BLOCK_VERSION_GENESIS, coinbaseOutKeyPubKey, chainHeadHeight);
        blocks.add(new BlockAndValidity(chainHead, true, false, chainHead.getHash(), 1, "Initial Block"));
        spendableOutputs.offer(new TransactionOutPointWithValue(
                new TransactionOutPoint(params, 0, chainHead.getTransactions().get(0).getHash()),
                FIFTY_COINS, chainHead.getTransactions().get(0).getOutputs().get(0).getScriptPubKey()));
        for (int i = 1; i < params.getSpendableCoinbaseDepth(); i++) {
            chainHead = chainHead.createNextBlockWithCoinbase(Block.BLOCK_VERSION_GENESIS, coinbaseOutKeyPubKey, chainHeadHeight);
            chainHeadHeight++;
            blocks.add(new BlockAndValidity(chainHead, true, false, chainHead.getHash(), i+1, "Initial Block chain output generation"));
            spendableOutputs.offer(new TransactionOutPointWithValue(
                    new TransactionOutPoint(params, 0, chainHead.getTransactions().get(0).getHash()),
                    FIFTY_COINS, chainHead.getTransactions().get(0).getOutputs().get(0).getScriptPubKey()));
        }

        // Start by building a couple of blocks on top of the genesis block.
        NewBlock b1 = createNextBlock(chainHead, chainHeadHeight + 1, spendableOutputs.poll(), null);
        blocks.add(new BlockAndValidity(b1, true, false, b1.getHash(), chainHeadHeight + 1, "b1"));
        spendableOutputs.offer(b1.getCoinbaseOutput());

        TransactionOutPointWithValue out1 = spendableOutputs.poll(); checkState(out1 != null);
        NewBlock b2 = createNextBlock(b1, chainHeadHeight + 2, out1, null);
        blocks.add(new BlockAndValidity(b2, true, false, b2.getHash(), chainHeadHeight + 2, "b2"));
        // Make sure nothing funky happens if we try to re-add b2
        blocks.add(new BlockAndValidity(b2, true, false, b2.getHash(), chainHeadHeight + 2, "b2"));
        spendableOutputs.offer(b2.getCoinbaseOutput());
        // We now have the following chain (which output is spent is in parentheses):
        //     genesis -> b1 (0) -> b2 (1)
        //
        // so fork like this:
        //
        //     genesis -> b1 (0) -> b2 (1)
        //                      \-> b3 (1)
        //
        // Nothing should happen at this point. We saw b2 first so it takes priority.
        NewBlock b3 = createNextBlock(b1, chainHeadHeight + 2, out1, null);
        blocks.add(new BlockAndValidity(b3, true, false, b2.getHash(), chainHeadHeight + 2, "b3"));
        // Make sure nothing breaks if we add b3 twice
        blocks.add(new BlockAndValidity(b3, true, false, b2.getHash(), chainHeadHeight + 2, "b3"));

        // Do a simple UTXO query.
        UTXORule utxo1;
        {
            Transaction coinbase = b2.block.getTransactions().get(0);
            TransactionOutPoint outpoint = new TransactionOutPoint(params, 0, coinbase.getHash());
            long[] heights = {chainHeadHeight + 2};
            UTXOsMessage result = new UTXOsMessage(params, ImmutableList.of(coinbase.getOutput(0)), heights, b2.getHash(), chainHeadHeight + 2);
            utxo1 = new UTXORule("utxo1", outpoint, result);
            blocks.add(utxo1);
        }

        // Now we add another block to make the alternative chain longer.
        //
        //     genesis -> b1 (0) -> b2 (1)
        //                      \-> b3 (1) -> b4 (2)
        //
        TransactionOutPointWithValue out2 = checkNotNull(spendableOutputs.poll());
        NewBlock b4 = createNextBlock(b3, chainHeadHeight + 3, out2, null);
        blocks.add(new BlockAndValidity(b4, true, false, b4.getHash(), chainHeadHeight + 3, "b4"));

        // Check that the old coinbase is no longer in the UTXO set and the new one is.
        {
            Transaction coinbase = b4.block.getTransactions().get(0);
            TransactionOutPoint outpoint = new TransactionOutPoint(params, 0, coinbase.getHash());
            List<TransactionOutPoint> queries = ImmutableList.of(utxo1.query.get(0), outpoint);
            List<TransactionOutput> results = Lists.asList(null, coinbase.getOutput(0), new TransactionOutput[]{});
            long[] heights = {chainHeadHeight + 3};
            UTXOsMessage result = new UTXOsMessage(params, results, heights, b4.getHash(), chainHeadHeight + 3);
            UTXORule utxo2 = new UTXORule("utxo2", queries, result);
            blocks.add(utxo2);
        }

        // ... and back to the first chain.
        NewBlock b5 = createNextBlock(b2, chainHeadHeight + 3, out2, null);
        blocks.add(new BlockAndValidity(b5, true, false, b4.getHash(), chainHeadHeight + 3, "b5"));
        spendableOutputs.offer(b5.getCoinbaseOutput());

        TransactionOutPointWithValue out3 = spendableOutputs.poll();

        NewBlock b6 = createNextBlock(b5, chainHeadHeight + 4, out3, null);
        blocks.add(new BlockAndValidity(b6, true, false, b6.getHash(), chainHeadHeight + 4, "b6"));
        //
        //     genesis -> b1 (0) -> b2 (1) -> b5 (2) -> b6 (3)
        //                      \-> b3 (1) -> b4 (2)
        //

        // Try to create a fork that double-spends
        //     genesis -> b1 (0) -> b2 (1) -> b5 (2) -> b6 (3)
        //                                          \-> b7 (2) -> b8 (4)
        //                      \-> b3 (1) -> b4 (2)
        //
        NewBlock b7 = createNextBlock(b5, chainHeadHeight + 5, out2, null);
        blocks.add(new BlockAndValidity(b7, true, false, b6.getHash(), chainHeadHeight + 4, "b7"));

        TransactionOutPointWithValue out4 = spendableOutputs.poll();

        NewBlock b8 = createNextBlock(b7, chainHeadHeight + 6, out4, null);
        blocks.add(new BlockAndValidity(b8, false, true, b6.getHash(), chainHeadHeight + 4, "b8"));

        // Try to create a block that has too much fee
        //     genesis -> b1 (0) -> b2 (1) -> b5 (2) -> b6 (3)
        //                                                    \-> b9 (4)
        //                      \-> b3 (1) -> b4 (2)
        //
        NewBlock b9 = createNextBlock(b6, chainHeadHeight + 5, out4, SATOSHI);
        blocks.add(new BlockAndValidity(b9, false, true, b6.getHash(), chainHeadHeight + 4, "b9"));

        // Create a fork that ends in a block with too much fee (the one that causes the reorg)
        //     genesis -> b1 (0) -> b2 (1) -> b5 (2) -> b6  (3)
        //                                          \-> b10 (3) -> b11 (4)
        //                      \-> b3 (1) -> b4 (2)
        //
        NewBlock b10 = createNextBlock(b5, chainHeadHeight + 4, out3, null);
        blocks.add(new BlockAndValidity(b10, true, false, b6.getHash(), chainHeadHeight + 4, "b10"));

        NewBlock b11 = createNextBlock(b10, chainHeadHeight + 5, out4, SATOSHI);
        blocks.add(new BlockAndValidity(b11, false, true, b6.getHash(), chainHeadHeight + 4, "b11"));

        // Try again, but with a valid fork first
        //     genesis -> b1 (0) -> b2 (1) -> b5 (2) -> b6  (3)
        //                                          \-> b12 (3) -> b13 (4) -> b14 (5)
        //                                              (b12 added last)
        //                      \-> b3 (1) -> b4 (2)
        //
        NewBlock b12 = createNextBlock(b5, chainHeadHeight + 4, out3, null);
        spendableOutputs.offer(b12.getCoinbaseOutput());

        NewBlock b13 = createNextBlock(b12, chainHeadHeight + 5, out4, null);
        blocks.add(new BlockAndValidity(b13, false, false, b6.getHash(), chainHeadHeight + 4, "b13"));
        // Make sure we dont die if an orphan gets added twice
        blocks.add(new BlockAndValidity(b13, false, false, b6.getHash(), chainHeadHeight + 4, "b13"));
        spendableOutputs.offer(b13.getCoinbaseOutput());

        TransactionOutPointWithValue out5 = spendableOutputs.poll();

        NewBlock b14 = createNextBlock(b13, chainHeadHeight + 6, out5, SATOSHI);
        // This will be "validly" added, though its actually invalid, it will just be marked orphan
        // and will be discarded when an attempt is made to reorg to it.
        // TODO: Use a WeakReference to check that it is freed properly after the reorg
        blocks.add(new BlockAndValidity(b14, false, false, b6.getHash(), chainHeadHeight + 4, "b14"));
        // Make sure we dont die if an orphan gets added twice
        blocks.add(new BlockAndValidity(b14, false, false, b6.getHash(), chainHeadHeight + 4, "b14"));

        blocks.add(new BlockAndValidity(b12, false, true, b13.getHash(), chainHeadHeight + 5, "b12"));

        // Add a block with MAX_BLOCK_SIGOPS and one with one more sigop
        //     genesis -> b1 (0) -> b2 (1) -> b5 (2) -> b6  (3)
        //                                          \-> b12 (3) -> b13 (4) -> b15 (5) -> b16 (6)
        //                      \-> b3 (1) -> b4 (2)
        //
        NewBlock b15 = createNextBlock(b13, chainHeadHeight + 6, out5, null);
        {
            int sigOps = 0;
            for (Transaction tx : b15.block.getTransactions())
                sigOps += tx.getSigOpCount();
            Transaction tx = new Transaction(params);
            byte[] outputScript = new byte[Block.MAX_BLOCK_SIGOPS - sigOps];
            Arrays.fill(outputScript, (byte) OP_CHECKSIG);
            tx.addOutput(new TransactionOutput(params, tx, SATOSHI, outputScript));
            addOnlyInputToTransaction(tx, b15);
            b15.addTransaction(tx);

            sigOps = 0;
            for (Transaction tx2 : b15.block.getTransactions())
                sigOps += tx2.getSigOpCount();
            checkState(sigOps == Block.MAX_BLOCK_SIGOPS);
        }
        b15.solve();

        blocks.add(new BlockAndValidity(b15, true, false, b15.getHash(), chainHeadHeight + 6, "b15"));
        spendableOutputs.offer(b15.getCoinbaseOutput());

        TransactionOutPointWithValue out6 = spendableOutputs.poll();

        NewBlock b16 = createNextBlock(b15, chainHeadHeight + 7, out6, null);
        {
            int sigOps = 0;
            for (Transaction tx : b16.block.getTransactions()) {
                sigOps += tx.getSigOpCount();
            }
            Transaction tx = new Transaction(params);
            byte[] outputScript = new byte[Block.MAX_BLOCK_SIGOPS - sigOps + 1];
            Arrays.fill(outputScript, (byte) OP_CHECKSIG);
            tx.addOutput(new TransactionOutput(params, tx, SATOSHI, outputScript));
            addOnlyInputToTransaction(tx, b16);
            b16.addTransaction(tx);

            sigOps = 0;
            for (Transaction tx2 : b16.block.getTransactions())
                sigOps += tx2.getSigOpCount();
            checkState(sigOps == Block.MAX_BLOCK_SIGOPS + 1);
        }
        b16.solve();

        blocks.add(new BlockAndValidity(b16, false, true, b15.getHash(), chainHeadHeight + 6, "b16"));

        // Attempt to spend a transaction created on a different fork
        //     genesis -> b1 (0) -> b2 (1) -> b5 (2) -> b6  (3)
        //                                          \-> b12 (3) -> b13 (4) -> b15 (5) -> b17 (6)
        //                      \-> b3 (1) -> b4 (2)
        //
        NewBlock b17 = createNextBlock(b15, chainHeadHeight + 7, out6, null);
        {
            Transaction tx = new Transaction(params);
            tx.addOutput(new TransactionOutput(params, tx, SATOSHI, new byte[] {}));
            addOnlyInputToTransaction(tx, b3);
            b17.addTransaction(tx);
        }
        b17.solve();
        blocks.add(new BlockAndValidity(b17, false, true, b15.getHash(), chainHeadHeight + 6, "b17"));

        // Attempt to spend a transaction created on a different fork (on a fork this time)
        //     genesis -> b1 (0) -> b2 (1) -> b5 (2) -> b6  (3)
        //                                          \-> b12 (3) -> b13 (4) -> b15 (5)
        //                                                                \-> b18 (5) -> b19 (6)
        //                      \-> b3 (1) -> b4 (2)
        //
        NewBlock b18 = createNextBlock(b13, chainHeadHeight + 6, out5, null);
        {
            Transaction tx = new Transaction(params);
            tx.addOutput(new TransactionOutput(params, tx, SATOSHI, new byte[] {}));
            addOnlyInputToTransaction(tx, b3);
            b18.addTransaction(tx);
        }
        b18.solve();
        blocks.add(new BlockAndValidity(b18, true, false, b15.getHash(), chainHeadHeight + 6, "b17"));

        NewBlock b19 = createNextBlock(b18, chainHeadHeight + 7, out6, null);
        blocks.add(new BlockAndValidity(b19, false, true, b15.getHash(), chainHeadHeight + 6, "b19"));

        // Attempt to spend a coinbase at depth too low
        //     genesis -> b1 (0) -> b2 (1) -> b5 (2) -> b6  (3)
        //                                          \-> b12 (3) -> b13 (4) -> b15 (5) -> b20 (7)
        //                      \-> b3 (1) -> b4 (2)
        //
        TransactionOutPointWithValue out7 = spendableOutputs.poll();

        NewBlock b20 = createNextBlock(b15.block, chainHeadHeight + 7, out7, null);
        blocks.add(new BlockAndValidity(b20, false, true, b15.getHash(), chainHeadHeight + 6, "b20"));

        // Attempt to spend a coinbase at depth too low (on a fork this time)
        //     genesis -> b1 (0) -> b2 (1) -> b5 (2) -> b6  (3)
        //                                          \-> b12 (3) -> b13 (4) -> b15 (5)
        //                                                                \-> b21 (6) -> b22 (5)
        //                      \-> b3 (1) -> b4 (2)
        //
        NewBlock b21 = createNextBlock(b13, chainHeadHeight + 6, out6, null);
        blocks.add(new BlockAndValidity(b21.block, true, false, b15.getHash(), chainHeadHeight + 6, "b21"));
        NewBlock b22 = createNextBlock(b21, chainHeadHeight + 7, out5, null);
        blocks.add(new BlockAndValidity(b22.block, false, true, b15.getHash(), chainHeadHeight + 6, "b22"));

        // Create a block on either side of MAX_BLOCK_SIZE and make sure its accepted/rejected
        //     genesis -> b1 (0) -> b2 (1) -> b5 (2) -> b6  (3)
        //                                          \-> b12 (3) -> b13 (4) -> b15 (5) -> b23 (6)
        //                                                                           \-> b24 (6) -> b25 (7)
        //                      \-> b3 (1) -> b4 (2)
        //
        NewBlock b23 = createNextBlock(b15, chainHeadHeight + 7, out6, null);
        {
            Transaction tx = new Transaction(params);
            byte[] outputScript = new byte[Block.MAX_BLOCK_SIZE - b23.block.getMessageSize() - 65];
            Arrays.fill(outputScript, (byte) OP_FALSE);