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
import org.bitcoinj.script.*;
import org.bitcoinj.wallet.Wallet;
import org.slf4j.*;

import javax.annotation.*;
import java.io.*;
import java.util.*;

import static com.google.common.base.Preconditions.*;

/**
 * <p>A TransactionOutput message contains a scriptPubKey that controls who is able to spend its value. It is a sub-part
 * of the Transaction message.</p>
 * 
 * <p>Instances of this class are not safe for use by multiple threads.</p>
 */
public class TransactionOutput extends ChildMessage {
    private static final Logger log = LoggerFactory.getLogger(TransactionOutput.class);

    // The output's value is kept as a native type in order to save class instances.
    protected long value;

    // A transaction output has a script used for authenticating that the redeemer is allowed to spend
    // this output.
    public byte[] scriptBytes;

    // The script bytes are parsed and turned into a Script on demand.
    private Script scriptPubKey;

    // These fields are not Bitcoin serialized. They are used for tracking purposes in our wallet
    // only. If set to true, 