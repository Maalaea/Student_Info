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

import com.google.common.base.Objects;

import org.bitcoinj.net.discovery.*;
import org.bitcoinj.params.*;
import org.bitcoinj.script.*;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;

import org.bitcoinj.utils.MonetaryFormat;

import javax.annotation.*;
import java.io.*;
import java.math.*;
import java.util.*;

import static org.bitcoinj.core.Coin.*;
import org.bitcoinj.utils.VersionTally;

/**
 * <p>NetworkParameters contains the data needed for working with an instantiation of a Bitcoin chain.</p>
 *
 * <p>This is an abstract class, concrete instantiations can be found in the params package. There are four:
 * one for the main network ({@link MainNetParams}), one for the public test network, and two others that are
 * intended for unit testing and local app development purposes. Although this class contains some aliases for
 * them, you are encouraged to call the static get() methods on each specific params class directly.</p>
 */
public abstract class NetworkParameters {
    /**
     * The alert signing key originally owned by Satoshi, and now passed on to Gavin along with a few others.
     */
    public static final byte[] SATOSHI_KEY = Utils.HEX.decode("04fc9702847840aaf195de8442ebecedf5b095cdbb9bc716bda9110971b28a49e0ead8564ff0db22209e0374782c093bb899692d524e9d6a6956e7c5ecbcd68284");

    /** The string returned by getId() for the main, production network where people trade things. */
    public static final String ID_MAINNET = "org.bitcoin.production";
    /** The string returned by getId() for the testnet. */
    public static final String ID_TESTNET = "org.bitcoin.test";
    /** The string returned by getId() for the segnet. */
    public static final String ID_SEGNET = "org.bitcoin.segnet";
    /** The string returned by getId() for regtest mode. */
    public static final String ID_REGTEST = "org.bitcoin.regtest";
    /** Unit test network. */
    public static final String ID_UNITTESTNET = "org.bitcoinj.unittest";

    /** The string returned by getId() for elements regtest mode. */
    public 