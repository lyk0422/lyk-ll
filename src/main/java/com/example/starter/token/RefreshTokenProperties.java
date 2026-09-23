package com.example.starter.token;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 刷新令牌服务配置。
 *
 * @param derivationKey      子令牌 HMAC 派生用的本地合成测试密钥
 * @param defaultTtlSeconds  刷新令牌默认有效期（秒）
 */
@ConfigurationProperties(prefix = "refresh-tokens")
public record RefreshTokenProperties(
        String derivationKey,
        long defaultTtlSeconds) {
}
