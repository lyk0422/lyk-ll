package com.example.starter.ctlog.crypto;

import com.example.starter.ctlog.config.CtLogProperties;
import org.springframework.stereotype.Component;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.HexFormat;

/**
 * 基于 JCA（Java 21 内置 Ed25519）的观察者签名验证。
 *
 * <p>公钥来自配置 {@code ctlog.observer-public-key}（32 字节原始公钥十六进制）；
 * 未配置时使用 {@link DemoObserverKeys} 中的内置合成演示公钥。</p>
 */
@Component
public class JcaEd25519 implements Ed25519 {

    /**
     * Ed25519 的 X.509 SubjectPublicKeyInfo DER 前缀（后接 32 字节原始公钥）。
     */
    private static final byte[] SPKI_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");

    private final byte[] publicKeyBytes;
    private final PublicKey publicKey;
    private final String source;

    public JcaEd25519(CtLogProperties properties) {
        String configured = properties.observerPublicKey();
        if (configured == null || configured.isBlank()) {
            this.publicKeyBytes = HexFormat.of().parseHex(DemoObserverKeys.PUBLIC_KEY_HEX);
            this.source = "DEFAULT";
        } else {
            this.publicKeyBytes = HexFormat.of().parseHex(configured.trim());
            this.source = "CONFIGURED";
        }
        this.publicKey = decodePublicKey(this.publicKeyBytes);
    }

    @Override
    public boolean verify(byte[] message, byte[] signature) {
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(message);
            return verifier.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    @Override
    public byte[] getPublicKeyBytes() {
        return publicKeyBytes.clone();
    }

    @Override
    public String source() {
        return source;
    }

    /**
     * 由 32 字节原始公钥还原 Ed25519 {@link PublicKey}。
     */
    public static PublicKey decodePublicKey(byte[] raw) {
        if (raw == null || raw.length != 32) {
            throw new IllegalArgumentException("Ed25519 public key must be 32 bytes");
        }
        byte[] spki = new byte[SPKI_PREFIX.length + raw.length];
        System.arraycopy(SPKI_PREFIX, 0, spki, 0, SPKI_PREFIX.length);
        System.arraycopy(raw, 0, spki, SPKI_PREFIX.length, raw.length);
        try {
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot decode Ed25519 public key", e);
        }
    }
}
