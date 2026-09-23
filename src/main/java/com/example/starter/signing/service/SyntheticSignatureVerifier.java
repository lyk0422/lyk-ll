package com.example.starter.signing.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.stereotype.Component;

/**
 * 本地合成签名验证适配器。
 *
 * <p>不依赖任何 CA 或外部签署平台，签名完全可复现：
 * <pre>signature = lower(SHA-256("SYNTHETIC-SIGNATURE-v1|" + signer + "|" + revision + "|" + documentHash))</pre>
 * 其中 documentHash 使用小写归一化形式。客户端可通过 {@link #compose} 生成合法签名。</p>
 */
@Component
public class SyntheticSignatureVerifier {

    private static final String SCHEME = "SYNTHETIC-SIGNATURE-v1";

    /**
     * 生成可被 {@link #verify} 接受的合成签名。
     *
     * @param signer       签署人
     * @param revision     修订号
     * @param documentHash 文档 SHA-256 摘要
     * @return 合成签名值
     */
    public String compose(String signer, int revision, String documentHash) {
        return Hashes.sha256(canonical(signer, revision, documentHash));
    }

    /**
     * 验证签名是否与参数匹配（常量时间比较）。
     *
     * @param signer       签署人
     * @param revision     修订号
     * @param documentHash 文档摘要
     * @param signature    待验证签名
     * @return true 仅当签名等于合成值
     */
    public boolean verify(String signer, int revision, String documentHash, String signature) {
        if (signature == null) {
            return false;
        }
        String expected = compose(signer, revision, documentHash);
        return MessageDigest.isEqual(
                expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                signature.trim().toLowerCase().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private String canonical(String signer, int revision, String documentHash) {
        return SCHEME + "|" + signer + "|" + revision + "|" + Hashes.normalize(documentHash);
    }
}
