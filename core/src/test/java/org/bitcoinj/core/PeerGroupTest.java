/*
 * Copyright 2011 Google Inc.
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

package org.bitcoinj.core;

import com.google.common.base.Stopwatch;
import com.google.common.collect.*;
import com.google.common.net.*;
import com.google.common.util.concurrent.*;
import org.bitcoinj.core.listeners.*;
import org.bitcoinj.net.discovery.*;
import org.bitcoinj.testing.*;
import org.bitcoinj.utils.*;
import org.bitcoinj.wallet.Wallet;
import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.bitcoinj.core.Coin.*;
import static org.junit.Assert.*;


// TX announcement and broadcast is tested in TransactionBroadcastTest.

@RunWith(value = Parameterized.class)
public class PeerGroupTest extends TestWithPeerGroup {
    private static final int BLOCK_HEIGHT_GENESIS = 0;

    private BlockingQueue<Peer> connectedPeers;
    private BlockingQueue<Peer> disconnectedPeers;
    private PeerConnectedEventListener connectedListener = new PeerConnectedEventListener() {
        @Override
        public void onPeerConnected(Peer peer, int peerCount) {
            connectedPeers.add(peer);
        }
    };
    private PeerDisconnectedEventListener disconnectedListener = new PeerDisconnectedEventListener() {
        @Override
        public void onPeerDisconnected(Peer peer, int peerCount) {
            disconnectedPeers.add(peer);
        }
    };
    private PreMessageReceivedEventListener preMessageReceivedListener;
    private Map<Peer, AtomicInteger> peerToMessageCount;

  