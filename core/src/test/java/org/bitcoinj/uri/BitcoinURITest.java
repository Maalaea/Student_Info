/*
 * Copyright 2012, 2014 the original author or authors.
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

package org.bitcoinj.uri;

import org.bitcoinj.core.Address;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.params.TestNet3Params;
import com.google.common.collect.ImmutableList;
import org.junit.Test;

import static org.bitcoinj.core.Coin.*;
import org.bitcoinj.core.NetworkParameters;
import static org.junit.Assert.*;

public class BitcoinURITest {
    private BitcoinURI testObject = null;

    private static final NetworkParameters MAINNET = MainNetParams.get();
    private static final String MAINNET_GOOD_ADDRESS = "1KzTSfqjF2iKCduwz59nv2uqh1W2JsTxZH";
    private static final String BITCOIN_SCHEME = MAINNET.getUriScheme();

    @Test
    public void testConvertToBitcoinURI() throws Exception {
        Address goodAddress = Address.fromBase58(MAINNET, MAINNET_GOOD_ADDRESS);
        
        // simple example
        assertEquals("bitcoin:" + MAINNET_GOOD_ADDRESS + "?amount=12.34&label=Hello&message=AMessage", BitcoinURI.convertToBitcoinURI(goodAddress, parseCoin("12.34"), "Hello", "AMessage"));
        
        // example with spaces, ampersand and plus
        assertEquals("bitcoin:" + MAINNET_GOOD_ADDRESS + "?amount=12.34&label=Hello%20World&message=Mess%20%26%20age%20%2B%20hope", BitcoinURI.convertToBitcoinURI(goodAddress, parseCoin("12.34"), "Hello World", "Mess & age + hope"));

        // no amount, label present, message present
        assertEquals("bitcoin:" + MAINNET_GOOD_ADDRESS + "?label=Hello&message=glory", BitcoinURI.convertToBitcoinURI(goodAddress, null, "Hello", "glory"));
        
        // amount present, no label, message present
        assertEquals("bitcoin:" + MAINNET_GOOD_ADDRESS + "?amount=0.1&message=glory", BitcoinURI.convertToBitcoinURI(goodAddress, parseCoin("0.1"), null, "glory"));
        assertEquals("bitcoin:" + MAINNET_GOOD_ADDRESS + "?amount=0.1&message=glory", BitcoinURI.convertToBitcoinURI(goodAddress, parseCoin("0.1"), "", "glory"));

        // amount present, label present, no message
        assertEquals("bitcoin:" + MAINNET_GOOD_ADDRESS + "?amount=12.34&label=Hello", BitcoinURI.convertToBitcoinURI(goodAddress, parseCoin("12.34"), "Hello", null));
        assertEquals("bitcoin:" + MAINNET_GOOD_ADDRESS + "?amount=12.34&label=Hello", BitcoinURI.convertToBitcoinURI(goodAddress, parseCoin("12.34"), "Hello", ""));
              
        // amount present, no label, no message
        assertEquals("bitcoin:" + MAINNET_GOOD_ADDRESS + "?amount=1000", BitcoinURI.convertToBitcoinURI(goodAddress, parseCoin("1000"), null, null));
        assertEquals("bitcoin:" + MAINNET_GOOD_ADDRESS + "?amount=1000", BitcoinURI.convertToBitcoinURI(goodAddress, parseCoin("1000"), "", ""));
        
        // no amount, label present, no message
        assertEquals("bitcoin:" + MAINNET_GOOD_ADDRESS + "?label=Hello", BitcoinURI.convertToBitcoinURI(goodAddress, null, "Hello", null));
        
        // no amount, no label, message present
        assertEquals("bitcoin:" + MAINNET_GOOD_ADDRESS + "?message=Agatha", BitcoinURI.convertToBitcoinURI(goodAddress, null, null, "Agatha"));
        assertEquals("bitcoin:" + MAINNET_GOOD_ADDRESS + "?message=Agatha", BitcoinURI.convertToBitcoinURI(goodAddress, null, "", "Agatha"));
      
        // no amount, no label, no message
        assertEquals("bitcoin:" + MAINNET_GOOD_ADDRESS, BitcoinURI.convertToBitcoinURI(goodAddress, null, null, null));
        assertEquals("bitcoin:" + MAINNET_GOOD_ADDRESS, BitcoinURI.convertToBitcoinURI(goodAddress, null, "", ""));

        // different scheme
        final NetworkParameters alternativeParameters = new MainNetParams() {
            @Override
            public String getUriScheme() {
                return "test";
            }
        };

        assertEquals("test:" + MAINNET_GOOD_ADDRESS + "?amount=12.34&label=Hello&message=AMessage",
             BitcoinURI.convertToBitcoinURI(Address.fromBase58(alternativeParameters, MAINNET_GOOD_ADDRESS), parseCoin("12.34"), "Hello", "AMessage"));
    }

    @Test
    public void testGood_Simple() throws BitcoinURIParseException {
        testObject = new BitcoinURI(MAINNET, BITCOIN_SCHEME + ":" + MAINNET_GOOD_ADDRESS);
        assertNotNull(testObject);
        assertNull("Unexpected amount", testObject.getAmount());
        assertNull("Unexpected label", testObject.getLabel());
        assertEquals("Unexpected label", 20, testObject.getAddress().getHash160().length);
    }

    /**
