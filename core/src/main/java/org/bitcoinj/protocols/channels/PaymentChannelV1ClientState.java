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
    private Coin refundF