package io.freemytv;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * SPAKE2 over Edwards25519, byte for byte as BoringSSL's (crypto/curve25519/spake25519.cc), which adbd uses to check
 * the pairing code of "Wireless debugging" (AdbPairing). Both sides turn the code into the same key without ever
 * sending it. Kept to the client's side ("alice").
 */
final class Spake2 {
    /** The points M and N of BoringSSL's tables (their encodings). */
    private static final Ed25519.Point M = Ed25519.decode(hex("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e"));
    private static final Ed25519.Point N = Ed25519.decode(hex("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778"));

    private final byte[] myName, theirName;
    private final BigInteger privateKey, passwordScalar;
    private final byte[] passwordHash, myMsg;

    /** As alice, with the names both sides agreed on and the password (the code and the TLS exporter's bytes). */
    Spake2(byte[] myName, byte[] theirName, byte[] password) {
        this.myName = myName;
        this.theirName = theirName;
        byte[] random = new byte[64];
        new SecureRandom().nextBytes(random);
        // Times the cofactor (8), so the peer's small-order part cancels out later.
        privateKey = Ed25519.reduce(random).shiftLeft(3);
        passwordHash = sha512(password);
        BigInteger s = Ed25519.reduce(passwordHash);
        // BoringSSL's fix for a missed cofactor multiplication: multiples of L added until the low 3 bits are zero,
        // checked bit by bit on the running value.
        if (s.testBit(0)) s = s.add(Ed25519.L);
        if (s.testBit(1)) s = s.add(Ed25519.L.shiftLeft(1));
        if (s.testBit(2)) s = s.add(Ed25519.L.shiftLeft(2));
        passwordScalar = s;
        myMsg = Ed25519.B.multiply(privateKey).add(M.multiply(passwordScalar)).encode();
    }

    byte[] message() {
        return myMsg.clone();
    }

    /** The shared key (64 bytes) from the peer's message; null if it isn't a point. */
    byte[] key(byte[] theirMsg) {
        Ed25519.Point q = Ed25519.decode(theirMsg);
        if (q == null) return null;
        Ed25519.Point unmasked = q.subtract(N.multiply(passwordScalar));
        byte[] shared = unmasked.multiply(privateKey).encode();
        ByteArrayOutputStream transcript = new ByteArrayOutputStream();
        prefixed(transcript, myName);
        prefixed(transcript, theirName);
        prefixed(transcript, myMsg);
        prefixed(transcript, theirMsg);
        prefixed(transcript, shared);
        prefixed(transcript, passwordHash);
        return sha512(transcript.toByteArray());
    }

    /** The length in 8 little-endian bytes, then the bytes. */
    private static void prefixed(ByteArrayOutputStream out, byte[] data) {
        long n = data.length;
        for (int i = 0; i < 8; i++) {
            out.write((int) (n & 0xff));
            n >>= 8;
        }
        out.write(data, 0, data.length);
    }

    private static byte[] sha512(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-512").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return out;
    }
}
