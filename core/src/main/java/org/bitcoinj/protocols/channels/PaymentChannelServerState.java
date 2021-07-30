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

import org.bitcoinj.wallet.SendRequest;
import org.bitcoinj.wallet.Wallet;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Multimap;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import org.bitcoinj.core.*;
import org.bitcoinj.crypto.TransactionSignature;
import org.bitcoinj.script.Script;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.bouncycastle.crypto.params.KeyParameter;

import javax.annotation.Nullable;

import java.util.Arrays;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

/**
 * <p>A payment channel is a method of sending money to someone such that the amount of money you send can be adjusted
 * after the fact, in an efficient manner that does not require broadcasting to the network. This can be used to
 * implement micropayments or other payment schemes in which immediate settlement is not required, but zero trust
 * negotiation is. Note that this class only allows the amount of money received to be incremented, not decremented.</p>
 *
 * <p>There are two subclasses that implement this one, for versions 1 and 2 of the protocol -
 * {@link PaymentChannelV1ServerState} and {@link PaymentChannelV2ServerState}.</p>
 *
 * <p>This class implements the core state machine for the server side of the protocol. The client side is implemented
 * by {@link PaymentChannelV1ClientState} and {@link PaymentChannelServerListener} implements the server-side network
 * protocol listening for TCP/IP connections and moving this class through each state. We say that the party who is
 * sending funds is the <i>client</i> or <i>initiating party</i>. The party that is receiving the funds is the
 * <i>server</i> or <i>receiving party</i>. Although the underlying Bitcoin protocol is capable of more complex
 * relationships than that, this class implements only the simplest case.</p>
 *
 * <p>To protect clients from malicious servers, a channel has an expiry parameter. When this expiration is reached, the
 * client will broadcast the created refund  transaction and take back all the money in this channel. Because this is
 * specified in terms of block timestamps, it is fairly fuzzy and it is possible to spend the refund transaction up to a
 * few hours before the actual timestamp. Thus, it is very important that the channel be closed with plenty of time left
 * to get the highest value payment transaction confirmed before the expire time (minimum 3-4 hours is suggested if the
 * payment transaction has enough fee to be confirmed in the next block or two).</p>
 *
 * <p>To begin, we must provide the client with a pubkey which we wish to use for the multi-sig contract which locks in
 * the channel. The client will then provide us with an incomplete refund transaction and the pubkey which they used in
 * the multi-sig contract. We use this pubkey to recreate the multi-sig output and then sign that to the refund
 * transaction. We provide that signature to the client and they then have the ability to spend the refund transaction
 * at the specified expire time. The client then provides us with the full, signed multi-sig contract which we verify
 * and broadcast, locking in their funds until we spend a payment transaction or the expire time is reached. The client
 * can then begin paying by providing us with signatures for the multi-sig contract which pay some amount back to the
 * client, and the rest is ours to do with as we wish.</p>
 */
public abstract class PaymentChannelServerState {
    private static final Logger log = LoggerFactory.getLogger(PaymentChannelServerState.class);

    /**
     * The different logical states the channel can be in. Because the first action we need to track is the client
     * providing the refund transaction, we begin in WAITING_FOR_REFUND_TRANSACTION. We then step through the states
     * until READY, at which time the client can increase payment incrementally.
     */
    public enum State {
        UNINITIALISED,
        WAITING_FOR_REFUND_TRANSACTION,
        WAITING_FOR_MULTISIG_CONTRACT,
        WAITING_FOR_MULTISIG_ACCEPTANCE,
        READY,
        CLOSING,
        CLOSED,
        ERROR,
    }

    protected StateMachine<State> stateMachine;

    // Package-local for checkArguments in StoredServerChannel
    final Wallet wallet;

    // The object that will broadcast transactions for us - usually a peer group.
    protected final TransactionBroadcaster broadcaster;

    // The last signature the client provided for a payment transaction.
    protected byte[] bestValueSignature;

    protected Coin bestValueToMe = Coin.ZERO;

    // The server key for the multi-sig contract
    // We currently also use the serverKey for payouts, but this is not required
    protected ECKey serverKey;

    protected long minExpireTime;

    protected StoredServerChannel storedServerChannel = null;

    // The contract and the output script from it
    protected Transaction contract = null;

    PaymentChannelServerState(StoredServerChannel storedServerChannel, Wallet wallet, TransactionBroadcaster broadcaster) throws VerificationException {
        synchronized (storedServerChannel) {
            this.stateMachine = new StateMachine<>(State.UNINITIALISED, getStateTransitions());
            this.wallet = checkNotNull(wallet);
            this.broadcaster = checkNotNull(broadcaster);
            this.contract = checkNotNull(storedServerChannel.contract);
            this.serverKey = checkNotNull(storedServerChannel.myKey);
            this.storedServerChannel = storedServerChannel;
            this.bestValueToMe = checkNotNull(storedServerChannel.bestValueToMe);
            this.minExpireTime = storedServerChannel.refundTransactionUnlockTimeSecs;
            this.bestValueSignature = storedServerChannel.bestValueSignature;
            checkArgument(bestValueToMe.equals(Coin.ZERO) || bestValueSignature != null);
            storedServerChannel.state = this;
        }
    }

    /**
     * Creates a new state object to track the server side of a payment channel.
     *
     * @param broadcaster The peer group which we will broadcast transactions to, this should have multiple peers
     * @param wallet The wallet which will be used to complete transactions
     * @param serverKey The private key which we use for our part of the multi-sig contract
     *                  (this MUST be fresh and CANNOT be used elsewhere)
     * @param minExpireTime The earliest time at which the client can claim the refund transaction (UNIX timestamp of block)
     */
    public PaymentChannelServerState(TransactionBroadcaster broadcaster, Wallet wallet, ECKey serverKey, long minExpireTime) {
        this.stateMachine = new StateMachine<>(State.UNINITIALISED, getStateTransitions());
        this.serverKey = checkNotNull(serverKey);
        this.wallet = checkNotNull(wallet);
        this.broadcaster = checkNotNull(broadcaster);
        this.minExpireTime = minExpireTime;
    }

    public abstract int getMajorVersion();

    public synchronized State getState() {
        return stateMachine.getState();
    }

    protected abstract Multimap<State, State> getStateTransitions();

    /**
     * Called when the client provides the multi-sig contract.  Checks that the previously-provided refund transaction
     * spends this transaction (because we will use it as a base to create payment transactions) as well as output value
     * and form (ie it is a 2-of-2 multisig to the correct keys).
     *
     * @param contract The provided multisig contract. Do not mutate this object after this call.
     * @return A future which completes when the provided multisig contract successfully broadcasts, or throws if the broadcast fails for some reason
     *         Note that if the network simply rejects the transaction, this future will never complete, a timeout should be used.
     * @throws VerificationException If the provided multisig contract is not well-formed or does not meet previously-specified parameters
     */
    public synchronized ListenableFuture<PaymentChannelServerState> provideContract(final Transaction contract) throws VerificationException {
        checkNotNull(contract);
        stateMachine.checkState(State.WAITING_FOR_MULTISIG_CONTRACT);
        try {
            contract.verify();
            this.contract = contract;
            verifyContract(contract);

            // Check that contract's first output is a 2-of-2 multisig to the correct pubkeys in the correct order
            final Script expectedScript = createOutputScript();
            if (!Arrays.equals(getContractScript().getProgram(), expectedScript.getProgram()))
                throw new VerificationException(getMajorVersion() == 1 ?
                        "Contract's first output was not a standard 2-of-2 multisig to client and server in that order." :
                        "Contract was not a P2SH script of a CLTV redeem script to client and server");

            if (getTotalValue().signum() <= 0)
                throw new VerificationException("Not accepting an attempt to open a contract with zero value.");
        } catch (VerificationException e) {
            // We couldn't parse the multisig transaction or its output.
            log.error("Provided multisig contract did not verify: {}", contract.toString());
            throw e;
        }
        log.info("Broadcasting multisig contract: {}", contract);
        wallet.addWatchedScripts(ImmutableList.of(contract.getOutput(0).getScriptPubKey()));
        stateMachine.transition(State.WAITING_FOR_MULTISIG_ACCEPTANCE);
        final SettableFuture<PaymentChannelServerState> future = SettableFuture.create();
        Futures.addCallback(broadcaster.broadcastTransaction(contract).future(), new FutureCallback<Transaction>() {
            @Override public void onSuccess(Transaction transaction) {
                log.info("Successfully broadcast multisig contract {}. Channel now open.", transaction.getHashAsStrin