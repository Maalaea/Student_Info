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

package org.bitcoinj.wallet;

import org.bitcoinj.core.listeners.TransactionConfidenceEventListener;
import org.bitcoinj.core.AbstractBlockChain;
import org.bitcoinj.core.Address;
import org.bitcoinj.core.Block;
import org.bitcoinj.core.BlockChain;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.PeerAddress;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.StoredBlock;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionConfidence;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutPoint;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.core.Utils;
import org.bitcoinj.core.VerificationException;
import org.bitcoinj.core.TransactionConfidence.ConfidenceType;
import org.bitcoinj.crypto.*;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.bitcoinj.signers.StatelessTransactionSigner;
import org.bitcoinj.signers.TransactionSigner;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.store.MemoryBlockStore;
import org.bitcoinj.testing.*;
import org.bitcoinj.utils.ExchangeRate;
import org.bitcoinj.utils.Fiat;
import org.bitcoinj.utils.Threading;
import org.bitcoinj.wallet.Wallet.BalanceType;
import org.bitcoinj.wallet.WalletTransaction.Pool;
import org.bitcoinj.wallet.listeners.KeyChainEventListener;
import org.bitcoinj.wallet.listeners.WalletChangeEventListener;
import org.bitcoinj.wallet.listeners.WalletCoinsReceivedEventListener;
import org.bitcoinj.wallet.listeners.WalletCoinsSentEventListener;
import org.easymock.EasyMock;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.protobuf.ByteString;
import org.bitcoinj.wallet.Protos.Wallet.EncryptionType;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.bouncycastle.crypto.params.KeyParameter;

import java.io.File;
import java.math.BigInteger;
import java.net.InetAddress;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.bitcoinj.core.Coin.*;
import static org.bitcoinj.core.Utils.HEX;
import static org.bitcoinj.testing.FakeTxBuilder.*;
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.replay;
import static com.google.common.base.Preconditions.checkNotNull;
import static org.junit.Assert.*;

public class WalletTest extends TestWithWallet {
    private static final Logger log = LoggerFactory.getLogger(WalletTest.class);

    private static final CharSequence PASSWORD1 = "my helicopter contains eels";
    private static final CharSequence WRONG_PASSWORD = "nothing noone nobody nowhere";

    private final Address OTHER_ADDRESS = new ECKey().toAddress(PARAMS);

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();
    }

    @After
    @Override
    public void tearDown() throws Exception {
        super.tearDown();
    }

    private void createMarriedWallet(int threshold, int numKeys) throws BlockStoreException {
        createMarriedWallet(threshold, numKeys, true);
    }

    private void createMarriedWallet(int threshold, int numKeys, boolean addSigners) throws BlockStoreException {
        wallet = new Wallet(PARAMS);
        blockStore = new MemoryBlockStore(PARAMS);
        chain = new BlockChain(PARAMS, wallet, blockStore);

        List<DeterministicKey> followingKeys = Lists.newArrayList();
        for (int i = 0; i < numKeys - 1; i++) {
            final DeterministicKeyChain keyChain = new DeterministicKeyChain(new SecureRandom());
            DeterministicKey partnerKey = DeterministicKey.deserializeB58(null, keyChain.getWatchingKey().serializePubB58(PARAMS), PARAMS);
            followingKeys.add(partnerKey);
            if (addSigners && i < threshold - 1)
                wallet.addTransactionSigner(new KeyChainTransactionSigner(keyChain));
        }

        MarriedKeyChain chain = MarriedKeyChain.builder()
                .random(new SecureRandom())
                .followingKeys(followingKeys)
                .threshold(threshold).build();
        wallet.addAndActivateHDChain(chain);
    }

    @Test
    public void getSeedAsWords1() {
        // Can't verify much here as the wallet is random each time. We could fix the RNG for the unit tests and solve.
        assertEquals(12, wallet.getKeyChainSeed().getMnemonicCode().size());
    }

    @Test
    public void checkSeed() throws MnemonicException {
        wallet.getKeyChainSeed().check();
    }

    @Test
    public void basicSpending() throws Exception {
        basicSpendingCommon(wallet, myAddress, OTHER_ADDRESS, null);
    }

    @Test
    public void basicSpendingToP2SH() throws Exception {
        Address destination = new Address(PARAMS, PARAMS.getP2SHHeader(), HEX.decode("4a22c3c4cbb31e4d03b15550636762bda0baf85a"));
        basicSpendingCommon(wallet, myAddress, destination, null);
    }

    @Test
    public void basicSpendingWithEncryptedWallet() throws Exception {
        Wallet encryptedWallet = new Wallet(PARAMS);
        encryptedWallet.encrypt(PASSWORD1);
        Address myEncryptedAddress = encryptedWallet.freshReceiveKey().toAddress(PARAMS);
        basicSpendingCommon(encryptedWallet, myEncryptedAddress, OTHER_ADDRESS, encryptedWallet);
    }

    @Test
    public void basicSpendingFromP2SH() throws Exception {
        createMarriedWallet(2, 2);
        myAddress = wallet.currentAddress(KeyChain.KeyPurpose.RECEIVE_FUNDS);
        basicSpendingCommon(wallet, myAddress, OTHER_ADDRESS, null);

        createMarriedWallet(2, 3);
        myAddress = wallet.currentAddress(KeyChain.KeyPurpose.RECEIVE_FUNDS);
        basicSpendingCommon(wallet, myAddress, OTHER_ADDRESS, null);

        createMarriedWallet(3, 3);
        myAddress = wallet.currentAddress(KeyChain.KeyPurpose.RECEIVE_FUNDS);
        basicSpendingCommon(wallet, myAddress, OTHER_ADDRESS, null);
    }

    @Test (expected = IllegalArgumentException.class)
    public void thresholdShouldNotExceedNumberOfKeys() throws Exception {
        createMarriedWallet(3, 2);
    }

    @Test
    public void spendingWithIncompatibleSigners() throws Exception {
        wallet.addTransactionSigner(new NopTransactionSigner(true));
        basicSpendingCommon(wallet, myAddress, OTHER_ADDRESS, null);
    }

    static class TestRiskAnalysis implements RiskAnalysis {
        private final boolean risky;

        public TestRiskAnalysis(boolean risky) {
            this.risky = risky;
        }

        @Override
        public Result analyze() {
            return risky ? Result.NON_FINAL : Result.OK;
        }

        public static class Analyzer implements RiskAnalysis.Analyzer {
            private final Transaction riskyTx;

            Analyzer(Transaction riskyTx) {
                this.riskyTx = riskyTx;
            }

            @Override
            public RiskAnalysis create(Wallet wallet, Transaction tx, List<Transaction> dependencies) {
                return new TestRiskAnalysis(tx == riskyTx);
            }
        }
    }

    static class TestCoinSelector extends DefaultCoinSelector {
        @Override
        protected boolean shouldSelect(Transaction tx) {
            return true;
        }
    }

    private Transaction cleanupCommon(Address destination) throws Exception {
        receiveATransaction(wallet, myAddress);

        Coin v2 = valueOf(0, 50);
        SendRequest req = SendRequest.to(destination, v2);
        wallet.completeTx(req);

        Transaction t2 = req.tx;

        // Broadcast the transaction and commit.
        broadcastAndCommit(wallet, t2);

        // At this point we have one pending and one spent

        Coin v1 = valueOf(0, 10);
        Transaction t = sendMoneyToWallet(null, v1, myAddress);
        Threading.waitForUserCode();
        sendMoneyToWallet(null, t);
        assertEquals("Wrong number of PENDING", 2, wallet.getPoolSize(Pool.PENDING));
        assertEquals("Wrong number of UNSPENT", 0, wallet.getPoolSize(Pool.UNSPENT));
        assertEquals("Wrong number of ALL", 3, wallet.getTransactions(true).size());
        assertEquals(valueOf(0, 60), wallet.getBalance(Wallet.BalanceType.ESTIMATED));

        // Now we have another incoming pending
        return t;
    }

    @Test
    public void cleanup() throws Exception {
        Transaction t = cleanupCommon(OTHER_ADDRESS);

        // Consider the new pending as risky and remove it from the wallet
        wallet.setRiskAnalyzer(new TestRiskAnalysis.Analyzer(t));

        wallet.cleanup();
        assertTrue(wallet.isConsistent());
        assertEquals("Wrong number of PENDING", 1, wallet.getPoolSize(WalletTransaction.Pool.PENDING));
        assertEquals("Wrong number of UNSPENT", 0, wallet.getPoolSize(WalletTransaction.Pool.UNSPENT));
        assertEquals("Wrong number of ALL", 2, wallet.getTransactions(true).size());
        assertEquals(valueOf(0, 50), wallet.getBalance(Wallet.BalanceType.ESTIMATED));
    }

    @Test
    public void cleanupFailsDueToSpend() throws Exception {
        Transaction t = cleanupCommon(OTHER_ADDRESS);

        // Now we have another incoming pending.  Spend everything.
        Coin v3 = valueOf(0, 60);
        SendRequest req = SendRequest.to(OTHER_ADDRESS, v3);

        // Force selection of the incoming coin so that we can spend it
        req.coinSelector = new TestCoinSelector();

        wallet.completeTx(req);
        wallet.commitTx(req.tx);

        assertEquals("Wrong number of PENDING", 3, wallet.getPoolSize(WalletTransaction.Pool.PENDING));
        assertEquals("Wrong number of UNSPENT", 0, wallet.getPoolSize(WalletTransaction.Pool.UNSPENT));
        assertEquals("Wrong number of ALL", 4, wallet.getTransactions(true).size());

        // Consider the new pending as risky and try to remove it from the wallet
        wallet.setRiskAnalyzer(new TestRiskAnalysis.Analyzer(t));

        wallet.cleanup();
        assertTrue(wallet.isConsistent());

        // The removal should have failed
        assertEquals("Wrong number of PENDING", 3, wallet.getPoolSize(WalletTransaction.Pool.PENDING));
        assertEquals("Wrong number of UNSPENT", 0, wallet.getPoolSize(WalletTransaction.Pool.UNSPENT));
        assertEquals("Wrong number of ALL", 4, wallet.getTransactions(true).size());
        assertEquals(ZERO, wallet.getBalance(Wallet.BalanceType.ESTIMATED));
    }

    private void basicSpendingCommon(Wallet wallet, Address toAddress, Address destination, Wallet encryptedWallet) throws Exception {
        // We'll set up a wallet that receives a coin, then sends a coin of lesser value and keeps the change. We
        // will attach a small fee. Because the Bitcoin protocol makes it difficult to determine the fee of an
        // arbitrary transaction in isolation, we'll check that the fee was set by examining the size of the change.

        // Receive some money as a pending transaction.
        receiveATransaction(wallet, toAddress);

        // Try to send too much and fail.
        Coin vHuge = valueOf(10, 0);
        SendRequest req = SendRequest.to(destination, vHuge);
        try {
            wallet.completeTx(req);
            fail();
        } catch (InsufficientMoneyException e) {
            assertEquals(valueOf(9, 0), e.missing);
        }

        // Prepare to send.
        Coin v2 = valueOf(0, 50);
        req = SendRequest.to(destination, v2);

        if (encryptedWallet != null) {
            KeyCrypter keyCrypter = encryptedWallet.getKeyCrypter();
            KeyParameter aesKey = keyCrypter.deriveKey(PASSWORD1);
            KeyParameter wrongAesKey = keyCrypter.deriveKey(WRONG_PASSWORD);

            // Try to create a send with a fee but no password (this should fail).
            try {
                wallet.completeTx(req);
                fail();
            } catch (ECKey.MissingPrivateKeyException kce) {
            }
            assertEquals("Wrong number of UNSPENT", 1, wallet.getPoolSize(WalletTransaction.Pool.UNSPENT));
            assertEquals("Wrong number of ALL", 1, wallet.getTransactions(true).size());

            // Try to create a send with a fee but the wrong password (this should fail).
            req = SendRequest.to(destination, v2);
            req.aesKey = wrongAesKey;

            try {
                wallet.completeTx(req);
                fail("No exception was thrown trying to sign an encrypted key with the wrong password supplied.");
            } catch (KeyCrypterException kce) {
                assertEquals("Could not decrypt bytes", kce.getMessage());
            }

            assertEquals("Wrong number of UNSPENT", 1, wallet.getPoolSize(WalletTransaction.Pool.UNSPENT));
            assertEquals("Wrong number of ALL", 1, wallet.getTransactions(true).size());

            // Create a send with a fee with the correct password (this should succeed).
            req = SendRequest.to(destination, v2);
            req.aesKey = aesKey;
        }

        // Complete the transaction successfully.
        req.shuffleOutputs = false;
        wallet.completeTx(req);

        Transaction t2 = req.tx;
        assertEquals("Wrong number of UNSPENT", 1, wallet.getPoolSize(WalletTransaction.Pool.UNSPENT));
        assertEquals("Wrong number of ALL", 1, wallet.getTransactions(true).size());
        assertEquals(TransactionConfidence.Source.SELF, t2.getConfidence().getSource());
        assertEquals(Transaction.Purpose.USER_PAYMENT, t2.getPurpose());

        // Do some basic sanity checks.
        basicSanityChecks(wallet, t2, destination);

        // Broadcast the transaction and commit.
        List<TransactionOutput> unspents1 = wallet.getUnspents();
        assertEquals(1, unspents1.size());
        broadcastAndCommit(wallet, t2);
        List<TransactionOutput> unspents2 = wallet.getUnspents();
        assertNotEquals(unspents1, unspents2.size());

        // Now check that we can spend the unconfirmed change, with a new change address of our own selection.
        // (req.aesKey is null for unencrypted / the correct aesKey for encrypted.)
        wallet = spendUnconfirmedChange(wallet, t2, req.aesKey);
        assertNotEquals(unspents2, wallet.getUnspents());
    }

    private void receiveATransaction(Wallet wallet, Address toAddress) throws Exception {
        receiveATransactionAmount(wallet, toAddress, COIN);
    }

    private void receiveATransactionAmount(Wallet wallet, Address toAddress, Coin amount) {
        final ListenableFuture<Coin> availFuture = wallet.getBalanceFuture(amount, Wallet.BalanceType.AVAILABLE);
        final ListenableFuture<Coin> estimatedFuture = wallet.getBalanceFuture(amount, Wallet.BalanceType.ESTIMATED);
        assertFalse(availFuture.isDone());
        assertFalse(estimatedFuture.isDone());
        // Send some pending coins to the wallet.
        Transaction t1 = sendMoneyToWallet(wallet, null, amount, toAddress);
        Threading.waitForUserCode();
        final ListenableFuture<TransactionConfidence> depthFuture = t1.getConfidence().getDepthFuture(1);
        assertFalse(depthFuture.isDone());
        assertEquals(ZERO, wallet.getBalance());
        assertEquals(amount, wallet.getBalance(Wallet.BalanceType.ESTIMATED));
        assertFalse(availFuture.isDone());
        // Our estimated balance has reached the requested level.
        assertTrue(estimatedFuture.isDone());
        assertEquals(1, wallet.getPoolSize(Pool.PENDING));
        assertEquals(0, wallet.getPoolSize(Pool.UNSPENT));
        // Confirm the coins.
        sendMoneyToWallet(wallet, AbstractBlockChain.NewBlockType.BEST_CHAIN, t1);
        assertEquals("Incorrect confirmed tx balance", amount, wallet.getBalance());
        assertEquals("Incorrect confirmed tx PENDING pool size", 0, wallet.getPoolSize(Pool.PENDING));
        assertEquals("Incorrect confirmed tx UNSPENT pool size", 1, wallet.getPoolSize(Pool.UNSPENT));
        assertEquals("Incorrect confirmed tx ALL pool size", 1, wallet.getTransactions(true).size());
        Threading.waitForUserCode();
        assertTrue(availFuture.isDone());
        assertTrue(estimatedFuture.isDone());
        assertTrue(depthFuture.isDone());
    }

    private void basicSanityChecks(Wallet wallet, Transaction t, Address destination) throws VerificationException {
        assertEquals("Wrong number of tx inputs", 1, t.getInputs().size());
        assertEquals("Wrong number of tx outputs",2, t.getOutputs().size());
        assertEquals(destination, t.getOutput(0).getScriptPubKey().getToAddress(PARAMS));
        assertEquals(wallet.currentChangeAddress(), t.getOutputs().get(1).getScriptPubKey().getToAddress(PARAMS));
        assertEquals(valueOf(0, 50), t.getOutputs().get(1).getValue());
        // Check the script runs and signatures verify.
        t.getInputs().get(0).verify();
    }

    private static void broadcastAndCommit(Wallet wallet, Transaction t) throws Exception {
        final LinkedList<Transaction> txns = Lists.newLinkedList();
        wallet.addCoinsSentEventListener(new WalletCoinsSentEventListener() {
            @Override
            public void onCoinsSent(Wallet wallet, Transaction tx, Coin prevBalance, Coin newBalance) {
                txns.add(tx);
            }
        });

        t.getConfidence().markBroadcastBy(new PeerAddress(PARAMS, InetAddress.getByAddress(new byte[]{1,2,3,4})));
        t.getConfidence().markBroadcastBy(new PeerAddress(PARAMS, InetAddress.getByAddress(new byte[]{10,2,3,4})));
        wallet.commitTx(t);
        Threading.waitForUserCode();
        assertEquals(1, wallet.getPoolSize(WalletTransaction.Pool.PENDING));
        assertEquals(1, wallet.getPoolSize(WalletTransaction.Pool.SPENT));
        assertEquals(2, wallet.getTransactions(true).size());
        assertEquals(t, txns.getFirst());
        assertEquals(1, txns.size());
    }

    private Wallet spendUnconfirmedChange(Wallet wallet, Transaction t2, KeyParameter aesKey) throws Exception {
        if (wallet.getTransactionSigners().size() == 1)   // don't bother reconfiguring the p2sh wallet
            wallet = roundTrip(wallet);
        Coin v3 = valueOf(0, 50);
        assertEquals(v3, wallet.getBalance());
        SendRequest req = SendRequest.to(OTHER_ADDRESS, valueOf(0, 48));
        req.aesKey = aesKey;
        req.shuffleOutputs = false;
        wallet.completeTx(req);
        Transaction t3 = req.tx;
        assertNotEquals(t2.getOutput(1).getScriptPubKey().getToAddress(PARAMS),
                        t3.getOutput(1).getScriptPubKey().getToAddress(PARAMS));
        assertNotNull(t3);
        wallet.commitTx(t3);
        assertTrue(wallet.isConsistent());
        // t2 and t3 gets confirmed in the same block.
        sendMoneyToWallet(AbstractBlockChain.NewBlockType.BEST_CHAIN, t2, t3);
        assertTrue(wallet.isConsistent());
        return wallet;
    }

    @Test
    @SuppressWarnings("deprecation")
    // Having a test for deprecated method getFromAddress() is no evil so we suppress the warning here.
    public void customTransactionSpending() throws Exception {
        // We'll set up a wallet that receives a coin, then sends a coin of lesser value and keeps the change.
        Coin v1 = valueOf(3, 0);
        sendMoneyToWallet(AbstractBlockChain.NewBlockType.BEST_CHAIN, v1);
        assertEquals(v1, wallet.getBalance());
        assertEquals(1, wallet.getPoolSize(WalletTransaction.Pool.UNSPENT));
        assertEquals(1, wallet.getTransactions(true).size());

        Coin v2 = valueOf(0, 50);
        Coin v3 = valueOf(0, 75);
        Coin v4 = valueOf(1, 25);

        Transaction t2 = new Transaction(PARAMS);
        t2.addOutput(v2, OTHER_ADDRESS);
        t2.addOutput(v3, OTHER_ADDRESS);
        t2.addOutput(v4, OTHER_ADDRESS);
        SendRequest req = SendRequest.forTx(t2);
        wallet.completeTx(req);

        // Do some basic sanity checks.
        assertEquals(1, t2.getInputs().size());
        assertEquals(myAddress, t2.getInput(0).getScriptSig().getFromAddress(PARAMS));
        assertEquals(TransactionConfidence.ConfidenceType.UNKNOWN, t2.getConfidence().getConfidenceType());

        // We have NOT proven that the signature is correct!
        wallet.commitTx(t2);
        assertEquals(1, wallet.getPoolSize(WalletTransaction.Pool.PENDING));
        assertEquals(1, wallet.getPoolSize(WalletTransaction.Pool.SPENT));
        assertEquals(2, wallet.getTransactions(true).size());
    }

    @Test
    public void sideChain() throws Exception {
        // The wallet receives a coin on the main chain, then on a side chain. Balance is equal to both added together
        // as we assume the side chain tx is pending and will be included shortly.
        Coin v1 = COIN;
        sendMoneyToWallet(AbstractBlockChain.NewBlockType.BEST_CHAIN, v1);
        assertEquals(v1, wallet.getBalance());
        assertEquals(1, wallet.getPoolSize(WalletTransaction.Pool.UNSPENT));
        assertEquals(1, wallet.getTransactions(true).size());

        Coin v2 = valueOf(0, 50);
        sendMoneyToWallet(AbstractBlockChain.NewBlockType.SIDE_CHAIN, v2);
        assertEquals(2, wallet.getTransactions(true).size());
        assertEquals(v1, wallet.getBalance());
        assertEquals(v1.add(v2), wallet.getBalance(Wallet.BalanceType.ESTIMATED));
    }

    @Test
    public void balance() throws Exception {
        // Receive 5 coins then half a coin.
        Coin v1 = valueOf(5, 0);
        Coin v2 = valueOf(0, 50);
        Coin expected = valueOf(5, 50);
        assertEquals(0, wallet.getTransactions(true).size());
        sendMoneyToWallet(AbstractBlockChain.NewBlockType.BEST_CHAIN, v1);
        assertEquals(1, wallet.getPoolSize(WalletTransaction.Pool.UNSPENT));
        sendMoneyToWallet(AbstractBlockChain.NewBlockType.BEST_CHAIN, v2);
        assertEquals(2, wallet.getPoolSize(WalletTransaction.Pool.UNSPENT));
        assertEquals(expected, wallet.getBalance());

        // Now spend one coin.
        Coin v3 = COIN;
        Transaction spend = wallet.createSend(OTHER_ADDRESS, v3);
        wallet.commitTx(spend);
        assertEquals(1, wallet.getPoolSize(WalletTransaction.Pool.PENDING));

        // Available and estimated balances should not be the same. We don't check the exact available balance here
        // because it depends on the coin selection algorithm.
        assertEquals(valueOf(4, 50), wallet.getBalance(Wallet.BalanceType.ESTIMATED));
        assertFalse(wallet.getBalance(Wallet.BalanceType.AVAILABLE).equals(
                    wallet.getBalance(Wallet.BalanceType.ESTIMATED)));

        // Now confirm the transaction by including it into a block.
        sendMoneyToWallet(BlockChain.NewBlockType.BEST_CHAIN, spend);

        // Change is confirmed. We started with 5.50 so we should have 4.50 left.
        Coin v4 = valueOf(4, 50);
        assertEquals(v4, wallet.getBalance(Wallet.BalanceType.AVAILABLE));
    }

    @Test
    public void balanceWithIdenticalOutputs() {
        assertEquals(Coin.ZERO, wallet.getBalance(BalanceType.ESTIMATED));
        Transaction tx = new Transaction(PARAMS);
        tx.addOutput(Coin.COIN, myAddress);
        tx.addOutput(Coin.COIN, myAddress); // identical to the above
        wallet.addWalletTransaction(new WalletTransaction(Pool.UNSPENT, tx));
        assertEquals(Coin.COIN.plus(Coin.COIN), wallet.getBalance(BalanceType.ESTIMATED));
    }

    // Intuitively you'd expect to be able to create a transaction with identical inputs and outputs and get an
    // identical result to Bitcoin Core. However the signatures are not deterministic - signing the same data
    // with the same key twice gives two different outputs. So we cannot prove bit-for-bit compatibility in this test
    // suite.

    @Test
    public void blockChainCatchup() throws Exception {
        // Test that we correctly process transactions arriving from the chain, with callbacks for inbound and outbound.
        final Coin[] bigints = new Coin[4];
        final Transaction[] txn = new Transaction[2];
        final LinkedList<Transaction> confTxns = new LinkedList<>();
        wallet.addCoinsReceivedEventListener(new WalletCoinsReceivedEventListener() {
            @Override
            public void onCoinsReceived(Wallet wallet, Transaction tx, Coin prevBalance, Coin newBalance) {
                bigints[0] = prevBalance;
                bigints[1] = newBalance;
                txn[0] = tx;
            }
        });

        wallet.addCoinsSentEventListener(new WalletCoinsSentEventListener() {
            @Override
            public void onCoinsSent(Wallet wallet, Transaction tx, Coin prevBalance, Coin newBalance) {
                bigints[2] = prevBalance;
                bigints[3] = newBalance;
                txn[1] = tx;
            }
        });

        wallet.addTransactionConfidenceEventListener(new TransactionConfidenceEventListener() {
            @Override
            public void onTransactionConfidenceChanged(Wallet wallet, Transaction tx) {
                confTxns.add(tx);
            }
        });

        // Receive some money.
        Coin oneCoin = COIN;
        Transaction tx1 = sendMoneyToWallet(AbstractBlockChain.NewBlockType.BEST_CHAIN, oneCoin);
        Threading.waitForUserCode();
        assertEquals(null, txn[1]);  // onCoinsSent not called.
        assertEquals(tx1, confTxns.getFirst());   // onTransactionConfidenceChanged called
        assertEquals(txn[0].getHash(), tx1.getHash());
        assertEquals(ZERO, bigints[0]);
        assertEquals(oneCoin, bigints[1]);
        assertEquals(TransactionConfidence.ConfidenceType.BUILDING, tx1.getConfidence().getConfidenceType());
        assertEquals(1, tx1.getConfidence().getAppearedAtChainHeight());
        // Send 0.10 to somebody else.
        Transaction send1 = wallet.createSend(OTHER_ADDRESS, valueOf(0, 10));
        // Pretend it makes it into the block chain, our wallet state is cleared but we still have the keys, and we
        // want to get back to our previous state. We can do this by just not confirming the transaction as
        // createSend is stateless.
        txn[0] = txn[1] = null;
        confTxns.clear();
        sendMoneyToWallet(AbstractBlockChain.NewBlockType.BEST_CHAIN, send1);
        Threading.waitForUserCode();
        assertEquals(Coin.valueOf(0, 90), wallet.getBalance());
        assertEquals(null, txn[0]);
        assertEquals(2, confTxns.size());
        assertEquals(txn[1].getHash(), send1.getHash());
        assertEquals(Coin.COIN, bigints[2]);
        assertEquals(Coin.valueOf(0, 90), bigints[3]);
        // And we do it again after the catchup.
        Transaction send2 = wallet.createSend(OTHER_ADDRESS, valueOf(0, 10));
        // What we'd really like to do is prove Bitcoin Core would accept it .... no such luck unfortunately.
        wallet.commitTx(send2);
        sendMoneyToWallet(AbstractBlockChain.NewBlockType.BEST_CHAIN, send2);
        assertEquals(Coin.valueOf(0, 80), wallet.getBalance());
        Threading.waitForUserCode();
        FakeTxBuilder.BlockPair b4 = createFakeBlock(blockStore, Block.BLOCK_HEIGHT_GENESIS);
        confTxns.clear();
        wallet.notifyNewBestBlock(b4.storedBlock);
        Threading.waitForUserCode();
        assertEquals(3, confTxns.size());
    }

    @Test
    public void balances() throws Exception {
        Coin nanos = COIN;
        Transaction tx1 = sendMoneyToWallet(AbstractBlockChain.NewBlockType.BEST_CHAIN, nanos);
        assertEquals(nanos, tx1.getValueSentToMe(wallet));
        assertTrue(tx1.getWalletOutputs(wallet).size() >= 1);
        // Send 0.10 to somebody else.
        Transaction send1 = wallet.createSend(OTHER_ADDRESS, valueOf(0, 10));
        // Reserialize.
        Transaction send2 = PARAMS.getDefaultSerializer().makeTransaction(send1.bitcoinSerialize());
        assertEquals(nanos, send2.getValueSentFromMe(wallet));
        assertEquals(ZERO.subtract(valueOf(0, 10)), send2.getValue(wallet));
    }

    @Test
    public void isConsistent_duplicates() throws Exception {
        // This test ensures that isConsistent catches duplicate transactions, eg, because we submitted the same block
        // twice (this is not allowed).
        Transaction tx = createFakeTx(PARAMS, COIN, myAddress);
        TransactionOutput output = new TransactionOutput(PARAMS, tx, valueOf(0, 5), OTHER_ADDRESS);
        tx.addOutput(output);
        wallet.receiveFromBlock(tx, null, BlockChain.NewBlockType.BEST_CHAIN, 0);

        assertTrue(wallet.isConsistent());

        Transaction txClone = PARAMS.getDefaultSerializer().makeTransaction(tx.bitcoinSerialize());
        try {
            wallet.receiveFromBlock(txClone, null, BlockChain.NewBlockType.BEST_CHAIN, 0);
            fail("Illegal argument not thrown when it should have been.");
        } catch (IllegalStateException ex) {
            // expected
        }
    }

    @Test
    public void isConsistent_pools() throws Exception {
        // This test ensures that isConsistent catches transactions that are in incompatible pools.
        Transaction tx = createFakeTx(PARAMS, COIN, myAddress);
        TransactionOutput output = new TransactionOutput(PARAMS, tx, valueOf(0, 5), OTHER_ADDRESS);
        tx.addOutput(output);
        wallet.receiveFromBlock(tx, null, BlockChain.NewBlockType.BEST_CHAIN, 0);

        assertTrue(wallet.isConsistent());

        wallet.addWalletTransaction(new WalletTransaction(Pool.PENDING, tx));
        assertFalse(wallet.isConsistent());
    }

    @Test
    public void isConsistent_spent() throws Exception {
        // This test ensures that isConsistent catches transactions that are marked spent when
        // they aren't.
        Transaction tx = createFakeTx(PARAMS, COIN, myAddress);
        TransactionOutput output = new TransactionOutput(PARAMS, tx, valueOf(0, 5), OTHER_ADDRESS);
        tx.addOutput(output);
        assertTrue(wallet.isConsistent());

        wallet.addWalletTransaction(new WalletTransaction(Pool.SPENT, tx));
        assertFalse(wallet.isConsistent());
    }

    @Test
    public void isTxConsistentReturnsFalseAsExpected() {
        Wallet wallet = new Wallet(PARAMS);
        TransactionOutput to = createMock(TransactionOutput.class);
        EasyMock.expect(to.isAvailableForSpending()).andReturn(true);
        EasyMock.expect(to.isMineOrWatched(wallet)).andReturn(true);
        EasyMock.expect(to.getSpentBy()).andReturn(new TransactionInput(PARAMS, null, new byte[0]));

        Transaction tx = FakeTxBuilder.createFakeTxWithoutChange(PARAMS, to);

        replay(to);

        boolean isConsistent = wallet.isTxConsistent(tx, false);
        assertFalse(isConsistent);
    }

    @Test
    public void isTxConsistentReturnsFalseAsExpected_WhenAvailableForSpendingEqualsFalse() {
        Wallet wallet = new Wallet(PARAMS);
        TransactionOutput to = createMock(TransactionOutput.class);
        EasyMock.expect(to.isAvailableForSpending()).andReturn(false);
        EasyMock.expect(to.getSpentBy()).andReturn(null);

        Transaction tx = FakeTxBuilder.createFakeTxWithoutChange(PARAMS, to);

        replay(to);

        boolean isConsistent = wallet.isTxConsistent(tx, false);
        assertFalse(isConsistent);
    }

    @Test
    public void transactions() throws Exception {
        // This test covers a bug in which Transaction.getValueSentFromMe was calculating incorrectly.
        Transaction tx = createFakeTx(PARAMS, COIN, myAddress);
        // Now add another output (ie, change) that goes to some other address.
        TransactionOutput output = new TransactionOutput(PARAMS, tx, valueOf(0, 5), OTHER_ADDRESS);
        tx.addOutput(output);
        // Note that tx is no longer valid: it spends more than it imports. However checking transactions balance
        // correctly isn't possible in SPV mode because value is a property of outputs not inputs. Without all
        // transactions you can't check they add up.
        sendMoneyToWallet(AbstractBlockChain.NewBlockType.BEST_CHAIN, tx);
        // Now the other guy creates a transaction which spends that change.
        Transaction tx2 = new Transaction(PARAMS);
        tx2.addInput(output);
        tx2.addOutput(new TransactionOutput(PARAMS, tx2, valueOf(0, 5), myAddress));
        // tx2 doesn't send any coins from us, even though the output is in the wallet.
        assertEquals(ZERO, tx2.getValueSentFromMe(wallet));
    }

    @Test
    public void bounce() throws Exception {
        // This test covers bug 64 (False double spends). Check that if we create a spend and it's immediately sent
        // back to us, this isn't considered as a double spend.
        Coin coin1 = COIN;
        sendMoneyToWallet(AbstractBlockChain.NewBlockType.BEST_CHAIN, coin1);
        // Send half to some other guy. Sending only half then waiting for a confirm is important to ensure the tx is
        // in the unspent pool, not pending or spent.
        Coin coinHalf = valueOf(0, 50);
        assertEquals(1, wallet.getPoolSize(WalletTransaction.Pool.UNSPENT));
        assertEquals(1, wallet.getTransactions(true).size());
        Transaction outbound1 = wallet.createSend(OTHER_ADDRESS, coinHalf);
        wallet.commitTx(outbound1);
        sendMoneyToWallet(AbstractBlockChain.NewBlockType.BEST_CHAIN, outbound1);
        assertTrue(outbound1.getWalletOutputs(wallet).size() <= 1); //the change address at most
        // That other guy gives us the coins right back.
        Transaction inbound2 = new Transaction(PARAMS);
        inbound2.addOutput(new TransactionOutput(PARAMS, inbound2, coinHalf, myAddress));
        assertTrue(outbound1.getWalletOutputs(wallet).size() >= 1);
        inbound2.addInput(outbound1.getOutputs().get(0));
        sendMoneyToWallet(AbstractBlockChain.NewBlockType.BEST_CHAIN, inbound2);
        assertEquals(coin1, wallet.getBalance());
    }

    @Test
    public void doubleSpendUnspendsOtherInputs() throws Exception {
        // Test another Finney attack, but this time the killed transaction was also spending some other outputs in
        // our wallet which were not themselves double spent. This test ensures the death of the pending transaction
        // frees up the other outputs and makes them spendable again.

        // Receive 1 coin and then 2 coins in separate transactions.
        sendMoneyToWallet(AbstractBlockChain.NewBlockType.BEST_CHAIN, COIN);
        sendMoneyToWallet(AbstractBlockChain.NewBlockType.BEST_CHAIN, valueOf(2, 0));
        // Create a send to a merchant of all our coins.
        Transaction send1 = wallet.createSend(OTHER_ADDRESS, valueOf(2, 90));
        // Create a double spend of just the first one.
        Address BAD_GUY = new ECKey().toAddress(PARAMS);
        Transaction send2 = wallet.createSend(BAD_GUY, COIN);
        send2 = PARAMS.getDefaultSerializer().makeTransaction(send2.bitcoinSerialize());
        // Broadcast send1, it's now pending.
        wallet.commitTx(send1);
        assertEquals(ZERO, wallet.getBalance()); // change of 10 cents is not yet mined so not included in the balance.
        // Receive a block that overrides the send1 using send2.
        sendMoneyToWallet(AbstractBlockChain.NewBlockType.BEST_CHAIN, send2);
        // send1 got rolled back and replaced with a smaller send that only used one of our received coins, thus ...
        assertEquals(valueOf(2, 0), wallet.getBalance());
        assertTrue(wallet.isConsistent());
    }

    @Test
    public void doubleSpends() throws Exception {
        // Test the case where two semantically identical but bitwise different transactions double spend each other.
        // We call the second transaction a "mutant" of the first.
        //
        // This can (and has!) happened when a wallet is cloned between devices, and both devices decide to make the
        // same spend simultaneously - for example due a re-keying operation. It can also happen if there are malicious
        // nodes in the P2P network that are mutating transactions on the fly as occurred during Feb 2014.
        final Coin value = COIN;
        final Coin value2 = valueOf(2, 0);
        // Give us three coins and make sure we have some change.
        sendMoneyToWallet(AbstractBlockChain.NewBlockType.BEST_CHAIN, value.add(value2));
        Transaction send1 = checkNotNull(wallet.createSend(OTHER_ADDRESS, value2));
        Transaction send2 = checkNotNull(wallet.createSend(OTHER_ADDRESS, value2));
        byte[] buf = send1.bitcoinSerialize();
        buf[43] = 0;  // Break the signature: bitcoinj won't check in SPV mode and this is easier than other mutations.
        send1 = PARAMS.getDefaultSerializer().makeTransaction(buf);
        wallet.commitTx(send2);
        wallet.allowSpendingUnconfirmedTransactions();
        assertEquals(value, wallet.getBalance(Wallet.BalanceType.ESTIMATED));
        // Now spend the change. This transaction should die permanently when the mutant appears in the chain.
        Transaction send3 = checkNotNull(wallet.createSend(OTHER_ADDRESS, value));
        wallet.commitTx(send3);
        assertEquals(ZERO, wallet.getBalance());
        final LinkedList<TransactionConfidence> dead = new LinkedList<>();
        final TransactionConfidence.Listener listener = new TransactionConfidence.Listener() {
            @Override
            public void onConfidenceChanged(TransactionConfidence confidence, ChangeReason reason) {
                final TransactionConfidence.ConfidenceType type = confidence.getConfidenceType();
                if (reason == ChangeReason.TYPE && type == TransactionConfidence.ConfidenceType.DEAD)
                    dead.add(confidence);
            }
        };
        send2.getConfidence().addEventListener(Threading.SAME_THREAD, listener);
        send3.getConfidence().addEventListener(Threading.SAME_THREAD, listener);
        // Double spend!
        sendMoneyToWallet(AbstractBlockChain.NewBlockType.BEST_CHAIN, send1);
        // Back to having one coin.
        assertEquals(value, wallet.getBalance());
        assertEquals(send2.getHash(), dead.poll().getTransactionHash());
        assertEquals(send3.getHash(), dead.poll().getTransactionHash());
    }

    @Test
    public void doubleSpendFinneyAttack() throws Exception {
        // A Finney attack is where a miner includes a transaction spending coins to themselves but does not
        // broadcast it. When they find a solved block, they hold it back temporarily whilst they buy something with
        // those same coins. After purchasing, they broadcast the block thus reversing the transaction. It can be
        // done by any miner for products that can be bought at a chosen time and very quickly (as every second you
        // withold your block means somebody else might find it first, invalidating your work).
        //
        // Test that we handle the attack correctly: a double spend on the chain moves transactions from pending to dead.
        // This needs to work both for transactions we create, and that we receive from others.
        final Transaction[] eventDead = new Transaction[1];
        final Transaction[] eventReplacement = new Transaction[1];
        final int[] eventWalletChanged = new int[1];
        wallet.addTransactionConfidenceEventListener(new TransactionConfidenceEventListener() {
            @Override
            public void onTransactionConfidenceChanged(Wallet wallet, Transaction tx) {
                if (tx.getConfidence().getConfidenceType() ==
                        TransactionConfidence.ConfidenceType.DEAD) {
                    eventDead[0] = tx;
                    eventReplacement[0] = tx.getConfidence().getOverridingTransaction();
                }
            }
        });

        wallet.addChangeEventListener(new WalletChangeEventListener() {
            @Override
            public void onWalletChanged(Wallet wallet) {
                eventWalletChanged[0]++;
            }
        });

        // Receive 1 BTC.
        Coin nanos = COIN;
        sendMoneyToWallet(AbstractBlockChain.NewBlockType.BEST_CHAIN, nanos);
        Transaction received = wallet.getTransactions(false).iterator().next();
        // Create a send to a merchant.
        Transaction send1 = wallet.createSend(OTHER_ADDRESS, valueOf(0, 50));
        // Create a double spend.
        Address BAD_GUY = new ECKey().toAddress(PARAMS);
        Transaction send2 = wallet.createSend(BAD_GUY, valueOf(0, 50));
        send2 = PARAMS.getDefaultSerializer().makeTransaction(send2.bitcoinSerialize());
        // Broadcast send1.
        wallet.commitTx(send1);
        assertEquals(send1, received.getOutput(0).getSpentBy().getParentTransaction());
        // Receive a block that overrides it.
        sendMoneyToWallet(AbstractBlockChain.NewBlockType.BEST_CHAIN, send2);
        Threading.waitForUserCode();
        assertEquals(send1, eventDead[0]);
        assertEquals(send2, eventReplacement[0]);
        assertEquals(TransactionConfidence.ConfidenceType.DEAD,
                send1.getConfidence().getConfidenceType());
        assertEquals(send2, received.getOutput(0).getSpentBy().getParentTransaction());

        FakeTxBuilder.DoubleSpends doubleSpends = FakeTxBuilder.createFakeDoubleSpendTxns(PARAMS, myAddress);
        // t1 spends to our wallet. t2 double spends somewhere else.
        wallet.receivePending(doubleSpends.t1, null);
        assertEquals(TransactionConfidence.ConfidenceType.PENDING,
                doubleSpends.t1.getConfidence().getConfidenceType());
        sendMoneyToWallet(AbstractBlockChain.NewBlockType.BEST_CHAIN, doubleSpends.t2);
        Threading.waitForUserCode();
        assertEquals(TransactionConfidence.ConfidenceType.DEAD,
                doubleSpends.t1.getConfidence().getConfidenceType());
        assertEquals(doubleSpends.t2, doubleSpends.t1.getConfidence().getOverridingTransaction());
        assertEquals(5, eventWalletChanged[0]);
    }

    @Test
    public void doubleSpendWeCreate() throws Exception {
        // Test we keep pending double spends in IN_CONFLICT until one of them is included in a block
        // and we handle reorgs and dependency chains properly.
        // The following graph shows the txns we use in this test and how they are related
        // (Eg txA1 spends txARoot outputs, txC1 spends txA1 and txB1 outputs, etc).
        // txARoot (10)  -> txA1 (1)  -+
        //                             |--> txC1 (0.10) -> txD1 (0.01)
        // txBRoot (100) -> txB1 (11) -+
        //
        // txARoot (10)  -> txA2 (2)  -+
        //                             |--> txC2 (0.20) -> txD2 (0.02)
        // txBRoot (100) -> txB2 (22) -+
        //
        // txARoot (10)  -> txA3 (3)
        //
        // txA1 is in conflict with txA2 and txA3. txB1 is in conflict with txB2.

        CoinSelector originalCoinSelector = wallet.getCoinSelector();
        try {
            wallet.allowSpendingUnconfirmedTransactions();

            Transaction txARoot = sendMoneyToWallet(AbstractBlockChain.NewBlockType.BEST_CHAIN, valueOf(10, 0));
            SendRequest a1Req = SendRequest.to(OTHER_ADDRESS, valueOf(1, 0));
            a1Req.tx.addInput(txARoot.getOutput(0));
            a1Req.shuffleOutputs = false;
            wallet.completeTx(a1Req);
            Transaction txA1 = a1Req.tx;
            SendRequest a2Req = SendRequest.to(OTHER_ADDRESS, valueOf(2, 0));
            a2Req.tx.addInput(txARoot.getOutput(0));
            a2Req.shuffleOutputs = false;
            wallet.completeTx(a2Req);
            Transaction txA2 = a2Req.tx;
            SendRequest a3Req = SendRequest.to(OTHER_ADDRESS, valueOf(3, 0));
            a3Req.tx.addInput(txARoot.getOutput(0));
            a3Req.shuffleOutputs = false;
            wallet.completeTx(a3Req);
            Transaction txA3 = a3Req.tx;
            wallet.commitTx(txA1);
            wallet.commitTx(txA2);
            wallet.commitTx(txA3);

            Transaction txBRoot = sendMoneyToWallet(AbstractBlockChain.NewBlockType.BEST_CHAIN, valueOf(100, 0));
            SendRequest b1Req = SendRequest.to(OTHER_ADDRESS, valueOf(11, 0));
            b1Req.tx.addInput(txBRoot.getOutput(0));
            b1Req.shuffleOutputs = false;
            wallet.completeTx(b1Req);
            Transaction txB1 = b1Req.tx;
            SendRequest b2Req = SendRequest.to(OTHER_ADDRESS, valueOf(22, 0));
            b2Req.tx.addInput(txBRoot.getOutput(0));
            b2Req.shuffleOutputs = false;
            wallet.completeTx(b2Req);
            Transaction txB2 = b2Req.tx;
            wallet.commitTx(txB1);
            wallet.commitTx(txB2);

            SendRequest c1Req = SendRequest.to(OTHER_ADDRESS, valueOf(0, 10));
            c1Req.tx.addInput(txA1.getOutput(1));
            c1Req.tx.addInput(txB1.getOutput(1));
            c1Req.shuffleOutputs = false;
            wallet.completeTx(c1Req);
            Transaction txC1 = c1Req.tx;
            SendRequest c2Req = SendRequest.to(OTHER_ADDRESS, valueOf(0, 20));
            c2Req.tx.addInput(txA2.getOutput(1));
            c2Req.tx.addInput(txB2.getOutput(1));
            c2Req.shuffleOutputs = false;
            wallet.completeTx(c2Req);
            Transaction txC2 = c2Req.tx;
            wallet.commitTx(txC1);
            wallet.commitTx(txC2);

            SendRequest d1Req = SendRequest.to(OTHER_ADDRESS, valueOf(0, 1));
            d1Req.tx.addInput(txC1.getOutput(1));
            d1Req.shuffleOutputs = false;
            wallet.completeTx(d1Req);
            Transaction txD1 = d1Req.tx;
            SendRequest d2Req = SendRequest.to(OTHER_ADDRESS, valueOf(0, 2));
            d2Req.tx.addInput(txC2.getOutput(1));
            d2Req.shuffleOutputs = false;
            wallet.completeTx(d2Req);
            Transaction txD2 = d2Req.tx;
            wallet.commitTx(txD1);
            wallet.commitTx(txD2);

            assertInConflict(txA1);
            assertInConflict(txA2);
            assertInConflict(txA3);
            assertInConflict(txB1);
            assertInConflict(txB2);
            assertInConflict(txC1);
            assertInConflict(txC2);
            assertInConflict(txD1);
            assertInConflict(txD2);

            // Add a block to the block store. The rest of the blocks in this test will be on top of this one.
            FakeTxBuilder.BlockPair blockPair0 = createFakeBlock(blockStore, 1);

            // A block was mined including txA1
            FakeTxBuilder.BlockPair blockPair1 = createFakeBlock(blockStore, 2, txA1);
            wallet.receiveFromBlock(txA1, blockPair1.storedBlock, AbstractBlockChain.NewBlockType.BEST_CHAIN, 0);
            wallet.notifyNewBestBlock(blockPair1.storedBlock);
            assertSpent(txA1);
            assertDead(txA2);
            assertDead(txA3);
            assertInConflict(txB1);
            assertInConflict(txB2);
            assertInConflict(txC1);
            assertDead(txC2);
            assertInConflict(txD1);
            assertDead(txD2);

            // A reorg: previous block "replaced" by new block containing txA1 and txB1
            FakeTxBuilder.BlockPair blockPair2 = createFakeBlock(blockStore, blockPair0.storedBlock, 2, txA1, txB1);
            wallet.receiveFromBlock(txA1, blockPair2.storedBlock, AbstractBlockChain.NewBlockType.SIDE_CHAIN, 0);
            wallet.receiveFromBlock(txB1, blockPair2.storedBlock, AbstractBlockChain.NewBlockType.SIDE_CHAIN, 1);
            wallet.reorganize(blockPair0.storedBlock, Lists.newArrayList(blockPair1.storedBlock),
                    Lists.newArrayList(blockPair2.storedBlock));
            assertSpent(txA1);
            assertDead(txA2);
            assertDead(txA3);
            assertSpent(txB1);
            assertDead(txB2);
            assertPending(txC1);
            assertDead(txC2);
            assertPending(txD1);
            assertDead(txD2);

            // A reorg: previous block "replaced" by new block containing txA1, txB1 and txC1
            FakeTxBuilder.BlockPair blockPair3 = createFakeBlock(blockStore, blockPair0.storedBlock, 2, txA1, txB1,
                    txC1);
            wallet.receiveFromBlock(txA1, blockPair3.storedBlock, AbstractBlockChain.NewBlockType.SIDE_CHAIN, 0);
            wallet.receiveFromBlock(txB1, blockPair3.storedBlock, AbstractBlockChain.NewBlockType.SIDE_CHAIN, 1);
            wallet.receiveFromBlock(txC1, blockPair3.storedBlock, AbstractBlockChain.NewBlockType.SIDE_CHAIN, 2);
            wallet.reorganize(blockPair0.storedBlock, Lists.newArrayList(blockPair2.storedBlock),
                    Lists.newArrayList(blockPair3.storedBlock));
            assertSpent(txA1);
            assertDead(txA2);
            assertDead(txA3);
            assertSpent(txB1);
            assertDead(txB2);
            assertSpent(txC1);
            assertDead(txC2);
            assertPending(txD1);
            assertDead(txD2);

            // A reorg: previous block "replaced" by new block containing txB1
            FakeTxBuilder.BlockPair blockPair4 = createFakeBlock(blockStore, blockPair0.storedBlock, 2, txB1);
            wallet.receiveFromBlock(txB1, blockPair4.storedBlock, AbstractBlockChain.NewBlockType.SIDE_CHAIN, 0);
            wallet.reorganize(blockPair0.storedBlock, Lists.newArrayList(blockPair3.storedBlock),
                    Lists.newArrayList(blockPair4.storedBlock));
            assertPending(txA1);
            assertDead(txA2);
            assertDead(txA3);
            assertSpent(txB1);
            assertDead(txB2);
            assertPending(txC1);
            assertDead(txC2);
            assertPending(txD1);
            assertDead(txD2);

            // A reorg: previous block "replaced" by new block containing txA2
            FakeTxBuilder.BlockPair blockPair5 = createFakeBlock(blockStore, blockPair0.storedBlock, 2, txA2);
            wallet.receiveFromBlock(txA2, blockPair5.storedBlock, AbstractBlockChain.NewBlockType.SIDE_CHAIN, 0);
            wallet.reorganize(blockPair0.storedBlock, Lists.newArrayList(blockPair4.storedBlock),
                    Lists.newArrayList(blockPair5.storedBlock));
            assertDead(txA1);
            assertUnspent(txA2);
            assertDead(txA3);
            assertPending(txB1);
            assertDead(txB2);
            assertDead(txC1);
            assertDead(txC2);
            assertDead(txD1);
            assertDead(txD2);

            // A reorg: previous block "replaced" by new empty block
            FakeTxBuilder.BlockPair blockPair6 = createFakeBlock(blockStore, blockPair0.storedBlock, 2);
            wallet.reorganize(blockPair0.storedBlock, Lists.newArrayList(blockPair5.storedBlock),
                    Lists.newArrayList(blockPair6.storedBlock));
            assertDead(txA1);
            assertPending(txA2);
            assertDead(txA3);
            assertPending(txB1);
            assertDead(txB2);
            assertDead(txC1);
            assertDead(txC2);
            assertDead(txD1);
            assertDead(txD2);
        } finally {
            wallet.setCoinSelector(originalCoinSelector);
        }

    }

    @Test
    public void doubleSpendWeReceive() throws Exception {
        FakeTxBuilder.DoubleSpends doubleSpends = FakeTxBuilder.createFakeDoubleSpendTxns(PARAMS, myAddress);
        // doubleSpends.t1 spends to our wallet. doubleSpends.t2 double spends somewhere else.

        Transaction t1b = new Transaction(PARAMS);
        TransactionOutput t1bo = new TransactionOutput(PARAMS, t1b, valueOf(0, 50), OTHER_ADDRESS);
        t1b.addOutput(t1bo);
        t1b.addInput(doubleSpends.t1.getOutput(0));

        wallet.receivePending(doubleSpends.t1, null);
        wallet.receivePending(doubleSpends.t2, null);
        wallet.receivePending(t1b, null);
        assertInConflict(doubleSpends.t1);
        assertInConflict(doubleSpends.t1);
        assertInConflict(t1b);

        // Add a block to the block store. The rest of the blocks in this test will be on top of this one.
        FakeTxBuilder.BlockPair blockPair0 = createFakeBlock(blockStore, 1);

        // A block was mined including doubleSpends.t1
        FakeTxBuilder.BlockPair blockPair1 = createFakeBlock(blockStore, 2, doubleSpends.t1);
        wallet.receiveFromBlock(doubleSpends.t1, blockPair1.storedBlock, AbstractBlockChain.NewBlockType.BEST_CHAIN, 0);
        wallet.notifyNewBestBlock(blockPair1.storedBlock);
        assertSpent(doubleSpends.t1);
        assertDead(doubleSpends.t2);
        assertPending(t1b);

        // A reorg: previous block "replaced" by new block containing doubleSpends.t2
        FakeTxBuilder.BlockPair blockPair2 = createFakeBlock(blockStore, blockPair0.storedBlock, 2, doubleSpends.t2);
        wallet.receiveFromBlock(doubleSpends.t2, blockPair2.storedBlock, AbstractBlockChain.NewBlockType.SIDE_CHAIN, 0);
        wallet.reorganize(blockPair0.storedBlock, Lists.newArrayList(blockPair1.storedBlock),
                Lists.newArrayList(blockPair2.storedBlock));
        assertDead(doubleSpends.t1);
        assertSpent(doubleSpends.t2);
        assertDead(t1b);
    }

    @Test
    public void doubleSpendForBuildingTx() throws Exception {
        CoinSelector originalCoinSelector = wallet.getCoinSelector();
        try {
            wallet.allowSpendingUnconfirmedTransactions();

            sendMoneyToWallet(AbstractBlockChain.NewBlockType.BEST_CHAIN, valueOf(2, 0));
            Transaction send1 = checkNotNull(wallet.createSend(OTHER_ADDRESS, valueOf(1, 0)));
            Transaction send2 = checkNotNull(wallet.createSend(OTHER_ADDRESS, valueOf(1, 20)));

            sendMoneyToWallet(AbstractBlockChain.NewBlockType.BEST_CHAIN, send1);
            assertUnspent(send1);

            wallet.receivePending(send2, null);
            assertUnspent(send1);
            assertDead(send2);

        } finally {
            wallet.setCoinSelector(originalCoinSelector);
        }
    }

    @Test
    public void txSpendingDeadTx() throws Exception {
        CoinSelector originalCoinSelector = wallet.getCoinSelector();
        try {
            wallet.allowSpendingUnconfirmedTransactions();

            sendMoneyToWallet(AbstractBlockChain.NewBlockType.BEST_CHAIN, valueOf(2, 0));
            Transaction send1 = checkNotNull(wallet.createSend(OTHER_ADDRESS, valueOf(1, 0)));
            Transaction send2 = checkNotNull(wallet.createSend(OTHER_ADDRESS, valueOf(1, 20)));
            wallet.commitTx(send1);
            assertPending(send1);
            Transaction send1b = checkNotNull(wallet.createSend(OTHER_ADDRESS, valueOf(0, 50)));

            sendMoneyToWallet(AbstractBlockChain.NewBlockType.BEST_CHAIN, send2);
            assertDead(send1);
            assertUnspent(send2);

            wallet.receivePending(send1b, null);
            assertDead(send1);
            assertUnspent(send2);
            assertDead(send1b);

        } finally {
            wallet.setCoinSelector(originalCoinSelector);
        }
    }

    private void assertInConflict(Transaction tx) {
        assertEquals(ConfidenceType.IN_CONFLICT, tx.getConfidence().getConfidenceType());
        assertTrue(wallet.poolContainsTxHash(WalletTransaction.Pool.PENDING, tx.getHash()));
    }

    private void assertPending(Transaction tx) {
        assertEquals(ConfidenceType.PENDING, tx.getConfidence().getConfidenceType());
        assertTrue(wallet.poolContainsTxHash(WalletTransaction.Pool.PENDING, tx.getHash()));
    }

    private void assertSpent(Transaction tx) {
        assertEquals(ConfidenceType.BUILDING, tx.getConfidence().getConfidenceType());
        assertTrue(wallet.poolContainsTxHash(WalletTransaction.Pool.SPENT, tx.getHash()));
    }

    private void assertUnspent(Transaction tx) {
        assertEquals(ConfidenceType.BUILDING, tx.getConfidence().getConfidenceType());
        assertTrue(wallet.poolContainsTxHash(WalletTransaction.Pool.UNSPENT, tx.getHash()));
    }

    private void assertDead(Transaction tx) {
        assertEquals(ConfidenceType.DEAD, tx.getConfidence().getConfidenceType());
        assertTrue(wallet.poolContainsTxHash(WalletTransaction.Pool.DEAD, tx.getHash()));
    }

    @Test
    public void testAddTransactionsDependingOn() throws Exception {
        CoinSelector originalCoinSelector = wallet.getCoinSelector();
        try {
            wallet.allowSpendingUnconfirmedTransactions();
            sendMoneyToWallet(AbstractBlockChain.NewBlockType.BEST_CHAIN, valueOf(2, 0));
            Transaction send1 = checkNotNull(wallet.createSend(OTHER_ADDRESS, valueOf(1, 0)));
            Transaction send2 = checkNotNull(wallet.createSend(OTHER_ADDRESS, valueOf(1, 20)));
            wallet.commitTx(send1);
            Transaction send1b = checkNotNull(wallet.createSend(OTHER_ADDRESS, valueOf(0, 50)));
            wallet.commitTx(send1b);
            Transaction send1c = checkNotNull(wallet.createSend(OTHER_ADDRESS, valueOf(0, 25)));
            wallet.commitTx(send1c);
            wallet.commitTx(send2);
            Set<Transaction> txns = new HashSet<>();
            txns.add(send1);
            wallet.addTransactionsDependingOn(txns, wallet.getTransactions(true));
            assertEquals(3, txns.size());
            assertTrue(txns.contains(send1));
            assertTrue(txns.contains(send1b));
            assertTrue(txns.contains(send1c));
        } finally {
            wallet.setCoinSelector(originalCoinSelector);
        }
    }

    @Test
    public void sortTxnsByDependency() throws Exception {
        CoinSelector originalCoinSelector = wallet.getCoinSelector();
        try {
            wallet.allowSpendingUnconfirmedTransactions();
            Transaction send1 = sendMoneyToWallet(AbstractBlockChain.NewBlockType.BEST_CHAIN, valueOf(2, 0));
            Transaction send1a = checkNotNull(wallet.createSend(OTHER_ADDRESS, valueOf(1, 0)));
            wallet.commitTx(send1a);
            Transaction send1b = checkNotNull(wallet.createSend(OTHER_ADDRESS, valueOf(0, 50)));
            wallet.commitTx(send1b);
            Transaction send1c = checkNotNull(wallet.createSend(OTHER_ADDRESS, valueOf(0, 25)));
            wallet.commitTx(send1c);
            Transaction send1d = checkNotNull(wallet.createSend(OTHER_ADDRESS, valueOf(0, 12)));
            wallet.commitTx(send1d);
            Transaction send1e = checkNotNull(wallet.createSend(OTHER_ADDRESS, valueOf(0, 06)));
            wallet.commitTx(send1e);

            Transaction send2 = sendMoneyToWallet(AbstractBlockChain.NewBlockType.BEST_CHAIN, valueOf(200, 0));

            SendRequest req2a = SendRequest.to(OTHER_ADDRESS, valueOf(100, 0));
            req2a.tx.addInput(send2.getOutput(0));
            req2a.shuffleOutputs = false;
            wallet.completeTx(req2a);
            Transaction send2a = req2a.tx;

            SendRequest req2b = SendRequest.to(OTHER_ADDRESS, valueOf(50, 0));
            req2b.tx.addInput(send2a.getOutput(1));
            req2b.shuffleOutputs = false;
            wallet.completeTx(req2b);
            Transaction send2b = req2b.tx;

            SendRequest req2c = SendRequest.to(OTHER_ADDRESS, valueOf(25, 0));
            req2c.tx.addInput(send2b.getOutput(1));
            req2c.shuffleOutputs = false;
            wallet.completeTx(req2c);
            Transaction send2c = req2c.tx;

            Set<Transaction> unsortedTxns = new HashSet<>();
            unsortedTxns.add(send1a);
            unsortedTxns.add(send1b);
            unsortedTxns.add(send1c);
            unsortedTxns.add(send1d);
            unsortedTxns.add(send1e);
            unsortedTxns.add(send2a);
            unsortedTxns.add(send2b);
            unsortedTxns.add(send2c);
            List<Transaction> sortedTxns = wallet.sortTxnsByDependency(unsortedTxns);

            assertEquals(8, sortedTxns.size());
            assertTrue(sortedTxns.indexOf(send1a) < sortedTxns.indexOf(send1b));
            assertTrue(sortedTxns.indexOf(send1b) < sortedTxns.indexOf(send1c));
            assertTrue(sortedTxns.indexOf(send1c) < sortedTxns.indexOf(send1d));
            assertTrue(sortedTxns.indexOf(send1d) < sortedTxns.indexOf(send1e));
            assertTrue(sortedTxns.indexOf(send2a) < sortedTxns.indexOf(send2b));
            assertTrue(sortedTxns.indexOf(send2b) < sortedTxns.indexOf(send2c));
        } finally {
            wallet.setCoinSelector(originalCoinSelector);
        }
    }

    @Test
    public void pending1() throws Exception {
        // Check that if we receive a pending transaction that is then confirmed, we are notified as appropriate.
        final Coin nanos = COIN;
        final Transaction t1 = createFakeTx(PARAMS, nanos, myAddress);

        // First one is "called" second is "pending".
        final boolean[] flags = new boolean[2];
        final Transaction[] notifiedTx = new Transaction[1];
        final int[] walletChanged = new int[1];
        wallet.addCoinsReceivedEventListener(new WalletCoinsReceivedEventListener() {
            @Override
            public void onCoinsReceived(Wallet wallet, Transaction tx, Coin prevBalance, Coin newBalance) {
                // Check we got the expected transaction.
                assertEquals(tx, t1);
                // Check that it's considered to be pending inclusion in the block chain.
                assertEquals(prevBalance, ZERO);
                assertEquals(newBalance, nanos);
                flags[0] = true;
                flags[1] = tx.isPending();
                notifiedTx[0] = tx;
            }
        });

        wallet.addChangeEventListener(new WalletChangeEventListener() {
            @Override
            public void onWalletChanged(Wallet wallet) {
                walletChanged[0]++;
            }
        });

        if (wallet.isPendingTransactionRelevant(t1))
            wallet.receivePending(t1, null);
        Threading.waitForUserCode();
        assertTrue(flags[0]);
        assertTrue(flags[1]);   // is pending
        flags[0] = false;
        // Check we don't get notified if we receive it again.
        assertFalse(wallet.isPendingTransactionRelevant(t1));
        assertFalse(flags[0]);
        // Now check again, that we should NOT be notified when we receive it via a block (we were already notified).
        // However the confidence should be updated.
        // Make a fresh copy of the tx to ensure we're testing realistically.
        flags[0] = flags[1] = false;
        final TransactionConfidence.Listener.ChangeReason[] reasons = new TransactionConfidence.Listener.ChangeReason[1];
        notifiedTx[0].getConfidence().addEventListener(new TransactionConfidence.Listener() {
            @Override
            public void onConfidenceChanged(TransactionConfidence confidence, TransactionConfidence.Listener.ChangeReason reason) {
                flags[1] = true;
                reasons[0] = reason;
            }
        });
        assertEquals(TransactionConfidence.ConfidenceType.PENDING,
                notifiedTx[0].getConfidence().getConfidenceType());
        // Send a block with nothing interesting. Verify we don't get a callback.
        sendMoneyToWallet(AbstractBlockChain.NewBlockType.BEST_CHAIN);
        Threading.waitForUserCode();
        assertNull(reasons[0]);
        final Transaction t1Copy = PARAMS.getDefaultSerializer().makeTransaction(t1.bitcoinSerialize());
        sendMoneyToWallet(AbstractBlockChain.NewBlockType.BEST_CHAIN, t1Copy);
        Threading.waitForUserCode();
        assertFalse(flags[0]);
        assertTrue(flags[1]);
        assertEquals(TransactionConfidence.ConfidenceType.BUILDING, notifiedTx[0].getConfidence().getConfidenceType());
        // Check we don't get notified about an irrelevant transaction.
        flags[0] = false;
        flags[1] = false;
        Transaction irrelevant = createFakeTx(PARAMS, nanos, OTHER_ADDRESS);
        if (wallet.isPendingTransactionRelevant(irrelevant))
            wallet.receivePending(irrelevant, null);
        Threading.waitForUserCode();
        assertFalse(flags[0]);
        assertEquals(3, walletChanged[0]);
    }

    @Test
    public void pending2() throws Exception {
        // Check that if we receive a pending tx we did not send, it updates our spent flags correctly.
        final Transaction[] txn = new Transaction[1];
        final Coin[] bigints = new Coin[2];
        wallet.addCoinsSentEventListener(new WalletCoinsSentEventListener() {
            @Override
            public void onCoinsSent(Wallet wallet, Transaction tx, Coin prevBalance, Coin newBalance) {
                txn[0] = tx;
                bigints[0] = prevBalance;
                bigints[1] = newBalance;
            }
        });
        // Receive some coins.
        Coin nanos = COIN;
        sendMoneyToWallet(AbstractBlockChain.NewBlockType.BEST_CHAIN, nanos);
        // Create a spend with them, but don't commit it (ie it's from somewhere else but using our keys). This TX
        // will have change as we don't spend our entire balance.
        Coin halfNanos = valueOf(0, 50);
        Transaction t2 = wallet.createSend(OTHER_ADDRESS, halfNanos);
        // Now receive it as pending.
        if (wallet.isPendingTransactionRelevant(t2))
            wallet.receivePending(t2, null);
        // We received an onCoinsSent() callback.
        Threading.waitForUserCode();
        assertEquals(t2, txn[0]);
        assertEquals(nanos, bigints[0]);
        assertEquals(halfNanos, bigints[1]);
        // Our balance is now 0.50 BTC
        assertEquals(halfNanos, wallet.getBalance(Wallet.BalanceType.ESTIMATED));
    }

    @Test
    public void pending3() throws Exception {
        // Check that if we receive a pending tx, and it's overridden by a double spend from the main chain, we
        // are notified that it's dead. This should work even if the pending tx inputs are NOT ours, ie, they don't
        // connect to anything.
        Coin nanos = COIN;

        // Create two transactions that share the same input tx.
        Address badGuy = new ECKey().toAddress(PARAMS);
        Transaction doubleSpentTx = new Transaction(PARAMS);
        TransactionOutput doubleSpentOut = new TransactionOutput(PARAMS, doubleSpentTx, nanos, badGuy);
        doubleSpentTx.addOutput(doubleSpentOut);
        Transaction t1 = new Transaction(PARAMS);
        TransactionOutput o1 = new TransactionOutput(PARAMS, t1, nanos, myAddress);
        t1.addOutput(o1);
        t1.addInput(doubleSpentOut);
        Transaction t2 = new Transaction(PARAMS);
        TransactionOutput o2 = new TransactionOutput(PARAMS, t2, nanos, badGuy);
        t2.addOutput(o2);
        t2.addInput(doubleSpentOut);

        final Transaction[] called = new Transaction[2];
        wallet.addCoinsReceivedEventListener(new WalletCoinsReceivedEventListener() {
            @Override
            public void onCoinsReceived(Wallet wallet, Transaction tx, Coin prevBalance, Coin newBalance) {
                called[0] = tx;
            }
        });

        wallet.addTransactionConfidenceEventListener(new TransactionConfidenceEventListener() {
            @Override
            public void onTransactionConfidenceChanged(Wallet wallet, Transaction tx) {
                if (tx.getConfidence().getConfidenceType() ==
                        TransactionConfidence.ConfidenceType.DEAD) {
                    called[0] = tx;
                    called[1] = tx.getConfidence().getOverridingTransaction();
                }
            }
        });

        assertEquals(ZERO, wallet.getBalance());
        if (wallet.isPendingTransactionRelevant(t1))
            wallet.receivePending(t1, null);
        Threading.waitForUserCode();
        assertEquals(t1, called[0]);
        assertEquals(nanos, wallet.getBalance(Wallet.BalanceType.ESTIMATED));
        // Now receive a double spend on the main chain.
        called[0] = called[1] = null;
        sendMoneyToWallet(AbstractBlockChain.NewBlockType.BEST_CHAIN, t2);
        Threading.waitForUserCode();
        assertEquals(ZERO, wallet.getBalance());
        assertEquals(t1, called[0]); // dead
        assertEquals(t2, called[1]); // replacement
    }

    @Test
    public void transactionsList() throws Exception {
        // Check the wallet can give us an ordered list of all received transactions.
        Utils.setMockClock();
        Transaction tx1 = sendMoneyToWallet(AbstractBlockChain.NewBlockType.BEST_CHAIN, COIN);
        Utils.rollMockClock(60 * 10);
        Transaction tx2 = sendMoneyToWallet(AbstractBlockChain.NewBlockType.BEST_CHAIN, valueOf(0, 5));
        // Check we got them back in order.
        List<Transaction> transactions = wallet.getTransactionsByTime();
        assertEquals(tx2, transactions.get(0));
        assertEquals(tx1, transactions.get(1));
        assertEquals(2, transactions.size());
        // Check we get only the last transaction if we request a subrage.
        transactions = wallet.getRecentTransactions(1, false);
        assertEquals(1, transactions.size());
        assertEquals(tx2,  transactions.get(0));

        // Create a spend five minutes later.
        Utils.rollMockClock(60 * 5);
        Transaction tx3 = wallet.createSend(OTHER_ADDRESS, valueOf(0, 5));
        // Does not appear in list yet.
        assertEquals(2, wallet.getTransactionsByTime().size());
        wallet.commitTx(tx3);
        // Now it does.
        transactions = wallet.getTransactionsByTime();
        assertEquals(3, transactions.size());
        assertEquals(tx3, transactions.get(0));

        // Verify we can handle the case of older wallets in which the timestamp is null (guessed from the
        // block appearances list).
        tx1.setUpdateTime(null);
        tx3.setUpdateTime(null);
        // Check we got them back in order.
        transactions = wallet.getTransactionsByTime();
        assertEquals(tx2,  transactions.get(0));
        assertEquals(3, transactions.size());
    }

    @Test
    public void keyCreationTime() throws Exception {
        Utils.setMockClock();
        long now = Utils.currentTimeSeconds();
        wallet = new Wallet(PARAMS);
        assertEquals(now, wallet.getEarliestKeyCreationTime());
        Utils.rollMockClock(60);
        wallet.freshReceiveKey();
        assertEquals(now, wallet.getEarliestKeyCreationTime());
    }

    @Test
    public void scriptCreationTime() throws Exception {
        Utils.setMockClock();
        long now = Utils.currentTimeSeconds();
        wallet = new Wallet(PARAMS);
        assertEquals(now, wallet.getEarliestKeyCreationTime());
        Utils.rollMockClock(-120);
        wallet.addWatchedAddress(OTHER_ADDRESS);
        wallet.freshReceiveKey();
        assertEquals(now - 120, wallet.getEarliestKeyCreationTime());
    }

    @Test
    public void spendToSameWallet() throws Exception {
        // Test that a spend to the same wallet is dealt with correctly.
        // It should appear in the wallet and confirm.
        // This is a bit of a silly thing to do in the real world as all it does is burn a fee but it is perfectly valid.
        Coin coin1 = COIN;
        Coin coinHalf = valueOf(0, 50);
        // Start by giving us 1 coin.
        sendMoneyToWallet(AbstractBlockChain.NewBlockType.BEST_CHAIN, coin1);
        // Send half to ourselves. We should then have a balance available to spend of zero.
        assertEquals(1, wallet.getPoolSize(WalletTransaction.Pool.UNSPENT));
        assertEquals(1, wallet.getTransactions(true).size());
        Transaction outbound1 = wallet.createSend(myAddress, coinHalf);
        wallet.commitTx(outbound1);
        // We should have a zero available balance before the next block.
        assertEquals(ZERO, wallet.getBalance());
        sendMoneyToWallet(AbstractBlockChain.NewBlockType.BEST_CHAIN, outbound1);
        // We should have a balance of 1 BTC after the block is received.
        assertEquals(coin1, wallet.getBalance());
    }

    @Test
    public void lastBlockSeen() throws Exception {
        Coin v1 = valueOf(5, 0);
        Coin v2 = valueOf(0, 50);
        Coin v3 = valueOf(0, 25);
        Transaction t1 = createFakeTx(PARAMS, v1, myAddress);
        Transaction t2 = createFakeTx(PARAMS, v2, myAddress);
        Transaction t3 = createFakeTx(PARAMS, v3, myAddress);

        Block genesis = blockStore.getChainHead().getHeader();
        Block b10 = makeSolvedTestBlock(genesis, t1);
        Block b11 = makeSolvedTestBlock(genesis, t2);
        Block b2 = makeSolvedTestBlock(b10, t3);
        Block b3 = makeSolvedTestBlock(b2);

        // Receive a block on the best chain - this should set the last block seen hash.
        chain.add(b10);
        assertEquals(b10.getHash(), wallet.getLastBlockSeenHash());
        assertEquals(b10.getTimeSeconds(),