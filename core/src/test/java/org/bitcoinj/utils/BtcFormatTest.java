
/*
 * Copyright 2014 Adam Mackler
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

package org.bitcoinj.utils;

import org.bitcoinj.core.Coin;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import java.math.BigDecimal;
import java.text.*;
import java.text.AttributedCharacterIterator.Attribute;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import static org.bitcoinj.core.Coin.*;
import static org.bitcoinj.core.NetworkParameters.MAX_MONEY;
import static org.bitcoinj.utils.BtcAutoFormat.Style.CODE;
import static org.bitcoinj.utils.BtcAutoFormat.Style.SYMBOL;
import static org.bitcoinj.utils.BtcFixedFormat.REPEATING_DOUBLETS;
import static org.bitcoinj.utils.BtcFixedFormat.REPEATING_TRIPLETS;
import static java.text.NumberFormat.Field.DECIMAL_SEPARATOR;
import static java.util.Locale.*;
import static org.junit.Assert.*;

@RunWith(Parameterized.class)
public class BtcFormatTest {

    @Parameters
    public static Set<Locale[]> data() {
        Set<Locale[]> localeSet = new HashSet<>();
        for (Locale locale : Locale.getAvailableLocales()) {
            localeSet.add(new Locale[]{locale});
        }
        return localeSet;
    }

    public BtcFormatTest(Locale defaultLocale) {
        Locale.setDefault(defaultLocale);
    }
 
    @Test
    public void prefixTest() { // prefix b/c symbol is prefixed
        BtcFormat usFormat = BtcFormat.getSymbolInstance(Locale.US);
        assertEquals("฿1.00", usFormat.format(COIN));
        assertEquals("฿1.01", usFormat.format(101000000));
        assertEquals("₥฿0.01", usFormat.format(1000));
        assertEquals("₥฿1,011.00", usFormat.format(101100000));
        assertEquals("₥฿1,000.01", usFormat.format(100001000));
        assertEquals("µ฿1,000,001.00", usFormat.format(100000100));
        assertEquals("µ฿1,000,000.10", usFormat.format(100000010));
        assertEquals("µ฿1,000,000.01", usFormat.format(100000001));
        assertEquals("µ฿1.00", usFormat.format(100));
        assertEquals("µ฿0.10", usFormat.format(10));
        assertEquals("µ฿0.01", usFormat.format(1));
    }

    @Test
    public void suffixTest() {
        BtcFormat deFormat = BtcFormat.getSymbolInstance(Locale.GERMANY);
        // int
        assertEquals("1,00 ฿", deFormat.format(100000000));
        assertEquals("1,01 ฿", deFormat.format(101000000));
        assertEquals("1.011,00 ₥฿", deFormat.format(101100000));
        assertEquals("1.000,01 ₥฿", deFormat.format(100001000));
        assertEquals("1.000.001,00 µ฿", deFormat.format(100000100));
        assertEquals("1.000.000,10 µ฿", deFormat.format(100000010));
        assertEquals("1.000.000,01 µ฿", deFormat.format(100000001));
    }

    @Test
    public void defaultLocaleTest() {
        assertEquals(
             "Default Locale is " + Locale.getDefault().toString(),
             BtcFormat.getInstance().pattern(), BtcFormat.getInstance(Locale.getDefault()).pattern()
        );
        assertEquals(
            "Default Locale is " + Locale.getDefault().toString(),
            BtcFormat.getCodeInstance().pattern(),
            BtcFormat.getCodeInstance(Locale.getDefault()).pattern()
       );
    }

    @Test
    public void symbolCollisionTest() {
        Locale[] locales = BtcFormat.getAvailableLocales();
        for (int i = 0; i < locales.length; ++i) {
            String cs = ((DecimalFormat)NumberFormat.getCurrencyInstance(locales[i])).
                        getDecimalFormatSymbols().getCurrencySymbol();
            if (cs.contains("฿")) {
                BtcFormat bf = BtcFormat.getSymbolInstance(locales[i]);
                String coin = bf.format(COIN);
                assertTrue(coin.contains("Ƀ"));
                assertFalse(coin.contains("฿"));
                String milli = bf.format(valueOf(10000));
                assertTrue(milli.contains("₥Ƀ"));
                assertFalse(milli.contains("฿"));
                String micro = bf.format(valueOf(100));
                assertTrue(micro.contains("µɃ"));
                assertFalse(micro.contains("฿"));
                BtcFormat ff = BtcFormat.builder().scale(0).locale(locales[i]).pattern("¤#.#").build();
                assertEquals("Ƀ", ((BtcFixedFormat)ff).symbol());
                assertEquals("Ƀ", ff.coinSymbol());
                coin = ff.format(COIN);
                assertTrue(coin.contains("Ƀ"));
                assertFalse(coin.contains("฿"));
                BtcFormat mlff = BtcFormat.builder().scale(3).locale(locales[i]).pattern("¤#.#").build();
                assertEquals("₥Ƀ", ((BtcFixedFormat)mlff).symbol());
                assertEquals("Ƀ", mlff.coinSymbol());
                milli = mlff.format(valueOf(10000));
                assertTrue(milli.contains("₥Ƀ"));
                assertFalse(milli.contains("฿"));
                BtcFormat mcff = BtcFormat.builder().scale(6).locale(locales[i]).pattern("¤#.#").build();
                assertEquals("µɃ", ((BtcFixedFormat)mcff).symbol());
                assertEquals("Ƀ", mcff.coinSymbol());
                micro = mcff.format(valueOf(100));
                assertTrue(micro.contains("µɃ"));
                assertFalse(micro.contains("฿"));
            }
            if (cs.contains("Ƀ")) {  // NB: We don't know of any such existing locale, but check anyway.
                BtcFormat bf = BtcFormat.getInstance(locales[i]);
                String coin = bf.format(COIN);
                assertTrue(coin.contains("฿"));
                assertFalse(coin.contains("Ƀ"));
                String milli = bf.format(valueOf(10000));
                assertTrue(milli.contains("₥฿"));
                assertFalse(milli.contains("Ƀ"));
                String micro = bf.format(valueOf(100));
                assertTrue(micro.contains("µ฿"));
                assertFalse(micro.contains("Ƀ"));
            }
        }
    }

    @Test
    public void argumentTypeTest() {
        BtcFormat usFormat = BtcFormat.getSymbolInstance(Locale.US);
        // longs are tested above
        // Coin
        assertEquals("µ฿1,000,000.01", usFormat.format(COIN.add(valueOf(1))));
        // Integer
        assertEquals("µ฿21,474,836.47" ,usFormat.format(Integer.MAX_VALUE));
        assertEquals("(µ฿21,474,836.48)" ,usFormat.format(Integer.MIN_VALUE));
        // Long
        assertEquals("µ฿92,233,720,368,547,758.07" ,usFormat.format(Long.MAX_VALUE));
        assertEquals("(µ฿92,233,720,368,547,758.08)" ,usFormat.format(Long.MIN_VALUE));
        // BigInteger
        assertEquals("µ฿0.10" ,usFormat.format(java.math.BigInteger.TEN));
        assertEquals("฿0.00" ,usFormat.format(java.math.BigInteger.ZERO));
        // BigDecimal
        assertEquals("฿1.00" ,usFormat.format(java.math.BigDecimal.ONE));
        assertEquals("฿0.00" ,usFormat.format(java.math.BigDecimal.ZERO));
        // use of Double not encouraged but no way to stop user from converting one to BigDecimal
        assertEquals(
            "฿179,769,313,486,231,570,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000,000.00",
            usFormat.format(java.math.BigDecimal.valueOf(Double.MAX_VALUE)));
        assertEquals("฿0.00", usFormat.format(java.math.BigDecimal.valueOf(Double.MIN_VALUE)));
        assertEquals(
            "฿340,282,346,638,528,860,000,000,000,000,000,000,000.00",
            usFormat.format(java.math.BigDecimal.valueOf(Float.MAX_VALUE)));
        // Bad type
        try {
            usFormat.format("1");
            fail("should not have tried to format a String");
        } catch (IllegalArgumentException e) {
        }
    }

    @Test
    public void columnAlignmentTest() {
        BtcFormat germany = BtcFormat.getCoinInstance(2,BtcFixedFormat.REPEATING_PLACES);
        char separator = germany.symbols().getDecimalSeparator();
        Coin[] rows = {MAX_MONEY, MAX_MONEY.subtract(SATOSHI), Coin.parseCoin("1234"),
                       COIN, COIN.add(SATOSHI), COIN.subtract(SATOSHI),
                        COIN.divide(1000).add(SATOSHI), COIN.divide(1000), COIN.divide(1000).subtract(SATOSHI),
                       valueOf(100), valueOf(1000), valueOf(10000),
                       SATOSHI};
        FieldPosition fp = new FieldPosition(DECIMAL_SEPARATOR);
        String[] output = new String[rows.length];
        int[] indexes = new int[rows.length];
        int maxIndex = 0;
        for (int i = 0; i < rows.length; i++) {
            output[i] = germany.format(rows[i], new StringBuffer(), fp).toString();
            indexes[i] = fp.getBeginIndex();
            if (indexes[i] > maxIndex) maxIndex = indexes[i];
        }
        for (int i = 0; i < output.length; i++) {
            // uncomment to watch printout
            // System.out.println(repeat(" ", (maxIndex - indexes[i])) + output[i]);
            assertEquals(output[i].indexOf(separator), indexes[i]);
        }
    }

    @Test
    public void repeatingPlaceTest() {
        BtcFormat mega = BtcFormat.getInstance(-6, US);
        Coin value = MAX_MONEY.subtract(SATOSHI);
        assertEquals("20.99999999999999", mega.format(value, 0, BtcFixedFormat.REPEATING_PLACES));
        assertEquals("20.99999999999999", mega.format(value, 0, BtcFixedFormat.REPEATING_PLACES));
        assertEquals("20.99999999999999", mega.format(value, 1, BtcFixedFormat.REPEATING_PLACES));
        assertEquals("20.99999999999999", mega.format(value, 2, BtcFixedFormat.REPEATING_PLACES));
        assertEquals("20.99999999999999", mega.format(value, 3, BtcFixedFormat.REPEATING_PLACES));
        assertEquals("20.99999999999999", mega.format(value, 0, BtcFixedFormat.REPEATING_DOUBLETS));
        assertEquals("20.99999999999999", mega.format(value, 1, BtcFixedFormat.REPEATING_DOUBLETS));
        assertEquals("20.99999999999999", mega.format(value, 2, BtcFixedFormat.REPEATING_DOUBLETS));
        assertEquals("20.99999999999999", mega.format(value, 3, BtcFixedFormat.REPEATING_DOUBLETS));
        assertEquals("20.99999999999999", mega.format(value, 0, BtcFixedFormat.REPEATING_TRIPLETS));
        assertEquals("20.99999999999999", mega.format(value, 1, BtcFixedFormat.REPEATING_TRIPLETS));
        assertEquals("20.99999999999999", mega.format(value, 2, BtcFixedFormat.REPEATING_TRIPLETS));
        assertEquals("20.99999999999999", mega.format(value, 3, BtcFixedFormat.REPEATING_TRIPLETS));
        assertEquals("1.00000005", BtcFormat.getCoinInstance(US).
                                   format(COIN.add(Coin.valueOf(5)), 0, BtcFixedFormat.REPEATING_PLACES));
    }

    @Test
    public void characterIteratorTest() {
        BtcFormat usFormat = BtcFormat.getInstance(Locale.US);
        AttributedCharacterIterator i = usFormat.formatToCharacterIterator(parseCoin("1234.5"));
        java.util.Set<Attribute> a = i.getAllAttributeKeys();
        assertTrue("Missing currency attribute", a.contains(NumberFormat.Field.CURRENCY));
        assertTrue("Missing integer attribute", a.contains(NumberFormat.Field.INTEGER));
        assertTrue("Missing fraction attribute", a.contains(NumberFormat.Field.FRACTION));
        assertTrue("Missing decimal separator attribute", a.contains(NumberFormat.Field.DECIMAL_SEPARATOR));
        assertTrue("Missing grouping separator attribute", a.contains(NumberFormat.Field.GROUPING_SEPARATOR));
        assertTrue("Missing currency attribute", a.contains(NumberFormat.Field.CURRENCY));

        char c;
        i = BtcFormat.getCodeInstance(Locale.US).formatToCharacterIterator(new BigDecimal("0.19246362747414458"));
        // formatted as "µBTC 192,463.63"
        assertEquals(0, i.getBeginIndex());
        assertEquals(15, i.getEndIndex());
        int n = 0;
        for(c = i.first(); i.getAttribute(NumberFormat.Field.CURRENCY) != null; c = i.next()) {
            n++;
        }
        assertEquals(4, n);
        n = 0;
        for(i.next(); i.getAttribute(NumberFormat.Field.INTEGER) != null && i.getAttribute(NumberFormat.Field.GROUPING_SEPARATOR) != NumberFormat.Field.GROUPING_SEPARATOR; c = i.next()) {
            n++;
        }
        assertEquals(3, n);
        assertEquals(NumberFormat.Field.INTEGER, i.getAttribute(NumberFormat.Field.INTEGER));
        n = 0;
        for(c = i.next(); i.getAttribute(NumberFormat.Field.INTEGER) != null; c = i.next()) {
            n++;
        }
        assertEquals(3, n);
        assertEquals(NumberFormat.Field.DECIMAL_SEPARATOR, i.getAttribute(NumberFormat.Field.DECIMAL_SEPARATOR));
        n = 0;
        for(c = i.next(); c != CharacterIterator.DONE; c = i.next()) {
            n++;
            assertNotNull(i.getAttribute(NumberFormat.Field.FRACTION));
        }
        assertEquals(2,n);

        // immutability check
        BtcFormat fa = BtcFormat.getSymbolInstance(US);
        BtcFormat fb = BtcFormat.getSymbolInstance(US);
        assertEquals(fa, fb);
        assertEquals(fa.hashCode(), fb.hashCode());
        fa.formatToCharacterIterator(COIN.multiply(1000000));
        assertEquals(fa, fb);
        assertEquals(fa.hashCode(), fb.hashCode());
        fb.formatToCharacterIterator(COIN.divide(1000000));
        assertEquals(fa, fb);
        assertEquals(fa.hashCode(), fb.hashCode());
    }

    @Test
    public void parseTest() throws java.text.ParseException {
        BtcFormat us = BtcFormat.getSymbolInstance(Locale.US);
        BtcFormat usCoded = BtcFormat.getCodeInstance(Locale.US);
        // Coins
        assertEquals(valueOf(200000000), us.parseObject("BTC2"));
        assertEquals(valueOf(200000000), us.parseObject("XBT2"));
        assertEquals(valueOf(200000000), us.parseObject("฿2"));
        assertEquals(valueOf(200000000), us.parseObject("Ƀ2"));
        assertEquals(valueOf(200000000), us.parseObject("2"));
        assertEquals(valueOf(200000000), usCoded.parseObject("BTC 2"));
        assertEquals(valueOf(200000000), usCoded.parseObject("XBT 2"));
        assertEquals(valueOf(200000000), us.parseObject("฿2.0"));
        assertEquals(valueOf(200000000), us.parseObject("Ƀ2.0"));
        assertEquals(valueOf(200000000), us.parseObject("2.0"));
        assertEquals(valueOf(200000000), us.parseObject("BTC2.0"));
        assertEquals(valueOf(200000000), us.parseObject("XBT2.0"));
        assertEquals(valueOf(200000000), usCoded.parseObject("฿ 2"));
        assertEquals(valueOf(200000000), usCoded.parseObject("Ƀ 2"));
        assertEquals(valueOf(200000000), usCoded.parseObject(" 2"));
        assertEquals(valueOf(200000000), usCoded.parseObject("BTC 2"));
        assertEquals(valueOf(200000000), usCoded.parseObject("XBT 2"));
        assertEquals(valueOf(202222420000000L), us.parseObject("2,022,224.20"));
        assertEquals(valueOf(202222420000000L), us.parseObject("฿2,022,224.20"));
        assertEquals(valueOf(202222420000000L), us.parseObject("Ƀ2,022,224.20"));
        assertEquals(valueOf(202222420000000L), us.parseObject("BTC2,022,224.20"));
        assertEquals(valueOf(202222420000000L), us.parseObject("XBT2,022,224.20"));
        assertEquals(valueOf(220200000000L), us.parseObject("2,202.0"));
        assertEquals(valueOf(2100000000000000L), us.parseObject("21000000.00000000"));
        // MilliCoins
        assertEquals(valueOf(200000), usCoded.parseObject("mBTC 2"));
        assertEquals(valueOf(200000), usCoded.parseObject("mXBT 2"));
        assertEquals(valueOf(200000), usCoded.parseObject("m฿ 2"));
        assertEquals(valueOf(200000), usCoded.parseObject("mɃ 2"));
        assertEquals(valueOf(200000), us.parseObject("mBTC2"));
        assertEquals(valueOf(200000), us.parseObject("mXBT2"));
        assertEquals(valueOf(200000), us.parseObject("₥฿2"));
        assertEquals(valueOf(200000), us.parseObject("₥Ƀ2"));
        assertEquals(valueOf(200000), us.parseObject("₥2"));
        assertEquals(valueOf(200000), usCoded.parseObject("₥BTC 2.00"));
        assertEquals(valueOf(200000), usCoded.parseObject("₥XBT 2.00"));
        assertEquals(valueOf(200000), usCoded.parseObject("₥BTC 2"));
        assertEquals(valueOf(200000), usCoded.parseObject("₥XBT 2"));
        assertEquals(valueOf(200000), usCoded.parseObject("₥฿ 2"));
        assertEquals(valueOf(200000), usCoded.parseObject("₥Ƀ 2"));
        assertEquals(valueOf(200000), usCoded.parseObject("₥ 2"));
        assertEquals(valueOf(202222400000L), us.parseObject("₥฿2,022,224"));
        assertEquals(valueOf(202222420000L), us.parseObject("₥Ƀ2,022,224.20"));
        assertEquals(valueOf(202222400000L), us.parseObject("m฿2,022,224"));
        assertEquals(valueOf(202222420000L), us.parseObject("mɃ2,022,224.20"));
        assertEquals(valueOf(202222400000L), us.parseObject("₥BTC2,022,224"));
        assertEquals(valueOf(202222400000L), us.parseObject("₥XBT2,022,224"));
        assertEquals(valueOf(202222400000L), us.parseObject("mBTC2,022,224"));
        assertEquals(valueOf(202222400000L), us.parseObject("mXBT2,022,224"));
        assertEquals(valueOf(202222420000L), us.parseObject("₥2,022,224.20"));
        assertEquals(valueOf(202222400000L), usCoded.parseObject("₥฿ 2,022,224"));
        assertEquals(valueOf(202222420000L), usCoded.parseObject("₥Ƀ 2,022,224.20"));
        assertEquals(valueOf(202222400000L), usCoded.parseObject("m฿ 2,022,224"));
        assertEquals(valueOf(202222420000L), usCoded.parseObject("mɃ 2,022,224.20"));
        assertEquals(valueOf(202222400000L), usCoded.parseObject("₥BTC 2,022,224"));
        assertEquals(valueOf(202222400000L), usCoded.parseObject("₥XBT 2,022,224"));
        assertEquals(valueOf(202222400000L), usCoded.parseObject("mBTC 2,022,224"));
        assertEquals(valueOf(202222400000L), usCoded.parseObject("mXBT 2,022,224"));
        assertEquals(valueOf(202222420000L), usCoded.parseObject("₥ 2,022,224.20"));
        // Microcoins
        assertEquals(valueOf(435), us.parseObject("µ฿4.35"));
        assertEquals(valueOf(435), us.parseObject("uɃ4.35"));
        assertEquals(valueOf(435), us.parseObject("u฿4.35"));
        assertEquals(valueOf(435), us.parseObject("µɃ4.35"));
        assertEquals(valueOf(435), us.parseObject("uBTC4.35"));
        assertEquals(valueOf(435), us.parseObject("uXBT4.35"));
        assertEquals(valueOf(435), us.parseObject("µBTC4.35"));
        assertEquals(valueOf(435), us.parseObject("µXBT4.35"));
        assertEquals(valueOf(435), usCoded.parseObject("uBTC 4.35"));
        assertEquals(valueOf(435), usCoded.parseObject("uXBT 4.35"));
        assertEquals(valueOf(435), usCoded.parseObject("µBTC 4.35"));
        assertEquals(valueOf(435), usCoded.parseObject("µXBT 4.35"));
        // fractional satoshi; round up
        assertEquals(valueOf(435), us.parseObject("uBTC4.345"));
        assertEquals(valueOf(435), us.parseObject("uXBT4.345"));
        // negative with mu symbol
        assertEquals(valueOf(-1), usCoded.parseObject("(µ฿ 0.01)"));
        assertEquals(valueOf(-10), us.parseObject("(µBTC0.100)"));
        assertEquals(valueOf(-10), us.parseObject("(µXBT0.100)"));

        // Same thing with addition of custom code, symbol
        us = BtcFormat.builder().locale(US).style(SYMBOL).symbol("£").code("XYZ").build();
        usCoded = BtcFormat.builder().locale(US).scale(0).symbol("£").code("XYZ").
                            pattern("¤ #,##0.00").build();
        // Coins
        assertEquals(valueOf(200000000), us.parseObject("XYZ2"));
        assertEquals(valueOf(200000000), us.parseObject("BTC2"));
        assertEquals(valueOf(200000000), us.parseObject("XBT2"));
        assertEquals(valueOf(200000000), us.parseObject("£2"));
        assertEquals(valueOf(200000000), us.parseObject("฿2"));
        assertEquals(valueOf(200000000), us.parseObject("Ƀ2"));
        assertEquals(valueOf(200000000), us.parseObject("2"));
        assertEquals(valueOf(200000000), usCoded.parseObject("XYZ 2"));
        assertEquals(valueOf(200000000), usCoded.parseObject("BTC 2"));
        assertEquals(valueOf(200000000), usCoded.parseObject("XBT 2"));
        assertEquals(valueOf(200000000), us.parseObject("£2.0"));
        assertEquals(valueOf(200000000), us.parseObject("฿2.0"));
        assertEquals(valueOf(200000000), us.parseObject("Ƀ2.0"));
        assertEquals(valueOf(200000000), us.parseObject("2.0"));
        assertEquals(valueOf(200000000), us.parseObject("XYZ2.0"));
        assertEquals(valueOf(200000000), us.parseObject("BTC2.0"));
        assertEquals(valueOf(200000000), us.parseObject("XBT2.0"));
        assertEquals(valueOf(200000000), usCoded.parseObject("£ 2"));
        assertEquals(valueOf(200000000), usCoded.parseObject("฿ 2"));
        assertEquals(valueOf(200000000), usCoded.parseObject("Ƀ 2"));
        assertEquals(valueOf(200000000), usCoded.parseObject(" 2"));
        assertEquals(valueOf(200000000), usCoded.parseObject("XYZ 2"));
        assertEquals(valueOf(200000000), usCoded.parseObject("BTC 2"));
        assertEquals(valueOf(200000000), usCoded.parseObject("XBT 2"));
        assertEquals(valueOf(202222420000000L), us.parseObject("2,022,224.20"));
        assertEquals(valueOf(202222420000000L), us.parseObject("£2,022,224.20"));
        assertEquals(valueOf(202222420000000L), us.parseObject("฿2,022,224.20"));
        assertEquals(valueOf(202222420000000L), us.parseObject("Ƀ2,022,224.20"));
        assertEquals(valueOf(202222420000000L), us.parseObject("XYZ2,022,224.20"));
        assertEquals(valueOf(202222420000000L), us.parseObject("BTC2,022,224.20"));
        assertEquals(valueOf(202222420000000L), us.parseObject("XBT2,022,224.20"));
        assertEquals(valueOf(220200000000L), us.parseObject("2,202.0"));
        assertEquals(valueOf(2100000000000000L), us.parseObject("21000000.00000000"));
        // MilliCoins
        assertEquals(valueOf(200000), usCoded.parseObject("mXYZ 2"));
        assertEquals(valueOf(200000), usCoded.parseObject("mBTC 2"));
        assertEquals(valueOf(200000), usCoded.parseObject("mXBT 2"));
        assertEquals(valueOf(200000), usCoded.parseObject("m£ 2"));
        assertEquals(valueOf(200000), usCoded.parseObject("m฿ 2"));
        assertEquals(valueOf(200000), usCoded.parseObject("mɃ 2"));
        assertEquals(valueOf(200000), us.parseObject("mXYZ2"));
        assertEquals(valueOf(200000), us.parseObject("mBTC2"));
        assertEquals(valueOf(200000), us.parseObject("mXBT2"));
        assertEquals(valueOf(200000), us.parseObject("₥£2"));
        assertEquals(valueOf(200000), us.parseObject("₥฿2"));
        assertEquals(valueOf(200000), us.parseObject("₥Ƀ2"));
        assertEquals(valueOf(200000), us.parseObject("₥2"));
        assertEquals(valueOf(200000), usCoded.parseObject("₥XYZ 2.00"));
        assertEquals(valueOf(200000), usCoded.parseObject("₥BTC 2.00"));
        assertEquals(valueOf(200000), usCoded.parseObject("₥XBT 2.00"));
        assertEquals(valueOf(200000), usCoded.parseObject("₥XYZ 2"));
        assertEquals(valueOf(200000), usCoded.parseObject("₥BTC 2"));
        assertEquals(valueOf(200000), usCoded.parseObject("₥XBT 2"));
        assertEquals(valueOf(200000), usCoded.parseObject("₥£ 2"));
        assertEquals(valueOf(200000), usCoded.parseObject("₥฿ 2"));
        assertEquals(valueOf(200000), usCoded.parseObject("₥Ƀ 2"));
        assertEquals(valueOf(200000), usCoded.parseObject("₥ 2"));
        assertEquals(valueOf(202222400000L), us.parseObject("₥£2,022,224"));
        assertEquals(valueOf(202222400000L), us.parseObject("₥฿2,022,224"));
        assertEquals(valueOf(202222420000L), us.parseObject("₥Ƀ2,022,224.20"));
        assertEquals(valueOf(202222400000L), us.parseObject("m£2,022,224"));
        assertEquals(valueOf(202222400000L), us.parseObject("m฿2,022,224"));
        assertEquals(valueOf(202222420000L), us.parseObject("mɃ2,022,224.20"));
        assertEquals(valueOf(202222400000L), us.parseObject("₥XYZ2,022,224"));
        assertEquals(valueOf(202222400000L), us.parseObject("₥BTC2,022,224"));
        assertEquals(valueOf(202222400000L), us.parseObject("₥XBT2,022,224"));
        assertEquals(valueOf(202222400000L), us.parseObject("mXYZ2,022,224"));
        assertEquals(valueOf(202222400000L), us.parseObject("mBTC2,022,224"));
        assertEquals(valueOf(202222400000L), us.parseObject("mXBT2,022,224"));
        assertEquals(valueOf(202222420000L), us.parseObject("₥2,022,224.20"));
        assertEquals(valueOf(202222400000L), usCoded.parseObject("₥£ 2,022,224"));
        assertEquals(valueOf(202222400000L), usCoded.parseObject("₥฿ 2,022,224"));
        assertEquals(valueOf(202222420000L), usCoded.parseObject("₥Ƀ 2,022,224.20"));
        assertEquals(valueOf(202222400000L), usCoded.parseObject("m£ 2,022,224"));
        assertEquals(valueOf(202222400000L), usCoded.parseObject("m฿ 2,022,224"));
        assertEquals(valueOf(202222420000L), usCoded.parseObject("mɃ 2,022,224.20"));
        assertEquals(valueOf(202222400000L), usCoded.parseObject("₥XYZ 2,022,224"));
        assertEquals(valueOf(202222400000L), usCoded.parseObject("₥BTC 2,022,224"));
        assertEquals(valueOf(202222400000L), usCoded.parseObject("₥XBT 2,022,224"));
        assertEquals(valueOf(202222400000L), usCoded.parseObject("mXYZ 2,022,224"));
        assertEquals(valueOf(202222400000L), usCoded.parseObject("mBTC 2,022,224"));
        assertEquals(valueOf(202222400000L), usCoded.parseObject("mXBT 2,022,224"));
        assertEquals(valueOf(202222420000L), usCoded.parseObject("₥ 2,022,224.20"));
        // Microcoins
        assertEquals(valueOf(435), us.parseObject("µ£4.35"));
        assertEquals(valueOf(435), us.parseObject("µ฿4.35"));
        assertEquals(valueOf(435), us.parseObject("uɃ4.35"));
        assertEquals(valueOf(435), us.parseObject("u£4.35"));
        assertEquals(valueOf(435), us.parseObject("u฿4.35"));
        assertEquals(valueOf(435), us.parseObject("µɃ4.35"));
        assertEquals(valueOf(435), us.parseObject("uXYZ4.35"));
        assertEquals(valueOf(435), us.parseObject("uBTC4.35"));
        assertEquals(valueOf(435), us.parseObject("uXBT4.35"));
        assertEquals(valueOf(435), us.parseObject("µXYZ4.35"));
        assertEquals(valueOf(435), us.parseObject("µBTC4.35"));
        assertEquals(valueOf(435), us.parseObject("µXBT4.35"));
        assertEquals(valueOf(435), usCoded.parseObject("uXYZ 4.35"));
        assertEquals(valueOf(435), usCoded.parseObject("uBTC 4.35"));
        assertEquals(valueOf(435), usCoded.parseObject("uXBT 4.35"));
        assertEquals(valueOf(435), usCoded.parseObject("µXYZ 4.35"));
        assertEquals(valueOf(435), usCoded.parseObject("µBTC 4.35"));
        assertEquals(valueOf(435), usCoded.parseObject("µXBT 4.35"));
        // fractional satoshi; round up
        assertEquals(valueOf(435), us.parseObject("uXYZ4.345"));
        assertEquals(valueOf(435), us.parseObject("uBTC4.345"));
        assertEquals(valueOf(435), us.parseObject("uXBT4.345"));
        // negative with mu symbol
        assertEquals(valueOf(-1), usCoded.parseObject("µ£ -0.01"));
        assertEquals(valueOf(-1), usCoded.parseObject("µ฿ -0.01"));
        assertEquals(valueOf(-10), us.parseObject("(µXYZ0.100)"));
        assertEquals(valueOf(-10), us.parseObject("(µBTC0.100)"));
        assertEquals(valueOf(-10), us.parseObject("(µXBT0.100)"));

        // parse() method as opposed to parseObject
        try {
            BtcFormat.getInstance().parse("abc");
            fail("bad parse must raise exception");
        } catch (ParseException e) {}
    }

    @Test
    public void parseMetricTest() throws ParseException {
        BtcFormat cp = BtcFormat.getCodeInstance(Locale.US);
        BtcFormat sp = BtcFormat.getSymbolInstance(Locale.US);
        // coin
        assertEquals(parseCoin("1"), cp.parseObject("BTC 1.00"));
        assertEquals(parseCoin("1"), sp.parseObject("BTC1.00"));
        assertEquals(parseCoin("1"), cp.parseObject("฿ 1.00"));
        assertEquals(parseCoin("1"), sp.parseObject("฿1.00"));
        assertEquals(parseCoin("1"), cp.parseObject("B⃦ 1.00"));
        assertEquals(parseCoin("1"), sp.parseObject("B⃦1.00"));
        assertEquals(parseCoin("1"), cp.parseObject("Ƀ 1.00"));
        assertEquals(parseCoin("1"), sp.parseObject("Ƀ1.00"));
        // milli
        assertEquals(parseCoin("0.001"), cp.parseObject("mBTC 1.00"));
        assertEquals(parseCoin("0.001"), sp.parseObject("mBTC1.00"));
        assertEquals(parseCoin("0.001"), cp.parseObject("m฿ 1.00"));
        assertEquals(parseCoin("0.001"), sp.parseObject("m฿1.00"));
        assertEquals(parseCoin("0.001"), cp.parseObject("mB⃦ 1.00"));
        assertEquals(parseCoin("0.001"), sp.parseObject("mB⃦1.00"));
        assertEquals(parseCoin("0.001"), cp.parseObject("mɃ 1.00"));
        assertEquals(parseCoin("0.001"), sp.parseObject("mɃ1.00"));
        assertEquals(parseCoin("0.001"), cp.parseObject("₥BTC 1.00"));
        assertEquals(parseCoin("0.001"), sp.parseObject("₥BTC1.00"));
        assertEquals(parseCoin("0.001"), cp.parseObject("₥฿ 1.00"));
        assertEquals(parseCoin("0.001"), sp.parseObject("₥฿1.00"));
        assertEquals(parseCoin("0.001"), cp.parseObject("₥B⃦ 1.00"));
        assertEquals(parseCoin("0.001"), sp.parseObject("₥B⃦1.00"));
        assertEquals(parseCoin("0.001"), cp.parseObject("₥Ƀ 1.00"));
        assertEquals(parseCoin("0.001"), sp.parseObject("₥Ƀ1.00"));
        // micro
        assertEquals(parseCoin("0.000001"), cp.parseObject("uBTC 1.00"));
        assertEquals(parseCoin("0.000001"), sp.parseObject("uBTC1.00"));
        assertEquals(parseCoin("0.000001"), cp.parseObject("u฿ 1.00"));
        assertEquals(parseCoin("0.000001"), sp.parseObject("u฿1.00"));
        assertEquals(parseCoin("0.000001"), cp.parseObject("uB⃦ 1.00"));
        assertEquals(parseCoin("0.000001"), sp.parseObject("uB⃦1.00"));
        assertEquals(parseCoin("0.000001"), cp.parseObject("uɃ 1.00"));
        assertEquals(parseCoin("0.000001"), sp.parseObject("uɃ1.00"));
        assertEquals(parseCoin("0.000001"), cp.parseObject("µBTC 1.00"));
        assertEquals(parseCoin("0.000001"), sp.parseObject("µBTC1.00"));
        assertEquals(parseCoin("0.000001"), cp.parseObject("µ฿ 1.00"));
        assertEquals(parseCoin("0.000001"), sp.parseObject("µ฿1.00"));
        assertEquals(parseCoin("0.000001"), cp.parseObject("µB⃦ 1.00"));
        assertEquals(parseCoin("0.000001"), sp.parseObject("µB⃦1.00"));
        assertEquals(parseCoin("0.000001"), cp.parseObject("µɃ 1.00"));
        assertEquals(parseCoin("0.000001"), sp.parseObject("µɃ1.00"));
        // satoshi
        assertEquals(parseCoin("0.00000001"), cp.parseObject("uBTC 0.01"));
        assertEquals(parseCoin("0.00000001"), sp.parseObject("uBTC0.01"));
        assertEquals(parseCoin("0.00000001"), cp.parseObject("u฿ 0.01"));
        assertEquals(parseCoin("0.00000001"), sp.parseObject("u฿0.01"));
        assertEquals(parseCoin("0.00000001"), cp.parseObject("uB⃦ 0.01"));
        assertEquals(parseCoin("0.00000001"), sp.parseObject("uB⃦0.01"));
        assertEquals(parseCoin("0.00000001"), cp.parseObject("uɃ 0.01"));
        assertEquals(parseCoin("0.00000001"), sp.parseObject("uɃ0.01"));
        assertEquals(parseCoin("0.00000001"), cp.parseObject("µBTC 0.01"));
        assertEquals(parseCoin("0.00000001"), sp.parseObject("µBTC0.01"));
        assertEquals(parseCoin("0.00000001"), cp.parseObject("µ฿ 0.01"));
        assertEquals(parseCoin("0.00000001"), sp.parseObject("µ฿0.01"));
        assertEquals(parseCoin("0.00000001"), cp.parseObject("µB⃦ 0.01"));
        assertEquals(parseCoin("0.00000001"), sp.parseObject("µB⃦0.01"));
        assertEquals(parseCoin("0.00000001"), cp.parseObject("µɃ 0.01"));
        assertEquals(parseCoin("0.00000001"), sp.parseObject("µɃ0.01"));
        // cents
        assertEquals(parseCoin("0.01234567"), cp.parseObject("cBTC 1.234567"));
        assertEquals(parseCoin("0.01234567"), sp.parseObject("cBTC1.234567"));
        assertEquals(parseCoin("0.01234567"), cp.parseObject("c฿ 1.234567"));
        assertEquals(parseCoin("0.01234567"), sp.parseObject("c฿1.234567"));
        assertEquals(parseCoin("0.01234567"), cp.parseObject("cB⃦ 1.234567"));
        assertEquals(parseCoin("0.01234567"), sp.parseObject("cB⃦1.234567"));
        assertEquals(parseCoin("0.01234567"), cp.parseObject("cɃ 1.234567"));
        assertEquals(parseCoin("0.01234567"), sp.parseObject("cɃ1.234567"));
        assertEquals(parseCoin("0.01234567"), cp.parseObject("¢BTC 1.234567"));
        assertEquals(parseCoin("0.01234567"), sp.parseObject("¢BTC1.234567"));
        assertEquals(parseCoin("0.01234567"), cp.parseObject("¢฿ 1.234567"));
        assertEquals(parseCoin("0.01234567"), sp.parseObject("¢฿1.234567"));
        assertEquals(parseCoin("0.01234567"), cp.parseObject("¢B⃦ 1.234567"));
        assertEquals(parseCoin("0.01234567"), sp.parseObject("¢B⃦1.234567"));
        assertEquals(parseCoin("0.01234567"), cp.parseObject("¢Ƀ 1.234567"));
        assertEquals(parseCoin("0.01234567"), sp.parseObject("¢Ƀ1.234567"));
        // dekacoins
        assertEquals(parseCoin("12.34567"), cp.parseObject("daBTC 1.234567"));
        assertEquals(parseCoin("12.34567"), sp.parseObject("daBTC1.234567"));
        assertEquals(parseCoin("12.34567"), cp.parseObject("da฿ 1.234567"));
        assertEquals(parseCoin("12.34567"), sp.parseObject("da฿1.234567"));
        assertEquals(parseCoin("12.34567"), cp.parseObject("daB⃦ 1.234567"));
        assertEquals(parseCoin("12.34567"), sp.parseObject("daB⃦1.234567"));
        assertEquals(parseCoin("12.34567"), cp.parseObject("daɃ 1.234567"));
        assertEquals(parseCoin("12.34567"), sp.parseObject("daɃ1.234567"));
        // hectocoins
        assertEquals(parseCoin("123.4567"), cp.parseObject("hBTC 1.234567"));
        assertEquals(parseCoin("123.4567"), sp.parseObject("hBTC1.234567"));
        assertEquals(parseCoin("123.4567"), cp.parseObject("h฿ 1.234567"));
        assertEquals(parseCoin("123.4567"), sp.parseObject("h฿1.234567"));
        assertEquals(parseCoin("123.4567"), cp.parseObject("hB⃦ 1.234567"));
        assertEquals(parseCoin("123.4567"), sp.parseObject("hB⃦1.234567"));
        assertEquals(parseCoin("123.4567"), cp.parseObject("hɃ 1.234567"));
        assertEquals(parseCoin("123.4567"), sp.parseObject("hɃ1.234567"));
        // kilocoins
        assertEquals(parseCoin("1234.567"), cp.parseObject("kBTC 1.234567"));
        assertEquals(parseCoin("1234.567"), sp.parseObject("kBTC1.234567"));
        assertEquals(parseCoin("1234.567"), cp.parseObject("k฿ 1.234567"));
        assertEquals(parseCoin("1234.567"), sp.parseObject("k฿1.234567"));
        assertEquals(parseCoin("1234.567"), cp.parseObject("kB⃦ 1.234567"));
        assertEquals(parseCoin("1234.567"), sp.parseObject("kB⃦1.234567"));
        assertEquals(parseCoin("1234.567"), cp.parseObject("kɃ 1.234567"));
        assertEquals(parseCoin("1234.567"), sp.parseObject("kɃ1.234567"));
        // megacoins
        assertEquals(parseCoin("1234567"), cp.parseObject("MBTC 1.234567"));
        assertEquals(parseCoin("1234567"), sp.parseObject("MBTC1.234567"));
        assertEquals(parseCoin("1234567"), cp.parseObject("M฿ 1.234567"));
        assertEquals(parseCoin("1234567"), sp.parseObject("M฿1.234567"));
        assertEquals(parseCoin("1234567"), cp.parseObject("MB⃦ 1.234567"));
        assertEquals(parseCoin("1234567"), sp.parseObject("MB⃦1.234567"));
        assertEquals(parseCoin("1234567"), cp.parseObject("MɃ 1.234567"));
        assertEquals(parseCoin("1234567"), sp.parseObject("MɃ1.234567"));
    }

    @Test
    public void parsePositionTest() {
        BtcFormat usCoded = BtcFormat.getCodeInstance(Locale.US);
        // Test the field constants
        FieldPosition intField = new FieldPosition(NumberFormat.Field.INTEGER);
        assertEquals(
          "987,654,321",
          usCoded.format(valueOf(98765432123L), new StringBuffer(), intField).
          substring(intField.getBeginIndex(), intField.getEndIndex())
        );
        FieldPosition fracField = new FieldPosition(NumberFormat.Field.FRACTION);
        assertEquals(
          "23",
          usCoded.format(valueOf(98765432123L), new StringBuffer(), fracField).
          substring(fracField.getBeginIndex(), fracField.getEndIndex())
        );

        // for currency we use a locale that puts the units at the end
        BtcFormat de = BtcFormat.getSymbolInstance(Locale.GERMANY);
        BtcFormat deCoded = BtcFormat.getCodeInstance(Locale.GERMANY);
        FieldPosition currField = new FieldPosition(NumberFormat.Field.CURRENCY);
        assertEquals(
          "µ฿",
          de.format(valueOf(98765432123L), new StringBuffer(), currField).
          substring(currField.getBeginIndex(), currField.getEndIndex())
        );
        assertEquals(
          "µBTC",
          deCoded.format(valueOf(98765432123L), new StringBuffer(), currField).
          substring(currField.getBeginIndex(), currField.getEndIndex())
        );
        assertEquals(
          "₥฿",
          de.format(valueOf(98765432000L), new StringBuffer(), currField).
          substring(currField.getBeginIndex(), currField.getEndIndex())
        );
        assertEquals(
          "mBTC",
          deCoded.format(valueOf(98765432000L), new StringBuffer(), currField).
          substring(currField.getBeginIndex(), currField.getEndIndex())
        );
        assertEquals(
          "฿",
          de.format(valueOf(98765000000L), new StringBuffer(), currField).
          substring(currField.getBeginIndex(), currField.getEndIndex())
        );
        assertEquals(
          "BTC",
          deCoded.format(valueOf(98765000000L), new StringBuffer(), currField).
          substring(currField.getBeginIndex(), currField.getEndIndex())
        );
    }

    @Test
    public void currencyCodeTest() {
        /* Insert needed space AFTER currency-code */
        BtcFormat usCoded = BtcFormat.getCodeInstance(Locale.US);
        assertEquals("µBTC 0.01", usCoded.format(1));
        assertEquals("BTC 1.00", usCoded.format(COIN));

        /* Do not insert unneeded space BEFORE currency-code */
        BtcFormat frCoded = BtcFormat.getCodeInstance(Locale.FRANCE);
        assertEquals("0,01 µBTC", frCoded.format(1));
        assertEquals("1,00 BTC", frCoded.format(COIN));

        /* Insert needed space BEFORE currency-code: no known currency pattern does this? */

        /* Do not insert unneeded space AFTER currency-code */
        BtcFormat deCoded = BtcFormat.getCodeInstance(Locale.ITALY);
        assertEquals("µBTC 0,01", deCoded.format(1));
        assertEquals("BTC 1,00", deCoded.format(COIN));
    }

    @Test
    public void coinScaleTest() throws Exception {
        BtcFormat coinFormat = BtcFormat.getCoinInstance(Locale.US);
        assertEquals("1.00", coinFormat.format(Coin.COIN));
        assertEquals("-1.00", coinFormat.format(Coin.COIN.negate()));
        assertEquals(Coin.parseCoin("1"), coinFormat.parseObject("1.00"));
        assertEquals(valueOf(1000000), coinFormat.parseObject("0.01"));
        assertEquals(Coin.parseCoin("1000"), coinFormat.parseObject("1,000.00"));
        assertEquals(Coin.parseCoin("1000"), coinFormat.parseObject("1000"));
    }

    @Test
    public void millicoinScaleTest() throws Exception {
        BtcFormat coinFormat = BtcFormat.getMilliInstance(Locale.US);
        assertEquals("1,000.00", coinFormat.format(Coin.COIN));
        assertEquals("-1,000.00", coinFormat.format(Coin.COIN.negate()));
        assertEquals(Coin.parseCoin("0.001"), coinFormat.parseObject("1.00"));
        assertEquals(valueOf(1000), coinFormat.parseObject("0.01"));
        assertEquals(Coin.parseCoin("1"), coinFormat.parseObject("1,000.00"));
        assertEquals(Coin.parseCoin("1"), coinFormat.parseObject("1000"));
    }

    @Test
    public void microcoinScaleTest() throws Exception {
        BtcFormat coinFormat = BtcFormat.getMicroInstance(Locale.US);
        assertEquals("1,000,000.00", coinFormat.format(Coin.COIN));
        assertEquals("-1,000,000.00", coinFormat.format(Coin.COIN.negate()));
        assertEquals("1,000,000.10", coinFormat.format(Coin.COIN.add(valueOf(10))));
        assertEquals(Coin.parseCoin("0.000001"), coinFormat.parseObject("1.00"));
        assertEquals(valueOf(1), coinFormat.parseObject("0.01"));
        assertEquals(Coin.parseCoin("0.001"), coinFormat.parseObject("1,000.00"));
        assertEquals(Coin.parseCoin("0.001"), coinFormat.parseObject("1000"));
    }

    @Test
    public void testGrouping() throws Exception {
        BtcFormat usCoin = BtcFormat.getInstance(0, Locale.US, 1, 2, 3);
        assertEquals("0.1", usCoin.format(Coin.parseCoin("0.1")));
        assertEquals("0.010", usCoin.format(Coin.parseCoin("0.01")));
        assertEquals("0.001", usCoin.format(Coin.parseCoin("0.001")));
        assertEquals("0.000100", usCoin.format(Coin.parseCoin("0.0001")));
        assertEquals("0.000010", usCoin.format(Coin.parseCoin("0.00001")));
        assertEquals("0.000001", usCoin.format(Coin.parseCoin("0.000001")));

        // no more than two fractional decimal places for the default coin-denomination
        assertEquals("0.01", BtcFormat.getCoinInstance(Locale.US).format(Coin.parseCoin("0.005")));

        BtcFormat usMilli = BtcFormat.getInstance(3, Locale.US, 1, 2, 3);
        assertEquals("0.1", usMilli.format(Coin.parseCoin("0.0001")));
        assertEquals("0.010", usMilli.format(Coin.parseCoin("0.00001")));
        assertEquals("0.001", usMilli.format(Coin.parseCoin("0.000001")));
        // even though last group is 3, that would result in fractional satoshis, which we don't do
        assertEquals("0.00010", usMilli.format(Coin.valueOf(10)));
        assertEquals("0.00001", usMilli.format(Coin.valueOf(1)));

        BtcFormat usMicro = BtcFormat.getInstance(6, Locale.US, 1, 2, 3);
        assertEquals("0.1", usMicro.format(Coin.valueOf(10)));
        // even though second group is 2, that would result in fractional satoshis, which we don't do
        assertEquals("0.01", usMicro.format(Coin.valueOf(1)));
    }


    /* These just make sure factory methods don't raise exceptions.
     * Other tests inspect their return values. */
    @Test
    public void factoryTest() {
        BtcFormat coded = BtcFormat.getInstance(0, 1, 2, 3);
        BtcFormat.getInstance(BtcAutoFormat.Style.CODE);
        BtcAutoFormat symbolic = (BtcAutoFormat)BtcFormat.getInstance(BtcAutoFormat.Style.SYMBOL);
        assertEquals(2, symbolic.fractionPlaces());
        BtcFormat.getInstance(BtcAutoFormat.Style.CODE, 3);
        assertEquals(3, ((BtcAutoFormat)BtcFormat.getInstance(BtcAutoFormat.Style.SYMBOL, 3)).fractionPlaces());
        BtcFormat.getInstance(BtcAutoFormat.Style.SYMBOL, Locale.US, 3);
        BtcFormat.getInstance(BtcAutoFormat.Style.CODE, Locale.US);
        BtcFormat.getInstance(BtcAutoFormat.Style.SYMBOL, Locale.US);
        BtcFormat.getCoinInstance(2, BtcFixedFormat.REPEATING_PLACES);
        BtcFormat.getMilliInstance(1, 2, 3);
        BtcFormat.getInstance(2);
        BtcFormat.getInstance(2, Locale.US);
        BtcFormat.getCodeInstance(3);
        BtcFormat.getSymbolInstance(3);
        BtcFormat.getCodeInstance(Locale.US, 3);
        BtcFormat.getSymbolInstance(Locale.US, 3);
        try {
            BtcFormat.getInstance(SMALLEST_UNIT_EXPONENT + 1);
            fail("should not have constructed an instance with denomination less than satoshi");
        } catch (IllegalArgumentException e) {}
    }
    @Test
    public void factoryArgumentsTest() {
        Locale locale;
        if (Locale.getDefault().equals(GERMANY)) locale = FRANCE;
        else locale = GERMANY;
        assertEquals(BtcFormat.getInstance(), BtcFormat.getCodeInstance());
        assertEquals(BtcFormat.getInstance(locale), BtcFormat.getCodeInstance(locale));
        assertEquals(BtcFormat.getInstance(BtcAutoFormat.Style.CODE), BtcFormat.getCodeInstance());
        assertEquals(BtcFormat.getInstance(BtcAutoFormat.Style.SYMBOL), BtcFormat.getSymbolInstance());
        assertEquals(BtcFormat.getInstance(BtcAutoFormat.Style.CODE,3), BtcFormat.getCodeInstance(3));
        assertEquals(BtcFormat.getInstance(BtcAutoFormat.Style.SYMBOL,3), BtcFormat.getSymbolInstance(3));
        assertEquals(BtcFormat.getInstance(BtcAutoFormat.Style.CODE,locale), BtcFormat.getCodeInstance(locale));
        assertEquals(BtcFormat.getInstance(BtcAutoFormat.Style.SYMBOL,locale), BtcFormat.getSymbolInstance(locale));
        assertEquals(BtcFormat.getInstance(BtcAutoFormat.Style.CODE,locale,3), BtcFormat.getCodeInstance(locale,3));
        assertEquals(BtcFormat.getInstance(BtcAutoFormat.Style.SYMBOL,locale,3), BtcFormat.getSymbolInstance(locale,3));
        assertEquals(BtcFormat.getCoinInstance(), BtcFormat.getInstance(0));
        assertEquals(BtcFormat.getMilliInstance(), BtcFormat.getInstance(3));
        assertEquals(BtcFormat.getMicroInstance(), BtcFormat.getInstance(6));
        assertEquals(BtcFormat.getCoinInstance(3), BtcFormat.getInstance(0,3));
        assertEquals(BtcFormat.getMilliInstance(3), BtcFormat.getInstance(3,3));
        assertEquals(BtcFormat.getMicroInstance(3), BtcFormat.getInstance(6,3));
        assertEquals(BtcFormat.getCoinInstance(3,4,5), BtcFormat.getInstance(0,3,4,5));
        assertEquals(BtcFormat.getMilliInstance(3,4,5), BtcFormat.getInstance(3,3,4,5));
        assertEquals(BtcFormat.getMicroInstance(3,4,5), BtcFormat.getInstance(6,3,4,5));
        assertEquals(BtcFormat.getCoinInstance(locale), BtcFormat.getInstance(0,locale));
        assertEquals(BtcFormat.getMilliInstance(locale), BtcFormat.getInstance(3,locale));
        assertEquals(BtcFormat.getMicroInstance(locale), BtcFormat.getInstance(6,locale));
        assertEquals(BtcFormat.getCoinInstance(locale,4,5), BtcFormat.getInstance(0,locale,4,5));
        assertEquals(BtcFormat.getMilliInstance(locale,4,5), BtcFormat.getInstance(3,locale,4,5));
        assertEquals(BtcFormat.getMicroInstance(locale,4,5), BtcFormat.getInstance(6,locale,4,5));
    }

    @Test
    public void autoDecimalTest() {
        BtcFormat codedZero = BtcFormat.getCodeInstance(Locale.US, 0);
        BtcFormat symbolZero = BtcFormat.getSymbolInstance(Locale.US, 0);
        assertEquals("฿1", symbolZero.format(COIN));
        assertEquals("BTC 1", codedZero.format(COIN));
        assertEquals("µ฿1,000,000", symbolZero.format(COIN.subtract(SATOSHI)));
        assertEquals("µBTC 1,000,000", codedZero.format(COIN.subtract(SATOSHI)));
        assertEquals("µ฿1,000,000", symbolZero.format(COIN.subtract(Coin.valueOf(50))));
        assertEquals("µBTC 1,000,000", codedZero.format(COIN.subtract(Coin.valueOf(50))));
        assertEquals("µ฿999,999", symbolZero.format(COIN.subtract(Coin.valueOf(51))));
        assertEquals("µBTC 999,999", codedZero.format(COIN.subtract(Coin.valueOf(51))));
        assertEquals("฿1,000", symbolZero.format(COIN.multiply(1000)));
        assertEquals("BTC 1,000", codedZero.format(COIN.multiply(1000)));
        assertEquals("µ฿1", symbolZero.format(Coin.valueOf(100)));
        assertEquals("µBTC 1", codedZero.format(Coin.valueOf(100)));
        assertEquals("µ฿1", symbolZero.format(Coin.valueOf(50)));
        assertEquals("µBTC 1", codedZero.format(Coin.valueOf(50)));
        assertEquals("µ฿0", symbolZero.format(Coin.valueOf(49)));
        assertEquals("µBTC 0", codedZero.format(Coin.valueOf(49)));
        assertEquals("µ฿0", symbolZero.format(Coin.valueOf(1)));
        assertEquals("µBTC 0", codedZero.format(Coin.valueOf(1)));
        assertEquals("µ฿500,000", symbolZero.format(Coin.valueOf(49999999)));
        assertEquals("µBTC 500,000", codedZero.format(Coin.valueOf(49999999)));

        assertEquals("µ฿499,500", symbolZero.format(Coin.valueOf(49950000)));
        assertEquals("µBTC 499,500", codedZero.format(Coin.valueOf(49950000)));
        assertEquals("µ฿499,500", symbolZero.format(Coin.valueOf(49949999)));
        assertEquals("µBTC 499,500", codedZero.format(Coin.valueOf(49949999)));
        assertEquals("µ฿500,490", symbolZero.format(Coin.valueOf(50049000)));
        assertEquals("µBTC 500,490", codedZero.format(Coin.valueOf(50049000)));
        assertEquals("µ฿500,490", symbolZero.format(Coin.valueOf(50049001)));
        assertEquals("µBTC 500,490", codedZero.format(Coin.valueOf(50049001)));
        assertEquals("µ฿500,000", symbolZero.format(Coin.valueOf(49999950)));
        assertEquals("µBTC 500,000", codedZero.format(Coin.valueOf(49999950)));
        assertEquals("µ฿499,999", symbolZero.format(Coin.valueOf(49999949)));
        assertEquals("µBTC 499,999", codedZero.format(Coin.valueOf(49999949)));
        assertEquals("µ฿500,000", symbolZero.format(Coin.valueOf(50000049)));
        assertEquals("µBTC 500,000", codedZero.format(Coin.valueOf(50000049)));
        assertEquals("µ฿500,001", symbolZero.format(Coin.valueOf(50000050)));
        assertEquals("µBTC 500,001", codedZero.format(Coin.valueOf(50000050)));

        BtcFormat codedTwo = BtcFormat.getCodeInstance(Locale.US, 2);
        BtcFormat symbolTwo = BtcFormat.getSymbolInstance(Locale.US, 2);
        assertEquals("฿1.00", symbolTwo.format(COIN));
        assertEquals("BTC 1.00", codedTwo.format(COIN));
        assertEquals("µ฿999,999.99", symbolTwo.format(COIN.subtract(SATOSHI)));
        assertEquals("µBTC 999,999.99", codedTwo.format(COIN.subtract(SATOSHI)));
        assertEquals("฿1,000.00", symbolTwo.format(COIN.multiply(1000)));
        assertEquals("BTC 1,000.00", codedTwo.format(COIN.multiply(1000)));
        assertEquals("µ฿1.00", symbolTwo.format(Coin.valueOf(100)));
        assertEquals("µBTC 1.00", codedTwo.format(Coin.valueOf(100)));
        assertEquals("µ฿0.50", symbolTwo.format(Coin.valueOf(50)));
        assertEquals("µBTC 0.50", codedTwo.format(Coin.valueOf(50)));
        assertEquals("µ฿0.49", symbolTwo.format(Coin.valueOf(49)));
        assertEquals("µBTC 0.49", codedTwo.format(Coin.valueOf(49)));
        assertEquals("µ฿0.01", symbolTwo.format(Coin.valueOf(1)));
        assertEquals("µBTC 0.01", codedTwo.format(Coin.valueOf(1)));

        BtcFormat codedThree = BtcFormat.getCodeInstance(Locale.US, 3);
        BtcFormat symbolThree = BtcFormat.getSymbolInstance(Locale.US, 3);
        assertEquals("฿1.000", symbolThree.format(COIN));
        assertEquals("BTC 1.000", codedThree.format(COIN));
        assertEquals("µ฿999,999.99", symbolThree.format(COIN.subtract(SATOSHI)));
        assertEquals("µBTC 999,999.99", codedThree.format(COIN.subtract(SATOSHI)));
        assertEquals("฿1,000.000", symbolThree.format(COIN.multiply(1000)));
        assertEquals("BTC 1,000.000", codedThree.format(COIN.multiply(1000)));
        assertEquals("₥฿0.001", symbolThree.format(Coin.valueOf(100)));
        assertEquals("mBTC 0.001", codedThree.format(Coin.valueOf(100)));
        assertEquals("µ฿0.50", symbolThree.format(Coin.valueOf(50)));
        assertEquals("µBTC 0.50", codedThree.format(Coin.valueOf(50)));
        assertEquals("µ฿0.49", symbolThree.format(Coin.valueOf(49)));
        assertEquals("µBTC 0.49", codedThree.format(Coin.valueOf(49)));
        assertEquals("µ฿0.01", symbolThree.format(Coin.valueOf(1)));
        assertEquals("µBTC 0.01", codedThree.format(Coin.valueOf(1)));
    }


    @Test
    public void symbolsCodesTest() {
        BtcFixedFormat coin = (BtcFixedFormat)BtcFormat.getCoinInstance(US);
        assertEquals("BTC", coin.code());
        assertEquals("฿", coin.symbol());
        BtcFixedFormat cent = (BtcFixedFormat)BtcFormat.getInstance(2, US);
        assertEquals("cBTC", cent.code());
        assertEquals("¢฿", cent.symbol());
        BtcFixedFormat milli = (BtcFixedFormat)BtcFormat.getInstance(3, US);
        assertEquals("mBTC", milli.code());
        assertEquals("₥฿", milli.symbol());
        BtcFixedFormat micro = (BtcFixedFormat)BtcFormat.getInstance(6, US);
        assertEquals("µBTC", micro.code());
        assertEquals("µ฿", micro.symbol());
        BtcFixedFormat deka = (BtcFixedFormat)BtcFormat.getInstance(-1, US);
        assertEquals("daBTC", deka.code());
        assertEquals("da฿", deka.symbol());
        BtcFixedFormat hecto = (BtcFixedFormat)BtcFormat.getInstance(-2, US);
        assertEquals("hBTC", hecto.code());
        assertEquals("h฿", hecto.symbol());
        BtcFixedFormat kilo = (BtcFixedFormat)BtcFormat.getInstance(-3, US);
        assertEquals("kBTC", kilo.code());
        assertEquals("k฿", kilo.symbol());
        BtcFixedFormat mega = (BtcFixedFormat)BtcFormat.getInstance(-6, US);
        assertEquals("MBTC", mega.code());
        assertEquals("M฿", mega.symbol());
        BtcFixedFormat noSymbol = (BtcFixedFormat)BtcFormat.getInstance(4, US);
        try {
            noSymbol.symbol();
            fail("non-standard denomination has no symbol()");
        } catch (IllegalStateException e) {}
        try {
            noSymbol.code();
            fail("non-standard denomination has no code()");
        } catch (IllegalStateException e) {}

        BtcFixedFormat symbolCoin = (BtcFixedFormat)BtcFormat.builder().locale(US).scale(0).
                                                              symbol("B\u20e6").build();
        assertEquals("BTC", symbolCoin.code());
        assertEquals("B⃦", symbolCoin.symbol());
        BtcFixedFormat symbolCent = (BtcFixedFormat)BtcFormat.builder().locale(US).scale(2).
                                                              symbol("B\u20e6").build();
        assertEquals("cBTC", symbolCent.code());
        assertEquals("¢B⃦", symbolCent.symbol());
        BtcFixedFormat symbolMilli = (BtcFixedFormat)BtcFormat.builder().locale(US).scale(3).
                                                               symbol("B\u20e6").build();
        assertEquals("mBTC", symbolMilli.code());
        assertEquals("₥B⃦", symbolMilli.symbol());
        BtcFixedFormat symbolMicro = (BtcFixedFormat)BtcFormat.builder().locale(US).scale(6).
                                                               symbol("B\u20e6").build();
        assertEquals("µBTC", symbolMicro.code());
        assertEquals("µB⃦", symbolMicro.symbol());
        BtcFixedFormat symbolDeka = (BtcFixedFormat)BtcFormat.builder().locale(US).scale(-1).
                                                              symbol("B\u20e6").build();
        assertEquals("daBTC", symbolDeka.code());
        assertEquals("daB⃦", symbolDeka.symbol());
        BtcFixedFormat symbolHecto = (BtcFixedFormat)BtcFormat.builder().locale(US).scale(-2).
                                                               symbol("B\u20e6").build();
        assertEquals("hBTC", symbolHecto.code());