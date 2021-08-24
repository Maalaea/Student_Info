/*
 * Copyright 2013 Google Inc.
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

package org.bitcoinj.protocols.channels;

import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import org.bitcoinj.core.*;
import org.bitcoinj.utils.Threading;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.wallet.WalletExtension;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.HashMultimap;
import com.google.common.util.concurrent.SettableFuture;
import com.google.protobuf.ByteString;
import net.jcip.annotations.GuardedBy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

/**
 * This class maintains a set of {@link StoredClientChannel}s, automatically (re)broadcasting the contract transaction
 * and broadcasting the refund transaction over the given {@link TransactionBroadcaster}.
 */
public class StoredPaymentChannelClientStates implements WalletExtension {
    private static final Logger log = LoggerFactory.getLogger(StoredPaymentChannelClientStates.class);
    static final String EXTENSION_ID = StoredPaymentChannelClientStates.class.getName();
    static final int MAX_SECONDS_TO_WAIT_FOR_BROADCASTER_TO_BE_SET = 10;

    @GuardedBy("lock") @VisibleForTesting final HashMultimap<Sha256Hash, StoredClientChannel> mapChannels = HashMultimap.create();
    @VisibleForTesting final Timer channelTimeoutHandler = new Timer(true);

    private Wallet containingWallet;
    private final SettableFuture<TransactionBroadcaster> announcePeerGroupFuture = SettableFuture.create();

    protected final ReentrantLock lock = Threading.lock("StoredPaymentChannelClientStates");

    /**
     * Creates a new StoredPaymentChannelClientStates and associates it with the given {@link Wallet} and
     * {@link TransactionBroadcaster} which are used to complete and announce contract and refund
     * transactions.
     */
    public StoredPaymentChannelClientStates(@Nullable Wallet containingWallet, TransactionBroadcaster announcePeerGroup) {
        setTransactionBroadcaster(announcePeerGroup);
        this.containingWallet = containingWallet;
    }

    /**
     * Creates a new StoredPaymentChannelClientStates and associates it with the given {@link Wallet}
     *
     * Use this constructor if you use WalletAppKit, it will provide the broadcaster for you (no need to call the setter)
     */
    public StoredPaymentChannelClientStates(@Nullable Wallet containingWallet) {
        this.containingWallet = containingWallet;
    }

    /**
     * Use this setter if the broadcaster is not available during instantiation and you're not using WalletAppKit.
     * This setter will let you delay the setting of the broadcaster until the Bitcoin network is ready.
     *
     * @param transactionBroadcaster which is used to complete and announce contract and refund transactions.
     */
    public final void setTransactionBroadcaster(TransactionBroadcaster transactionBroadcaster) {
        this.announcePeerGroupFuture.set(checkNotNull(transactionBroadcaster));
    }

    /** Returns this extension from the given wallet, or null if no such extension was added. */
    @Nullable
    public static StoredPaymentChannelClientStates getFromWallet(Wallet wallet) {
        return (StoredPaymentChannelClientStates) wallet.getExtensions().get(EXTENSION_ID);
    }

    /** Returns the outstanding amount of money sent back to us for all channels to this server added together. */
    public Coin getBalanceForServer(Sha256Hash id) {
        Coin balance = Coin.ZERO;
        lock.lock();
        try {
            Set<StoredClientChannel> setChannels = mapChannels.get(id);
            for (StoredClientChannel channel : setChannels) {
                synchronized (channel) {
                    if (channel.close != null) continue;
                    balance = balance.add(channel.valueToMe);
                }
            }
            return balance;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the number of seconds from now until this servers next channel will expire, or zero if no unexpired
     * channels found.
     */
    public long getSecondsUntilExpiry(Sha256Hash id) {
        lock.lock();
        try {
            final Set<StoredClientChannel> setChannels = mapChannels.get(id);
            final long nowSeconds = Utils.currentTimeSeconds();
            int earliestTime = Integer.MAX_VALUE;
            for (StoredClientChannel channel : setChannels) {
                synchronized (channel) {
                    if (channel.expiryTimeSeconds() > nowSeconds)
                        earliestTime = Math.min(earliestTime, (int) channel.expiryTimeSeconds());
                }
            }
            return earliestTime == Integer.MAX_VALUE ? 0 : earliestTime - nowSeconds;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Finds an inactive channel with the given id and returns it, or returns null.
     */
    @Nullable
    StoredClientChannel getUsableChannelForServerID(Sha256Hash id) {
        lock.lock();
        try {
            Set<StoredClientChannel> setChannels = mapChannels.get(id);
            for (StoredClientChannel channel : setChannels) {
                synchronized (channel) {
                    // Check if the channel is usable (has money, inactive) and if so, activate it.
                    log.info("Considering channel {} contract {}", channel.hashCode(), channel.contract.getHash());
                    if (channel.close != null || channel.valueToMe.equals(Coin.ZERO)) {
                        log.info("  ... but is closed or empty");
                        continue;
                    }
                    if (!channel.active) {
                        log.info("  ... activating");
                        channel.active = true;
                        return channel;
                    }
                    log.info("  ... but is already active");
                }
            }
        } finally {
            lock.unlock();
        }
        return null;
    }

    /**
     * Finds a channel with the given id and contract hash and returns it, or returns null.
     */
    @Nullable
    public StoredClientChannel getChannel(Sha256Hash id, Sha256Hash contractHash) {
        lock.lock();
        try {
            Set<StoredClientChannel> setChannels = mapChannels.get(id);
            for (StoredClientChannel channel : setChannels) {
                if (channel.contract.getHash().equals(contractHash))
                    return channel;
            }
            return null;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Get a copy of all {@link StoredClientChannel}s
     */
    public Multimap<Sha256Hash, StoredClientChannel> getChannelMap() {
        lock.lock();
        try {
            return ImmutableMultimap.copyOf(mapChannels);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Notifies the set of stored states that a channel has been updated. Use to notify the wallet of an update to this
     * wallet extension.
     */
    void updatedChannel(final StoredClientChannel channel) {
        log.info("Stored client channel {} was updated", channel.hashCode());
        containingWallet.addOrUpdateExtension(this);
    }

    /**
     * Adds the given channel to this set of stored states, broadcasting the contract and refund transactions when the
     * channel expires and notifies the wallet of an update to this wallet extension
     */
    void putChannel(final StoredClientChannel channel) {
        putChannel(channel, true);
    }

    // Adds this channel and optionally notifies the wallet of an update to this extension (used during deserialize)
    private void putChannel(final StoredClientChannel channel, boolean updateWallet) {
        lock.lock();
        try {
            mapChannels.put(channel.id, channel);
            channelTimeoutHandler.schedule(new TimerTask() {
                @Override
                public void run() {
                    try {
                        TransactionBroadcaster announcePeerGroup = getAnnouncePeerGroup();
                        removeChannel(channel);
                        announcePeerGroup.broadcastTransaction(channel.contract);
                        announcePeerGroup.broadcastTransaction(channel.refund);
                    } catch (Exception e) {
                        // Something went wrong closing the channel - we catch
                        // here or else we take down the whole Timer.
                        log.error("Auto-closing channel failed", e);
                    }
                }
                // Add the difference between real time and Utils.now() so that test-cases can use a mock clock.
            }, new Date(channel.expiryTimeSeconds() * 1000 + (System.currentTimeMillis() - Utils.currentTimeMillis())));
        } finally {
            lock.unlock();
        }
        if (updateWallet)
            updatedChannel(channel);
    }

    /**
     * If the peer group has not been set for MAX_SECONDS_TO_WAIT_FOR_BROADCASTER_TO_BE_SET seconds, then
     * the programmer probably forgot to set it and we should throw exception.
     */
    private TransactionBroadcaster getAnnouncePeerGroup() {
        try {
            return announcePeerGroupFuture.get(MAX_SECONDS_TO_WAIT_FOR_BROADCASTER_TO_BE_SET, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } catch (TimeoutException e) {
            String err = "Transaction broadcaster not set";
            log.error(err);
            throw new RuntimeException(err, e);
        }
    }

    /**
     * <p>Removes the channel with the given id from this set of stored states and notifies the wallet of an update to
     * this wallet extension.</p>
     *
     * <p>Note that the channel will still have its contract and refund transactions broadcast via the connected
     * {@link TransactionBroadcaster} as long as this {@link StoredPaymentChannelClientStates} continues to
     * exist in memory.</p>
     */
    void removeChannel(StoredClientChannel channel) {
        lock.lock();
        try {
            mapChannels.remove(channel.id, channel);
        } finally {
            lock.unlock();
        }
        updatedChannel(channel);
    }

    @Override
    public String getWalletExtensionID() {
        return EXTENSION_ID;
    }

    @Override
    public boolean isWalletExtensionMandatory() {
        return false;
    }

    @Override
    public byte[] serializeWalletExtension() {
        lock.lock();
        try {
            final NetworkParameters params = getNetworkParameters();
            // If we haven't attached to a wallet yet we can't check against network parameters
            final boolean hasMaxMoney = params != null ? params.hasMaxMoney() : true;
            final Coin networkMaxMoney = params != null ? params.getMaxMoney() : NetworkParameters.MAX_MONEY;
            ClientState.StoredClientPaymentChannels.Builder builder = ClientState.StoredClientPaymentChannels.newBuilder();
            for (StoredClientChannel channel : mapChannels.values()) {
                // First a few 