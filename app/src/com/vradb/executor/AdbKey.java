package com.vradb.executor;

import android.util.Base64;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;

/** RSA key used to authenticate with adbd, stored in the app's private files. */
final class AdbKey {
    private static final int KEY_BITS = 2048;
    private static final int KEY_WORDS = KEY_BITS / 32;

    /** ASN.1 DigestInfo prefix for SHA-1, as used by adbd's RSA_verify(NID_sha1, ...). */
    private static final byte[] SHA1_PREFIX = {
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    };

    private final RSAPrivateCrtKey privateKey;
    private final RSAPublicKey publicKey;

    private AdbKey(RSAPrivateCrtKey priv, RSAPublicKey pub) {
        this.privateKey = priv;
        this.publicKey = pub;
    }

    static AdbKey loadOrCreate(File dir) throws Exception {
        File f = new File(dir, "adbkey.pk8");
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (f.exists()) {
            byte[] data = readAll(f);
            RSAPrivateCrtKey priv = (RSAPrivateCrtKey) kf.generatePrivate(new PKCS8EncodedKeySpec(data));
            RSAPublicKey pub = (RSAPublicKey) kf.generatePublic(
                    new RSAPublicKeySpec(priv.getModulus(), priv.getPublicExponent()));
            return new AdbKey(priv, pub);
        }
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(KEY_BITS);
        KeyPair kp = gen.generateKeyPair();
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(kp.getPrivate().getEncoded());
        } finally {
            out.close();
        }
        return new AdbKey((RSAPrivateCrtKey) kp.getPrivate(), (RSAPublicKey) kp.getPublic());
    }

    static void delete(File dir) {
        new File(dir, "adbkey.pk8").delete();
    }

    /** PKCS#1 v1.5 signature over SHA-1 DigestInfo with the raw 20-byte token as the digest. */
    byte[] signToken(byte[] token) {
        int k = KEY_BITS / 8;
        byte[] em = new byte[k];
        int tLen = SHA1_PREFIX.length + token.length;
        em[0] = 0x00;
        em[1] = 0x01;
        int psEnd = k - tLen - 1;
        for (int i = 2; i < psEnd; i++) em[i] = (byte) 0xff;
        em[psEnd] = 0x00;
        System.arraycopy(SHA1_PREFIX, 0, em, psEnd + 1, SHA1_PREFIX.length);
        System.arraycopy(token, 0, em, psEnd + 1 + SHA1_PREFIX.length, token.length);
        BigInteger m = new BigInteger(1, em);
        BigInteger s = m.modPow(privateKey.getPrivateExponent(), privateKey.getModulus());
        return toFixed(s, k);
    }

    /** Public key in adb's custom format: base64(struct RSAPublicKey) + " name\0". */
    byte[] adbPublicKey(String name) {
        BigInteger n = publicKey.getModulus();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = n.mod(r32).modInverse(r32).negate().mod(r32);
        BigInteger rr = BigInteger.ONE.shiftLeft(KEY_BITS * 2).mod(n);

        ByteBuffer bb = ByteBuffer.allocate(4 + 4 + KEY_WORDS * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(KEY_WORDS);
        bb.putInt(n0inv.intValue());
        putLittleEndianWords(bb, n);
        putLittleEndianWords(bb, rr);
        bb.putInt(publicKey.getPublicExponent().intValue());

        String b64 = Base64.encodeToString(bb.array(), Base64.NO_WRAP);
        String full = b64 + " " + name + "\u0000";
        try {
            return full.getBytes("UTF-8");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void putLittleEndianWords(ByteBuffer bb, BigInteger v) {
        BigInteger mask = BigInteger.valueOf(0xffffffffL);
        for (int i = 0; i < KEY_WORDS; i++) {
            bb.putInt(v.shiftRight(32 * i).and(mask).intValue());
        }
    }

    private static byte[] toFixed(BigInteger v, int len) {
        byte[] raw = v.toByteArray();
        if (raw.length == len) return raw;
        byte[] out = new byte[len];
        if (raw.length > len) {
            System.arraycopy(raw, raw.length - len, out, 0, len);
        } else {
            System.arraycopy(raw, 0, out, len - raw.length, raw.length);
        }
        return out;
    }

    private static byte[] readAll(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] data = new byte[(int) f.length()];
            int off = 0;
            while (off < data.length) {
                int r = in.read(data, off, data.length - off);
                if (r < 0) throw new IOException("short read");
                off += r;
            }
            return data;
        } finally {
            in.close();
        }
    }
}
