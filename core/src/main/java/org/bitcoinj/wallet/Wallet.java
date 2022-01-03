
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

import com.google.common.annotations.*;
import com.google.common.base.*;
import com.google.common.collect.*;
import com.google.common.primitives.*;
import com.google.common.util.concurrent.*;
import com.google.protobuf.*;
import net.jcip.annotations.*;
import org.bitcoin.protocols.payments.Protos.*;
import org.bitcoinj.core.listeners.*;
import org.bitcoinj.core.AbstractBlockChain;
import org.bitcoinj.core.Address;
import org.bitcoinj.core.BlockChain;
import org.bitcoinj.core.BloomFilter;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.FilteredBlock;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.Message;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Peer;
import org.bitcoinj.core.PeerFilterProvider;
import org.bitcoinj.core.PeerGroup;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.StoredBlock;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionBag;
import org.bitcoinj.core.TransactionBroadcast;
import org.bitcoinj.core.TransactionBroadcaster;
import org.bitcoinj.core.TransactionConfidence;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutPoint;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.core.UTXO;
import org.bitcoinj.core.UTXOProvider;
import org.bitcoinj.core.UTXOProviderException;
import org.bitcoinj.core.Utils;
import org.bitcoinj.core.VarInt;
import org.bitcoinj.core.VerificationException;
import org.bitcoinj.core.TransactionConfidence.*;
import org.bitcoinj.crypto.*;
import org.bitcoinj.script.*;
import org.bitcoinj.signers.*;
import org.bitcoinj.utils.*;
import org.bitcoinj.wallet.Protos.Wallet.*;
import org.bitcoinj.wallet.WalletTransaction.*;
import org.bitcoinj.wallet.listeners.KeyChainEventListener;
import org.bitcoinj.wallet.listeners.ScriptsChangeEventListener;
import org.bitcoinj.wallet.listeners.WalletChangeEventListener;
import org.bitcoinj.wallet.listeners.WalletCoinsReceivedEventListener;
import org.bitcoinj.wallet.listeners.WalletCoinsSentEventListener;
import org.bitcoinj.wallet.listeners.WalletEventListener;
import org.bitcoinj.wallet.listeners.WalletReorganizeEventListener;
import org.slf4j.*;
import org.bouncycastle.crypto.params.*;

import javax.annotation.*;
import java.io.*;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;

import static com.google.common.base.Preconditions.*;

// To do list:
//
// - Take all wallet-relevant data out of Transaction and put it into WalletTransaction. Make Transaction immutable.
// - Only store relevant transaction outputs, don't bother storing the rest of the data. Big RAM saving.
// - Split block chain and tx output tracking into a superclass that doesn't have any key or spending related code.
// - Simplify how transactions are tracked and stored: in particular, have the wallet maintain positioning information
//   for transactions independent of the transactions themselves, so the timeline can be walked without having to
//   process and sort every single transaction.
// - Split data persistence out into a backend class and make the wallet transactional, so we can store a wallet
//   in a database not just in RAM.
// - Make clearing of transactions able to only rewind the wallet a certain distance instead of all blocks.
// - Make it scale:
//     - eliminate all the algorithms with quadratic complexity (or worse)
//     - don't require everything to be held in RAM at once
//     - consider allowing eviction of no longer re-orgable transactions or keys that were used up
//
// Finally, find more ways to break the class up and decompose it. Currently every time we move code out, other code
// fills up the lines saved!

/**
 * <p>A Wallet stores keys and a record of transactions that send and receive value from those keys. Using these,
 * it is able to create new transactions that spend the recorded transactions, and this is the fundamental operation
 * of the Bitcoin protocol.</p>
 *
 * <p>To learn more about this class, read <b><a href="https://bitcoinj.github.io/working-with-the-wallet">
 *     working with the wallet.</a></b></p>
 *
 * <p>To fill up a Wallet with transactions, you need to use it in combination with a {@link BlockChain} and various
 * other objects, see the <a href="https://bitcoinj.github.io/getting-started">Getting started</a> tutorial
 * on the website to learn more about how to set everything up.</p>
 *
 * <p>Wallets can be serialized using protocol buffers. You need to save the wallet whenever it changes, there is an
 * auto-save feature that simplifies this for you although you're still responsible for manually triggering a save when
 * your app is about to quit because the auto-save feature waits a moment before actually committing to disk to avoid IO
 * thrashing when the wallet is changing very fast (eg due to a block chain sync). See
 * {@link Wallet#autosaveToFile(java.io.File, long, java.util.concurrent.TimeUnit, org.bitcoinj.wallet.WalletFiles.Listener)}
 * for more information about this.</p>
 */
public class Wallet extends BaseTaggableObject
    implements NewBestBlockListener, TransactionReceivedInBlockListener, PeerFilterProvider, KeyBag, TransactionBag, ReorganizeListener {
    private static final Logger log = LoggerFactory.getLogger(Wallet.class);
    private static final int MINIMUM_BLOOM_DATA_LENGTH = 8;

    // Ordering: lock > keyChainGroupLock. KeyChainGroup is protected separately to allow fast querying of current receive address
    // even if the wallet itself is busy e.g. saving or processing a big reorg. Useful for reducing UI latency.
    protected final ReentrantLock lock = Threading.lock("wallet");
    protected final ReentrantLock keyChainGroupLock = Threading.lock("wallet-keychaingroup");

    // The various pools below give quick access to wallet-relevant transactions by the state they're in:
    //
    // Pending:  Transactions that didn't make it into the best chain yet. Pending transactions can be killed if a
    //           double spend against them appears in the best chain, in which case they move to the dead pool.
    //           If a double spend appears in the pending state as well, we update the confidence type
    //           of all txns in conflict to IN_CONFLICT and wait for the miners to resolve the race.
    // Unspent:  Transactions that appeared in the best chain and have outputs we can spend. Note that we store the
    //           entire transaction in memory even though for spending purposes we only really need the outputs, the
    //           reason being that this simplifies handling of re-orgs. It would be worth fixing this in future.
    // Spent:    Transactions that appeared in the best chain but don't have any spendable outputs. They're stored here
    //           for history browsing/auditing reasons only and in future will probably be flushed out to some other
    //           kind of cold storage or just removed.
    // Dead:     Transactions that we believe will never confirm get moved here, out of pending. Note that Bitcoin
    //           Core has no notion of dead-ness: the assumption is that double spends won't happen so there's no
    //           need to notify the user about them. We take a more pessimistic approach and try to track the fact that
    //           transactions have been double spent so applications can do something intelligent (cancel orders, show
    //           to the user in the UI, etc). A transaction can leave dead and move into spent/unspent if there is a
    //           re-org to a chain that doesn't include the double spend.

    private final Map<Sha256Hash, Transaction> pending;
    private final Map<Sha256Hash, Transaction> unspent;
    private final Map<Sha256Hash, Transaction> spent;
    private final Map<Sha256Hash, Transaction> dead;

    // All transactions together.
    protected final Map<Sha256Hash, Transaction> transactions;

    // All the TransactionOutput objects that we could spend (ignoring whether we have the private key or not).
    // Used to speed up various calculations.
    protected final HashSet<TransactionOutput> myUnspents = Sets.newHashSet();

    // Transactions that were dropped by the risk analysis system. These are not in any pools and not serialized
    // to disk. We have to keep them around because if we ignore a tx because we think it will never confirm, but
    // then it actually does confirm and does so within the same network session, remote peers will not resend us
    // the tx data along with the Bloom filtered block, as they know we already received it once before
    // (so it would be wasteful to repeat). Thus we keep them around here for a while. If we drop our network
    // connections then the remote peers will forget that we were sent the tx data previously and send it again
    // when relaying a filtered merkleblock.
    private final LinkedHashMap<Sha256Hash, Transaction> riskDropped = new LinkedHashMap<Sha256Hash, Transaction>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Sha256Hash, Transaction> eldest) {
            return size() > 1000;
        }
    };

    // The key chain group is not thread safe, and generally the whole hierarchy of objects should not be mutated
    // outside the wallet lock. So don't expose this object directly via any accessors!
    @GuardedBy("keyChainGroupLock") private KeyChainGroup keyChainGroup;

    // A list of scripts watched by this wallet.
    @GuardedBy("keyChainGroupLock") private Set<Script> watchedScripts;

    protected final Context context;
    protected final NetworkParameters params;

    @Nullable private Sha256Hash lastBlockSeenHash;
    private int lastBlockSeenHeight;
    private long lastBlockSeenTimeSecs;

    private final CopyOnWriteArrayList<ListenerRegistration<WalletChangeEventListener>> changeListeners
        = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<ListenerRegistration<WalletCoinsReceivedEventListener>> coinsReceivedListeners
        = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<ListenerRegistration<WalletCoinsSentEventListener>> coinsSentListeners
        = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<ListenerRegistration<WalletReorganizeEventListener>> reorganizeListeners
        = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<ListenerRegistration<ScriptsChangeEventListener>> scriptChangeListeners
        = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<ListenerRegistration<TransactionConfidenceEventListener>> transactionConfidenceListeners
        = new CopyOnWriteArrayList<>();

    // A listener that relays confidence changes from the transaction confidence object to the wallet event listener,
    // as a convenience to API users so they don't have to register on every transaction themselves.
    private TransactionConfidence.Listener txConfidenceListener;

    // If a TX hash appears in this set then notifyNewBestBlock will ignore it, as its confidence was already set up
    // in receive() via Transaction.setBlockAppearance(). As the BlockChain always calls notifyNewBestBlock even if
    // it sent transactions to the wallet, without this we'd double count.
    private HashSet<Sha256Hash> ignoreNextNewBlock;
    // Whether or not to ignore pending transactions that are considered risky by the configured risk analyzer.
    private boolean acceptRiskyTransactions;
    // Object that performs risk analysis of pending transactions. We might reject transactions that seem like
    // a high risk of being a double spending attack.
    private RiskAnalysis.Analyzer riskAnalyzer = DefaultRiskAnalysis.FACTORY;

    // Stuff for notifying transaction objects that we changed their confidences. The purpose of this is to avoid
    // spuriously sending lots of repeated notifications to listeners that API users aren't really interested in as a
    // side effect of how the code is written (e.g. during re-orgs confidence data gets adjusted multiple times).
    private int onWalletChangedSuppressions;
    private boolean insideReorg;
    private Map<Transaction, TransactionConfidence.Listener.ChangeReason> confidenceChanged;
    protected volatile WalletFiles vFileManager;
    // Object that is used to send transactions asynchronously when the wallet requires it.
    protected volatile TransactionBroadcaster vTransactionBroadcaster;
    // UNIX time in seconds. Money controlled by keys created before this time will be automatically respent to a key
    // that was created after it. Useful when you believe some keys have been compromised.
    private volatile long vKeyRotationTimestamp;

    protected CoinSelector coinSelector = new DefaultCoinSelector();

    // The wallet version. This is an int that can be used to track breaking changes in the wallet format.
    // You can also use it to detect wallets that come from the future (ie they contain features you
    // do not know how to deal with).
    private int version;
    // User-provided description that may help people keep track of what a wallet is for.
    private String description;
    // Stores objects that know how to serialize/unserialize themselves to byte streams and whether they're mandatory
    // or not. The string key comes from the extension itself.
    private final HashMap<String, WalletExtension> extensions;

    // Objects that perform transaction signing. Applied subsequently one after another
    @GuardedBy("lock") private List<TransactionSigner> signers;

    // If this is set then the wallet selects spendable candidate outputs from a UTXO provider.
    @Nullable private volatile UTXOProvider vUTXOProvider;

    /**
     * Creates a new, empty wallet with a randomly chosen seed and no transactions. Make sure to provide for sufficient
     * backup! Any keys will be derived from the seed. If you want to restore a wallet from disk instead, see
     * {@link #loadFromFile}.
     */
    public Wallet(NetworkParameters params) {
        this(Context.getOrCreate(params));
    }

    /**
     * Creates a new, empty wallet with a randomly chosen seed and no transactions. Make sure to provide for sufficient
     * backup! Any keys will be derived from the seed. If you want to restore a wallet from disk instead, see
     * {@link #loadFromFile}.
     */
    public Wallet(Context context) {
        this(context, new KeyChainGroup(context.getParams()));
    }

    public static Wallet fromSeed(NetworkParameters params, DeterministicSeed seed) {
        return new Wallet(params, new KeyChainGroup(params, seed));
    }

    /**
     * Creates a wallet that tracks payments to and from the HD key hierarchy rooted by the given watching key. A
     * watching key corresponds to account zero in the recommended BIP32 key hierarchy.
     */
    public static Wallet fromWatchingKey(NetworkParameters params, DeterministicKey watchKey) {
        return new Wallet(params, new KeyChainGroup(params, watchKey));
    }

    /**
     * Creates a wallet that tracks payments to and from the HD key hierarchy rooted by the given watching key. A
     * watching key corresponds to account zero in the recommended BIP32 key hierarchy. The key is specified in base58
     * notation and the creation time of the key. If you don't know the creation time, you can pass
     * {@link DeterministicHierarchy#BIP32_STANDARDISATION_TIME_SECS}.
     */
    public static Wallet fromWatchingKeyB58(NetworkParameters params, String watchKeyB58, long creationTimeSeconds) {
        final DeterministicKey watchKey = DeterministicKey.deserializeB58(null, watchKeyB58, params);
        watchKey.setCreationTimeSeconds(creationTimeSeconds);
        return fromWatchingKey(params, watchKey);
    }

    /**
     * Creates a wallet containing a given set of keys. All further keys will be derived from the oldest key.
     */
    public static Wallet fromKeys(NetworkParameters params, List<ECKey> keys) {
        for (ECKey key : keys)
            checkArgument(!(key instanceof DeterministicKey));

        KeyChainGroup group = new KeyChainGroup(params);
        group.importKeys(keys);
        return new Wallet(params, group);
    }

    public Wallet(NetworkParameters params, KeyChainGroup keyChainGroup) {
        this(Context.getOrCreate(params), keyChainGroup);
    }

    private Wallet(Context context, KeyChainGroup keyChainGroup) {
        this.context = context;
        this.params = context.getParams();
        this.keyChainGroup = checkNotNull(keyChainGroup);
        if (params.getId().equals(NetworkParameters.ID_UNITTESTNET))
            this.keyChainGroup.setLookaheadSize(5);  // Cut down excess computation for unit tests.
        // If this keyChainGroup was created fresh just now (new wallet), make HD so a backup can be made immediately
        // without having to call current/freshReceiveKey. If there are already keys in the chain of any kind then
        // we're probably being deserialized so leave things alone: the API user can upgrade later.
        if (this.keyChainGroup.numKeys() == 0)
            this.keyChainGroup.createAndActivateNewHDChain();
        watchedScripts = Sets.newHashSet();
        unspent = new HashMap<>();
        spent = new HashMap<>();
        pending = new HashMap<>();
        dead = new HashMap<>();
        transactions = new HashMap<>();
        extensions = new HashMap<>();
        // Use a linked hash map to ensure ordering of event listeners is correct.
        confidenceChanged = new LinkedHashMap<>();
        signers = new ArrayList<>();
        addTransactionSigner(new LocalTransactionSigner());
        createTransientState();
    }

    private void createTransientState() {
        ignoreNextNewBlock = new HashSet<>();
        txConfidenceListener = new TransactionConfidence.Listener() {
            @Override
            public void onConfidenceChanged(TransactionConfidence confidence, TransactionConfidence.Listener.ChangeReason reason) {
                // This will run on the user code thread so we shouldn't do anything too complicated here.
                // We only want to queue a wallet changed event and auto-save if the number of peers announcing
                // the transaction has changed, as that confidence change is made by the networking code which
                // doesn't necessarily know at that point which wallets contain which transactions, so it's up
                // to us to listen for that. Other types of confidence changes (type, etc) are triggered by us,
                // so we'll queue up a wallet change event in other parts of the code.
                if (reason == ChangeReason.SEEN_PEERS) {
                    lock.lock();
                    try {
                        checkBalanceFuturesLocked(null);
                        Transaction tx = getTransaction(confidence.getTransactionHash());
                        queueOnTransactionConfidenceChanged(tx);
                        maybeQueueOnWalletChanged();
                    } finally {
                        lock.unlock();
                    }
                }
            }
        };
        acceptRiskyTransactions = false;
    }

    public NetworkParameters getNetworkParameters() {
        return params;
    }

    /**
     * Gets the active keychain via {@link KeyChainGroup#getActiveKeyChain()}
     */
    public DeterministicKeyChain getActiveKeyChain() {
        return keyChainGroup.getActiveKeyChain();
    }

    /**
     * <p>Adds given transaction signer to the list of signers. It will be added to the end of the signers list, so if
     * this wallet already has some signers added, given signer will be executed after all of them.</p>
     * <p>Transaction signer should be fully initialized before adding to the wallet, otherwise {@link IllegalStateException}
     * will be thrown</p>
     */
    public final void addTransactionSigner(TransactionSigner signer) {
        lock.lock();
        try {
            if (signer.isReady())
                signers.add(signer);
            else
                throw new IllegalStateException("Signer instance is not ready to be added into Wallet: " + signer.getClass());
        } finally {
            lock.unlock();
        }
    }

    public List<TransactionSigner> getTransactionSigners() {
        lock.lock();
        try {
            return ImmutableList.copyOf(signers);
        } finally {
            lock.unlock();
        }
    }

    /******************************************************************************************************************/

    //region Key Management

    /**
     * Returns a key that hasn't been seen in a transaction yet, and which is suitable for displaying in a wallet
     * user interface as "a convenient key to receive funds on" when the purpose parameter is
     * {@link org.bitcoinj.wallet.KeyChain.KeyPurpose#RECEIVE_FUNDS}. The returned key is stable until
     * it's actually seen in a pending or confirmed transaction, at which point this method will start returning
     * a different key (for each purpose independently).
     */
    public DeterministicKey currentKey(KeyChain.KeyPurpose purpose) {
        keyChainGroupLock.lock();
        try {
            maybeUpgradeToHD();
            return keyChainGroup.currentKey(purpose);
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    /**
     * An alias for calling {@link #currentKey(org.bitcoinj.wallet.KeyChain.KeyPurpose)} with
     * {@link org.bitcoinj.wallet.KeyChain.KeyPurpose#RECEIVE_FUNDS} as the parameter.
     */
    public DeterministicKey currentReceiveKey() {
        return currentKey(KeyChain.KeyPurpose.RECEIVE_FUNDS);
    }

    /**
     * Returns address for a {@link #currentKey(org.bitcoinj.wallet.KeyChain.KeyPurpose)}
     */
    public Address currentAddress(KeyChain.KeyPurpose purpose) {
        keyChainGroupLock.lock();
        try {
            maybeUpgradeToHD();
            return keyChainGroup.currentAddress(purpose);
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    /**
     * An alias for calling {@link #currentAddress(org.bitcoinj.wallet.KeyChain.KeyPurpose)} with
     * {@link org.bitcoinj.wallet.KeyChain.KeyPurpose#RECEIVE_FUNDS} as the parameter.
     */
    public Address currentReceiveAddress() {
        return currentAddress(KeyChain.KeyPurpose.RECEIVE_FUNDS);
    }

    /**
     * Returns a key that has not been returned by this method before (fresh). You can think of this as being
     * a newly created key, although the notion of "create" is not really valid for a
     * {@link org.bitcoinj.wallet.DeterministicKeyChain}. When the parameter is
     * {@link org.bitcoinj.wallet.KeyChain.KeyPurpose#RECEIVE_FUNDS} the returned key is suitable for being put
     * into a receive coins wizard type UI. You should use this when the user is definitely going to hand this key out
     * to someone who wishes to send money.
     */
    public DeterministicKey freshKey(KeyChain.KeyPurpose purpose) {
        return freshKeys(purpose, 1).get(0);
    }

    /**
     * Returns a key/s that has not been returned by this method before (fresh). You can think of this as being
     * a newly created key/s, although the notion of "create" is not really valid for a
     * {@link org.bitcoinj.wallet.DeterministicKeyChain}. When the parameter is
     * {@link org.bitcoinj.wallet.KeyChain.KeyPurpose#RECEIVE_FUNDS} the returned key is suitable for being put
     * into a receive coins wizard type UI. You should use this when the user is definitely going to hand this key/s out
     * to someone who wishes to send money.
     */
    public List<DeterministicKey> freshKeys(KeyChain.KeyPurpose purpose, int numberOfKeys) {
        List<DeterministicKey> keys;
        keyChainGroupLock.lock();
        try {
            maybeUpgradeToHD();
            keys = keyChainGroup.freshKeys(purpose, numberOfKeys);
        } finally {
            keyChainGroupLock.unlock();
        }
        // Do we really need an immediate hard save? Arguably all this is doing is saving the 'current' key
        // and that's not quite so important, so we could coalesce for more performance.
        saveNow();
        return keys;
    }

    /**
     * An alias for calling {@link #freshKey(org.bitcoinj.wallet.KeyChain.KeyPurpose)} with
     * {@link org.bitcoinj.wallet.KeyChain.KeyPurpose#RECEIVE_FUNDS} as the parameter.
     */
    public DeterministicKey freshReceiveKey() {
        return freshKey(KeyChain.KeyPurpose.RECEIVE_FUNDS);
    }

    /**
     * Returns address for a {@link #freshKey(org.bitcoinj.wallet.KeyChain.KeyPurpose)}
     */
    public Address freshAddress(KeyChain.KeyPurpose purpose) {
        Address key;
        keyChainGroupLock.lock();
        try {
            key = keyChainGroup.freshAddress(purpose);
        } finally {
            keyChainGroupLock.unlock();
        }
        saveNow();
        return key;
    }

    /**
     * An alias for calling {@link #freshAddress(org.bitcoinj.wallet.KeyChain.KeyPurpose)} with
     * {@link org.bitcoinj.wallet.KeyChain.KeyPurpose#RECEIVE_FUNDS} as the parameter.
     */
    public Address freshReceiveAddress() {
        return freshAddress(KeyChain.KeyPurpose.RECEIVE_FUNDS);
    }

    /**
     * Returns only the keys that have been issued by {@link #freshReceiveKey()}, {@link #freshReceiveAddress()},
     * {@link #currentReceiveKey()} or {@link #currentReceiveAddress()}.
     */
    public List<ECKey> getIssuedReceiveKeys() {
        keyChainGroupLock.lock();
        try {
            return keyChainGroup.getActiveKeyChain().getIssuedReceiveKeys();
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    /**
     * Returns only the addresses that have been issued by {@link #freshReceiveKey()}, {@link #freshReceiveAddress()},
     * {@link #currentReceiveKey()} or {@link #currentReceiveAddress()}.
     */
    public List<Address> getIssuedReceiveAddresses() {
        final List<ECKey> keys = getIssuedReceiveKeys();
        List<Address> addresses = new ArrayList<>(keys.size());
        for (ECKey key : keys)
            addresses.add(key.toAddress(getParams()));
        return addresses;
    }

    /**
     * Upgrades the wallet to be deterministic (BIP32). You should call this, possibly providing the users encryption
     * key, after loading a wallet produced by previous versions of bitcoinj. If the wallet is encrypted the key
     * <b>must</b> be provided, due to the way the seed is derived deterministically from private key bytes: failing
     * to do this will result in an exception being thrown. For non-encrypted wallets, the upgrade will be done for
     * you automatically the first time a new key is requested (this happens when spending due to the change address).
     */
    public void upgradeToDeterministic(@Nullable KeyParameter aesKey) throws DeterministicUpgradeRequiresPassword {
        keyChainGroupLock.lock();
        try {
            keyChainGroup.upgradeToDeterministic(vKeyRotationTimestamp, aesKey);
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    /**
     * Returns true if the wallet contains random keys and no HD chains, in which case you should call
     * {@link #upgradeToDeterministic(org.bouncycastle.crypto.params.KeyParameter)} before attempting to do anything
     * that would require a new address or key.
     */
    public boolean isDeterministicUpgradeRequired() {
        keyChainGroupLock.lock();
        try {
            return keyChainGroup.isDeterministicUpgradeRequired();
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    private void maybeUpgradeToHD() throws DeterministicUpgradeRequiresPassword {
        maybeUpgradeToHD(null);
    }

    @GuardedBy("keyChainGroupLock")
    private void maybeUpgradeToHD(@Nullable KeyParameter aesKey) throws DeterministicUpgradeRequiresPassword {
        checkState(keyChainGroupLock.isHeldByCurrentThread());
        if (keyChainGroup.isDeterministicUpgradeRequired()) {
            log.info("Upgrade to HD wallets is required, attempting to do so.");
            try {
                upgradeToDeterministic(aesKey);
            } catch (DeterministicUpgradeRequiresPassword e) {
                log.error("Failed to auto upgrade due to encryption. You should call wallet.upgradeToDeterministic " +
                        "with the users AES key to avoid this error.");
                throw e;
            }
        }
    }

    /**
     * Returns a snapshot of the watched scripts. This view is not live.
     */
    public List<Script> getWatchedScripts() {
        keyChainGroupLock.lock();
        try {
            return new ArrayList<>(watchedScripts);
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    /**
     * Removes the given key from the basicKeyChain. Be very careful with this - losing a private key <b>destroys the
     * money associated with it</b>.
     * @return Whether the key was removed or not.
     */
    public boolean removeKey(ECKey key) {
        keyChainGroupLock.lock();
        try {
            return keyChainGroup.removeImportedKey(key);
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    /**
     * Returns the number of keys in the key chain group, including lookahead keys.
     */
    public int getKeyChainGroupSize() {
        keyChainGroupLock.lock();
        try {
            return keyChainGroup.numKeys();
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    @VisibleForTesting
    public int getKeyChainGroupCombinedKeyLookaheadEpochs() {
        keyChainGroupLock.lock();
        try {
            return keyChainGroup.getCombinedKeyLookaheadEpochs();
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    /**
     * Returns a list of the non-deterministic keys that have been imported into the wallet, or the empty list if none.
     */
    public List<ECKey> getImportedKeys() {
        keyChainGroupLock.lock();
        try {
            return keyChainGroup.getImportedKeys();
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    /** Returns the address used for change outputs. Note: this will probably go away in future. */
    public Address currentChangeAddress() {
        return currentAddress(KeyChain.KeyPurpose.CHANGE);
    }
    /**
     * @deprecated use {@link #currentChangeAddress()} instead.
     */
    public Address getChangeAddress() {
        return currentChangeAddress();
    }

    /**
     * <p>Deprecated alias for {@link #importKey(ECKey)}.</p>
     *
     * <p><b>Replace with either {@link #freshReceiveKey()} if your call is addKey(new ECKey()), or with {@link #importKey(ECKey)}
     * which does the same thing this method used to, but with a better name.</b></p>
     */
    @Deprecated
    public boolean addKey(ECKey key) {
        return importKey(key);
    }

    /**
     * <p>Imports the given ECKey to the wallet.</p>
     *
     * <p>If the wallet is configured to auto save to a file, triggers a save immediately. Runs the onKeysAdded event
     * handler. If the key already exists in the wallet, does nothing and returns false.</p>
     */
    public boolean importKey(ECKey key) {
        return importKeys(Lists.newArrayList(key)) == 1;
    }

    /** Replace with {@link #importKeys(java.util.List)}, which does the same thing but with a better name. */
    @Deprecated
    public int addKeys(List<ECKey> keys) {
        return importKeys(keys);
    }

    /**
     * Imports the given keys to the wallet.
     * If {@link Wallet#autosaveToFile(java.io.File, long, java.util.concurrent.TimeUnit, org.bitcoinj.wallet.WalletFiles.Listener)}
     * has been called, triggers an auto save bypassing the normal coalescing delay and event handlers.
     * Returns the number of keys added, after duplicates are ignored. The onKeyAdded event will be called for each key
     * in the list that was not already present.
     */
    public int importKeys(final List<ECKey> keys) {
        // API usage check.
        checkNoDeterministicKeys(keys);
        int result;
        keyChainGroupLock.lock();
        try {
            result = keyChainGroup.importKeys(keys);
        } finally {
            keyChainGroupLock.unlock();
        }
        saveNow();
        return result;
    }

    private void checkNoDeterministicKeys(List<ECKey> keys) {
        // Watch out for someone doing wallet.importKey(wallet.freshReceiveKey()); or equivalent: we never tested this.
        for (ECKey key : keys)
            if (key instanceof DeterministicKey)
                throw new IllegalArgumentException("Cannot import HD keys back into the wallet");
    }

    /** Takes a list of keys and a password, then encrypts and imports them in one step using the current keycrypter. */
    public int importKeysAndEncrypt(final List<ECKey> keys, CharSequence password) {
        keyChainGroupLock.lock();
        try {
            checkNotNull(getKeyCrypter(), "Wallet is not encrypted");
            return importKeysAndEncrypt(keys, getKeyCrypter().deriveKey(password));
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    /** Takes a list of keys and an AES key, then encrypts and imports them in one step using the current keycrypter. */
    public int importKeysAndEncrypt(final List<ECKey> keys, KeyParameter aesKey) {
        keyChainGroupLock.lock();
        try {
            checkNoDeterministicKeys(keys);
            return keyChainGroup.importKeysAndEncrypt(keys, aesKey);
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    /**
     * Add a pre-configured keychain to the wallet.  Useful for setting up a complex keychain,
     * such as for a married wallet.  For example:
     * <pre>
     * MarriedKeyChain chain = MarriedKeyChain.builder()
     *     .random(new SecureRandom())
     *     .followingKeys(followingKeys)
     *     .threshold(2).build();
     * wallet.addAndActivateHDChain(chain);
     * </p>
     */
    public void addAndActivateHDChain(DeterministicKeyChain chain) {
        keyChainGroupLock.lock();
        try {
            keyChainGroup.addAndActivateHDChain(chain);
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    /** See {@link org.bitcoinj.wallet.DeterministicKeyChain#setLookaheadSize(int)} for more info on this. */
    public void setKeyChainGroupLookaheadSize(int lookaheadSize) {
        keyChainGroupLock.lock();
        try {
            keyChainGroup.setLookaheadSize(lookaheadSize);
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    /** See {@link org.bitcoinj.wallet.DeterministicKeyChain#setLookaheadSize(int)} for more info on this. */
    public int getKeyChainGroupLookaheadSize() {
        keyChainGroupLock.lock();
        try {
            return keyChainGroup.getLookaheadSize();
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    /** See {@link org.bitcoinj.wallet.DeterministicKeyChain#setLookaheadThreshold(int)} for more info on this. */
    public void setKeyChainGroupLookaheadThreshold(int num) {
        keyChainGroupLock.lock();
        try {
            maybeUpgradeToHD();
            keyChainGroup.setLookaheadThreshold(num);
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    /** See {@link org.bitcoinj.wallet.DeterministicKeyChain#setLookaheadThreshold(int)} for more info on this. */
    public int getKeyChainGroupLookaheadThreshold() {
        keyChainGroupLock.lock();
        try {
            maybeUpgradeToHD();
            return keyChainGroup.getLookaheadThreshold();
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    /**
     * Returns a public-only DeterministicKey that can be used to set up a watching wallet: that is, a wallet that
     * can import transactions from the block chain just as the normal wallet can, but which cannot spend. Watching
     * wallets are very useful for things like web servers that accept payments. This key corresponds to the account
     * zero key in the recommended BIP32 hierarchy.
     */
    public DeterministicKey getWatchingKey() {
        keyChainGroupLock.lock();
        try {
            maybeUpgradeToHD();
            return keyChainGroup.getActiveKeyChain().getWatchingKey();
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    /**
     * Returns whether this wallet consists entirely of watching keys (unencrypted keys with no private part). Mixed
     * wallets are forbidden.
     *
     * @throws IllegalStateException
     *             if there are no keys, or if there is a mix between watching and non-watching keys.
     */
    public boolean isWatching() {
        keyChainGroupLock.lock();
        try {
            maybeUpgradeToHD();
            return keyChainGroup.isWatching();
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    /**
     * Return true if we are watching this address.
     */
    public boolean isAddressWatched(Address address) {
        Script script = ScriptBuilder.createOutputScript(address);
        return isWatchedScript(script);
    }

    /**
     * Same as {@link #addWatchedAddress(Address, long)} with the current time as the creation time.
     */
    public boolean addWatchedAddress(final Address address) {
        long now = Utils.currentTimeMillis() / 1000;
        return addWatchedAddresses(Lists.newArrayList(address), now) == 1;
    }

    /**
     * Adds the given address to the wallet to be watched. Outputs can be retrieved by {@link #getWatchedOutputs(boolean)}.
     *
     * @param creationTime creation time in seconds since the epoch, for scanning the blockchain
     * @return whether the address was added successfully (not already present)
     */
    public boolean addWatchedAddress(final Address address, long creationTime) {
        return addWatchedAddresses(Lists.newArrayList(address), creationTime) == 1;
    }

    /**
     * Adds the given address to the wallet to be watched. Outputs can be retrieved
     * by {@link #getWatchedOutputs(boolean)}.
     *
     * @return how many addresses were added successfully
     */
    public int addWatchedAddresses(final List<Address> addresses, long creationTime) {
        List<Script> scripts = Lists.newArrayList();

        for (Address address : addresses) {
            Script script = ScriptBuilder.createOutputScript(address);
            script.setCreationTimeSeconds(creationTime);
            scripts.add(script);
        }

        return addWatchedScripts(scripts);
    }

    /**
     * Adds the given output scripts to the wallet to be watched. Outputs can be retrieved by {@link #getWatchedOutputs(boolean)}.
     * If a script is already being watched, the object is replaced with the one in the given list. As {@link Script}
     * equality is defined in terms of program bytes only this lets you update metadata such as creation time. Note that
     * you should be careful not to add scripts with a creation time of zero (the default!) because otherwise it will
     * disable the important wallet checkpointing optimisation.
     *
     * @return how many scripts were added successfully
     */
    public int addWatchedScripts(final List<Script> scripts) {
        int added = 0;
        keyChainGroupLock.lock();
        try {
            for (final Script script : scripts) {
                // Script.equals/hashCode() only takes into account the program bytes, so this step lets the user replace
                // a script in the wallet with an incorrect creation time.
                if (watchedScripts.contains(script))
                    watchedScripts.remove(script);
                if (script.getCreationTimeSeconds() == 0)
                    log.warn("Adding a script to the wallet with a creation time of zero, this will disable the checkpointing optimization!    {}", script);
                watchedScripts.add(script);
                added++;
            }
        } finally {
            keyChainGroupLock.unlock();
        }
        if (added > 0) {
            queueOnScriptsChanged(scripts, true);
            saveNow();
        }
        return added;
    }

    /**
     * Removes the given output scripts from the wallet that were being watched.
     *
     * @return true if successful
     */
    public boolean removeWatchedAddress(final Address address) {
        return removeWatchedAddresses(ImmutableList.of(address));
    }

    /**
     * Removes the given output scripts from the wallet that were being watched.
     *
     * @return true if successful
     */
    public boolean removeWatchedAddresses(final List<Address> addresses) {
        List<Script> scripts = Lists.newArrayList();

        for (Address address : addresses) {
            Script script = ScriptBuilder.createOutputScript(address);
            scripts.add(script);
        }

        return removeWatchedScripts(scripts);
    }

    /**
     * Removes the given output scripts from the wallet that were being watched.
     *
     * @return true if successful
     */
    public boolean removeWatchedScripts(final List<Script> scripts) {
        lock.lock();
        try {
            for (final Script script : scripts) {
                if (!watchedScripts.contains(script))
                    continue;

                watchedScripts.remove(script);
            }

            queueOnScriptsChanged(scripts, false);
            saveNow();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns all addresses watched by this wallet.
     */
    public List<Address> getWatchedAddresses() {
        keyChainGroupLock.lock();
        try {
            List<Address> addresses = new LinkedList<>();
            for (Script script : watchedScripts)
                if (script.isSentToAddress())
                    addresses.add(script.getToAddress(params));
            return addresses;
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    /**
     * Locates a keypair from the basicKeyChain given the hash of the public key. This is needed when finding out which
     * key we need to use to redeem a transaction output.
     *
     * @return ECKey object or null if no such key was found.
     */
    @Override
    @Nullable
    public ECKey findKeyFromPubHash(byte[] pubkeyHash) {
        keyChainGroupLock.lock();
        try {
            return keyChainGroup.findKeyFromPubHash(pubkeyHash);
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    /** Returns true if the given key is in the wallet, false otherwise. Currently an O(N) operation. */
    public boolean hasKey(ECKey key) {
        keyChainGroupLock.lock();
        try {
            return keyChainGroup.hasKey(key);
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean isPubKeyHashMine(byte[] pubkeyHash) {
        return findKeyFromPubHash(pubkeyHash) != null;
    }

    /** {@inheritDoc} */
    @Override
    public boolean isWatchedScript(Script script) {
        keyChainGroupLock.lock();
        try {
            return watchedScripts.contains(script);
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    /**
     * Locates a keypair from the basicKeyChain given the raw public key bytes.
     * @return ECKey or null if no such key was found.
     */
    @Override
    @Nullable
    public ECKey findKeyFromPubKey(byte[] pubkey) {
        keyChainGroupLock.lock();
        try {
            return keyChainGroup.findKeyFromPubKey(pubkey);
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean isPubKeyMine(byte[] pubkey) {
        return findKeyFromPubKey(pubkey) != null;
    }

    /**
     * Locates a redeem data (redeem script and keys) from the keyChainGroup given the hash of the script.
     * Returns RedeemData object or null if no such data was found.
     */
    @Nullable
    @Override
    public RedeemData findRedeemDataFromScriptHash(byte[] payToScriptHash) {
        keyChainGroupLock.lock();
        try {
            return keyChainGroup.findRedeemDataFromScriptHash(payToScriptHash);
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean isPayToScriptHashMine(byte[] payToScriptHash) {
        return findRedeemDataFromScriptHash(payToScriptHash) != null;
    }

    /**
     * Marks all keys used in the transaction output as used in the wallet.
     * See {@link org.bitcoinj.wallet.DeterministicKeyChain#markKeyAsUsed(DeterministicKey)} for more info on this.
     */
    private void markKeysAsUsed(Transaction tx) {
        keyChainGroupLock.lock();
        try {
            for (TransactionOutput o : tx.getOutputs()) {
                try {
                    Script script = o.getScriptPubKey();
                    if (script.isSentToRawPubKey()) {
                        byte[] pubkey = script.getPubKey();
                        keyChainGroup.markPubKeyAsUsed(pubkey);
                    } else if (script.isSentToAddress()) {
                        byte[] pubkeyHash = script.getPubKeyHash();
                        keyChainGroup.markPubKeyHashAsUsed(pubkeyHash);
                    } else if (script.isPayToScriptHash()) {
                        Address a = Address.fromP2SHScript(tx.getParams(), script);
                        keyChainGroup.markP2SHAddressAsUsed(a);
                    }
                } catch (ScriptException e) {
                    // Just means we didn't understand the output of this transaction: ignore it.
                    log.warn("Could not parse tx output script: {}", e.toString());
                }
            }
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    /**
     * Returns the immutable seed for the current active HD chain.
     * @throws org.bitcoinj.core.ECKey.MissingPrivateKeyException if the seed is unavailable (watching wallet)
     */
    public DeterministicSeed getKeyChainSeed() {
        keyChainGroupLock.lock();
        try {
            DeterministicSeed seed = keyChainGroup.getActiveKeyChain().getSeed();
            if (seed == null)
                throw new ECKey.MissingPrivateKeyException();
            return seed;
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    /**
     * Returns a key for the given HD path, assuming it's already been derived. You normally shouldn't use this:
     * use currentReceiveKey/freshReceiveKey instead.
     */
    public DeterministicKey getKeyByPath(List<ChildNumber> path) {
        keyChainGroupLock.lock();
        try {
            maybeUpgradeToHD();
            return keyChainGroup.getActiveKeyChain().getKeyByPath(path, false);
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    /**
     * Convenience wrapper around {@link Wallet#encrypt(org.bitcoinj.crypto.KeyCrypter,
     * org.bouncycastle.crypto.params.KeyParameter)} which uses the default Scrypt key derivation algorithm and
     * parameters to derive a key from the given password.
     */
    public void encrypt(CharSequence password) {
        keyChainGroupLock.lock();
        try {
            final KeyCrypterScrypt scrypt = new KeyCrypterScrypt();
            keyChainGroup.encrypt(scrypt, scrypt.deriveKey(password));
        } finally {
            keyChainGroupLock.unlock();
        }
        saveNow();
    }

    /**
     * Encrypt the wallet using the KeyCrypter and the AES key. A good default KeyCrypter to use is
     * {@link org.bitcoinj.crypto.KeyCrypterScrypt}.
     *
     * @param keyCrypter The KeyCrypter that specifies how to encrypt/ decrypt a key
     * @param aesKey AES key to use (normally created using KeyCrypter#deriveKey and cached as it is time consuming to create from a password)
     * @throws KeyCrypterException Thrown if the wallet encryption fails. If so, the wallet state is unchanged.
     */
    public void encrypt(KeyCrypter keyCrypter, KeyParameter aesKey) {
        keyChainGroupLock.lock();
        try {
            keyChainGroup.encrypt(keyCrypter, aesKey);
        } finally {
            keyChainGroupLock.unlock();
        }
        saveNow();
    }

    /**
     * Decrypt the wallet with the wallets keyCrypter and password.
     * @throws KeyCrypterException Thrown if the wallet decryption fails. If so, the wallet state is unchanged.
     */
    public void decrypt(CharSequence password) {
        keyChainGroupLock.lock();
        try {
            final KeyCrypter crypter = keyChainGroup.getKeyCrypter();
            checkState(crypter != null, "Not encrypted");
            keyChainGroup.decrypt(crypter.deriveKey(password));
        } finally {
            keyChainGroupLock.unlock();
        }
        saveNow();
    }

    /**
     * Decrypt the wallet with the wallets keyCrypter and AES key.
     *
     * @param aesKey AES key to use (normally created using KeyCrypter#deriveKey and cached as it is time consuming to create from a password)
     * @throws KeyCrypterException Thrown if the wallet decryption fails. If so, the wallet state is unchanged.
     */
    public void decrypt(KeyParameter aesKey) {
        keyChainGroupLock.lock();
        try {
            keyChainGroup.decrypt(aesKey);
        } finally {
            keyChainGroupLock.unlock();
        }
        saveNow();
    }

    /**
     *  Check whether the password can decrypt the first key in the wallet.
     *  This can be used to check the validity of an entered password.
     *
     *  @return boolean true if password supplied can decrypt the first private key in the wallet, false otherwise.
     *  @throws IllegalStateException if the wallet is not encrypted.
     */
    public boolean checkPassword(CharSequence password) {
        keyChainGroupLock.lock();
        try {
            return keyChainGroup.checkPassword(password);
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    /**
     *  Check whether the AES key can decrypt the first encrypted key in the wallet.
     *
     *  @return boolean true if AES key supplied can decrypt the first encrypted private key in the wallet, false otherwise.
     */
    public boolean checkAESKey(KeyParameter aesKey) {
        keyChainGroupLock.lock();
        try {
            return keyChainGroup.checkAESKey(aesKey);
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    /**
     * Get the wallet's KeyCrypter, or null if the wallet is not encrypted.
     * (Used in encrypting/ decrypting an ECKey).
     */
    @Nullable
    public KeyCrypter getKeyCrypter() {
        keyChainGroupLock.lock();
        try {
            return keyChainGroup.getKeyCrypter();
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    /**
     * Get the type of encryption used for this wallet.
     *
     * (This is a convenience method - the encryption type is actually stored in the keyCrypter).
     */
    public EncryptionType getEncryptionType() {
        keyChainGroupLock.lock();
        try {
            KeyCrypter crypter = keyChainGroup.getKeyCrypter();
            if (crypter != null)
                return crypter.getUnderstoodEncryptionType();
            else
                return EncryptionType.UNENCRYPTED;
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    /** Returns true if the wallet is encrypted using any scheme, false if not. */
    public boolean isEncrypted() {
        return getEncryptionType() != EncryptionType.UNENCRYPTED;
    }

    /** Changes wallet encryption password, this is atomic operation. */
    public void changeEncryptionPassword(CharSequence currentPassword, CharSequence newPassword){
        keyChainGroupLock.lock();
        try {
            decrypt(currentPassword);
            encrypt(newPassword);
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    /** Changes wallet AES encryption key, this is atomic operation. */
    public void changeEncryptionKey(KeyCrypter keyCrypter, KeyParameter currentAesKey, KeyParameter newAesKey){
        keyChainGroupLock.lock();
        try {
            decrypt(currentAesKey);
            encrypt(keyCrypter, newAesKey);
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    //endregion

    /******************************************************************************************************************/

    //region Serialization support

    // TODO: Make this package private once the classes finish moving around.
    /** Internal use only. */
    public List<Protos.Key> serializeKeyChainGroupToProtobuf() {
        keyChainGroupLock.lock();
        try {
            return keyChainGroup.serializeToProtobuf();
        } finally {
            keyChainGroupLock.unlock();
        }
    }

    /** Saves the wallet first to the given temp file, then renames to the dest file. */
    public void saveToFile(File temp, File destFile) throws IOException {
        FileOutputStream stream = null;
        lock.lock();
        try {
            stream = new FileOutputStream(temp);
            saveToFileStream(stream);
            // Attempt to force the bits to hit the disk. In reality the OS or hard disk itself may still decide
            // to not write through to physical media for at least a few seconds, but this is the best we can do.
            stream.flush();
            stream.getFD().sync();
            stream.close();
            stream = null;
            if (Utils.isWindows()) {
                // Work around an issue on Windows whereby you can't rename over existing files.
                File canonical = destFile.getCanonicalFile();
                if (canonical.exists() && !canonical.delete())
                    throw new IOException("Failed to delete canonical wallet file for replacement with autosave");
                if (temp.renameTo(canonical))
                    return;  // else fall through.
                throw new IOException("Failed to rename " + temp + " to " + canonical);
            } else if (!temp.renameTo(destFile)) {
                throw new IOException("Failed to rename " + temp + " to " + destFile);
            }
        } catch (RuntimeException e) {
            log.error("Failed whilst saving wallet", e);
            throw e;
        } finally {
            lock.unlock();
            if (stream != null) {
                stream.close();
            }
            if (temp.exists()) {
                log.warn("Temp file still exists after failed save.");
            }
        }
    }

    /**
     * Uses protobuf serialization to save the wallet to the given file. To learn more about this file format, see
     * {@link WalletProtobufSerializer}. Writes out first to a temporary file in the same directory and then renames
     * once written.
     */
    public void saveToFile(File f) throws IOException {
        File directory = f.getAbsoluteFile().getParentFile();
        File temp = File.createTempFile("wallet", null, directory);
        saveToFile(temp, f);
    }

    /**
     * <p>Whether or not the wallet will ignore pending transactions that fail the selected
     * {@link RiskAnalysis}. By default, if a transaction is considered risky then it won't enter the wallet
     * and won't trigger any event listeners. If you set this property to true, then all transactions will
     * be allowed in regardless of risk. For example, the {@link DefaultRiskAnalysis} checks for non-finality of
     * transactions.</p>
     *
     * <p>Note that this property is not serialized. You have to set it each time a Wallet object is constructed,
     * even if it's loaded from a protocol buffer.</p>
     */
    public void setAcceptRiskyTransactions(boolean acceptRiskyTransactions) {
        lock.lock();
        try {
            this.acceptRiskyTransactions = acceptRiskyTransactions;
        } finally {
            lock.unlock();
        }
    }

    /**
     * See {@link Wallet#setAcceptRiskyTransactions(boolean)} for an explanation of this property.
     */
    public boolean isAcceptRiskyTransactions() {
        lock.lock();
        try {
            return acceptRiskyTransactions;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Sets the {@link RiskAnalysis} implementation to use for deciding whether received pending transactions are risky
     * or not. If the analyzer says a transaction is risky, by default it will be dropped. You can customize this
     * behaviour with {@link #setAcceptRiskyTransactions(boolean)}.
     */
    public void setRiskAnalyzer(RiskAnalysis.Analyzer analyzer) {
        lock.lock();
        try {
            this.riskAnalyzer = checkNotNull(analyzer);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Gets the current {@link RiskAnalysis} implementation. The default is {@link DefaultRiskAnalysis}.
     */
    public RiskAnalysis.Analyzer getRiskAnalyzer() {
        lock.lock();
        try {
            return riskAnalyzer;
        } finally {
            lock.unlock();
        }
    }

    /**
     * <p>Sets up the wallet to auto-save itself to the given file, using temp files with atomic renames to ensure
     * consistency. After connecting to a file, you no longer need to save the wallet manually, it will do it
     * whenever necessary. Protocol buffer serialization will be used.</p>
     *
     * <p>If delayTime is set, a background thread will be created and the wallet will only be saved to
     * disk every so many time units. If no changes have occurred for the given time period, nothing will be written.
     * In this way disk IO can be rate limited. It's a good idea to set this as otherwise the wallet can change very
     * frequently, eg if there are a lot of transactions in it or during block sync, and there will be a lot of redundant
     * writes. Note that when a new key is added, that always results in an immediate save regardless of
     * delayTime. <b>You should still save the wallet manually when your program is about to shut down as the JVM
     * will not wait for the background thread.</b></p>
     *
     * <p>An event listener can be provided. If a delay >0 was specified, it will be called on a background thread
     * with the wallet locked when an auto-save occurs. If delay is zero or you do something that always triggers
     * an immediate save, like adding a key, the event listener will be invoked on the calling threads.</p>
     *
     * @param f The destination file to save to.
     * @param delayTime How many time units to wait until saving the wallet on a background thread.
     * @param timeUnit the unit of measurement for delayTime.
     * @param eventListener callback to be informed when the auto-save thread does things, or null
     */
    public WalletFiles autosaveToFile(File f, long delayTime, TimeUnit timeUnit,
                                      @Nullable WalletFiles.Listener eventListener) {
        lock.lock();
        try {
            checkState(vFileManager == null, "Already auto saving this wallet.");
            WalletFiles manager = new WalletFiles(this, f, delayTime, timeUnit);
            if (eventListener != null)
                manager.setListener(eventListener);
            vFileManager = manager;
            return manager;
        } finally {
            lock.unlock();
        }
    }

    /**
     * <p>
     * Disables auto-saving, after it had been enabled with
     * {@link Wallet#autosaveToFile(java.io.File, long, java.util.concurrent.TimeUnit, org.bitcoinj.wallet.WalletFiles.Listener)}
     * before. This method blocks until finished.
     * </p>
     */
    public void shutdownAutosaveAndWait() {
        lock.lock();
        try {
            WalletFiles files = vFileManager;
            vFileManager = null;
            checkState(files != null, "Auto saving not enabled.");
            files.shutdownAndWait();
        } finally {
            lock.unlock();
        }
    }

    /** Requests an asynchronous save on a background thread */
    protected void saveLater() {
        WalletFiles files = vFileManager;
        if (files != null)
            files.saveLater();
    }

    /** If auto saving is enabled, do an immediate sync write to disk ignoring any delays. */
    protected void saveNow() {
        WalletFiles files = vFileManager;
        if (files != null) {
            try {
                files.saveNow();  // This calls back into saveToFile().
            } catch (IOException e) {
                // Can't really do much at this point, just let the API user know.
                log.error("Failed to save wallet to disk!", e);
                Thread.UncaughtExceptionHandler handler = Threading.uncaughtExceptionHandler;
                if (handler != null)
                    handler.uncaughtException(Thread.currentThread(), e);
            }
        }
    }

    /**
     * Uses protobuf serialization to save the wallet to the given file stream. To learn more about this file format, see
     * {@link WalletProtobufSerializer}.
     */
    public void saveToFileStream(OutputStream f) throws IOException {
        lock.lock();
        try {
            new WalletProtobufSerializer().writeWallet(this, f);
        } finally {
            lock.unlock();
        }
    }

    /** Returns the parameters this wallet was created with. */
    public NetworkParameters getParams() {
        return params;
    }

    /** Returns the API context that this wallet was created with. */
    public Context getContext() {
        return context;
    }

    /**
     * <p>Returns a wallet deserialized from the given file. Extensions previously saved with the wallet can be
     * deserialized by calling @{@link WalletExtension#deserializeWalletExtension(Wallet, byte[])}}</p>
     *
     * @param file the wallet file to read
     * @param walletExtensions extensions possibly added to the wallet.
     */
    public static Wallet loadFromFile(File file, @Nullable WalletExtension... walletExtensions) throws UnreadableWalletException {
        try {
            FileInputStream stream = null;
            try {
                stream = new FileInputStream(file);
                return loadFromFileStream(stream, walletExtensions);
            } finally {
                if (stream != null) stream.close();
            }
        } catch (IOException e) {
            throw new UnreadableWalletException("Could not open file", e);
        }
    }

    /**
     * Returns if this wallet is structurally consistent, so e.g. no duplicate transactions. First inconsistency and a
     * dump of the wallet will be logged.
     */
    public boolean isConsistent() {
        try {
            isConsistentOrThrow();
            return true;
        } catch (IllegalStateException x) {
            log.error(x.getMessage());
            try {
                log.error(toString());
            } catch (RuntimeException x2) {
                log.error("Printing inconsistent wallet failed", x2);
            }
            return false;
        }
    }

    /**
     * Variant of {@link Wallet#isConsistent()} that throws an {@link IllegalStateException} describing the first
     * inconsistency.
     */
    public void isConsistentOrThrow() throws IllegalStateException {
        lock.lock();
        try {
            Set<Transaction> transactions = getTransactions(true);

            Set<Sha256Hash> hashes = new HashSet<>();
            for (Transaction tx : transactions) {
                hashes.add(tx.getHash());
            }

            int size1 = transactions.size();
            if (size1 != hashes.size()) {
                throw new IllegalStateException("Two transactions with same hash");
            }

            int size2 = unspent.size() + spent.size() + pending.size() + dead.size();
            if (size1 != size2) {
                throw new IllegalStateException("Inconsistent wallet sizes: " + size1 + ", " + size2);
            }

            for (Transaction tx : unspent.values()) {
                if (!isTxConsistent(tx, false)) {
                    throw new IllegalStateException("Inconsistent unspent tx: " + tx.getHashAsString());
                }
            }

            for (Transaction tx : spent.values()) {
                if (!isTxConsistent(tx, true)) {
                    throw new IllegalStateException("Inconsistent spent tx: " + tx.getHashAsString());
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /*
     * If isSpent - check that all my outputs spent, otherwise check that there at least
     * one unspent.
     */
    @VisibleForTesting
    boolean isTxConsistent(final Transaction tx, final boolean isSpent) {
        boolean isActuallySpent = true;
        for (TransactionOutput o : tx.getOutputs()) {
            if (o.isAvailableForSpending()) {
                if (o.isMineOrWatched(this)) isActuallySpent = false;
                if (o.getSpentBy() != null) {
                    log.error("isAvailableForSpending != spentBy");
                    return false;
                }
            } else {
                if (o.getSpentBy() == null) {
                    log.error("isAvailableForSpending != spentBy");
                    return false;
                }
            }
        }
        return isActuallySpent == isSpent;
    }

    /** Returns a wallet deserialized from the given input stream and wallet extensions. */
    public static Wallet loadFromFileStream(InputStream stream, @Nullable WalletExtension... walletExtensions) throws UnreadableWalletException {
        Wallet wallet = new WalletProtobufSerializer().readWallet(stream, walletExtensions);
        if (!wallet.isConsistent()) {
            log.error("Loaded an inconsistent wallet");
        }
        return wallet;
    }

    //endregion

    /******************************************************************************************************************/

    //region Inbound transaction reception and processing

    /**
     * Called by the {@link BlockChain} when we receive a new filtered block that contains a transactions previously
     * received by a call to {@link #receivePending}.<p>
     *
     * This is necessary for the internal book-keeping Wallet does. When a transaction is received that sends us
     * coins it is added to a pool so we can use it later to create spends. When a transaction is received that
     * consumes outputs they are marked as spent so they won't be used in future.<p>
     *
     * A transaction that spends our own coins can be received either because a spend we created was accepted by the
     * network and thus made it into a block, or because our keys are being shared between multiple instances and
     * some other node spent the coins instead. We still have to know about that to avoid accidentally trying to
     * double spend.<p>
     *
     * A transaction may be received multiple times if is included into blocks in parallel chains. The blockType
     * parameter describes whether the containing block is on the main/best chain or whether it's on a presently
     * inactive side chain. We must still record these transactions and the blocks they appear in because a future
     * block might change which chain is best causing a reorganize. A re-org can totally change our balance!
     */
    @Override
    public boolean notifyTransactionIsInBlock(Sha256Hash txHash, StoredBlock block,
                                              BlockChain.NewBlockType blockType,
                                              int relativityOffset) throws VerificationException {
        lock.lock();
        try {
            Transaction tx = transactions.get(txHash);
            if (tx == null) {
                tx = riskDropped.get(txHash);
                if (tx != null) {
                    // If this happens our risk analysis is probably wrong and should be improved.
                    log.info("Risk analysis dropped tx {} but was included in block anyway", tx.getHash());
                } else {
                    // False positive that was broadcast to us and ignored by us because it was irrelevant to our keys.
                    return false;
                }
            }
            receive(tx, block, blockType, relativityOffset);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * <p>Called when we have found a transaction (via network broadcast or otherwise) that is relevant to this wallet
     * and want to record it. Note that we <b>cannot verify these transactions at all</b>, they may spend fictional
     * coins or be otherwise invalid. They are useful to inform the user about coins they can expect to receive soon,
     * and if you trust the sender of the transaction you can choose to assume they are in fact valid and will not
     * be double spent as an optimization.</p>
     *
     * <p>This is the same as {@link Wallet#receivePending(Transaction, java.util.List)} but allows you to override the
     * {@link Wallet#isPendingTransactionRelevant(Transaction)} sanity-check to keep track of transactions that are not
     * spendable or spend our coins. This can be useful when you want to keep track of transaction confidence on
     * arbitrary transactions. Note that transactions added in this way will still be relayed to peers and appear in
     * transaction lists like any other pending transaction (even when not relevant).</p>
     */
    public void receivePending(Transaction tx, @Nullable List<Transaction> dependencies, boolean overrideIsRelevant) throws VerificationException {
        // Can run in a peer thread. This method will only be called if a prior call to isPendingTransactionRelevant
        // returned true, so we already know by this point that it sends coins to or from our wallet, or is a double
        // spend against one of our other pending transactions.
        lock.lock();
        try {
            tx.verify();