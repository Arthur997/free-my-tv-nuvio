package io.freemytv;

import android.content.Context;
import android.os.Build;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509TrustManager;

/**
 * "Wireless debugging" (Android 11+), for TVs whose ADB isn't open on port 5555: pairing with the 6-digit code the TV
 * shows, and the TLS that every connection after it uses. adbd's own protocol (packages/modules/adb: pairing_auth,
 * pairing_connection, tls), with only what Android has: TLS 1.3 with the app's ADB key as a client certificate,
 * SPAKE2 (Spake2) on the code plus the TLS session's exported key, then AES-128-GCM to send the app's public key.
 */
final class AdbTls {
    private static final byte[] CLIENT_NAME = "adb pair client\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SERVER_NAME = "adb pair server\0".getBytes(StandardCharsets.US_ASCII);
    private static final int HEADER_VERSION = 1, SPAKE2_MSG = 0, PEER_INFO = 1, PEER_INFO_SIZE = 8192;
    private static final int MAX_PAYLOAD = PEER_INFO_SIZE * 2;

    private AdbTls() {}

    /** Whether this Android has wireless debugging and what it needs (TLS 1.3 key export: Android 10+). */
    static boolean supported() {
        return Build.VERSION.SDK_INT >= 30;
    }

    // MARK: - Pairing

    /**
     * Pairs this app with the TV's ADB: `port` is the pairing port the TV shows (or found by AdbPorts), `code` the
     * 6 digits. After it, the TV trusts the app's key on wireless debugging (as "Always allow" does on USB debugging).
     */
    static void pair(Context c, int port, String code) throws IOException {
        if (!supported()) throw new IOException(Ui.tr("esta TV não tem depuração sem fio", "this TV has no wireless debugging",
                "esta TV no tiene depuración inalámbrica"));
        KeyPair keys = Adb.keys(c);
        try (SSLSocket tls = tls(keys, port)) {
            byte[] exported = android.net.ssl.SSLSockets.exportKeyingMaterial(tls, "adb-label\0", null, 64);
            byte[] digits = code.trim().getBytes(StandardCharsets.US_ASCII);
            byte[] password = new byte[digits.length + exported.length];
            System.arraycopy(digits, 0, password, 0, digits.length);
            System.arraycopy(exported, 0, password, digits.length, exported.length);
            Spake2 spake = new Spake2(CLIENT_NAME, SERVER_NAME, password);
            DataInputStream in = new DataInputStream(tls.getInputStream());
            OutputStream out = tls.getOutputStream();
            writePacket(out, SPAKE2_MSG, spake.message());
            byte[] theirs = readPacket(in, SPAKE2_MSG);
            byte[] key = spake.key(theirs);
            if (key == null) throw new IOException(Ui.tr("resposta inválida da TV", "invalid reply from the TV", "respuesta inválida de la TV"));
            byte[] aesKey = hkdf(key, "adb pairing_auth aes-128-gcm key".getBytes(StandardCharsets.US_ASCII), 16);
            // Our PeerInfo: type 0 (an ADB RSA public key), the key as adb_keys has it, zero-padded to 8192 bytes.
            byte[] info = new byte[PEER_INFO_SIZE];
            byte[] blob = Adb.publicKeyBlob(keys.getPublic());
            System.arraycopy(blob, 0, info, 1, Math.min(blob.length, PEER_INFO_SIZE - 1));
            writePacket(out, PEER_INFO, gcm(Cipher.ENCRYPT_MODE, aesKey, 0, info));
            byte[] reply;
            try {
                reply = readPacket(in, PEER_INFO);
            } catch (IOException e) {
                // A wrong code: the TV closes the connection instead of answering.
                throw new IOException(Ui.tr("o código não confere; confira os 6 números e tente de novo",
                        "the code doesn't match; check the 6 digits and try again", "el código no coincide; revisa los 6 números"));
            }
            gcm(Cipher.DECRYPT_MODE, aesKey, 0, reply);   // decrypts only with the same key: the code matched
            AppLog.w("wireless debugging: paired");
        } catch (GeneralSecurityException e) {
            throw new IOException(Ui.tr("o código não confere; confira os 6 números e tente de novo",
                    "the code doesn't match; check the 6 digits and try again", "el código no coincide; revisa los 6 números"));
        }
    }

    /** Packet: version (1 byte), type (1 byte), payload size (4 bytes, big-endian), payload. */
    private static void writePacket(OutputStream out, int type, byte[] payload) throws IOException {
        byte[] header = {HEADER_VERSION, (byte) type, (byte) (payload.length >>> 24), (byte) (payload.length >>> 16),
                (byte) (payload.length >>> 8), (byte) payload.length};
        out.write(header);
        out.write(payload);
        out.flush();
    }

    private static byte[] readPacket(DataInputStream in, int type) throws IOException {
        int version = in.readUnsignedByte(), got = in.readUnsignedByte(), size = in.readInt();
        if (version != HEADER_VERSION || got != type || size <= 0 || size > MAX_PAYLOAD) {
            throw new IOException(Ui.tr("resposta inválida da TV", "invalid reply from the TV", "respuesta inválida de la TV"));
        }
        byte[] payload = new byte[size];
        in.readFully(payload);
        return payload;
    }

    /** AES-128-GCM with the nonce adbd uses: the message's sequence number in the first 8 bytes (little-endian). */
    private static byte[] gcm(int mode, byte[] key, long sequence, byte[] data) throws GeneralSecurityException {
        byte[] nonce = new byte[12];
        for (int i = 0; i < 8; i++) nonce[i] = (byte) (sequence >>> (8 * i));
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        return cipher.doFinal(data);
    }

    /** HKDF-SHA256 without a salt (RFC 5869), as BoringSSL's HKDF with no salt. */
    private static byte[] hkdf(byte[] ikm, byte[] info, int length) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
        byte[] prk = mac.doFinal(ikm);
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        mac.update(info);
        mac.update((byte) 1);
        byte[] okm = mac.doFinal();
        byte[] out = new byte[length];
        System.arraycopy(okm, 0, out, 0, length);
        return out;
    }

    // MARK: - TLS (pairing, and every connection to the wireless debugging port)

    /** A TLS 1.3 connection to the TV's own `port`, with the app's key as the client certificate. */
    private static SSLSocket tls(KeyPair keys, int port) throws IOException, GeneralSecurityException {
        Socket plain = AdbPorts.connect(port, 3000);
        plain.setSoTimeout(15_000);
        return upgrade(keys, plain, port);
    }

    /** Turns a connected socket into TLS 1.3 (ADB's STLS does it mid-connection). */
    static SSLSocket upgrade(KeyPair keys, Socket plain, int port) throws IOException, GeneralSecurityException {
        SSLContext context = SSLContext.getInstance("TLSv1.3");
        context.init(new KeyManager[] {new AppKey(keys)}, new TrustManager[] {new TrustAny()}, null);
        SSLSocket tls = (SSLSocket) context.getSocketFactory().createSocket(plain, "127.0.0.1", port, true);
        tls.setEnabledProtocols(new String[] {"TLSv1.3"});
        tls.setUseClientMode(true);
        tls.startHandshake();
        return tls;
    }

    /** The app's ADB key and its certificate, offered to the TV (adbd checks the key, not the certificate). */
    private static final class AppKey extends X509ExtendedKeyManager {
        private final KeyPair keys;
        private final X509Certificate[] chain;

        AppKey(KeyPair keys) throws GeneralSecurityException {
            this.keys = keys;
            chain = new X509Certificate[] {certificate(keys)};
        }

        @Override public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) { return "adb"; }
        @Override public String[] getClientAliases(String keyType, Principal[] issuers) { return new String[] {"adb"}; }
        @Override public X509Certificate[] getCertificateChain(String alias) { return chain; }
        @Override public PrivateKey getPrivateKey(String alias) { return keys.getPrivate(); }
        @Override public String[] getServerAliases(String keyType, Principal[] issuers) { return null; }
        @Override public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) { return null; }
    }

    /** The TV's certificate is its own self-signed one: the pairing code (or the earlier pairing) is the trust. */
    private static final class TrustAny implements X509TrustManager {
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }

    // MARK: - A self-signed X.509 certificate for the key, in DER by hand (no libraries)

    private static X509Certificate certificate(KeyPair keys) throws GeneralSecurityException {
        byte[] algorithm = seq(oid(new int[] {1, 2, 840, 113549, 1, 1, 11}), new byte[] {0x05, 0x00});   // sha256WithRSA
        byte[] name = seq(set(seq(oid(new int[] {2, 5, 4, 3}), tlv(0x0c, "Free My TV".getBytes(StandardCharsets.UTF_8)))));
        long now = System.currentTimeMillis();
        byte[] validity = seq(time(now - 86_400_000L), time(now + 20L * 365 * 86_400_000L));
        byte[] tbs = seq(tlv(0xa0, tlv(0x02, new byte[] {2})), tlv(0x02, new byte[] {1}), algorithm, name, validity, name,
                keys.getPublic().getEncoded());
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keys.getPrivate());
        signer.update(tbs);
        byte[] signature = signer.sign();
        byte[] bits = new byte[signature.length + 1];
        System.arraycopy(signature, 0, bits, 1, signature.length);
        byte[] der = seq(tbs, algorithm, tlv(0x03, bits));
        return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der));
    }

    private static byte[] time(long at) {
        SimpleDateFormat f = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return tlv(0x17, f.format(new Date(at)).getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] oid(int[] parts) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(parts[0] * 40 + parts[1]);
        for (int i = 2; i < parts.length; i++) {
            int v = parts[i];
            byte[] tmp = new byte[5];
            int n = 0;
            do {
                tmp[n++] = (byte) (v & 0x7f);
                v >>>= 7;
            } while (v != 0);
            for (int j = n - 1; j >= 0; j--) b.write(tmp[j] | (j > 0 ? 0x80 : 0));
        }
        return tlv(0x06, b.toByteArray());
    }

    private static byte[] seq(byte[]... parts) {
        return tlv(0x30, concat(parts));
    }

    private static byte[] set(byte[]... parts) {
        return tlv(0x31, concat(parts));
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (byte[] p : parts) b.write(p, 0, p.length);
        return b.toByteArray();
    }

    private static byte[] tlv(int tag, byte[] value) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(tag);
        int n = value.length;
        if (n < 0x80) {
            b.write(n);
        } else if (n < 0x100) {
            b.write(0x81);
            b.write(n);
        } else {
            b.write(0x82);
            b.write(n >>> 8);
            b.write(n);
        }
        b.write(value, 0, n);
        return b.toByteArray();
    }
}
