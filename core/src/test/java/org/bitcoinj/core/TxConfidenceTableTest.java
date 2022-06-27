/*
 * Copyright 2012 Google Inc.
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

import org.bitcoinj.params.*;
import org.bitcoinj.testing.*;
import org.bitcoinj.utils.*;
import org.junit.*;

import java.net.*;

import static org.bitcoinj.core.Coin.*;
import static org.junit.Assert.*;

public class TxConfidenceTableTest {
    private static final NetworkParameters PARAMS = UnitTestParams.get();
    private Transaction tx1, tx2;
    private PeerAddress address1, address2, address3;
    private TxConfi