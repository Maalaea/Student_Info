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

package org.bitcoinj.core;

import com.google.common.annotations.*;
import com.google.common.base.*;
import com.google.common.collect.*;
import com.google.common.net.*;
import com.google.common.primitives.*;
import com.google.common.util.concurrent.*;
import net.jcip.annotations.*;
import org.bitcoinj.core.listeners.*;
import org.bitcoinj.net.*;
import org.bitcoinj.net.discovery.*;
import org.bitcoinj.script.*;
import org.bitcoinj.utils.*;
import org.bitcoinj.utils.Threading;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.wallet.listeners.KeyChainEventListener;
import org.bitcoinj.wallet.listeners.ScriptsChangeEventListener;
import org.bitcoinj.wallet.listeners.WalletCoinsReceivedEventListener;
import org.slf4j.*;

import javax.annotation.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;

import static com.google.common.base.Preconditions.*;

/**
 * <p>Runs a set of connections to the P2P network, brings up connections to replace disconnected nodes and manages
 * the interaction between them all. Most applications will want to use one of these.</p>
 * 
 * <p>PeerGroup tries to maintain a constant number of connections to a set of distinct peers.
 * Each peer runs a network listener in its own thread.  When a connection is lost, a new peer
 * will be tried after a delay as long as the number of connections less than the maximum.</p>
 * 
 * <p>Connections are made to addresses from a provided list.  When that list is exhausted,
 * we start again from the head of the list.</p>
 * 
 * <p>The PeerGroup can broadcast a transaction to the currently connected set of peers.  It can
 * also handle download of the blockchain from peers, restarting the process when peers die.</p>
 *
 * <p>A PeerGroup won't do anything until you call the {@link PeerGroup#start()} method 
 * which will block until peer discovery is completed and some outbound connections 
 * have been initiated (it will return before handshaking is done, however). 
 * You should call {@link PeerGroup#stop()} when finished. Note that not all methods
 * of PeerGroup are safe to call from a UI thread as some may do network IO, 
 * but starting and stopping the service should be fine.</p>
 */
public class PeerGroup implements TransactionBroadcaster {
    private static final Logger log = LoggerFactory.getLogger(PeerGroup.class);

    // All members in this class should be marked with final, volatile, @GuardedBy or a mix as appropriate to define
    // their thread safety semantics. Volatile requires a Hungarian-style v prefix.

    // By default we don't require any services because any peer will do.
    private long requiredServices = 0;
    /**
     * The default number of connections to the p2p network the library will try to build. This is set to 12 empirically.
     * It used to be 4, but because we divide the connection pool in two for broadcasting transactions, that meant we
     * were only sending transactions to two peers and sometimes this wasn't reliable enough: transactions wouldn't
     * get through.
     */
    public static final int DEFAULT_CONNECTIONS = 12;
    private volatile int vMaxPeersToDiscoverCount = 100;
    private static final long DEFAULT_PEER_DISCOVERY_TIMEOUT_MILLIS = 5000;
    private volatile long vPeerDiscoveryTimeoutMillis = DEFAULT_PEER_DISCOVERY_TIMEOUT_MILLIS;

    protected final ReentrantLock lock = Threading.lock("peergroup");

    protected final NetworkParameters params;
    @Nullable protected final AbstractBlockChain chain;

    // This executor is used to queue up jobs: it's used when we don't want to use locks for mutual exclusion,
    // typically because the job might call in to user provided code that needs/wants the freedom to use the API
    // however it wants, or because a job needs to be ordered relative to other jobs like that.
    protected final ListeningScheduledExecutorService executor;

    // Whether the peer group is currently running. Once shut down it cannot be restarted.
    private volatile boolean vRunning;
    // Whether the peer group has been started or not. An unstarted PG does not try to access the network.
    private volatile boolean vUsedUp;

    // Addresses to try to connect to, excluding active peers.
    @GuardedBy("lock") private final PriorityQueue<PeerAddress> inactives;
    @GuardedBy("lock") private final Map<PeerAddress, ExponentialBackoff> backoffMap;

    // Currently active peers. This is an ordered list rather than a set to make unit tests predictable.
    private final CopyOnWriteArrayList<Peer> peers;
    // Currently connecting peers.
    private final CopyOnWriteArrayList<Peer> pendingPeers;
    private final ClientConnectionManager channels;

    // The peer that has been s