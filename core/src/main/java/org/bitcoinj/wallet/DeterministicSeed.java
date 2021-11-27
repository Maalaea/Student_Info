/*
 * Copyright 2014 Google Inc.
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

import org.bitcoinj.core.Utils;
import org.bitcoinj.crypto.*;

import com.google.common.base.Charsets;
import com.google.common.base.Objects;
import com.google.common.base.Splitter;
import org.bouncycastle.crypto.params.KeyParameter;

import javax.annotation.Nullable;
import java.security.SecureRandom;
import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;
import static org.bitcoinj.core.Utils.HEX;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

/**
 * Holds the seed bytes for the BIP32 deterministic wallet algorithm, inside a
 * {@link DeterministicKeyChain}. The purpose of this wrapper is to simplify the encryption
 * code.
 */
public class DeterministicSeed implements EncryptableItem {
    // It would take more than 10^12 years to brute-force a 128 bit seed using $1B worth of comput