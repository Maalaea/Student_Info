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

import com.google.common.base.Stopwatch;
import com.google.common.collect.*;
import com.google.common.net.*;
import com.google.common.util.concurrent.*;
import org.bitcoinj.core.listeners.*;
import org.bitcoinj.net.discovery.*;
import org.bitcoinj.testing.*;
import org.bitcoinj.utils.*;
import org.bitcoinj.wallet.Wallet;
import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.bitcoinj.core.Coin.*;
import static org.junit.Assert.*;


// TX announcement and broadcast is tested in TransactionBroadcastTest.

@RunWith(value = Parameterized.class)
public class PeerGroupTest extends TestWithPeerGroup {
    private static final int BLOCK_HEIGHT_GENESIS = 0;

    private BlockingQueue<Peer> connectedPeers;
    private BlockingQueue<Peer> disconnectedPeers;
    private PeerConnectedEventListener connectedListener = new PeerConnectedEventListener() {
        @Override
        public void onPeerConnected(Peer peer, int peerCount) {
            connectedPeers.add(peer);
        }
    };
    private PeerDisconnectedEventListener disconnectedListener = new PeerDisconnectedEventListener() {
        @Override
        public void onPeerDisconnected(Peer peer, int peerCount) {
            disconnectedPeers.add(peer);
        }
    };
    private PreMessageReceivedEventListener preMessageReceivedListener;
    private Map<Peer, AtomicInteger> peerToMessageCount;

    @Parameterized.Parameters
    public static Collection<ClientType[]> parameters() {
        return Arrays.asList(new ClientType[] {ClientType.NIO_CLIENT_MANAGER},
                             new ClientType[] {ClientType.BLOCKING_CLIENT_MANAGER});
    }

    public PeerGroupTest(ClientType clientType) {
        super(clientType);
    }

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        peerToMessageCount = new HashMap<>();
        connectedPeers = new LinkedBlockingQueue<>();
        disconnectedPeers = new LinkedBlockingQueue<>();
        preMessageReceivedListener = new PreMessageReceivedEventListener() {
            @Override
            public Message onPreMessageReceived(Peer peer, Message m) {
                AtomicInteger messageCount = peerToMessageCount.get(peer);
                if (messageCount == null) {
                    messageCount = new AtomicInteger(0);
                    peerToMessageCount.put(peer, messageCount);
                }
                messageCount.incrementAndGet();
                // Just pass the message right through for further processing.
                return m;
            }
        };
    }

    @Override
    @After
    public void tearDown() {
        super.tearDown();
    }

    @Test
    public void listener() throws Exception {
        peerGroup.addConnectedEventListener(connectedListener);
        peerGroup.addDisconnectedEventListener(disconnectedListener);
        peerGroup.addPreMessageReceivedEventListener(preMessageReceivedListener);
        peerGroup.start();

        // Create a couple of peers.
        InboundMessageQueuer p1 = connectPeer(1);
        InboundMessageQueuer p2 = connectPeer(2);
        connectedPeers.take();
        connectedPeers.take();

        pingAndWait(p1);
        pingAndWait(p2);
        Threading.waitForUserCode();
        assertEquals(0, disconnectedPeers.size());

        p1.close();
        disconnectedPeers.take();
        assertEquals(0, disconnectedPeers.size());
        p2.close();
        disconnectedPeers.take();
        assertEquals(0, disconnectedPeers.size());

        assertTrue(peerGroup.removeConnectedEventListener(connectedListener));
        assertFalse(peerGroup.removeConnectedEventListener(connectedListener));
        assertTrue(peerGroup.removeDisconnectedEventListener(disconnectedListener));
        assertFalse(peerGroup.removeDisconnectedEventListener(disconnectedListener));
        assertTrue(peerGroup.removePreMessageReceivedEventListener(preMessageReceivedListener));
        assertFalse(peerGroup.removePreMessageReceivedEventListener(preMessageReceivedListener));
    }

    @Test
    public void peerDiscoveryPolling() throws InterruptedException {
        // Check that if peer discovery fails, we keep trying until we have some nodes to talk with.
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean result = new AtomicBoolean();
        peerGroup.addPeerDiscovery(new PeerDiscovery() {
            @Override
            public InetSocketAddress[] getPeers(long services, long unused, TimeUnit unused2) throws PeerDiscoveryException {
                if (!result.getAndSet(true)) {
                    // Pretend we are not connected to the internet.
                    throw new PeerDiscoveryException("test failure");
                } else {
                    // Return a bogus address.
                    latch.countDown();
                    return new InetSocketAddress[]{new InetSocketAddress("localhost", 1)};
                }
            }

            @Override
            public void shutdown() {
            }
        });
        peerGroup.start();
        latch.await();
        // Check that we did indeed throw an exception. If we got here it means we threw and then PeerGroup tried
        // again a bit later.
        assertTrue(result.get());
    }

    // Utility method to create a PeerDiscovery with a certain number of addresses.
    private PeerDiscovery createPeerDiscovery(int nrOfAddressesWanted, int port) {
        final InetSocketAddress[] addresses = new InetSocketAddress[nrOfAddressesWanted];
        for (int addressNr = 0; addressNr < nrOfAddressesWanted; addressNr++) {
            // make each address unique by using the counter to increment the port.
            addresses[addressNr] = new InetSocketAddress("localhost", port + addressNr);
        }
        return new PeerDiscovery() {
            public InetSocketAddress[] getPeers(long services, long unused, TimeUnit unused2) throws PeerDiscoveryException {
                return addresses;
            }
            public void shutdown() {
            }
        };
    }

    @Test
    public void multiplePeerDiscovery() throws InterruptedException {
        peerGroup.setMaxPeersToDiscoverCount(98);
        peerGroup.addPeerDiscovery(createPeerDiscovery(1, 0));
        peerGroup.addPeerDiscovery(createPeerDiscovery(2, 100));
        peerGroup.addPeerDiscovery(createPeerDiscovery(96, 200));
        peerGroup.addPeerDiscovery(createPeerDiscovery(3, 300));
        peerGroup.addPeerDiscovery(createPeerDiscovery(1, 400));
        peerGroup.addDiscoveredEventListener(new PeerDiscoveredEventListener() {
            @Override
            public void onPeersDiscovered(Set<PeerAddress> peerAddresses) {
                assertEquals(99, peerAddresses.size());
            }
        });
        peerGroup.start();
    }

    @Test
    public void receiveTxBroadcast() throws Exception {
        // Check that when we receive transactions on all our peers, we do the right thing.
        peerGroup.start();

        // Create a couple of peers.
        InboundMessageQueuer p1 = connectPeer(1);
        InboundMessageQueuer p2 = connectPeer(2);
        
        // Check the peer accessors.
        assertEquals(2, peerGroup.numConnectedPeers());
        Set<Peer> tmp = new HashSet<>(peerGroup.getConnectedPeers());
        Set<Peer> expectedPeers = new HashSet<>();
        expectedPeers.add(peerOf(p1));
        expectedPeers.add(peerOf(p2));
        assertEquals(tmp, expectedPeers);

        Coin value = COIN;
        Transaction t1 = FakeTxBuilder.createFakeTx(PARAMS, value, address);
        InventoryMessage inv = new InventoryMessage(PARAMS);
        inv.addTransaction(t1);

        // Note: we start with p2 here to verify that transactions are downloaded from whichever peer announces first
        // which does not have to be the same as the download peer (which is really the "block download peer").
        inbound(p2, inv);
        assertTrue(outbound(p2) instanceof GetDataMessage);
        inbound(p1, inv);
        assertNull(outbound(p1));  // Only one peer is used to download.
        inbound(p2, t1);
        assertNull(outbound(p1));
        // Asks for dependency.
        GetDataMessage getdata = (GetDataMessage) outbound(p2);
        assertNotNull(getdata);
        inbound(p2, new NotFoundMessage(PARAMS, getdata.getItems()));
        pingAndWait(p2);
        assertEquals(value, wallet.getBalance(Wallet.BalanceType.ESTIMATED));
    }

    
    @Test
    public void receiveTxBroadcastOnAddedWallet() throws Exception {
        // Check that when we receive transactions on all our peers, we do the right thing.
        peerGroup.start();

        // Create a peer.
        InboundMessageQueuer p1 = connectPeer(1);
        
        Wallet wallet2 = new Wallet(PARAMS);
        ECKey key2 = wallet2.freshReceiveKey();
        Address address2 = key2.toAddress(PARAMS);
        
        peerGroup.addWallet(wallet2);
        blockChain.addWallet(wallet2);

        assertEquals(BloomFilter.class, waitForOutbound(p1).getClass());
        assertEquals(MemoryPoolMessage.class, waitForOutbound(p1).getClass());

        Coin value = COIN;
        Transaction t1 = FakeTxBuilder.createFakeTx(PARAMS, value, address2);
        InventoryMessage inv = new InventoryMessage(PARAMS);
        inv.addTransaction(t1);

        inbound(p1, inv);
        assertTrue(outbound(p1) instanceof GetDataMessage);
        inbound(p1, t1);
        // Asks for dependency.
        GetDataMessage getdata = (GetDataMessage) outbound(p1);
        assertNotNull(getdata);
        inbound(p1, new NotFoundMessage(PARAMS, getdata.getItems()));
        pingAndWait(p1);
        assertEquals(value, wallet2.getBalance(Wallet.BalanceType.ESTIMATED));
    }
    
    @Test
    public void singleDownloadPeer1() throws Exception {
        // Check that we don't attempt to retrieve blocks on multiple peers.
        peerGroup.start();

        // Create a couple of peers.
        InboundMessageQueuer p1 = connectPeer(1);
        InboundMessageQueuer p2 = connectPeer(2);
        assertEquals(2, peerGroup.numConnectedPeers());

        // Set up a little block chain. We heard about b1 but not b2 (it is pending download). b3 is solved whilst we
        // are downloading the chain.
        Block b1 = FakeTxBuilder.createFakeBlock(blockStore, BLOCK_HEIGHT_GENESIS).block;
        blockChain.add(b1);
        Block b2 = FakeTxBuilder.makeSolvedTestBlock(b1);
        Block b3 = FakeTxBuilder.makeSolvedTestBlock(b2);

        // Peer 1 and 2 receives an inv advertising a newly solved block.
        InventoryMessage inv = new InventoryMessage(PARAMS);
        inv.addBlock(b3);
        // Only peer 1 tries to download it.
        inbound(p1, inv);
        pingAndWait(p1);
        
        assertTrue(outbound(p1) instanceof GetDataMessage);
        assertNull(outbound(p2));
        // Peer 1 goes away, peer 2 becomes the download peer and thus queries the remote mempool.
        final SettableFuture<Void> p1CloseFuture = SettableFuture.create();
        peerOf(p1).addDisconnectedEventListener(new PeerDisconnectedEventListener() {
            @Override
            public void onPeerDisconnected(Peer peer, int peerCount) {
                p1CloseFuture.set(null);
            }
        });
        closePeer(peerOf(p1));
        p1CloseFuture.get();
        // Peer 2 fetches it next time it hears an inv (should it fetch immediately?).
        inbound(p2, inv);
        assertTrue(outbound(p2) instanceof GetDataMessage);
    }

    @Test
    public void singleDownloadPeer2() throws Exception {
        // Check that we don't attempt multiple simultaneous block chain downloads, when adding a new peer in the
        // middle of an existing chain download.
        // Create a couple of peers.
        peerGroup.start();

        // Create a couple of peers.
        InboundMessageQueuer p1 = connectPeer(1);

        // Set up a little block chain.
        Block b1 = FakeTxBuilder.createFakeBlock(blockStore, BLOCK_HEIGHT_GENESIS).block;
        Block b2 = FakeTxBuilder.makeSolvedTestBlock(b1);
        Block b3 = FakeTxBuilder.makeSolvedTestBlock(b2);

        // Expect a zero hash getblocks on p1. This is how the process starts.
        peerGroup.startBlockChainDownload(new AbstractPeerDataEventListener() {
        });
        GetBlocksMessage getblocks = (GetBlocksMessage) outbound(p1);
        assertEquals(Sha256Hash.ZERO_HASH, getblocks.getStop