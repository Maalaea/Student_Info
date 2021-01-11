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

    // The peer that has been selected for the purposes of downloading announced data.
    @GuardedBy("lock") private Peer downloadPeer;
    // Callback for events related to chain download.
    @Nullable @GuardedBy("lock") private PeerDataEventListener downloadListener;
    private final CopyOnWriteArrayList<ListenerRegistration<BlocksDownloadedEventListener>> peersBlocksDownloadedEventListeners
        = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<ListenerRegistration<ChainDownloadStartedEventListener>> peersChainDownloadStartedEventListeners
        = new CopyOnWriteArrayList<>();
    /** Callbacks for events related to peers connecting */
    protected final CopyOnWriteArrayList<ListenerRegistration<PeerConnectedEventListener>> peerConnectedEventListeners
        = new CopyOnWriteArrayList<>();
    /** Callbacks for events related to peer connection/disconnection */
    protected final CopyOnWriteArrayList<ListenerRegistration<PeerDiscoveredEventListener>> peerDiscoveredEventListeners
        = new CopyOnWriteArrayList<>();
    /** Callbacks for events related to peers disconnecting */
    protected final CopyOnWriteArrayList<ListenerRegistration<PeerDisconnectedEventListener>> peerDisconnectedEventListeners
        = new CopyOnWriteArrayList<>();
    /** Callbacks for events related to peer data being received */
    private final CopyOnWriteArrayList<ListenerRegistration<GetDataEventListener>> peerGetDataEventListeners
        = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<ListenerRegistration<PreMessageReceivedEventListener>> peersPreMessageReceivedEventListeners
        = new CopyOnWriteArrayList<>();
    protected final CopyOnWriteArrayList<ListenerRegistration<OnTransactionBroadcastListener>> peersTransactionBroadastEventListeners
        = new CopyOnWriteArrayList<>();
    // Peer discovery sources, will be polled occasionally if there aren't enough inactives.
    private final CopyOnWriteArraySet<PeerDiscovery> peerDiscoverers;
    // The version message to use for new connections.
    @GuardedBy("lock") private VersionMessage versionMessage;
    // Maximum depth up to which pending transaction dependencies are downloaded, or 0 for disabled.
    @GuardedBy("lock") private int downloadTxDependencyDepth;
    // How many connections we want to have open at the current time. If we lose connections, we'll try opening more
    // until we reach this count.
    @GuardedBy("lock") private int maxConnections;
    // Minimum protocol version we will allow ourselves to connect to: require Bloom filtering.
    private volatile int vMinRequiredProtocolVersion;

    /** How many milliseconds to wait after receiving a pong before sending another ping. */
    public static final long DEFAULT_PING_INTERVAL_MSEC = 2000;
    @GuardedBy("lock") private long pingIntervalMsec = DEFAULT_PING_INTERVAL_MSEC;

    @GuardedBy("lock") private boolean useLocalhostPeerWhenPossible = true;
    @GuardedBy("lock") private boolean ipv6Unreachable = false;

    @GuardedBy("lock") private long fastCatchupTimeSecs;
    private final CopyOnWriteArrayList<Wallet> wallets;
    private final CopyOnWriteArrayList<PeerFilterProvider> peerFilterProviders;

    // This event listener is added to every peer. It's here so when we announce transactions via an "inv", every
    // peer can fetch them.
    private final PeerListener peerListener = new PeerListener();

    private int minBroadcastConnections = 0;
    private final ScriptsChangeEventListener walletScriptEventListener = new ScriptsChangeEventListener() {
        @Override public void onScriptsChanged(Wallet wallet, List<Script> scripts, boolean isAddingScripts) {
            recalculateFastCatchupAndFilter(FilterRecalculateMode.SEND_IF_CHANGED);
        }
    };

    private final KeyChainEventListener walletKeyEventListener = new KeyChainEventListener() {
        @Override public void onKeysAdded(List<ECKey> keys) {
            recalculateFastCatchupAndFilter(FilterRecalculateMode.SEND_IF_CHANGED);
        }
    };

    private final WalletCoinsReceivedEventListener walletCoinsReceivedEventListener = new WalletCoinsReceivedEventListener() {
        @Override
        public void onCoinsReceived(Wallet wallet, Transaction tx, Coin prevBalance, Coin newBalance) {
            // We received a relevant transaction. We MAY need to recalculate and resend the Bloom filter, but only
            // if we have received a transaction that includes a relevant pay-to-pubkey output.
            //
            // The reason is that pay-to-pubkey outputs, when spent, will not repeat any data we can predict in their
            // inputs. So a remote peer will update the Bloom filter for us when such an output is seen matching the
            // existing filter, so that it includes the tx hash in which the pay-to-pubkey output was observed. Thus
            // the spending transaction will always match (due to the outpoint structure).
            //
            // Unfortunately, whilst this is required for correct sync of the chain in blocks, there are two edge cases.
            //
            // (1) If a wallet receives a relevant, confirmed p2pubkey output that was not broadcast across the network,
            // for example in a coinbase transaction, then the node that's serving us the chain will update its filter
            // but the rest will not. If another transaction then spends it, the other nodes won't match/relay it.
            //
            // (2) If we receive a p2pubkey output broadcast across the network, all currently connected nodes will see
            // it and update their filter themselves, but any newly connected nodes will receive the last filter we
            // calculated, which would not include this transaction.
            //
            // For this reason we check if the transaction contained any relevant pay to pubkeys and force a recalc
            // and possibly retransmit if so. The recalculation process will end up including the tx hash into the
            // filter. In case (1), we need to retransmit the filter to the connected peers. In case (2), we don't
            // and shouldn't, we should just recalculate and cache the new filter for next time.
            for (TransactionOutput output : tx.getOutputs()) {
                if (output.getScriptPubKey().isSentToRawPubKey() && output.isMine(wallet)) {
                    if (tx.getConfidence().getConfidenceType() == TransactionConfidence.ConfidenceType.BUILDING)
                        recalculateFastCatchupAndFilter(FilterRecalculateMode.SEND_IF_CHANGED);
                    else
                        recalculateFastCatchupAndFilter(FilterRecalculateMode.DONT_SEND);
                    return;
                }
            }
        }
    };

    // Exponential backoff for peers starts at 1 second and maxes at 10 minutes.
    private final ExponentialBackoff.Params peerBackoffParams = new ExponentialBackoff.Params(1000, 1.5f, 10 * 60 * 1000);
    // Tracks failures globally in case of a network failure.
    @GuardedBy("lock") private ExponentialBackoff groupBackoff = new ExponentialBackoff(new ExponentialBackoff.Params(1000, 1.5f, 10 * 1000));

    // This is a synchronized set, so it locks on itself. We use it to prevent TransactionBroadcast objects from
    // being garbage collected if nothing in the apps code holds on to them transitively. See the discussion
    // in broadcastTransaction.
    private final Set<TransactionBroadcast> runningBroadcasts;

    private class PeerListener implements GetDataEventListener, BlocksDownloadedEventListener {

        public PeerListener() {
        }

        @Override
        public List<Message> getData(Peer peer, GetDataMessage m) {
            return handleGetData(m);
        }

        @Override
        public void onBlocksDownloaded(Peer peer, Block block, @Nullable FilteredBlock filteredBlock, int blocksLeft) {
            if (chain == null) return;
            final double rate = chain.getFalsePositiveRate();
            final double target = bloomFilterMerger.getBloomFilterFPRate() * MAX_FP_RATE_INCREASE;
            if (rate > target) {
                // TODO: Avoid hitting this path if the remote peer didn't acknowledge applying a new filter yet.
                if (log.isDebugEnabled())
                    log.debug("Force update Bloom filter due to high false positive rate ({} vs {})", rate, target);
                recalculateFastCatchupAndFilter(FilterRecalculateMode.FORCE_SEND_FOR_REFRESH);
            }
        }
    }

    private class PeerStartupListener implements PeerConnectedEventListener, PeerDisconnectedEventListener {
        @Override
        public void onPeerConnected(Peer peer, int peerCount) {
            handleNewPeer(peer);
        }

        @Override
        public void onPeerDisconnected(Peer peer, int peerCount) {
            // The channel will be automatically removed from channels.
            handlePeerDeath(peer, null);
        }
    }

    private final PeerStartupListener startupListener = new PeerStartupListener();

    /**
     * The default Bloom filter false positive rate, which is selected to be extremely low such that you hardly ever
     * download false positives. This provides maximum performance. Although this default can be overridden to push
     * the FP rate higher, due to <a href="https://groups.google.com/forum/#!msg/bitcoinj/Ys13qkTwcNg/9qxnhwnkeoIJ">
     * various complexities</a> there are still ways a remote peer can deanonymize the users wallet. This is why the
     * FP rate is chosen for performance rather than privacy. If a future version of bitcoinj fixes the known
     * de-anonymization attacks this FP rate may rise again (or more likely, become expressed as a bandwidth allowance).
     */
    public static final double DEFAULT_BLOOM_FILTER_FP_RATE = 0.00001;
    /** Maximum increase in FP rate before forced refresh of the bloom filter */
    public static final double MAX_FP_RATE_INCREASE = 10.0f;
    // An object that calculates bloom filters given a list of filter providers, whilst tracking some state useful
    // for privacy purposes.
    private final FilterMerger bloomFilterMerger;

    /** The default timeout between when a connection attempt begins and version message exchange completes */
    public static final int DEFAULT_CONNECT_TIMEOUT_MILLIS = 5000;
    private volatile int vConnectTimeoutMillis = DEFAULT_CONNECT_TIMEOUT_MILLIS;
    
    /** Whether bloom filter support is enabled when using a non FullPrunedBlockchain*/
    private volatile boolean vBloomFilteringEnabled = true;

    /** See {@link #PeerGroup(Context)} */
    public PeerGroup(NetworkParameters params) {
        this(params, null);
    }

    /**
     * Creates a PeerGroup with the given context. No chain is provided so this node will report its chain height
     * as zero to other peers. This constructor is useful if you just want to explore the network but aren't interested
     * in downloading block data.
     */
    public PeerGroup(Context context) {
        this(context, null);
    }

    /** See {@link #PeerGroup(Context, AbstractBlockChain)} */
    public PeerGroup(NetworkParameters params, @Nullable AbstractBlockChain chain) {
        this(Context.getOrCreate(params), chain, new NioClientManager());
    }

    /**
     * Creates a PeerGroup for the given context and chain. Blocks will be passed to the chain as they are broadcast
     * and downloaded. This is probably the constructor you want to use.
     */
    public PeerGroup(Context context, @Nullable AbstractBlockChain chain) {
        this(context, chain, new NioClientManager());
    }

    /** See {@link #PeerGroup(Context, AbstractBlockChain, ClientConnectionManager)} */
    public PeerGroup(NetworkParameters params, @Nullable AbstractBlockChain chain, ClientConnectionManager connectionManager) {
        this(Context.getOrCreate(params), chain, connectionManager);
    }

    /**
     * Creates a new PeerGroup allowing you to specify the {@link ClientConnectionManager} which is used to create new
     * connections and keep track of existing ones.
     */
    private PeerGroup(Context context, @Nullable AbstractBlockChain chain, ClientConnectionManager connectionManager) {
        checkNotNull(context);
        this.params = context.getParams();
        this.chain = chain;
        fastCatchupTimeSecs = params.getGenesisBlock().getTimeSeconds();
        wallets = new CopyOnWriteArrayList<>();
        peerFilterProviders = new CopyOnWriteArrayList<>();

        executor = createPrivateExecutor();

        // This default sentinel value will be overridden by one of two actions:
        //   - adding a peer discovery source sets it to the default
        //   - using connectTo() will increment it by one
        maxConnections = 0;

        int height = chain == null ? 0 : chain.getBestChainHeight();
        versionMessage = new VersionMessage(params, height);
        // We never request that the remote node wait for a bloom filter yet, as we have no wallets
        versionMessage.relayTxesBeforeFilter = true;

        downloadTxDependencyDepth = Integer.MAX_VALUE;

        inactives = new PriorityQueue<>(1, new Comparator<PeerAddress>() {
            @SuppressWarnings("FieldAccessNotGuarded")   // only called when inactives is accessed, and lock is held then.
            @Override
            public int compare(PeerAddress a, PeerAddress b) {
                checkState(lock.isHeldByCurrentThread());
                int result = backoffMap.get(a).compareTo(backoffMap.get(b));
                // Sort by port if otherwise equals - for testing
                if (result == 0)
                    result = Ints.compare(a.getPort(), b.getPort());
                return result;
            }
        });
        backoffMap = new HashMap<>();
        peers = new CopyOnWriteArrayList<>();
        pendingPeers = new CopyOnWriteArrayList<>();
        channels = connectionManager;
        peerDiscoverers = new CopyOnWriteArraySet<>();
        runningBroadcasts = Collections.synchronizedSet(new HashSet<TransactionBroadcast>());
        bloomFilterMerger = new FilterMerger(DEFAULT_BLOOM_FILTER_FP_RATE);
        vMinRequiredProtocolVersion = params.getProtocolVersionNum(NetworkParameters.ProtocolVersion.BLOOM_FILTER);
    }

    private CountDownLatch executorStartupLatch = new CountDownLatch(1);

    protected ListeningScheduledExecutorService createPrivateExecutor() {
        ListeningScheduledExecutorService result = MoreExecutors.listeningDecorator(
                new ScheduledThreadPoolExecutor(1, new ContextPropagatingThreadFactory("PeerGroup Thread"))
        );
        // Hack: jam the executor so jobs just queue up until the user calls start() on us. For example, adding a wallet
        // results in a bloom filter recalc being queued, but we don't want to do that until we're actually started.
        result.execute(new Runnable() {
            @Override
            public void run() {
                Uninterruptibles.awaitUninterruptibly(executorStartupLatch);
            }
        });
        return result;
    }

    /**
     * This is how many milliseconds we wait for peer discoveries to return their results.
     */
    public void setPeerDiscoveryTimeoutMillis(long peerDiscoveryTimeoutMillis) {
        this.vPeerDiscoveryTimeoutMillis = peerDiscoveryTimeoutMillis;
    }

    /**
     * Adjusts the desired number of connections that we will create to peers. Note that if there are already peers
     * open and the new value is lower than the current number of peers, those connections will be terminated. Likewise
     * if there aren't enough current connections to meet the new requested max size, some will be added.
     */
    public void setMaxConnections(int maxConnections) {
        int adjustment;
        lock.lock();
        try {
            this.maxConnections = maxConnections;
            if (!isRunning()) return;
        } finally {
            lock.unlock();
        }
        // We may now have too many or too few open connections. Add more or drop some to get to the right amount.
        adjustment = maxConnections - channels.getConnectedClientCount();
        if (adjustment > 0)
            triggerConnections();

        if (adjustment < 0)
            channels.closeConnections(-adjustment);
    }

    /**
     * Configure download of pending transaction dependencies. A change of values only takes effect for newly connected
     * peers.
     */
    public void setDownloadTxDependencies(int depth) {
        lock.lock();
        try {
            this.downloadTxDependencyDepth = depth;
        } finally {
            lock.unlock();
        }
    }

    private Runnable triggerConnectionsJob = new Runnable() {
        private boolean firstRun = true;
        private final static long MIN_PEER_DISCOVERY_INTERVAL = 1000L;

        @Override
        public void run() {
            try {
                go();
            } catch (Throwable e) {
                log.error("Exception when trying to build connections", e);  // The executor swallows exceptions :(
            }
        }

        public void go() {
            if (!vRunning) return;

            boolean doDiscovery = false;
            long now = Utils.currentTimeMillis();
            lock.lock();
            try {
                // First run: try and use a local node if there is one, for the additional security it can provide.
                // But, not on Android as there are none for this platform: it could only be a malicious app trying
                // to hijack our traffic.
                if (!Utils.isAndroidRuntime() && useLocalhostPeerWhenPossible && maybeCheckForLocalhostPeer() && firstRun) {
                    log.info("Localhost peer detected, trying to use it instead of P2P discovery");
                    maxConnections = 0;
                    connectToLocalHost();
                    return;
                }

                boolean havePeerWeCanTry = !inactives.isEmpty() && backoffMap.get(inactives.peek()).getRetryTime() <= now;
                doDiscovery = !havePeerWeCanTry;
            } finally {
                firstRun = false;
                lock.unlock();
            }

            // Don't hold the lock across discovery as this process can be very slow.
            boolean discoverySuccess = false;
            if (doDiscovery) {
                try {
                    discoverySuccess = discoverPeers() > 0;
                } catch (PeerDiscoveryException e) {
                    log.error("Peer discovery failure", e);
                }
            }

            long retryTime;
            PeerAddress addrToTry;
            lock.lock();
            try {
                if (doDiscovery) {
                    // Require that we have enough connections, to consider this
                    // a success, or we just constantly test for new peers
                    if (discoverySuccess && countConnectedAndPendingPeers() >= getMaxConnections()) {
                        groupBackoff.trackSuccess();
                    } else {
                        groupBackoff.trackFailure();
                    }
                }
                // Inactives is sorted by backoffMap time.
                if (inactives.isEmpty()) {
                    if (countConnectedAndPendingPeers() < getMaxConnections()) {
                        long interval = Math.max(groupBackoff.getRetryTime() - now, MIN_PEER_DISCOVERY_INTERVAL);
                        log.info("Peer discovery didn't provide us any more peers, will try again in "
                            + interval + "ms.");
                        executor.schedule(this, interval, TimeUnit.MILLISECONDS);
                    } else {
                        // We have enough peers and discovery provided no more, so just settle down. Most likely we
                        // were given a fixed set of addresses in some test scenario.
                    }
                    return;
                } else {
                    do {
                        addrToTry = inactives.poll();
                    } while (ipv6Unreachable && addrToTry.getAddr() instanceof Inet6Address);
                    retryTime = backoffMap.get(addrToTry).getRetryTime();
                }
                retryTime = Math.max(retryTime, groupBackoff.getRetryTime());
                if (retryTime > now) {
                    long delay = retryTime - now;
                    log.info("Waiting {} msec before next connect attempt {}", delay, addrToTry == null ? "" : "to " + addrToTry);
                    inactives.add(addrToTry);
                    executor.schedule(this, delay, TimeUnit.MILLISECONDS);
                    return;
                }
                connectTo(addrToTry, false, vConnectTimeoutMillis);
            } finally {
                lock.unlock();
            }
            if (countConnectedAndPendingPeers() < getMaxConnections()) {
                executor.execute(this);   // Try next peer immediately.
            }
        }
    };

    private void triggerConnections() {
        // Run on a background thread due to the need to potentially retry and back off in the background.
        if (!executor.isShutdown())
            executor.execute(triggerConnectionsJob);
    }

    /** The maximum number of connections that we will create to peers. */
    public int getMaxConnections() {
        lock.lock();
        try {
            return maxConnections;
        } finally {
            lock.unlock();
        }
    }

    private List<Message> handleGetData(GetDataMessage m) {
        // Scans the wallets and memory pool for transactions in the getdata message and returns them.
        // Runs on peer threads.
        lock.lock();
        try {
            LinkedList<Message> transactions = new LinkedList<>();
            LinkedList<InventoryItem> items = new LinkedList<>(m.getItems());
            Iterator<InventoryItem> it = items.iterator();
            while (it.hasNext()) {
                InventoryItem item = it.next();
                // Check the wallets.
                for (Wallet w : wallets) {
                    Transaction tx = w.getTransaction(item.hash);
                    if (tx == null) continue;
                    transactions.add(tx);
                    it.remove();
                    break;
                }
            }
            return transactions;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Sets the {@link VersionMessage} that will be announced on newly created connections. A version message is
     * primarily interesting because it lets you customize the "subVer" field which is used a bit like the User-Agent
     * field from HTTP. It means your client tells the other side what it is, see
     * <a href="https://github.com/bitcoin/bips/blob/master/bip-0014.mediawiki">BIP 14</a>.
     *
     * The VersionMessage you provide is copied and the best chain height/time filled in for each new connection,
     * therefore you don't have to worry about setting that. The provided object is really more of a template.
     */
    public void setVersionMessage(VersionMessage ver) {
        lock.lock();
        try {
            versionMessage = ver;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the version message provided by setVersionMessage or a default if none was given.
     */
    public VersionMessage getVersionMessage() {
        lock.lock();
        try {
            return versionMessage;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Sets information that identifies this software to remote nodes. This is a convenience wrapper for creating 
     * a new {@link VersionMessage}, calling {@link VersionMessage#appendToSubVer(String, String, String)} on it,
     * and then calling {@link PeerGroup#setVersionMessage(VersionMessage)} on the result of that. See the docs for
     * {@link VersionMessage#appendToSubVer(String, String, String)} for information on what the fields should contain.
     */
    public void setUserAgent(String name, String version, @Nullable String comments) {
        //TODO Check that height is needed here (it wasnt, but it should be, no?)
        int height = chain == null ? 0 : chain.getBestChainHeight();
        VersionMessage ver = new VersionMessage(params, height);
        ver.relayTxesBeforeFilter = false;
        updateVersionMessageRelayTxesBeforeFilter(ver);
        ver.appendToSubVer(name, version, comments);
        setVersionMessage(ver);
    }
    
    // Updates the relayTxesBeforeFilter flag of ver
    private void updateVersionMessageRelayTxesBeforeFilter(VersionMessage ver) {
        // We will provide the remote node with a bloom filter (ie they shouldn't relay yet)
        // if chain == null || !chain.shouldVerifyTransactions() and a wallet is added and bloom filters are enabled
        // Note that the default here means that no tx invs will be received if no wallet is ever added
        lock.lock();
        try {
            boolean spvMode = chain != null && !chain.shouldVerifyTransactions();
            boolean willSendFilter = spvMode && peerFilterProviders.size() > 0 && vBloomFilteringEnabled;
            ver.relayTxesBeforeFilter = !willSendFilter;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Sets information that identifies this software to remote nodes. This is a convenience wrapper for creating
     * a new {@link VersionMessage}, calling {@link VersionMessage#appendToSubVer(String, String, String)} on it,
     * and then calling {@link PeerGroup#setVersionMessage(VersionMessage)} on the result of that. See the docs for
     * {@link VersionMessage#appendToSubVer(String, String, String)} for information on what the fields should contain.
     */
    public void setUserAgent(String name, String version) {
        setUserAgent(name, version, null);
    }

    /** Use the more specific listener methods instead */
    @Deprecated @SuppressWarnings("deprecation")
    public void addEventListener(AbstractPeerEventListener listener, Executor executor) {
        addBlocksDownloadedEventListener(Threading.USER_THREAD, listener);
        addChainDownloadStartedEventListener(Threading.USER_THREAD, listener);
        addConnectedEventListener(Threading.USER_THREAD, listener);
        addDisconnectedEventListener(Threading.USER_THREAD, listener);
        addDiscoveredEventListener(Threading.USER_THREAD, listener);
        addGetDataEventListener(Threading.USER_THREAD, listener);
        addOnTransactionBroadcastListener(Threading.USER_THREAD, listener);
        addPreMessageReceivedEventListener(Threading.USER_THREAD, listener);
    }

    /** Use the more specific listener methods instead */
    @Deprecated @SuppressWarnings("deprecation")
    public void addEventListener(AbstractPeerEventListener listener) {
        addBlocksDownloadedEventListener(executor, listener);
        addChainDownloadStartedEventListener(executor, listener);
        addConnectedEventListener(executor, listener);
        addDisconnectedEventListener(executor, listener);
        addDiscoveredEventListener(executor, listener);
        addGetDataEventListener(executor, listener);
        addOnTransactionBroadcastListener(executor, listener);
        addPreMessageReceivedEventListener(executor, listener);
    }

    /** See {@link Peer#addBlocksDownloadedEventListener(BlocksDownloadedEventListener)} */
    public void addBlocksDownloadedEventListener(BlocksDownloadedEventListener listener) {
        addBlocksDownloadedEventListener(Threading.USER_THREAD, listener);
    }

    /**
     * <p>Adds a listener that will be notified on the given executor when
     * blocks are downloaded by the download peer.</p>
     * @see Peer#addBlocksDownloadedEventListener(Executor, BlocksDownloadedEventListener)
     */
    public void addBlocksDownloadedEventListener(Executor executor, BlocksDownloadedEventListener listener) {
        peersBlocksDownloadedEventListeners.add(new ListenerRegistration<>(checkNotNull(listener), executor));
        for (Peer peer : getConnectedPeers())
            peer.addBlocksDownloadedEventListener(executor, listener);
        for (Peer peer : getPendingPeers())
            peer.addBlocksDownloadedEventListener(executor, listener);
    }

    /** See {@link Peer#addBlocksDownloadedEventListener(BlocksDownloadedEventListener)} */
    public void addChainDownloadStartedEventListener(ChainDownloadStartedEventListener listener) {
        addChainDownloadStartedEventListener(Threading.USER_THREAD, listener);
    }

    /**
     * <p>Adds a listener that will be notified on the given executor when
     * chain download starts.</p>
     */
    public void addChainDownloadStartedEventListener(Executor executor, ChainDownloadStartedEventListener listener) {
        peersChainDownloadStartedEventListeners.add(new ListenerRegistration<>(checkNotNull(listener), executor));
        for (Peer peer : getConnectedPeers())
            peer.addChainDownloadStartedEventListener(executor, listener);
        for (Peer peer : getPendingPeers())
            peer.addChainDownloadStartedEventListener(executor, listener);
    }

    /** See {@link Peer#addConnectedEventListener(PeerConnectedEventListener)} */
    public void addConnectedEventListener(PeerConnectedEventListener listener) {
        addConnectedEventListener(Threading.USER_THREAD, listener);
    }

    /**
     * <p>Adds a listener that will be notified on the given executor when
     * new peers are connected to.</p>
     */
    public void addConnectedEventListener(Executor executor, PeerConnectedEventListener listener) {
        peerConnectedEventListeners.add(new ListenerRegistration<>(checkNotNull(listener), executor));
        for (Peer peer : getConnectedPeers())
            peer.addConnectedEventListener(executor, listener);
        for (Peer peer : getPendingPeers())
            peer.addConnectedEventListener(executor, listener);
    }

    /** See {@link Peer#addDisconnectedEventListener(PeerDisconnectedEventListener)} */
    public void addDisconnectedEventListener(PeerDisconnectedEventListener listener) {
        addDisconnectedEventListener(Threading.USER_THREAD, listener);
    }

    /**
     * <p>Adds a listener that will be notified on the given executor when
     * peers are disconnected from.</p>
     */
    public void addDisconnectedEventListener(Executor executor, PeerDisconnectedEventListener listener) {
        peerDisconnectedEventListeners.add(new ListenerRegistration<>(checkNotNull(listener), executor));
        for (Peer peer : getConnectedPeers())
            peer.addDisconnectedEventListener(executor, listener);
        for (Peer peer : getPendingPeers())
            peer.addDisconnectedEventListener(executor, listener);
    }

    /** See {@link Peer#addDiscoveredEventListener(PeerDiscoveredEventListener)} */
    public void addDiscoveredEventListener(PeerDiscoveredEventListener listener) {
        addDiscoveredEventListener(Threading.USER_THREAD, listener);
    }

    /**
     * <p>Adds a listener that will be notified on the given executor when new
     * peers are discovered.</p>
     */
    public void addDiscoveredEventListener(Executor executor, PeerDiscoveredEventListener listener) {
        peerDiscoveredEventListeners.add(new ListenerRegistration<>(checkNotNull(listener), executor));
    }

    /** See {@link Peer#addGetDataEventListener(GetDataEventListener)} */
    public void addGetDataEventListener(GetDataEventListener listener) {
        addGetDataEventListener(Threading.USER_THREAD, listener);
    }

    /** See {@link Peer#addGetDataEventListener(Executor, GetDataEventListener)} */
    public void addGetDataEventListener(final Executor executor, final GetDataEventListener listener) {
        peerGetDataEventListeners.add(new ListenerRegistration<>(checkNotNull(listener), executor));
        for (Peer peer : getConnectedPeers())
            peer.addGetDataEventListener(executor, listener);
        for (Peer peer : getPendingPeers())
            peer.addGetDataEventListener(executor, listener);
    }

    /** See {@link Peer#addOnTransactionBroadcastListener(OnTransactionBroadcastListener)} */
    public void addOnTransactionBroadcastListener(OnTransactionBroadcastListener listener) {
        addOnTransactionBroadcastListener(Threading.USER_THREAD, listener);
    }

    /** See {@link Peer#addOnTransactionBroadcastListener(OnTransactionBroadcastListener)} */
    public void addOnTransactionBroadcastListener(Executor executor, OnTransactionBroadcastListener listener) {
        peersTransactionBroadastEventListeners.add(new ListenerRegistration<>(checkNotNull(listener), executor));
        for (Peer peer : getConnectedPeers())
            peer.addOnTransactionBroadcastListener(executor, listener);
        for (Peer peer : getPendingPeers())
            peer.addOnTransactionBroadcastListener(executor, listener);
    }

    /** See {@link Peer#addPreMessageReceivedEventListener(PreMessageReceivedEventListener)} */
    public void addPreMessageReceivedEventListener(PreMessageReceivedEventListener listener) {
        addPreMessageReceivedEventListener(Threading.USER_THREAD, listener);
    }

    /** See {@link Peer#addPreMessageReceivedEventListener(Executor, PreMessageReceivedEventListener)} */
    public void addPreMessageReceivedEventListener(Executor executor, PreMessageReceivedEventListener listener) {
        peersPreMessageReceivedEventListeners.add(new ListenerRegistration<>(checkNotNull(listener), executor));
        for (Peer peer : getConnectedPeers())
            peer.addPreMessageReceivedEventListener(executor, listener);
        for (Peer peer : getPendingPeers())
            peer.addPreMessageReceivedEventListener(executor, listener);
    }

    /** Use the more specific listener methods instead */
    @Deprecated @SuppressWarnings("deprecation")
    public void removeEventListener(AbstractPeerEventListener listener) {
        removeBlocksDownloadedEventListener(listener);
        removeChainDownloadStartedEventListener(listener);
        removeConnectedEventListener(listener);
        removeDisconnectedEventListener(listener);
        removeDiscoveredEventListener(listener);
        removeGetDataEventListener(listener);
        removeOnTransactionBroadcastListener(listener);
        removePreMessageReceivedEventListener(listener);
    }

    public boolean removeBlocksDownloadedEventListener(BlocksDownloadedEventListener listener) {
        boolean result = ListenerRegistration.removeFromList(listener, peersBlocksDownloadedEventListeners);
        for (Peer peer : getConnectedPeers())
            peer.removeBlocksDownloadedEventListener(listener);
        for (Peer peer : getPendingPeers())
            peer.removeBlocksDownloadedEventListener(listener);
        return result;
    }

    public boolean removeChainDownloadStartedEventListener(ChainDownloadStartedEventListener listener) {
        boolean result = ListenerRegistration.removeFromList(listener, peersChainDownloadStartedEventListeners);
        for (Peer peer : getConnectedPeers())
            peer.removeChainDownloadStartedEventListener(listener);
        for (Peer peer : getPendingPeers())
            peer.removeChainDownloadStartedEventListener(listener);
        return result;
    }

    /** The given event listener will no longer be called with events. */
    public boolean removeConnectedEventListener(PeerConnectedEventListener listener) {
        boolean result = ListenerRegistration.removeFromList(listener, peerConnectedEventListeners);
        for (Peer peer : getConnectedPeers())
            peer.removeConnectedEventListener(listener);
        for (Peer peer : getPendingPeers())
            peer.removeConnectedEventListener(listener);
        return result;
    }

    /** The given event listener will no longer be called with events. */
    public boolean removeDisconnectedEventListener(PeerDisconnectedEventListener listener) {
        boolean result = ListenerRegistration.removeFromList(listener, peerDisconnectedEventListeners);
        for (Peer peer : getConnectedPeers())
            peer.removeDisconnectedEventListener(listener);
        for (Peer peer : getPendingPeers())
            peer.removeDisconnectedEventListener(listener);
        return result;
    }

    /** The given event listener will no longer be called with events. */
    public boolean removeDiscoveredEventListener(PeerDiscoveredEventListener listener) {
        boolean result = ListenerRegistration.removeFromList(listener, peerDiscoveredEventListeners);
        return result;
    }

    /** The given event listener will no longer be called with events. */
    public boolean removeGetDataEventListener(GetDataEventListener listener) {
        boolean result = ListenerRegistration.removeFromList(listener, peerGetDataEventListeners);
        for (Peer peer : getConnectedPeers())
            peer.removeGetDataEventListener(listener);
        for (Peer peer : getPendingPeers())
            peer.removeGetDataEventListener(listener);
        return result;
    }

    /** The given event listener will no longer be called with events. */
    public boolean removeOnTransactionBroadcastListener(OnTransactionBroadcastListener listener) {
        boolean result = ListenerRegistration.removeFromList(listener, peersTransactionBroadastEventListeners);
        for (Peer peer : getConnectedPeers())
            peer.removeOnTransactionBroadcastListener(listener);
        for (Peer peer : getPendingPeers())
            peer.removeOnTransactionBroadcastListener(listener);
        return result;
    }

    public boolean removePreMessageReceivedEventListener(PreMessageReceivedEventListener listener) {
        boolean result = ListenerRegistration.removeFromList(listener, peersPreMessageReceivedEventListeners);
        for (Peer peer : getConnectedPeers())
            peer.removePreMessageReceivedEventListener(listener);
        for (Peer peer : getPendingPeers())
            peer.removePreMessageReceivedEventListener(listener);
        return result;
    }

    /**
     * Returns a newly allocated list containing the currently connected peers. If all you care about is the count,
     * use numConnectedPeers().
     */
    public List<Peer> getConnectedPeers() {
        lock.lock();
        try {
            return new ArrayList<>(peers);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns a list containing Peers that did not complete connection yet.
     */
    public List<Peer> getPendingPeers() {
        lock.lock();
        try {
            return new ArrayList<>(pendingPeers);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Add an address to the list of potential peers to connect to. It won't necessarily be used unless there's a need
     * to build new connections to reach the max connection count.
     *
     * @param peerAddress IP/port to use.
     */
    public void addAddress(PeerAddress peerAddress) {
        int newMax;
        lock.lock();
        try {
            addInactive(peerAddress);
            newMax = getMaxConnections() + 1;
        } finally {
            lock.unlock();
        }
        setMaxConnections(newMax);
    }

    private void addInactive(PeerAddress peerAddress) {
        lock.lock();
        try {
            // Deduplicate
            if (backoffMap.containsKey(peerAddress))
                return;
            backoffMap.put(peerAddress, new ExponentialBackoff(peerBackoffParams));
            inactives.offer(peerAddress);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Convenience for connecting only to peers that can serve specific services. It will configure suitable peer
     * discoveries.
     * @param requiredServices Required services as a bitmask, e.g. {@link VersionMessage#NODE_NETWORK}.
     */
    public void setRequiredServices(long requiredServices) {
        lock.lock();
        try {
            this.requiredServices = requiredServices;
            peerDiscoverers.clear();
            addPeerDiscovery(MultiplexingDiscovery.forServices(params, requiredServices));
        } finally {
            lock.unlock();
        }
    }

    /** Convenience method for addAddress(new PeerAddress(address, params.port)); */
    public void addAddress(InetAddress address) {
        addAddress(new PeerAddress(params, address, params.getPort()));
    }

    /**
     * Add addresses from a discovery source to the list of potential peers to connect to. If max connections has not
     * been configured, or set to zero, then it's set to the default at this point.
     */
    public void addPeerDiscovery(PeerDiscovery peerDiscovery) {
        lock.lock();
        try {
            if (getMaxConnections() == 0)
                setMaxConnections(DEFAULT_CONNECTIONS);
            peerDiscoverers.add(peerDiscovery);
        } finally {
            lock.unlock();
        }
    }

    /** Returns number of discovered peers. */
    protected int discoverPeers() throws PeerDiscoveryException {
        // Don't hold the lock whilst doing peer discovery: it can take a long time and cause high API latency.
        checkState(!lock.isHeldByCurrentThread());
        int maxPeersToDiscoverCount = this.vMaxPeersToDiscoverCount;
        long peerDiscoveryTimeoutMillis = this.vPeerDiscoveryTimeoutMillis;
        final Stopwatch watch = Stopwatch.createStarted();
        final List<PeerAddress> addressList = Lists.newLinkedList();
        for (PeerDiscovery peerDiscovery : peerDiscoverers /* COW */) {
            InetSocketAddress[] addresses;
            addresses = peerDiscovery.getPeers(requiredServices, peerDiscoveryTimeoutMillis, TimeUnit.MILLISECONDS);
            for (InetSocketAddress address : addresses) addressList.add(new PeerAddress(params, address));
            if (addressList.size() >= maxPeersToDiscoverCount) break;
        }
        if (!addressList.isEmpty()) {
            for (PeerAddress address : addressList) {
                addInactive(address);
            }
            final ImmutableSet<PeerAddress> peersDiscoveredSet = ImmutableSet.copyOf(addressList);
            for (final ListenerRegistration<PeerDiscoveredEventListener> registration : peerDiscoveredEventListeners /* COW */) {
                registration.executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        registration.listener.onPeersDiscovered(peersDiscoveredSet);
                    }
                });
            }
        }
        watch.stop();
        log.info("Peer discovery took {} and returned {} items", watch, addressList.size());
        return addressList.size();
    }

    @VisibleForTesting
    void waitForJobQueue() {
        Futures.getUnchecked(executor.submit(Runnables.doNothing()));
    }

    private int countConnectedAndPendingPeers() {
        lock.lock();
        try {
            return peers.size() + pendingPeers.size();
        } finally {
            lock.unlock();
        }
    }

    private enum LocalhostCheckState {
        NOT_TRIED,
        FOUND,
        FOUND_AND_CONNECTED,
        NOT_THERE
    }
    private LocalhostCheckState localhostCheckState = LocalhostCheckState.NOT_TRIED;

    private boolean maybeCheckForLocalhostPeer() {
        checkState(lock.isHeldByCurrentThread());
        if (localhostCheckState == LocalhostCheckState.NOT_TRIED) {
            // Do a fast blocking connect to see if anything is listening.
            Socket socket = null;
            try {
                socket = new Socket();
                socket.connect(new InetSocketAddress(InetAddresses.forString("127.0.0.1"), params.getPort()), vConnectTimeoutMillis);
                localhostCheckState = LocalhostCheckState.FOUND;
                return true;
            } catch (IOException e) {
                log.info("Localhost peer not detected.");
                localhostCheckState = LocalhostCheckState.NOT_THERE;
            } finally {
                if (socket != null) {
                    try {
                        socket.close();
                    } catch (IOException e) {
                        // Ignore.
                    }
                }
            }
        }
        return false;
    }

    /**
     * Starts the PeerGroup and begins network activity.
     * @return A future that completes when first connection activity has been triggered (note: not first connection made).
     */
    public ListenableFuture startAsync() {
        // This is run in a background thread by the Service implementation.
        if (chain == null) {
            // Just try to help catch what might be a programming error.
            log.warn("Starting up with no attached block chain. Did you forget to pass one to the constructor?");
        }
        checkState(!vUsedUp, "Cannot start a peer group twice");
        vRunning = true;
        vUsedUp = true;
        executorStartupLatch.countDown();
        // We do blocking waits during startup, so run on the executor thread.
        return executor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    log.info("Starting ...");
                    channels.startAsync();
                    channels.awaitRunning();
                    triggerConnections();
                    setupPinging();
                } catch (Throwable e) {
                    log.error("Exception when starting up", e);  // The executor swallows exceptions :(
                }
            }
        });
    }

    /** Does a blocking startup. */
    public void start() {
        Futures.getUnchecked(startAsync());
    }

    /** Can just use start() for a blocking start here instead of startAsync/awaitRunning: PeerGroup is no longer a Guava service. */
    @Deprecated
    public void awaitRunning() {
        waitForJobQueue();
    }

    public ListenableFuture stopAsync() {
        checkState(vRunning);
        vRunning = false;
        ListenableFuture future = executor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    log.info("Stopping ...");
                    // Blocking close of all sockets.
                    channels.stopAsync();
                    channels.awaitTerminated();
                    for (PeerDiscovery peerDiscovery : peerDiscoverers) {
                        peerDiscovery.shutdown();
                    }
                    vRunning = false;
                    log.info("Stopped.");
                } catch (Throwable e) {
                    log.error("Exception when shutting down", e);  // The executor swallows exceptions :(
                }
            }
        });
        executor.shutdown();
        return future;
    }

    /** Does a blocking stop */
    public void stop() {
        try {
            stopAsync();
            log.info("Awaiting PeerGroup shutdown ...");
            executor.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    /** Can just use stop() here instead of stopAsync/awaitTerminated: PeerGroup is no longer a Guava service. */
    @Deprecated
    public void awaitTerminated() {
        try {
            executor.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * <p>Link the given wallet to this PeerGroup. This is used for three purposes:</p>
     *
     * <ol>
     *   <li>So the wallet receives broadcast transactions.</li>
     *   <li>Announcing pending transactions that didn't get into the chain yet to our peers.</li>
     *   <li>Set the fast catchup time using {@link PeerGroup#setFastCatchupTimeSecs(long)}, to optimize chain
     *       download.</li>
     * </ol>
     *
     * <p>Note that this should be done before chain download commences because if you add a wallet with keys earlier
     * than the current chain head, the relevant parts of the chain won't be redownloaded for you.</p>
     *
     * <p>The Wallet will have an event listener registered on it, so to avoid leaks remember to use
     * {@link PeerGroup#removeWallet(Wallet)} on it if you wish to keep the Wallet but lose the PeerGroup.</p>
     */
    public void addWallet(Wallet wallet) {
        lock.lock();
        try {
            checkNotNull(wallet);
            checkState(!wallets.contains(wallet));
            wallets.add(wallet);
            wallet.setTransactionBroadcaster(this);
            wallet.addCoinsReceivedEventListener(Threading.SAME_THREAD, walletCoinsReceivedEventListener);
            wallet.addKeyChainEventListener(Threading.SAME_THREAD, walletKeyEventListener);
            wallet.addScriptChangeEventListener(Threading.SAME_THREAD, walletScriptEventListener);
            addPeerFilterProvider(wallet);
            for (Peer peer : peers) {
                peer.addWallet(wallet);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * <p>Link the given PeerFilterProvider to this PeerGroup. DO NOT use this for Wallets, use
     * {@link PeerGroup#addWallet(Wallet)} instead.</p>
     *
     * <p>Note that this should be done before chain download commences because if you add a listener with keys earlier
     * than the current chain head, the relevant parts of the chain won't be redownloaded for you.</p>
     *
     * <p>This method invokes {@link PeerGroup#recalculateFastCatchupAndFilter(FilterRecalculateMode)}.
     * The return value of this method is the <code>ListenableFuture</code> returned by that invocation.</p>
     *
     * @return a future that completes once each <code>Peer</code> in this group has had its
     *         <code>BloomFilter</code> (re)set.
     */
    public ListenableFuture<BloomFilter> addPeerFilterProvider(PeerFilterProvider provider) {
        lock.lock();
        try {
            checkNotNull(provider);
            checkState(!peerFilterProviders.contains(provider));
            // Insert provider at the start. This avoids various concurrency problems that could occur because we need
            // all providers to be in a consistent, unchanging state whilst the filter is built. Providers can give
            // this guarantee by taking a lock in their begin method, but if we add 