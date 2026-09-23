package com.example.starter.ct;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * 测试夹具：与 application.yaml 中合成公钥配对的 Ed25519 私钥及签名工具。
 * 仅用于测试与演示，全部为合成数据。
 */
public final class TestKeys {

    /** 合成私钥（PKCS#8，Base64），对应配置的验签公钥。 */
    public static final String PRIVATE_KEY_BASE64 = "MC4CAQAwBQYDK2VwBCIEIEodecBmq2lQJYzDv3Jw83fycXD3eiV9NCBEykQqghYa";

    private TestKeys() {
    }

    /**
     * 按规范消息 observerId|treeSize|rootHashHex|timestamp 计算 Ed25519 签名（Base64）。
     */
    public static String sign(String observerId, long treeSize, String rootHashHex, long timestamp) {
        try {
            byte[] pkcs8 = Base64.getDecoder().decode(PRIVATE_KEY_BASE64);
            PrivateKey privateKey = KeyFactory.getInstance("Ed25519")
                    .generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
            String message = observerId + "|" + treeSize + "|" + rootHashHex + "|" + timestamp;
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(privateKey);
            signer.update(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (Exception e) {
            throw new IllegalStateException("sign failed", e);
        }
    }
}
