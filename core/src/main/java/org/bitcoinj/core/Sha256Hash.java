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

import com.google.common.io.ByteStreams;
import com.google.common.primitives.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.Serializable;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * A Sha256Hash just wraps a byte[] so that equals and hashcode work correctly, allowing it to be used as keys in a
 * map. It also checks that the length is correct and provides a bit more type safety.
 */
public class Sha256Hash implements Serializable, Comparable<Sha256Hash> {
    public static final int LENGTH = 32; // bytes
    public static final Sha256Hash ZERO_HASH = wrap(new byte[LENGTH]);

    private final byte[] bytes;

    /**
     * Use {@link #wrap(byte[])} instead.
     */
    @Deprecated
    public Sha256Hash(byte[] rawHashBytes) {
        checkArgument(rawHashBytes.length == LENGTH);
        this.bytes = rawHashBytes;
    }

    /**
     * Use {@link #wrap(String)} instead.
     */
    @Deprecated
    public Sha256Hash(String hexString) {
        checkArgument(hexString.length() == LENGTH * 2);
        this.bytes = Utils.HEX.decode(hexString);
    }

    /**
     * Creates a new instance that wraps the given hash value.
     *
     * @param rawHashBytes the raw hash bytes to wrap
     * @return a new instance
     * @throws IllegalArgumentException if the given array length is not exactly 32
     */
    @SuppressWarnings("deprecation") // the constructor will be made private in the future
    public static Sha256Hash wrap(byte[] rawHashBytes) {
        return new Sha256Hash(rawHashBytes);
    }

    /**
     * Creates a new instance that wraps the given hash value (represented as a hex string).
     *
     * @param hexString a hash value represented as a hex string
     * @return a new instance
     * @throws IllegalArgumentException if the given string is not a valid
     *         hex string, or if it does not represent exactly 32 bytes
     */
    public static Sha256Hash wrap(String hexString) {
        return wrap(Utils.HEX.decode(hexString));
    }

    /**
     * Creates a new instance that wraps the given hash value, but with byte order reversed.
     *
     * @param rawHashBytes the raw hash bytes to wrap
     * @return a new instance
     * @throws IllegalArgumentException if the given array length is not exactly 32
     */
    @SuppressWarnings("deprecation") // the constructor will be made private in the future
    public static Sha256Hash wrapReversed(byte[] rawHashBytes) {
        return wrap(Utils.reverseBytes(rawHashBytes));
    }

    /** Use {@link #of(byte[])} instead: this old name is ambiguous. */
    @Deprecated
    public static Sha256Hash create(byte[] contents) {
        return of(contents);
    }

    /**
     * Creates a new instance containing the calculated (one-time) hash of the given bytes.
     *
     * @param contents the bytes on which the hash value is calculated
     * @return a new instance containing the calculated (one-time) hash
     */
    public static Sha256Hash of(byte[] contents) {
        return wrap(hash(contents));
    }

    /** Use {@link #twiceOf(byte[])} instead: this old name is ambiguous. */
    @Deprecated
    public static Sha256Hash createDouble(byte[] contents) {
        return twiceOf(contents);
    }

    /**
     * Creates a new instance containing the hash of the calculated hash of the given bytes.
     *
     * @param contents the bytes on which the hash value is calculated
     * @return a new instance containing the calculated (two-time) hash
     */
    public static Sha256Hash twiceOf(byte[] contents) {
        return wrap(hashTwice(contents));
    }

    /**
     * Creates a new instance containing the calculated (one-time) hash of the given file's contents.
     *
     * The file contents are read fully into memory, so this method should only be used with small files.
     *
     * @param file the file on which the hash value is calculated
     * @return a new instance containing the calculated (one-time) hash
     * @throws IOException if an error occurs while reading the file
     */
    public static Sha256Hash of(File file) throws IOException {
        FileInput