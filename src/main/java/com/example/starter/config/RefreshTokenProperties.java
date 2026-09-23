package com.example.starter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 刷新令牌业务配置。
 *
 * @param signingKey 合成 HMAC 签名密钥，仅用于本地/测试的确定性令牌派生
 * @param ttlSeconds 刷新令牌有效期，单位秒
 */
@ConfigurationProperties(prefix = "refresh-token")
public record RefreshTokenProperties(
        String signingKey,
        long ttlSeconds
) {
}
