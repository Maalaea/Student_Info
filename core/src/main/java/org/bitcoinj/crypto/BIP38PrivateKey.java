/*
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
import com.google.common.base.Charsets;
import com.google.common.base.Objects;
import com.google.common.primitives.Bytes;
import com.lambdaworks.crypto.SCrypt;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.text.Normalizer;
import java.util.Arrays;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

/**
 * Implementation of <a href="https://github.com/bitcoin/bips/blob/master/bip-0038.mediawiki">BIP 38</a>
 * passphrase-protected private keys. Currently, only decryption is supported.
 */
public class BIP38PrivateKey extends VersionedChecksummedBytes {

    public transient NetworkParameters params;
    public final boolean ecMultiply;
    public final boolean compressed;
    public final boolean hasLotAndSequence;
    public final byte[] addressHash;
    public final byte[] content;

    public static final class BadPassphraseException extends Exception {
    }

    /**
     * Construct a password-protected private key from its Base58 representation.
     * @param params
     *            The network parameters of the chain that the key is for.
     * @param base58
     *            The textual form of the password-protected private key.
     * @throws AddressFormatException
     *             if the given base58 doesn't parse or the checksum is invalid
     */
    public static BIP38PrivateKey fromBase58(NetworkParameters params, String base58) throws AddressFormatException {
        return new BIP38PrivateKey(params, base58);
    }

    /** @deprecated Use {@link #fromBase58(NetworkParameters, String)} */
    @Deprecated
    public BIP38PrivateKey(NetworkParameters params, String encoded) throws AddressFormatException {
        super(encoded);
        this.params = checkNotNull(params);
        if (version != 0x01)
            throw new AddressFormatException("Mismatched version number: " + version);
        if (bytes.length != 38)
            throw new AddressFormatException("Wrong number of bytes, excluding version byte: " + bytes.length);
        hasLotAndSequence = (bytes[1] & 0x04) != 0; // bit 2
        compressed = (bytes[1] & 0x20) != 0; // bit 5
        if ((bytes[1] & 0x01) != 0) // bit 0
            throw new AddressFormatException("Bit 0x01 reserved for future use.");
        if ((bytes[1] & 0x02) != 0) // bit 1
            throw new AddressFormatException("Bit 0x02 reserved for future use.");
        if ((bytes[1] & 0x08) != 0) // bit 3
            throw new AddressFormatException("Bit 0x08 reserved for future use.");
        if ((bytes[1] & 0x10) != 0) // bit 4
            throw new AddressFormatException("Bit 0x10 reserved for future use.");
        final int byte0 = bytes[0] & 0xff;
        if (byte0 == 0x42) {
            // Non-EC-multiplied key
            if ((bytes[1] & 0xc0) != 0xc0) // bits 6+7
                throw new AddressFormatException("Bits 0x40 and 0x80 must be set for non-EC-multiplied keys.");
            ecMultiply = false;
            if (hasLotAndSequence)
                throw new AddressFormatException("Non-EC-multiplied keys cannot have lot/sequence.");
        } else if (byte0 == 0x43) {
            // EC-multiplied key
            if ((bytes[1] & 0xc0) != 0x00) // bits 6+7
                throw new AddressFormatException("Bits 0x40 and 0x80 must be cleared for EC-multiplied keys.");
            ecMultiply = true;
        } else {
            throw new AddressFormatException("Second byte must by 0x42 or 0x43.");
        }
        addressHash = Arrays.copyOfRange(bytes, 2, 6);
        content = Arrays.copyOfRange(bytes, 6, 38);
    }

    public ECKey decrypt(String passphrase) throws BadPassphraseException {
        String normalizedPassphrase = Normalizer.normalize(passphrase, Normalizer.Form.NFC);
        ECKey key = ecMultiply ? decryptEC(normalizedPassphrase) : decryptNoEC(normalizedPassphrase);
        Sha256Hash hash = Sha256Hash.twiceOf(key.toAddress(params).toString().getBytes(Charsets.US_ASCII));
        byte[] actualAddressHash = Arrays.copyOfRange(hash.getBytes(), 0, 4);
        if (!Arrays.equals(actualAddressHash, addressHash))
            throw new BadPassphraseException();
        return key;
    }

    private ECKey decryptNoEC(String normalizedPassphrase) {
        try {
            byte[] derived = SCrypt.scrypt(normalizedPassphrase.getBytes(Charsets.UTF_8), addressHash, 16384, 8, 8, 64);
            byte[] key = Arrays.copyOfRange(derived, 32, 64);
            SecretKeySpec keyspec = new SecretKeySpec(key, "AES");

            DRMWorkaround.maybeDisableExportControls();
            Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");

            cipher.init(Cipher.DECRYPT_MODE, keyspec);
            byte[] decrypted = cipher.doFinal(content, 0, 32);
            for (int i = 0; i < 32; i++)
                decrypted[i] ^= derived[i];
            return ECKey.fromPrivate(decrypted, compressed);
        } catch (GeneralSecurityException x) {
            throw new RuntimeException(x);
        }
    }

    private ECKey decryptEC(String normalizedPassphrase) {
        try {
            byte[] ownerEntropy = Arrays.copyOfRange(content, 0, 8);
            byte[] ownerSalt = hasLotAndSequence ? Arrays.copyOfRange(ownerEntropy, 0, 4) : ownerEntropy;

            byte[] passFactorBytes = SCrypt.scrypt(normalizedPassphrase.getBytes(Charsets.UTF_8), ownerSalt, 16384, 8, 8, 32);
            if (hasLotAndSequence) {
    