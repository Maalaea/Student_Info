/*
 * Copyright 2013 Matija Mazi.
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

package org.bitcoinj.crypto;

import java.util.Locale;

import com.google.common.primitives.Ints;

/**
 * <p>This is just a wrapper for the i (child number) as per BIP 32 with a boolean getter for the most significant bit
 * and a getter for the actual 0-based child number. A {@link java.util.List} of these forms a <i>path</i> through a
 * {@link DeterministicHierarchy}. This class is immutable.
 */
public class ChildNumber implements Comparable<ChildNumber> {
    /**
     * The bit that's set in the child number to indicate whether this key is "hardened". Given a hardened key, it is
     * not possible to derive a child public key if you know only the hardened public key. With a non-hardened key this
     * is possible, so you can derive trees of public keys given only a public parent, but the downside is that it's
     * possible to leak private keys if you disclose a parent public key and a child private key (elliptic curve maths
     * allows you to work upwards).
     */
    public static final int HARDENED_BIT = 0x80000000;

    public static final ChildNumber 