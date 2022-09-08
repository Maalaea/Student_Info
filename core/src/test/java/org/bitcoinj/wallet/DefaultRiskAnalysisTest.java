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

package org.bitcoinj.wallet;

import com.google.common.collect.*;
import org.bitcoinj.core.*;
import org.bitcoinj.crypto.*;
import org.bitcoinj.params.*;
import org.bitcoinj.script.*;
import org.bitcoinj.testing.FakeTxBuilder;
import org.bitcoinj.wallet.DefaultRiskAnalysis.*;
import org.junit.*;

import java.util.*;

import static org.bitcoinj.core.Coin.*;
import static org.bitcoinj.script.ScriptOpCodes.*;
import static org.junit.Assert.*;

public class DefaultRiskAnalysisTest {
    // Uses mainnet because isStandard checks are disabled on testnet.
    private static final NetworkParameters PARAMS = MainNetParams.get();
    private Wallet wallet;
    private final int TIMESTAMP = 1384190189;
    private static final ECKey key1 = new ECKey();
    private final ImmutableList<Transaction> NO_DEPS = ImmutableList.of();

    @Before
    public void setup() {
        wallet = new Wallet(new Context(PARAMS)) {
            @Override
            public int getLastBlockSeenHeight() {
                return 1000;
            }

            @Override
            public long getLastBlockSeenTimeSecs() {
                return TIMESTAMP;
            }
        };
    }

    @Test(expected = IllegalStateException.class)
    public void a