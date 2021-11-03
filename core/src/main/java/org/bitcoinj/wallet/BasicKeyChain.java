/*
 * Copyright 2013 Google Inc.
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

import org.bitcoinj.core.BloomFilter;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.crypto.*;
import org.bitcoinj.utils.ListenerRegistration;
import org.bitcoinj.utils.Threading;
import org.bitcoinj.wallet.listeners.KeyChainEventListener;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.protobuf.ByteString;
import org.bouncycastle.crypto.params.KeyParameter;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantLock;

import static com.google.common.base.Preconditions.*;

/**
 * A {@link KeyChain} that implements the simplest model possible: it can have keys imported into it, and just acts as
 * a dumb bag of keys. It will, left to its own devices, always return the same key for usage by the wallet, although
 * it will automatically add one to itself if it's empty or if encryption is requested.
 */
public class BasicKeyChain implements EncryptableKeyChain {
    private final ReentrantLock lock = Threading.lock("BasicKeyChain");

    // Maps used to let us quickly look up a key given data we find in transcations or the block chain.
    private final LinkedHashMap<ByteString, ECKey> hashToKeys;
    private final LinkedHashMap<ByteString, ECKey> pubkeyToKeys;
    @Nullable private final KeyCrypter keyCrypter;
    private boolean isWatching;

    private final CopyOnWriteArrayList<ListenerRegistration<KeyChainEventListener>> listeners;

    public BasicKeyChain() {
        this(null);
    }

    public BasicKeyChain(@Nullable KeyCrypter crypter) {
        this.keyCrypter = crypter;
        hashToKeys = new LinkedHashMap<>();
        pubkeyToKeys = new LinkedHashMap<>();
        listeners = new CopyOnWriteArrayList<>();
    }

    /** Returns the {@link KeyCrypter} in use or null if the key chain is not encrypted. */
    @Override
    @Nullable
    public KeyCrypter getKeyCrypter() {
        lock.lock();
        try {
            return keyCrypter;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public ECKey getKey(@Nullable KeyPurpose ignored) {
        lock.lock();
        try {
            if (hashToKeys.isEmpty()) {
                checkState(keyCrypter == null);   // We will refuse to encrypt an empty key chain.
                final ECKey key = new ECKey();
                importKeyLocked(key);
                queueOnKeysAdded(ImmutableList.of(key));
            }
            return hashToKeys.values().iterator().next();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<ECKey> getKeys(@Nullable KeyPurpose purpose, int numberOfKeys) {
        checkArgument(numberOfKeys > 0);
        lock.lock();
        try {
            if (hashToKeys.size() < numberOfKeys) {
                checkState(keyCrypter == null);

            