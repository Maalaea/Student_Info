/*
 * Copyright 2012 Google Inc.
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

import org.bitcoinj.core.*;
import org.bitcoinj.core.TransactionConfidence.ConfidenceType;
import org.bitcoinj.crypto.KeyCrypter;
import org.bitcoinj.crypto.KeyCrypterScrypt;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptException;
import org.bitcoinj.signers.LocalTransactionSigner;
import org.bitcoinj.signers.TransactionSigner;
import org.bitcoinj.utils.ExchangeRate;
import org.bitcoinj.utils.Fiat;
import org.bitcoinj.wallet.Protos.Wallet.EncryptionType;

import com.google.common.collect.Lists;
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.TextFormat;
import com.google.protobuf.WireFormat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Serialize and de-serialize a wallet to a byte stream containing a
 * <a href="https://developers.google.com/protocol-buffers/docs/overview">protocol buffer</a>. Protocol buffers are
 * a data interchange format developed by Google with an efficient binary representation, a type safe specification
 * language and compilers that generate code to work with those data structures for many languages. Protocol buffers
 * can have their format evolved over time: conceptually they represent data using (tag, length, value) tuples. The
 * format is defined by the <tt>wallet.proto</tt> file in the bitcoinj source distribution.<p>
 *
 * This class is used through its static methods. The most common operations are writeWallet and readWallet, which do
 * the obvious operations on Output/InputStreams. You can use a {@link java.io.ByteArrayInputStream} and equivalent
 * {@link java.io.ByteArrayOutputStream} if you'd like byte arrays instead. The protocol buffer can also be manipulated
 * in its object form if you'd like to modify the flattened data structure before serialization to binary.<p>
 *
 * You can extend the wallet format with additional fields specific to your application if you want, but make sure
 * to either put the extra data in the provided extension areas, or select tag numbers that are unlikely to be used
 * by anyone else.<p>
 *
 * @author Miron Cuperman
 * @author Andreas Schildbach
 */
public class WalletProtobufSerializer {
    private static final Logger log = LoggerFactory.getLogger(WalletProtobufSerializer.class);
    /** Current version used for serializing wallets. A version higher than this is considered from the future. */
    public static final int CURRENT_WALLET_VERSION = Protos.Wallet.getDefaultInstance().getVersion();
    // 512 MB
    private static final int WALLET_SIZE_LIMIT = 512 * 1024 * 1024;
    // Used for de-serialization
    protected Map<ByteString, Transaction> txMap;

    private boolean requireMandatoryExtensions = true;
    private boolean requireAllExtensionsKnown = false;
    private int walletWriteBufferSize = CodedOutputStream.DEFAULT_BUFFER_SIZE;

    public interface WalletFactory {
        Wallet create(NetworkParameters params, KeyChainGroup keyChainGroup);
    }

    private final WalletFactory factory;
    private KeyChainFactory keyChainFactory;

    public WalletProtobufSerializer() {
        this(new WalletFactory() {
            @Override
            public Wallet create(NetworkParameters params, KeyChainGroup keyChainGroup) {
                return new Wallet(params, keyChainGroup);
            }
        });
    }

    public WalletProtobufSerializer(WalletFactory factory) {
        txMap = new HashMap<>();
        this.factory = factory;
        this.keyChainFactory = new DefaultKeyChainFactory();
    }

    public void setKeyChainFactory(KeyChainFactory keyChainFactory) {
        this.keyChainFactory = keyChainFactory;
    }

    /**
     * If this property is set to false, then unknown mandatory extensions will be ignored instead of causing load
     * errors. You should only use this if you know exactly what you are doing, as the extension data will NOT be
     * round-tripped, possibly resulting in a corrupted wallet if you save it back out again.
     */
    public void setRequireMandatoryExtensions(boolean value) {
        requireMandatoryExtensions = value;
    }

    /**
     * If this property is set to true, the wallet will fail to load if  any found extensions are unknown..
     */
    public void setRequireAllExtensionsKnown(boolean value) {
        requireAllExtensionsKnown = value;
    }

    /**
     * Change buffer size for writing wallet to output stream. Default is {@link com.google.protobuf.CodedOutputStream.DEFAULT_BUFFER_SIZE}
     * @param walletWriteBufferSize - buffer size in bytes
     */
    public void setWalletWriteBufferSize(int walletWriteBufferSize) {
        this.walletWriteBufferSize = walletWriteBufferSize;
    }

    /**
     * Formats the given wallet (transactions and keys) to the given output stream in protocol buffer format.<p>
     *
     * Equivalent to <tt>walletToProto(wallet).writeTo(output);</tt>
     */
    public void writeWallet(Wallet wallet, OutputStream output) throws IOException {
        Protos.Wallet walletProto = walletToProto(wallet);
        final CodedOutputStream codedOutput = CodedOutputStream.newInstance(output, this.walletWriteBufferSize);
        walletProto.writeTo(codedOutput);
        codedOutput.flush();
    }

    /**
     * Returns the given wallet formatted as text. The text format is that used by protocol buffers and although it
     * can also be parsed using {@link TextFormat#merge(CharSequence, com.google.protobuf.Message.Builder)},
     * it is designed more for debugging than storage. It is not well specified and wallets are largely binary data
     * structures anyway, consisting as they do of keys (large random numbers) and {@link Transaction}s which also
     * mostly contain keys and hashes.
     */
    public String walletToText(Wallet wallet) {
        Protos.Wallet walletProto = walletToProto(wallet);
        return TextFormat.printToString(walletProto);
    }

    /**
     * Converts the given wallet to the object representation of the protocol buffers. This can be modified, or
     * additional data fields set, before serialization takes place.
     */
    public Protos.Wallet walletToProto(Wallet wallet) {
        Protos.Wallet.Builder walletBuilder = Protos.Wallet.newBuilder();
        walletBuilder.setNetworkIdentifier(wallet.getNetworkParameters().getId());
        if (wallet.getDescription() != null) {
            walletBuilder.setDescription(wallet.getDescription());
        }

        for (WalletTransaction wtx : wallet.getWalletTransactions()) {
            Protos.Transaction txProto = makeTxProto(wtx);
            walletBuilder.addTransaction(txProto);
        }

        walletBuilder.addAllKey(wallet.serializeKeyChainGroupToProtobuf());

        for (Script script : wallet.getWatchedScripts()) {
            Protos.Script protoScript =
                    Protos.Script.newBuilder()
                            .setProgram(ByteString.copyFrom(script.getProgram()))
                            .setCreationTimestamp(script.getCreationTimeSeconds() * 1000)
                            .build();

            walletBuilder.addWatchedScript(protoScript);
        }

        // Populate the lastSeenBlockHash field.
        Sha256Hash lastSeenBlockHash = wallet.getLastBlockSeenHash();
        if (lastSeenBlockHash != null) {
            walletBuilder.setLastSeenBlockHash(hashToByteString(lastSeenBlockHash));
            walletBuilder.setLastSeenBlockHeight(wallet.getLastBlockSeenHeight());
        }
        if (wallet.getLastBlockSeenTimeSecs() > 0)
            walletBuilder.setLastSeenBlockTimeSecs(wallet.getLastBlockSeenTimeSecs());

        // Populate the scrypt parameters.
        KeyCrypter keyCrypter = wallet.getKeyCrypter();
        if (keyCrypter == null) {
            // The wallet is unencrypted.
            walletBuilder.setEncryptionType(EncryptionType.UNENCRYPTED);
        } else {
            // The wallet is encrypted.
            walletBuilder.setEncryptionType(keyCrypter.getUnderstoodEncryptionType());
            if (keyCrypter instanceof KeyCrypterScrypt) {
                KeyCrypterScrypt keyCrypterScrypt = (KeyCrypterScrypt) keyCrypter;
                walletBuilder.setEncryptionParameters(keyCrypterScrypt.getScryptParameters());
            } else {
                // Some other form of encryption has been specified that we do not know how to persist.
                throw new RuntimeException("The wallet has encryption of type '" + keyCrypter.getUnderstoodEncryptionType() + "' but this WalletProtobufS