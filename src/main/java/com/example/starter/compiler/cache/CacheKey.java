package com.example.starter.compiler.cache;

/**
 * 编译缓存键：覆盖入口内容、全部传递依赖内容摘要与规范化参数。
 *
 * @param keyHash 上述要素的聚合 SHA-256（十六进制），作为表主键
 */
public record CacheKey(String keyHash) {
}
