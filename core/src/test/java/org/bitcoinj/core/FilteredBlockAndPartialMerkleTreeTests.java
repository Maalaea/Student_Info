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
        Block block = FakeTxBuilder.makeSolvedTestBlock(PARAMS.getGenesisBlock(), Address.fromBase58(PARAMS, "msg2t2V2sWNd85LccoddtWysBTR8oPnkzW"), tx1, tx2);
        BloomFilter filter = new BloomFilter(4, 0.1, 1);
        filter.insert(key1);
        filter.insert(key2);
        FilteredBlock filteredBlock = filter.applyAndUpdate(block);
        assertEquals(4, filteredBlock.getTransactionCount());
        // This call triggers verification of the just created data.
        List<Sha256Hash> txns = filteredBlock.getTransactionHashes();
        assertTrue(txns.contains(tx1.getHash()));
        assertTrue(txns.contains(tx2.getHash()));
    }

    private Sha256Hash numAsHash(int num) {
        byte[] bits = new byte[32];
        bits[0] = (byte) num;
        return Sha256Hash.wrap(bits);
    }

    @Test(expected = VerificationException.class)
    public void merkleTreeMalleability() throws Exception {
        List<Sha256Hash> hashes = Lists.newArrayList();
        for (byte i = 1; i <= 10; i++) hashes.add(numAsHash(i));
        hashes.add(numAsHash(9));
        hashes.add(numAsHash(10));
        byte[] includeBits = new byte[2];
        Utils.setBitLE(includeBits, 9);
        Utils.setBitLE(includeBits, 10);
        PartialMerkleTree pmt = PartialMerkleTree.buildFromLeaves(PARAMS, includeBits, hashes);
        List<Sha256Hash> matchedHashes = Lists.newArrayList();
        pmt.getTxnHashAndMerkleRoot(matchedHashes);
    }

    @Test
    public void serializeDownloadBlockWithWallet() throws Exception {
        // First we create all the neccessary objects, including lots of serialization and double-checks
        // Note that all serialized forms here are generated by Bitcoin Core/pulled from block explorer
        Block block = new Block(PARAMS, HEX.decode("0100000006e533fd1ada86391f3f6c343204b0d278d4aaec1c0b20aa27ba0300000000006abbb3eb3d733a9fe18967fd7d4c117e4ccbbac5bec4d910d900b3ae0793e77f54241b4d4c86041b4089cc9b0c01000000010000000000000000000000000000000000000000000000000000000000000000ffffffff07044c86041b010dffffffff0100f2052a01000000434104b27f7e9475ccf5d9a431cb86d665b8302c140144ec2397fce792f4a4e7765fecf8128534eaa71df04f93c74676ae8279195128a1506ebf7379d23dab8fca0f63ac000000000100000001d992e5a888a86d4c7a6a69167a4728ee69497509740fc5f456a24528c340219a000000008b483045022100f0519bdc9282ff476da1323b8ef7ffe33f495c1a8d52cc522b437022d83f6a230220159b61d197fbae01b4a66622a23bc3f1def65d5fa24efd5c26fa872f3a246b8e014104839f9023296a1fabb133140128ca2709f6818c7d099491690bd8ac0fd55279def6a2ceb6ab7b5e4a71889b6e739f09509565eec789e86886f6f936fa42097adeffffffff02000fe208010000001976a914948c765a6914d43f2a7ac177da2c2f6b52de3d7c88ac00e32321000000001976a9140c34f4e29ab5a615d5ea28d4817f12b137d62ed588ac0000000001000000059daf0abe7a92618546a9dbcfd65869b6178c66ec21ccfda878c1175979cfd9ef000000004a493046022100c2f7f25be5de6ce88ac3c1a519514379e91f39b31ddff279a3db0b1a229b708b022100b29efbdbd9837cc6a6c7318aa4900ed7e4d65662c34d1622a2035a3a5534a99a01ffffffffd516330ebdf075948da56db13d22632a4fb941122df2884397dda45d451acefb0000000048473044022051243debe6d4f2b433bee0cee78c5c4073ead0e3bde54296dbed6176e128659c022044417bfe16f44eb7b6eb0cdf077b9ce972a332e15395c09ca5e4f602958d266101ffffffffe1f5aa33961227b3c344e57179417ce01b7ccd421117fe2336289b70489883f900000000484730440220593252bb992ce3c85baf28d6e3aa32065816271d2c822398fe7ee28a856bc943022066d429dd5025d3c86fd8fd8a58e183a844bd94aa312cefe00388f57c85b0ca3201ffffffffe207e83718129505e6a7484831442f668164ae659fddb82e9e5421a081fb90d50000000049483045022067cf27eb733e5bcae412a586b25a74417c237161a084167c2a0b439abfebdcb2022100efcc6baa6824b4c5205aa967e0b76d31abf89e738d4b6b014e788c9a8cccaf0c01ffffffffe23b8d9d80a9e9d977fab3c94dbe37befee63822443c3ec5ae5a713ede66c3940000000049483045022020f2eb35036666b1debe0d1d2e77a36d5d9c4e96c1dba23f5100f193dbf524790221008ce79bc1321fb4357c6daee818038d41544749127751726e46b2b320c8b565a201ffffffff0200ba1dd2050000001976a914366a27645806e817a6cd40bc869bdad92fe5509188ac40420f00000000001976a914ee8bd501094a7d5ca318da2506de35e1cb025ddc88ac0000000001000000010abad2dc0c9b4b1dbb023077da513f81e5a71788d8680fca98ef1c37356c459c000000004a493046022100a894e521c87b3dbe23007079db4ac2896e9e791f8b57317ba6c0d99a7becd27a022100bc40981393eafeb33e89079f857c728701a9af4523c3f857cd96a500f240780901ffffffff024026ee22010000001976a914d28f9cefb58c1f7a5f97aa6b79047585f58fbd4388acc0cb1707000000001976a9142229481696e417aa5f51ad751d8cd4c6a669e4fe88ac000000000100000001f66d89b3649e0b18d84db056930676cb81c0168042fc4324c3682e252ea9410d0000000048473044022038e0b55b37c9253bfeda59c76c0134530f91fb586d6eb21738a77a984f370a44022048d4d477aaf97ef9c82