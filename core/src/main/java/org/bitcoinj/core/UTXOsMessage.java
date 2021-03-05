/*
 * Copyright 2014 the bitcoinj authors
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
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * <p>Message representing a list of unspent transaction outputs ("utxos"), returned in response to sending a
 * {@link GetUTXOsMessage} ("getutxos"). Note that both this message and the query that generates it are not
 * supported by Bitcoin Core. An implementation is available in <a href="https://github.com/bitcoinxt/bitcoinxt">Bitcoin XT</a>,
 * a patch set on top of Core. Thus if you want to use it, you must find some XT peers to connect to. This can be