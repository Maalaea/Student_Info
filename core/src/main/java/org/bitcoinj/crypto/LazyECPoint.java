/*
 * Copyright by the original author or authors.
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

import org.bouncycastle.math.ec.ECCurve;
import org.bouncycastle.math.ec.ECFieldElement;
import org.bouncycastle.math.ec.ECPoint;

import javax.annotation.Nullable;
import java.math.BigInteger;
import java.util.Arrays;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * A wrapper around ECPoint that delays decoding of the point for as long as possible. This is useful because point
 * encode/decode in Bouncy Castle is quite slow especially on Dalvik, as it often involves decompression/recompression.
 */
public class LazyECPoint {
    // If curve is set, bits is also set. If curve is unset, point is set and bits is unset. Point can be set along
    // with curve and bits when the cached form has been accessed and thus must have been converted.

    private final ECCurve curve;
    private final byte[] bits;

    // This field is effectively final - once set it won't change again. However it can be set after
    // construction.
    @Nullable
    private ECPoint point;

    public LazyECPoint(ECCurve curve, byte[] bits) {
        this.curve = curve;
        this.bits = bits;
    }

    public LazyECPoint(ECPoint point) {
        this.point = checkNotNull(point);
        this.curve = null;
        this.bits = null;
    }

    public ECPoint get() {
        if (point == null)
            point = curve.decodePoint(bits);
        return point;
    }

    // Delegated methods.

    public ECPoint getDetachedPoint() {
        return get().getDetachedPoint();
    }

    public byte[] getEncoded() {
        if (bits != null)
            return Arrays.copyOf(bits, bits.length);
        else
            return get().getEncoded();
    }

    public boolean isInfinity() {
        return get().isInfinity();
    }

    public ECPoint timesPow2(int e) {
        return get().timesPow2(e);
    }

    public ECFieldElement getYCoord() {
        return get().getYCoord();
    }

    public ECFieldElement[] getZCoords() {
        return get().getZCoords();
    }

    public boolean isNormalized() {
        return get().isNormalized();
    }

    public boolean isCompressed() {
        if (bits != null)
            return bits[0] == 2 || bits[0] == 3;
        else
            return get().isCompressed();
    }

    public ECPoint multiply(BigInteger k) {
        return get().multiply(k);
    }

    public ECPoint subtract(ECPoint b) {
        return get().subtract(b);
    }

    public boolean isValid() {
        re