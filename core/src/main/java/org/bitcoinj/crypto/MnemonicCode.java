/*
 * Copyright 2013 Ken Sedgwick
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

import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Stopwatch;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.bitcoinj.core.Utils.HEX;

/**
 * A MnemonicCode object may be used to convert between binary seed values and
 * lists of words per <a href="https://github.com/bitcoin/bips/blob/master/bip-0039.mediawiki">the BIP 39
 * specification</a>
 */

public class MnemonicCode {
    private static final Logger log = LoggerFactory.getLogger(MnemonicCode.class);

    private ArrayList<String> wordList;

    private static final String BIP39_ENGLISH_RESOURCE_NAME = "mnemonic/wordlist/english.txt";
    private static final String BIP39_ENGLISH_SHA256 = "ad90bf3beb7b0eb7e5acd74727dc0da96e0a280a258354e7293fb7e211ac03db";

    /** UNIX time for when the BIP39 standard was finalised. This can be used as a default seed birthday. */
    public static long BIP39_STANDARDISATION_TIME_SECS = 1381276800;

    private static final int PBKDF2_ROUNDS = 2048;

    public static MnemonicCode INSTANCE;

    static {
        try {
            INSTANCE = new MnemonicCode();
        } catch (FileNotFoundException e) {
            // We expect failure on Android. The developer has to set INSTANCE themselves.
            if (!Utils.isAndroidRuntime())
                log.error("Could not find word list", e);
        } catch (IOException e) {
            log.error("Failed to load word list", e);
        }
    }

    /** Initialise from the included word list. Won't work on Android. */
    public MnemonicCode() throws IOException {
        this(openDefaultWords(), BIP39_ENGLISH_SHA256);
    }

    private static InputStream openDefaultWords() throws IOException {
        InputStream stream = MnemonicCode.class.getResourceAsStream(BIP39_ENGLISH_RESOURCE_NAME);
        if (stream == null)
            throw new FileNotFoundException(BIP39_ENGLISH_RESOURCE_NAME);
        return stream;
    }

    /**
     * Creates an MnemonicCode object, initializing with words read from the supplied input stream.  If a wordListDigest
     * is supplied the digest of the words will be checked.
     */
    public MnemonicCode(InputStream wordstream, String wordListDigest) throws IOException, IllegalArgumentException {
        BufferedReader br = new BufferedReader(new InputStreamReader(wordstream, "UTF-8"));
        wordList = new ArrayList<>(2048);

        String word;
        while ((word = br.readLine()) != null)
            wordList.add(word);
        br.close();

        initializeFromWords(wordList, wordListDigest);
    }

    /**
     * Creates an MnemonicCode object, initializing with words read from the supplied ArrayList.  If a wordListDigest
     * is supplied the digest of the words will be checked.
     */
    public MnemonicCode(ArrayList<String> wordList, String wordListDigest) throws IllegalArgumentException {
        initializeFromWords(wordList, wordListDigest);
    }

    private void initializeFromWords(ArrayList<String> wordList, String wordListDigest) throws IllegalArgumentException {
        if (wordList.size() != 2048)
            throw new IllegalArgumentException("input wordlist did not contain 2048 words");

        this.wordList = wordList;

        // If a wordListDigest is supplied check to make sure it matches.
        if (wordListDigest != null) {
            MessageDigest md = Sha256Hash.newDigest();
            for (String word : wordList)
                md.update(word.getBytes());

            byte[] digest = md.digest();
            String hexdigest = HEX.encode(digest);
            if (!hexdigest.equals(wordListDigest))
                throw new IllegalArgumentException("wordlist digest mismatch");
        }
    }

    /**
     * Gets the word list this code uses.
     */
    public List<String> getWordList() {
        return wordList;
    }

    /**
     * Convert mnemonic word list to seed.
     */
    public static byte[] toSeed(List<String> words, String passphrase) {

        // To create binary seed from mnemonic, we use PBKDF2 function
        // with mnemonic sentence (in UTF-8) used as a password and
        // string "mnemonic" + passphrase (again in UTF-8) used as a
        // salt. Iteration count is set to 4096 and HMAC-SHA512 is
        // used as a pseudo-random function. Desired length of the
        // derived key is 512 bits (= 64 bytes).
        //
        String pass = Utils.join(words);
        String salt = "mnemonic" + passphrase;

        final Stopwatch watch 