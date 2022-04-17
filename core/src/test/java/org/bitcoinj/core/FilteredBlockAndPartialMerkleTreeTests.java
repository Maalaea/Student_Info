/*
 * Copyright 2012 Matt Corallo
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

import com.google.common.collect.*;
import org.bitcoinj.core.TransactionConfidence.*;
import org.bitcoinj.store.*;
import org.bitcoinj.testing.*;
import org.bitcoinj.wallet.*;
import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;

import java.math.*;
import java.util.*;

import static org.bitcoinj.core.Utils.*;
import static org.junit.Assert.*;

@RunWith(value = Parameterized.class)
public class FilteredBlockAndPartialMerkleTreeTests extends TestWithPeerGroup {
    @Parameterized.Parameters
    public static Collection<ClientType[]> parameters() {
        return Arrays.asList(new ClientType[] {ClientType.NIO_CLIENT_MANAGER},
                             new ClientType[] {ClientType.BLOCKING_CLIENT_MANAGER});
    }

    public FilteredBlockAndPartialMerkleTreeTests(ClientType clientType) {
        super(clientType);
    }

    @Before
    public void setUp() throws Exception {
        context = new Context(PARAMS);
        MemoryBlockStore store = new MemoryBlockStore(PARAMS);

        // Cheat and place the previous block (block 100000) at the head of the block store without supporting blocks
        store.put(new StoredBlock(new Block(PARAMS, HEX.decode("0100000050120119172a610421a6c3011dd330d9df07b63616c2cc1f1cd00200000000006657a9252aacd5c0b2940996ecff952228c3067cc38d4885efb5a4ac4247e9f337221b4d4c86041b0f2b5710")),
                BigInteger.valueOf(1), 100000));
        store.setChainHead(store.get(Sha256Hash.wrap("000000000003ba27aa200b1cecaad478d2b00432346c3f1f3986da1afd33e506")));

        KeyChainGroup group = new KeyChainGroup(PARAMS);
        group.importKeys(ECKey.fromPublicOnly(HEX.decode("04b27f7e9475ccf5d9a431cb86d665b8302c140144ec2397fce792f4a4e7765fecf8128534eaa71df04f93c74676ae8279195128a1506ebf7379d23dab8fca0f63")),
                ECKey.fromPublicOnly(HEX.decode("04732012cb962afa90d31b25d8fb0e32c94e513ab7a17805c14ca4c3423e18b4fb5d0e676841733cb83abaf975845c9f6f2a8097b7d04f4908b18368d6fc2d68ec")),
                ECKey.fromPublicOnly(HEX.decode("04cfb4113b3387637131ebec76871fd2760fc430dd16de0110f0eb07bb31ffac85e2607c189cb8582ea1ccaeb64ffd655409106589778f3000fdfe3263440b0350")),
                ECKey.fromPublicOnly(HEX.decode("04b2f30018908a59e829c1534bfa5010d7ef7f79994159bba0f534d863ef9e4e973af6a8de20dc41dbea50bc622263ec8a770b2c9406599d39e4c9afe61f8b1613")));
        wallet = new Wallet(PARAMS, group);

        super.setUp(store);
    }

    @After
    public void tearDown() {
        super.tearDown();
    }

    @Test
    public void deserializeFilteredBlock() throws Exception {
        // Random real block (000000000000dab0130bbcc991d3d7ae6b81aa6f50a798888dfe62337458dc45)
        // With one tx
        FilteredBlock block = new FilteredBlock(PARAMS, HEX.decode("0100000079cda856b143d9db2c1caff01d1aecc8630d30625d10e8b4b8b0000000000000b50cc069d6a3e33e3ff84a5c41d9d3febe7c770fdcc96b2c3ff60abe184f196367291b4d4c86041b8fa45d630100000001b50cc069d6a3e33e3ff84a5c41d9d3febe7c770fdcc96b2c3ff60abe184f19630101"));
        
        // Check that the header was properly deserialized
        assertTrue(block.getBlockHeader().getHash().equals(Sha256Hash.wrap("000000000000dab0130bbcc991d3d7ae6b81aa6f50a798888dfe62337458dc45")));
        
        // Check that the partial merkle tree is correct
        List<Sha256Hash> txesMatched = block.getTransactionHashes();
        assertTrue(txesMatched.size() == 1);
        assertTrue(txesMatched.contains(Sha256Hash.wrap("63194f18be0af63f2c6bc9dc0f777cbefed3d9415c4af83f3ee3a3d669c00cb5")));

        // Check round tripping.
        assertEquals(block, new FilteredBlock(PARAMS, block.bitcoinSerialize()));
    }

    @Test
    public void createFilteredBlock() throws Exception {
        ECKey key1 = new ECKey();
        ECKey key2 = new ECKey();
        Transaction tx1 = FakeTxBuilder.createFakeTx(PARAMS, Coin.COIN,  key1);
        Transaction tx2 = FakeTxBuilder.createFakeTx(PARAMS, Coin.FIFTY_COINS, key2.toAddress(PARAMS));
        Block block = FakeTxBuilder.makeSolvedTestBlock(PARAMS.getGenesisBlock(), Address.fromBase58(PARAMS, "msg2t2V2sWNd85LccoddtWysBTR8oPnkzW"), tx1, t