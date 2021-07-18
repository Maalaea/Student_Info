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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Throwables;
import com.google.common.collect.Multimap;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import org.bitcoinj.core.*;
import org.bitcoinj.crypto.TransactionSignature;
import org.bitcoinj.protocols.channels.IPaymentChannelClient.ClientChannelProperties;
import org.bitcoinj.script.Script;
import org.bitcoinj.utils.Threading;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.wallet.listeners.WalletCoinsReceivedEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.bouncycastle.crypto.params.KeyParameter;

import javax.annotation.Nullable;

import static com.google.common.base.Preconditions.*;

/**
 * <p>A payment channel is a method of sending money to someone such that the amount of money you send can be adjusted
 * after the fact, in an efficient manner that does not require broadcasting to the network. This can be used to
 * implement micropayments or other payment schemes in which immediate settlement is not required, but zero trust
 * negotiation is. Note that this class only allows the amount of money sent to be incremented, not decremented.</p>
 *
 * <p>This class has two subclasses, {@link PaymentChannelV1ClientState} and {@link PaymentChannelV2ClientState} for
 * protocols version 1 and 2.</p>
 *
 * <p>This class implements the core state machine for the client side of the protocol. The server side is implemented
 * by {@link PaymentChannelServerState} and {@link PaymentChannelClientConnection} implements a network protocol
 * suitable for TCP/IP connections which moves this class through each state. We say that the party who is sending funds
 * is the <i>client</i> or <i>initiating party</i>. The party that is receiving the funds is the <i>server</i> or
 * <i>receiving party</i>. Although the underlying Bitcoin protocol is capable of more complex relationships than that,
 * this class implements only the simplest case.</p>
 *
 * <p>A channel has an expiry parameter. If the server halts after the multi-signature contract which locks
 * up the given value is broadcast you could get stuck in a state where you've lost all the money put into the
 * contract. To avoid this, a refund transaction is agreed ahead of time but it may only be used/broadcast after
 * the expiry time. This is specified in terms of block timestamps and once the timestamp of the chain chain approaches
 * the given time (within a few hours), the channel must be closed or else the client will broadcast the refund
 * transaction and take back all the money once the expiry time is reached.</p>
 *
 * <p>To begin, the client calls {@link PaymentChannelClientState#initiate(KeyParameter, ClientChannelProperties)}, which moves the channel into state
 * INITIATED and creates the initial multi-sig contract and refund transaction. If the wallet has insufficient funds an
 * exception will be thrown at this point. Once this is done, call
 * {@link PaymentChannelV1ClientState#getIncompleteRefundTransaction()} and pass the resultant transaction through to the
 * server. Once you have retrieved the signature, use {@link PaymentChannelV1ClientState#provideRefundSignature(byte[], KeyParameter)}.
 * You must then call {@link PaymentChannelClientState#storeChannelInWallet(Sha256Hash)} to store the refund transaction
 * in the wallet, protecting you against a malicious server attempting to destroy all your coins. At this point, you can
 * provide the server with the multi-sig contract (via {@link PaymentChannelClientState#getContract()}) safely.
 * </p>
 */
public abstract class PaymentChannelClientState {
    private static final Logger log = LoggerFactory.getLogger(PaymentChannelClientState.class);
    // How much value is currently allocated to us. Starts as being same as totalValue.
    protected Coin valueToMe;

    /**
     * The different logical states the channel can be in. The channel starts out as NEW, and then steps through the
     * states until it becomes finalized. The server should have already been contacted and asked for a public key
     * by the time the NEW state is reached.
     */
    public enum State {
        UNINITIALISED,
        NEW,
        INITIATED,
        WAITING_FOR_SIGNED_REFUND,
        SAVE_STATE_IN_WALLET,
        PROVIDE_MULTISIG_CONTRACT_TO_SERVER,
        READY,
        EXPIRED,
        CLOSED
    }
    protected final StateMachine<State> stateMachine;

    final Wallet wallet;

    // Both sides need a key (private in our case, public for the server) in order to manage the multisig contract
    // and transactions that spend it.
    final ECKey myKey, serverKey;

    // The id of this channel in the StoredPaymentChannelClientStates, or null if it is not stored
    protected StoredClientChannel storedChannel;

    PaymentChannelClientState(StoredClientChannel storedClientChannel, Wallet wallet) throws VerificationException {
        this.stateMachine = new StateMachine<>(State.UNINITIALISED, getStateTransitions());
        this.wallet = checkNotNull(wallet);
        this.myKey = checkNotNull(storedClientChannel.myKey);
        this.serverKey = checkNotNull(storedClientChannel.serverKey);
        this.storedChannel = storedClientChannel;
        this.valueToMe = checkNotNull(storedClientChannel.valueToMe);
    }

    /**
     * Returns true if the tx is a valid settlement transaction.
     */
    public synchronized boolean isSettlementTransaction(Transaction tx) {
        try {
            tx.verify();
            tx.getInput(0).verify(getContractInternal().getOutput(0));
            return true;
        } catch (VerificationException e) {
            return false;
        }
    }

    /**
     * Creates a state object for a payment channel client. It is expected that you be ready to
     * {@link PaymentChannelClientState#initiate(KeyParameter, ClientChannelProperties)} after construction (to avoid creating objects for channels which are
     * not going to finish opening) and thus some parameters provided here are only used in
     * {@link PaymentChannelClientState#initiate(KeyParameter, ClientChannelProperties)} to create the Multisig contract and refund transaction.
     *
     * @param wallet a wallet that contains at least the specified amount of value.
     * @param myKey a freshly generated private key for this channel.
     * @param serverKey a public key retrieved from the server used for the initial multisig contract
     * @param value how many satoshis to put into this contract. If the channel 