package com.example.starter.match;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 匹配结果查询缓存：以（地图版本， 输入摘要）为键。
 * 版本切换/废弃时按版本驱逐；键内含版本号，旧版本缓存不会命中新版本，
 * 已持久化的旧请求结果保存在 route_request 表中，不受缓存驱逐影响。
 */
@Component
public class MatchCache {

    private record Key(String mapVersionId, String inputDigest) {
    }

    private final Map<Key, MatchResult> entries = new ConcurrentHashMap<>();

    public Optional<MatchResult> get(String mapVersionId, String inputDigest) {
        return Optional.ofNullable(entries.get(new Key(mapVersionId, inputDigest)));
    }

    public void put(String mapVersionId, String inputDigest, MatchResult result) {
        entries.putIfAbsent(new Key(mapVersionId, inputDigest), result);
    }

    public void evictVersion(String mapVersionId) {
        entries.keySet().removeIf(key -> key.mapVersionId().equals(mapVersionId));
    }

    public int size() {
        return entries.size();
    }

    /**
     * 清空全部缓存；用于测试场景隔离。
     */
    public void clear() {
        entries.clear();
    }
}
