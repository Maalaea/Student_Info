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

import com.google.common.collect.*;
import org.bitcoinj.core.*;
import org.bitcoinj.crypto.TransactionSignature;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.bitcoinj.wallet.SendRequest;
import org.bitcoinj.wallet.Wallet;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.bouncycastle.crypto.params.KeyParameter;

import javax.annotation.Nullable;
import java.util.Locale;

import static com.google.common.base.Preconditions.*;

/**
 * Version 1 of the payment channel server state object. Common functionality is
 * present in the parent class.
 */
public class PaymentChannelV1ServerState extends PaymentChannelServerState {
    private static final Logger log = LoggerFactory.getLogger(PaymentChannelV1ServerState.class);

    // The total value locked into the multi-sig output and the value to us in the last signature the client provided
    private Coin feePaidForPayment;

    // The client key for the multi-sig contract
    // We currently also use the serverKey for payouts, but this is not required
    protected ECKey clientKey;

    // The refund/change transaction output that goes back to the client
    private TransactionOutput clientOutput;
    private long refundTransactionUnlockTimeSecs;

    PaymentChannelV1ServerState(StoredServerChannel storedServerChannel, Wallet wallet, TransactionBroadcaster broadcaster) throws VerificationException {
        super(storedServerChannel, wallet, broadcaster);
        synchronized (storedServerChannel) {
            this.clientKey = ECKey.fromPublicOnly(getContractScript().getChunks().get(1).data);
            this.clientOutput = checkNotNull(storedServerChannel.clientOutput);
            this.refundTransactionUnlockTimeSecs = storedServerChannel.refundTransactionUnlockTimeSecs;
            stateMachine.transition(State.READY);
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
    public PaymentChannelV1ServerState(TransactionBroadcaster broadcaster, Wallet wallet, ECKey serverKey, long minExpireTime) {
        super(broadcaster, wallet, serverKey, minExpireTime);
        stateMachine.transition(State.WAITING_FOR_REFUND_TRANSACTION);
    }

    @Override
    public Multimap<State, State> getStateTransitions() {
        Multimap<State, State> result = MultimapBuilder.enumKeys(State.class).arrayListValues().build();
        result.put(State.UNINITIALISED, State.READY);
        result.put(State.UNINITIALISED, State.WAITING_FOR_REFUND_TRANSACTION);
        result.put(State.WAITING_FOR_REFUND_TRANSACTION, State.WAITING_FOR_MULTISIG_CONTRACT);
        result.put(State.WAITING_FOR_MULTISIG_CONTRACT, State.WAITING_FOR_MULTISIG_ACCEPTANCE);
        result.put(State.WAITING_FOR_MULTISIG_ACCEPTANCE, State.READY);
        result.put(State.READY, State.CLOSING);
        result.put(State.CLOSING, State.CLOSED);
        for (State state : State.values()) {
            result.put(state, State.ERROR);
        }
        return result;
    }

    @Override
    public int getMajorVersion() {
        return 1;
    }

    @Override
    public TransactionOutput getClientOutput() {
        return clientOutput;
    }

    @Override
    protected Sc