/*
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

import static org.bitcoinj.core.Coin.*;
import static org.bitcoinj.core.NetworkParameters.MAX_MONEY;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import org.junit.Test;

public class CoinTest {

    @Test
    public void testParseCoin() {
        // String version
        assertEquals(CENT, parseCoin("0.01"));
        assertEquals(CENT, parseCoin("1E-2"));
        assertEquals(COIN.add(CENT), parseCoin("1.01"));
        assertEquals(COIN.negate(), parseCoin("-1"));
        try {
            parseCoin("2E-20");
            org.junit.Assert.fail("should not have accepted fractional satoshis");
        } catch (IllegalArgumentException expected) {
        } catch (Exception e) {
            org.junit.Assert.fail("should throw IllegalArgumentException");
        }
        assertEquals(1, parseCoin("0.00000001").value);
        assertEquals(1, parseCoin("0.000000010").value);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParseCoinOverprecise() {
        parseCoin("0.000000011");
    }

    @Test
    public void testParseCoinInexact() {
        assertEquals(1, parseCoinInexact("0.00000001").value);
        assertEquals(1, parseCoinInexact("0.000000011").value);
    }

    @Test
    public void testValueOf() {
        // int version
        assertEquals(CENT, valueOf(0, 1));
        assertEquals(SATOSHI, valueOf(1));
        assertEquals(NEGATIVE_SATOSHI, valueOf(-1));
        assertEquals(MAX_MONEY, valueOf(MAX_MONEY.value));
        assertEquals(MAX_MONEY.negate(), valueOf(MAX_MONEY.value * -1));
        valueOf(MAX_MONEY.value + 1);
        valueOf((MAX_MONEY.value * -1) - 1);
        valueOf(Long.M