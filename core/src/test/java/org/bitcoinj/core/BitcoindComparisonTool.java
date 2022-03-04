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
            //store = new MemoryFullPrunedBlockStore(params, blockList.maximumReorgBlockCount);
            chain = new FullPrunedBlockChain(params, store);
        } catch (BlockStoreException e) {
            e.printStackTrace();
            System.exit(1);
        }

        VersionMessage ver = new VersionMessage(params, 42);
        ver.appendToSubVer("BlockAcceptanceComparisonTool", "1.1", null);
        ver.localServices = VersionMessage.NODE_NETWORK;
        final Peer bitcoind = new Peer(params, ver, new BlockChain(params, new MemoryBlockStore(params)), new PeerAddress(params, InetAddress.getLocalHost()));
        Preconditions.checkState(bitcoind.getVersionMessage().hasBlockChain());

        final BlockWrapper currentBlock = new BlockWrapper();

        final Set<Sha256Hash> blocksRequested = Collections.synchronizedSet(new HashSet<Sha256Hash>());
        final Set<Sha256Hash> blocksPendingSend = Collections.synchronizedSet(new HashSet<Sha256Hash>());
        final AtomicInteger unexpectedInvs = new AtomicInteger(0);
        final SettableFuture<Void> connectedFuture = SettableFuture.create();
        bitcoind.addConnectedEventListener(Threading.SAME_THREAD, new PeerConnectedEventListener() {
            @Override
            public void onPeerConnected(Peer peer, int peerCount) {
                if (!peer.getPeerVersionMessage().subVer.contains("Satoshi")) {
                    System.out.println();
                    System.out.println("************************************************************************************************************************\n" +
                                       "WARNING: You appear to be using this to test an alternative implementation with full validation rules. You should go\n" +
                                       "think hard about what you're doing. Seriously, no one has gotten even close to correctly reimplementing Bitcoin\n" +
                                       "consensus rules, despite serious investment in trying. It is a huge task and the slightest difference is a huge bug.\n" +
                                       "Instead, go work on making Bitcoin Core consensus rules a shared library and use that. Seriously, you wont get it right,\n" +
                                       "and starting with this tester as a way to try to do so will simply end in pain and lost coins.\n" +
                                       "************************************************************************************************************************");
                    System.out.println();
                }
                log.info("bitcoind connected");
                // Make sure bitcoind has no blocks
                bitcoind.setDownloadParameters(0, false);
                bitcoind.startBlockChainDownload();
                connectedFuture.set(null);
            }
        });

        bitcoind.addDisconnectedEventListener(Threading.SAME_THREAD, new PeerDisconnectedEventListener() {
            @Override
            public void onPeerDisconnected(Peer peer, int peerCount) {
                log.error("bitcoind node disconnected!");
                System.exit(1);
            }
        });

        bitcoind.addPreMessageReceivedEventListener(Threading.SAME_THREAD, new PreMessageReceivedEventListener() {
            @Override
            public Message onPreMessageReceived(Peer peer, Message m) {
                if (m instanceof HeadersMessage) {
                    if (!((HeadersMessage) m).getBlockHeaders().isEmpty()) {
                        Block b = Iterables.getLast(((HeadersMessage) m).getBlockHeaders());
                        log.info("Got header from bitcoind " + b.getHashAsString());
                        bitcoindChainHead = b.getHash();
                    } else
                        log.info("Got empty header message from bitcoind");
                    return null;
                } else if (m instanceof Block) {
                    log.error("bitcoind sent us a block it already had, make sure bitcoind has no blocks!");
                    System.exit(1);
                } else if (m instanceof GetDataMessage) {
                    for (InventoryItem item : ((GetDataMessage) m).items)
                        if (item.type == InventoryItem.Type.Block) {
                            log.info("Requested " + item.hash);
                            if (currentBlock.block.getHash().equals(item.hash))
                                bitcoind.sendMessage(currentBlock.block);
                            else {
                                Block nextBlock = preloadedBlocks.get(item.hash);
                                if (nextBlock != null)
                                    bitcoind.sendMessage(nextBlock);
                                else {
                                    blocksPendingSend.add(item.hash);
                                    log.info("...which we will not provide yet");
                                }
                            }
                            blocksRequested.add(item.hash);
                        }
                    return null;
                } else if (m instanceof GetHeadersMessage) {
                    try {
                        if (currentBlock.block == null) {
                            log.info("Got a request for a header before we had even begun processing blocks!");
                            return null;
                        }
                        LinkedList<Block> headers = new LinkedList<>();
                        Block it = blockList.hashHeaderMap.get(currentBlock.block.getHash());
                        while (it != null) {
                            headers.addFirst(it);
                            it = blockList.hashHeaderMap.get(it.getPrevBlockHash());
                        }
                        LinkedList<Block> sendHeaders = new LinkedList<>();
                        boolean found = false;
                        for (Sha256Hash hash : ((GetHeadersMessage) m).getLocator()) {
                            for (Block b : headers) {
                                if (found) {
                                    sendHeaders.addLast(b);
                                    log.info("Sending header (" + b.getPrevBlockHash() + ") -> " + b.getHash());
                                    if (b.getHash().equals(((GetHeadersMessage) m).getStopHash()))
                                        break;
                                } else if (b.getHash().equals(hash)) {
                                    log.info("Found header " + b.getHashAsString());
 