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