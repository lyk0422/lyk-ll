package com.example.starter.compiler.cache;

import java.util.Optional;

/**
 * 编译缓存存储抽象（H2 内存库实现）。
 */
public interface CompileCacheStore {

    /**
     * 按缓存键读取成功编译条目。
     */
    Optional<CacheEntry> find(String keyHash);

    /**
     * 原子写入成功编译条目；并发下只允许一个写入者成功。
     *
     * @return 已存在或本次写入后生效的条目
     */
    CacheEntry saveIfAbsent(CacheEntry entry);
}
