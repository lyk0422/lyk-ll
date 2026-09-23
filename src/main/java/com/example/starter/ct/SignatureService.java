package com.example.starter.ct;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import org.springframework.stereotype.Service;

/**
 * Ed25519 验签服务：使用配置的合成公钥验证观察者上报签名。
 *
 * <p>签名消息为规范文本 {@code observerId|treeSize|rootHashHex|timestamp} 的 UTF-8 字节，
 * 其中 rootHashHex 为小写十六进制。</p>
 */
@Service
public class SignatureService {

    private final CtProperties properties;

    public SignatureService(CtProperties properties) {
        this.properties = properties;
    }

    /**
     * 验签服务是否已配置公钥。
     */
    public boolean isConfigured() {
        String key = properties.getObserver().getPublicKey();
        return key != null && !key.isBlank();
    }

    /**
     * 验证上报签名；公钥未配置、签名格式非法或验证不通过均返回 false。
     */
    public boolean verify(String observerId, long treeSize, String rootHashHex, long timestamp,
            String signatureBase64) {
        if (!isConfigured()) {
            return false;
        }
        try {
            byte[] keyBytes = Base64.getDecoder().decode(properties.getObserver().getPublicKey());
            PublicKey publicKey = KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(keyBytes));
            byte[] signature = Base64.getDecoder().decode(signatureBase64);
            String message = observerId + "|" + treeSize + "|" + rootHashHex + "|" + timestamp;
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(message.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(signature);
        } catch (Exception e) {
            return false;
        }
    }
}
