package com.example.starter.compiler.cache;

/**
 * 成功编译的缓存条目（校验失败不会写入）。
 *
 * @param keyHash        缓存键哈希（主键）
 * @param urdf           规范 URDF 文本
 * @param urdfSha256     规范 URDF 的 SHA-256
 * @param dependenciesJson 依赖清单 JSON（路径+摘要）
 * @param chainJson      展开链路 JSON
 */
public record CacheEntry(
        String keyHash,
        String urdf,
        String urdfSha256,
        String dependenciesJson,
        String chainJson) {
}
