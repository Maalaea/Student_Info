/*
 * Copyright 2013 Matija Mazi.
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

package org.bitcoinj.crypto;

import org.bitcoinj.core.*;
import org.bitcoinj.params.*;
import org.junit.*;
import org.bouncycastle.crypto.params.*;

import static org.bitcoinj.core.Utils.*;
import static org.junit.Assert.*;

import java.util.List;
import java.util.ArrayList;

/**
 * This test is adapted from Armory's BIP 32 tests.
 */
public class ChildKeyDerivationTest {
    private static final int HDW_CHAIN_EXTERNAL = 0;
    private static final int HDW_CHAIN_INTERNAL = 1;

    @Test
    public void testChildKeyDerivation() throws Exception {
        String[] ckdTestVectors = {
                // test case 1:
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa