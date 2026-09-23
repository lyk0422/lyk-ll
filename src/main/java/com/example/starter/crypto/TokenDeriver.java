package com.example.starter.crypto;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import com.example.starter.config.RefreshTokenProperties;

/**
 * 基于 HMAC-SHA256 的确定性令牌派生器。
 *
 * <p>子令牌 = Base64URL(HMAC-SHA256(本地测试密钥, 族标识 | 父令牌哈希 | 请求键 | 客户端))。
 * 同一密钥、同一请求键与同一令牌族上下文必然派生出同一子令牌，使安全重试可恢复；
 * 不同输入（改令牌/改客户端/改请求键）派生出不同子令牌。
 *
 * <p>族内首个令牌无请求上下文，使用 {@link SecureRandom} 生成不可预测随机串。
 */
@Component
public class TokenDeriver {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int ROOT_TOKEN_BYTES = 32;

    private final byte[] signingKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public TokenDeriver(RefreshTokenProperties properties) {
        this.signingKey = properties.signingKey().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 生成族内首个刷新令牌（随机，仅创建会话时使用一次）。
     */
    public String generateRootToken() {
        byte[] bytes = new byte[ROOT_TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 确定性派生子令牌：同一（族、父令牌哈希、请求键、客户端）输入永远得到同一输出。
     */
    public String deriveChild(String familyId, String parentTokenHash, String requestId, String clientId) {
        String message = familyId + "|" + parentTokenHash + "|" + requestId + "|" + clientId;
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(signingKey, HMAC_ALGORITHM));
            byte[] raw = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 derivation failed", e);
        }
    }
}
