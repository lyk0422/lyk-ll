package com.example.starter.ctlog.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 证书透明日志配置。
 *
 * @param observerPublicKey 观察者上报签名验证使用的 Ed25519 公钥（32 字节原始公钥十六进制）；
 *                          为空时使用内置固定合成演示密钥对
 */
@ConfigurationProperties(prefix = "ctlog")
public record CtLogProperties(String observerPublicKey) {
}
