package io.freemytv;

import java.math.BigInteger;

/**
 * Just enough of the Edwards25519 curve for SPAKE2 (Spake2): points in extended coordinates, addition, scalar
 * multiplication, and the 32-byte encoding. BigInteger keeps it short and exact; it runs a handful of times per
 * pairing (a fraction of a second), never in a frame. Curve and formulas: RFC 8032.
 */
final class Ed25519 {
    static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    /** The order of the prime-order subgroup. */
    static final BigInteger L = BigInteger.ONE.shiftLeft(252).add(new BigInteger("27742317777372353535851937790883648493"));
    private static final BigInteger D = BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P);
    private static final BigInteger D2 = D.shiftLeft(1).mod(P);
    private static final BigInteger SQRT_M1 = BigInteger.valueOf(2).modPow(P.subtract(BigInteger.ONE).shiftRight(2), P);
    private static final BigInteger TWO = BigInteger.valueOf(2);

    /** The base point. */
    static final Point B = new Point(
            new BigInteger("15112221349535400772501151409588531511454012693041857206046113283949847762202"),
            new BigInteger("46316835694926478169428394003475163141307993866256225615783033603165251855960"),
            BigInteger.ONE,
            new BigInteger("15112221349535400772501151409588531511454012693041857206046113283949847762202")
                    .multiply(new BigInteger("46316835694926478169428394003475163141307993866256225615783033603165251855960")).mod(P));

    static final Point IDENTITY = new Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO);

    private Ed25519() {}

    /** A point in extended coordinates: x = X/Z, y = Y/Z, x·y = T/Z. */
    static final class Point {
        final BigInteger x, y, z, t;

        Point(BigInteger x, BigInteger y, BigInteger z, BigInteger t) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.t = t;
        }

        /** The unified addition (also doubles), for a = −1. */
        Point add(Point q) {
            BigInteger a = y.subtract(x).multiply(q.y.subtract(q.x)).mod(P);
            BigInteger b = y.add(x).multiply(q.y.add(q.x)).mod(P);
            BigInteger c = t.multiply(D2).multiply(q.t).mod(P);
            BigInteger d = z.multiply(TWO).multiply(q.z).mod(P);
            BigInteger e = b.subtract(a), f = d.subtract(c), g = d.add(c), h = b.add(a);
            return new Point(e.multiply(f).mod(P), g.multiply(h).mod(P), f.multiply(g).mod(P), e.multiply(h).mod(P));
        }

        Point negate() {
            return new Point(P.subtract(x).mod(P), y, z, P.subtract(t).mod(P));
        }

        Point subtract(Point q) {
            return add(q.negate());
        }

        /** k·this, for any non-negative k (not reduced: a point outside the prime-order group keeps its small part). */
        Point multiply(BigInteger k) {
            Point result = IDENTITY, addend = this;
            for (int i = 0; i < k.bitLength(); i++) {
                if (k.testBit(i)) result = result.add(addend);
                addend = addend.add(addend);
            }
            return result;
        }

        /** y in little-endian, with x's lowest bit on top. */
        byte[] encode() {
            BigInteger zi = z.modInverse(P);
            BigInteger ax = x.multiply(zi).mod(P), ay = y.multiply(zi).mod(P);
            byte[] out = littleEndian(ay, 32);
            if (ax.testBit(0)) out[31] |= (byte) 0x80;
            return out;
        }
    }

    /** The point encoded in 32 bytes, or null when it's not on the curve. */
    static Point decode(byte[] s) {
        if (s.length != 32) return null;
        byte[] copy = s.clone();
        boolean sign = (copy[31] & 0x80) != 0;
        copy[31] &= 0x7f;
        BigInteger y = fromLittleEndian(copy);
        if (y.compareTo(P) >= 0) y = y.mod(P);
        BigInteger yy = y.multiply(y).mod(P);
        BigInteger u = yy.subtract(BigInteger.ONE).mod(P), v = D.multiply(yy).add(BigInteger.ONE).mod(P);
        // x = u·v³·(u·v⁷)^((p−5)/8), then fixed with √−1 if that squares to −u/v.
        BigInteger v3 = v.pow(3).mod(P);
        BigInteger x = u.multiply(v3).multiply(u.multiply(v3.pow(2).multiply(v).mod(P)).mod(P)
                .modPow(P.subtract(BigInteger.valueOf(5)).shiftRight(3), P)).mod(P);
        BigInteger vxx = v.multiply(x).multiply(x).mod(P);
        if (!vxx.equals(u)) {
            if (!vxx.equals(P.subtract(u).mod(P))) return null;
            x = x.multiply(SQRT_M1).mod(P);
        }
        if (x.testBit(0) != sign) x = P.subtract(x).mod(P);
        return new Point(x, y, BigInteger.ONE, x.multiply(y).mod(P));
    }

    /** The 64-byte little-endian number reduced mod L (BoringSSL's x25519_sc_reduce). */
    static BigInteger reduce(byte[] wide) {
        return fromLittleEndian(wide).mod(L);
    }

    static BigInteger fromLittleEndian(byte[] b) {
        byte[] big = new byte[b.length + 1];
        for (int i = 0; i < b.length; i++) big[b.length - i] = b[i];
        return new BigInteger(big);
    }

    static byte[] littleEndian(BigInteger v, int size) {
        byte[] be = v.toByteArray();
        byte[] out = new byte[size];
        for (int i = 0; i < size && i < be.length; i++) out[i] = be[be.length - 1 - i];
        return out;
    }
}
