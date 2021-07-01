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

package org.bitcoinj.protocols.channels;

import org.bitcoinj.core.*;
import org.bitcoinj.protocols.channels.PaymentChannelCloseException.CloseReason;
import org.bitcoinj.utils.Threading;
import org.bitcoinj.wallet.SendRequest;
import org.bitcoinj.wallet.Wallet;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import com.google.protobuf.ByteString;
import net.jcip.annotations.GuardedBy;
import org.bitcoin.paymentchannel.Protos;
import org.slf4j.LoggerFactory;
import org.bouncycastle.crypto.params.KeyParameter;

import javax.annotation.Nullable;
import java.util.concurrent.locks.ReentrantLock;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

/**
 * <p>A class which handles most of the complexity of creating a payment channel connection by providing a
 * simple in/out interface which is provided with protobufs from the server and which generates protobufs which should
 * be sent to the server.</p>
 *
 * <p>Does all required verification of server messages and properly stores state objects in the wallet-attached
 * {@link StoredPaymentChannelClientStates} so that they are automatically closed when necessary and refund
 * transactions are not lost if the application crashes before it unlocks.</p>
 *
 * <p>Though this interface is largely designed with stateful protocols (eg simple TCP connections) in mind, it is also
 * possible to use it with stateless protocols (eg sending protobufs when required over HTTP headers). In this case, the
 * "connection" translates roughly into the server-client relationship. See the javadocs for specific functions for more
 * details.</p>
 */
public class PaymentChannelClient implements IPaymentChannelClient {
    private static final org.slf4j.Logger log = LoggerFactory.getLogger(PaymentChannelClient.class);

    protected final ReentrantLock lock = Threading.lock("channelclient");
    protected final ClientChannelProperties clientChannelProperties;

    // Used to track the negotiated version number
    @GuardedBy("lock") private int majorVersion;

    @GuardedBy("lock") private final ClientConnection conn;

    // Used to keep track of whether or not the "socket" ie connection is open and we can generate messages
    @VisibleForTesting @GuardedBy("lock") boolean connectionOpen = false;

    // The state object used to step through initialization and pay the server
    @GuardedBy("lock") private PaymentChannelClientState state;

    // The step we are at in initialization, this is partially duplicated in the state object
    private enum InitStep {
        WAITING_FOR_CONNECTION_OPEN,
        WAITING_FOR_VERSION_NEGOTIATION,
        WAITING_FOR_INITIATE,
        WAITING_FOR_REFUND_RETURN,
        WAITING_FOR_CHANNEL_OPEN,
        CHANNEL_OPEN,
        WAITING_FOR_CHANNEL_CLOSE,
        CHANNEL_CLOSED,
    }
    @GuardedBy("lock") private InitStep step = InitStep.WAITING_FOR_CONNECTION_OPEN;

    public enum VersionSelector {
        VERSION_1,
        VERSION_2_ALLOW_1,
        VERSION_2;

        public int getRequestedMajorVersion() {
            switch (this) {
                case VERSION_1:
                    return 1;
                case VERSION_2_ALLOW_1:
                case VERSION_2:
                default:
                    return 2;
            }
        }

        public int getRequestedMinorVersion() {
            return 0;
        }

        public boolean isServerVersionAccepted(int major, int minor) {
            switch (this) {
                case VERSION_1:
                    return major == 1;
                case VERSION_2_ALLOW_1:
                    return major == 1 || major == 2;
                case VERSION_2:
                    return major == 2;
                default:
                    return false;
            }
        }
    }

    private final VersionSelector versionSelector;

    // Will either hold the StoredClientChannel of this channel or null after connectionOpen
    private StoredClientChannel storedChannel;
    // An arbitrary hash which identifies this channel (specified by the API user)
    private final Sha256Hash serverId;

    // The wallet associated with this channel
    private final Wallet wallet;

    // Information used during channel initialization to send to the server or check what the server sends to us
    private final ECKey myKey;
    private final Coin maxValue;

    private Coin missing;

    // key to decrypt myKey, if it is encrypted, during setup.
    private KeyParameter userKeySetup;

    private final long timeWindow;

    @GuardedBy("lock") private long minPayment;

    @GuardedBy("lock") SettableFuture<PaymentIncrementAck> increasePaymentFuture;
    @GuardedBy("lock") Coin lastPaymentActualAmount;

    /**
     * <p>The default maximum amount of time for which we will accept the server locking up our funds for the multisig
     * contract.</p>
     *
     * <p>24 hours less a minute  is the default as it is expected that clients limit risk exposure by limiting channel size instead of
     * limiting lock time when dealing with potentially malicious servers.</p>
     */
    public static final long DEFAULT_TIME_WINDOW = 24*60*60-60;

    /**
     * Constructs a new channel manager which waits for {@link PaymentChannelClient#connectionOpen()} before acting.
     * A default time window of {@link #DEFAULT_TIME_WINDOW} will be used.
     *
     * @param wallet The wallet which will be paid from, and where completed transactions will be committed.
     *               Must already have a {@link StoredPaymentChannelClientStates} object in its extensions set.
     * @param myKey A freshly generated keypair used for the multisig contract and refund output.
     * @param maxValue The maximum value the server 