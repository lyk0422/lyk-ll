package com.example.starter.ctlog.crypto;

/**
 * Ed25519 签名工具与内置合成密钥持有器（骨架）。
 */
public interface Ed25519 {

    /**
     * 验证观察者签名。
     */
    boolean verify(byte[] message, byte[] signature);

    /**
     * 当前用于验证的公钥（32 字节原始编码）。
     */
    byte[] getPublicKeyBytes();

    /**
     * 当前公钥来源（DEFAULT 内置合成密钥 / CONFIGURED 外部配置）。
     */
    String source();
}
