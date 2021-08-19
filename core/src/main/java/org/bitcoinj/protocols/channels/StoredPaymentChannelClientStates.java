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
     * Use this setter if the broadcaste