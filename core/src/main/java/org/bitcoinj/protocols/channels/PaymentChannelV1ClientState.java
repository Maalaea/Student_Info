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

import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import org.bitcoinj.core.*;
import org.bitcoinj.crypto.TransactionSignature;
import org.bitcoinj.protocols.channels.IPaymentChannelClient.ClientChannelProperties;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.bitcoinj.script.ScriptException;
import org.bitcoinj.wallet.AllowUnconfirmedCoinSelector;
import org.bitcoinj.wallet.SendRequest;
import org.bitcoinj.wallet.Wallet;
import org.bouncycastle.crypto.params.KeyParameter;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.List;

import static com.google.common.base.Preconditions.*;

/**
 * Version 1 of the payment channel state machine - uses time locked multisig
 * contracts.
 */
public class PaymentChannelV1ClientState extends PaymentChannelClientState {
    private static final Logger log = LoggerFactory.getLogger(PaymentChannelV1ClientState.class);
    // How much value (in satoshis) is locked up into the channel.
    private final Coin totalValue;
    // When the channel will automatically settle in favor of the client, if the server halts before protocol termination
    // specified in terms of block timestamps (so it can off real time by a few hours).
    private final long expiryTime;

    // The refund is a time locked transaction that spends all the money of the channel back to the client.
    private Transaction refundTx;
    private Coin refundFees;
    // The multi-sig contract locks the value of the channel up such that the agreement of both parties is required
    // to spend it.
    private Transaction multisigContract;
    private Script multisigScript;

    PaymentChannelV1ClientState(StoredClientChannel storedClientChannel, Wallet wallet) throws VerificationException {
        super(storedClientChannel, wallet);
        // The PaymentChannelClientConnection handles storedClientChannel.active and ensures we aren't resuming channels
        this.multisigContract = checkNotNull(storedClientChannel.contract);
        this.multisigScript = multisigContract.getOutput(0).getScriptPubKey();
        this.refundTx = checkNotNull(storedClientChannel.refund);
        this.refundFees = checkNotNull(storedClientChannel.refundFees);
        this.expiryTime = refundTx.getLockTime();
        this.totalValue = multisigContract.getOutput(0).getValue();
        stateMachine.transition(State.READY);
        initWalletListeners();
    }

    /**
     * Creates a state object for a payment channel client. It is expected that you be ready to
     * {@link PaymentChannelClientState#initiate(KeyParameter, ClientChannelProperties)} after construction (to avoid creating objects for channels which are
     * not going to finish opening) and thus some parameters provided here are only used in
     * {@link PaymentChannelClientState#initiate(KeyParameter, ClientChannelProperties)} to create the Multisig contract and refund transaction.
     *
     * @param wallet a wallet that contains at least the specified amount of value.
     * @param myKey a freshly generated private key for this channel.
     * @param serverMultisigKey a public key retrieved from the server used for the initial multisig contract
     * @param value how many satoshis to put into this contract. If the channel reaches this limit, it must be closed.
     * @param expiryTimeInSeconds At what point (UNIX timestamp +/- a few hours) the channel will expire
     *
     * @throws VerificationException If either myKey's pubkey or serverKey's pubkey are non-canonical (ie invalid)
     */
    public PaymentChannelV1ClientState(Wallet wallet, ECKey myKey, ECKey serverMultisigKey,
                                       Coin value, long expiryTim