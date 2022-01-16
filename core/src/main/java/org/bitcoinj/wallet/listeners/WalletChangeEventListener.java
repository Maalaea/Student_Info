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

package org.bitcoinj.wallet.listeners;

import org.bitcoinj.wallet.Wallet;

/**
 * <p>Implementors are called when the contents of the wallet changes, for instance due to receiving/sending money
 * or a block chain re-organize. It may be convenient to derive from {@link AbstractWalletEventListener} instead.</p>
 */
public interface WalletChangeEventListener {
    /**
     * <p>Designed for GUI applications to refresh their transaction lists. This callback is invoked in the following
     * situations:</p>
     *
     * <ol>
     *     <li>A new block is received (and thus building transactions got more confidence)</li>
     *     <li>A pending transaction is received</li>
     *     <li>A pending transaction changes confidence due to some non-new-block related event, such as being
     *     announced by more peers or by  a double-spend conflict being observed.</li>
    