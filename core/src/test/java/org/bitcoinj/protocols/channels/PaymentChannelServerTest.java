/*
 * Copyright by the original author or authors.
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

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.TransactionBroadcaster;
import org.bitcoinj.core.Utils;
import org.bitcoinj.wallet.Wallet;
import org.bitcoin.paymentchannel.Protos;
import org.easymock.Capture;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.Collection;

import static junit.framework.TestCase.assertTrue;
import static org.bitcoin.paymentchannel.Protos.TwoWayChannelMessage;
import static org.bitcoin.paymentchannel.Protos.TwoWayChannelMessage.MessageType;
import static org.easymock.EasyMock.*;
import static org.junit.Assert.assertEquals;

@RunWith(Parameterized.class)
public class PaymentChannelServerTest {
    public Wallet wallet;
    public PaymentChannelServer.ServerConnection connection;
    public PaymentChannelServer dut;
    public Capture<? extends TwoWayChannelMessage> serverVersionCapture;
    private TransactionBroadcaster broadcaster;

    @Before
    public void setUp() {
        broadcaster = createMock(TransactionBroadcaster.class);
        wallet = createMock(Wallet.class);
        connection = createMock(PaymentChannelServer.ServerConnection.class);
        serverVersionCapture = new Capture<TwoWayChannelMessage>();
        connection.sendToClient(capture(serverVersionCapture));
        Utils.setMockClock();
    }

    /**
     * We use parameterized tests to run the client channel tests with each
     * version of the channel.
     */
    @Parameterized.Parameters(name = "{index}: PaymentChannelServerTest(version {0})")
    public static Collection<Integer> data() {
        return Arrays.asList(1, 2);
    }

    @Parameterized.Parameter
    public int protocolVersion;

    @Test
    public void shouldAcceptDefaultTimeWindow() {
        final TwoWayChannelMessage message = createClientVersionMessage();
        final Capture<TwoWayChannelMessage> initiateCapture = new Capture<>();
        connection.sendToClient(capture(initiateCapture));
        replay(connection);

        dut = new PaymentChannelServer(broadcaster, wallet, Coin.CENT, connection);

        dut.connectionOpen();
        dut.receiveMessage(message);

        long expectedExpire = Utils.currentTimeSeconds() + 24 * 60 * 60 - 60;  // This the default defined in paymentchannel.proto
        assertServerVersion();
        assertExpireTime(expectedExpire, initiateCapture);
    }

    @Test
    public void shouldTruncateTooSmallTimeWindow() {
        final int minTimeWindow = 20000;
        final int timeWindow = minTimeWindow - 1;
        final TwoWayChannelMessage message = createClientVersionMessage(timeWindow);
        final Capture<TwoWayChannelMessage> initiateCapture = new Capture<>();
        connection.sendToClient(capture(initiateCapture));

        replay(connection);
        dut = new PaymentChannelServer(broadcaster, wallet, Coin.CENT, new PaymentChannelServer.DefaultServerChannelProperties() {
            @Override
            public long getMinTimeWindow() {
                return minTimeWindow;
            }
            @Override
            public long getMaxTimeWindow() {
                return 40000;
            }
        }, connection);

        dut.connectionOpen();
        dut.receiveMessage(message);

        long expectedExpire = Utils.currentTimeSeconds() + minTimeWindow;
        assertServerVersion();
        assertExpireTime(expectedExpire, initiateCapture);
    }

    @Test
    public void shouldTruncateTooLargeTimeWindow() {
        final in