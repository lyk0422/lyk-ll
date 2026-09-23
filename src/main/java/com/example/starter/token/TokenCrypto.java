package com.example.starter.token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 令牌密码学工具：SHA-256 散列、根令牌随机生成、子令牌 HMAC-SHA256 确定性派生。
 */
public final class TokenCrypto {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String DIGEST_ALGORITHM = "SHA-256";

    private TokenCrypto() {
    }

    /**
     * 计算明文令牌的 SHA-256 十六进制散列。
     */
    public static String sha256(String plainToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance(DIGEST_ALGORITHM);
            byte[] hash = digest.digest(plainToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 生成族内首个随机明文令牌（rt. 前缀 + 32 字节随机数的十六进制）。
     */
    public static String newRootToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return "rt." + HexFormat.of().formatHex(bytes);
    }

    /**
     * 由本地测试密钥、请求键与族上下文确定性派生子令牌。
     * 相同入参必定产生相同子令牌，使安全重试可以无状态恢复首次结果。
     *
     * @param derivationKey 本地合成测试密钥
     * @param requestId     请求键
     * @param familyId      令牌族 ID
     * @param parentHash    父令牌散列
     * @param clientId      客户端标识
     * @param seq           子令牌代数
     */
    public static String deriveChildToken(String derivationKey, String requestId, String familyId,
                                          String parentHash, String clientId, int seq) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(derivationKey.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            mac.update("v1".getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            mac.update(familyId.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            mac.update(parentHash.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            mac.update(Integer.toString(seq).getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            mac.update(clientId.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            mac.update(requestId.getBytes(StandardCharsets.UTF_8));
            byte[] raw = mac.doFinal();
            return "rt." + HexFormat.of().formatHex(raw);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 派生失败", e);
        }
    }
}
