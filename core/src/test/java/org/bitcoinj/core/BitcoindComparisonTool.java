/*
 * Copyright 2012 Matt Corallo.
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

import com.google.common.base.*;
import com.google.common.collect.*;
import com.google.common.util.concurrent.*;
import org.bitcoinj.core.listeners.*;
import org.bitcoinj.net.*;
import org.bitcoinj.params.*;
import org.bitcoinj.store.*;
import org.bitcoinj.utils.*;
import org.slf4j.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/**
 * A tool for comparing the blocks which are accepted/rejected by bitcoind/bitcoinj
 * It is designed to run as a testnet-in-a-box network between a single bitcoind node and bitcoinj
 * It is not an automated unit-test because it requires a bit more set-up...read comments below
 */
public class BitcoindComparisonTool {
    private static final Logger log = LoggerFactory.getLogger(BitcoindComparisonTool.class);

    private static NetworkParameters params;
    private static FullPrunedBlockChain chain;
    private static Sha256Hash bitcoindChainHead;
    private static volatile InventoryMessage mostRecentInv = null;

    static class BlockWrapper {
        public Block block;
    }

    public static void main(String[] args) throws Exception {
        BriefLogFormatter.init();
        System.out.println("USAGE: bitcoinjBlockStoreLocation runExpensiveTests(1/0) [port=18444]");
        boolean runExpensiveTests = args.length > 1 && Integer.parseInt(args[1]) == 1;

        params = RegTestParams.get();
        Context ctx = new Context(params);

        File blockFile = File.createTempFile("testBlocks", ".dat");
        blockFile.deleteOnExit();

        FullBlockTestGenerator generator = new FullBlockTestGenerator(params);
        final RuleList blockList = generator.getBlocksToTest(false, runExpensiveTests, blockFile);
        final Map<Sha256Hash, Block> preloadedBlocks = new HashMap<>();
        final Iterator<Block> blocks = new BlockFileLoader(params, Arrays.asList(blockFile));

        try {
            H2FullPrunedBlockStore store = new H2FullPrunedBlockStore(params, args.length > 0 ? args[0] : "BitcoindComparisonTool", blockList.maximumReorgBlockCount);
            store.resetStore();
            //store = new MemoryFullPrunedBlockStore(pa