package com.example.starter.route.cache;

import com.example.starter.route.matcher.MatchOutcome;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 查询计算缓存：以「地图版本 + 输入摘要」为键。
 *
 * <p>键中显式包含版本，因此新版本与旧版本天然不串键：版本切换后旧条目不可能命中新版本；
 * 候选数据按版本不可变（热更新只发新版本），缓存值为不可变 {@link MatchOutcome}，
 * 也不会污染仍引用旧版本快照的在途请求。版本废弃时按版本整体驱逐。</p>
 */
@Component
public class RouteQueryCache {

    private final Map<String, MatchOutcome> store = new ConcurrentHashMap<>();

    private static String key(String mapVersion, String inputHash) {
        return mapVersion + '|' + inputHash;
    }

    public Optional<MatchOutcome> get(String mapVersion, String inputHash) {
        return Optional.ofNullable(store.get(key(mapVersion, inputHash)));
    }

    public void put(String mapVersion, String inputHash, MatchOutcome outcome) {
        store.put(key(mapVersion, inputHash), outcome);
    }

    /**
     * 驱逐指定版本的全部缓存条目（版本废弃/切换时调用）。
     *
     * @return 被驱逐条目数
     */
    public int invalidateVersion(String mapVersion) {
        String prefix = mapVersion + '|';
        int[] removed = {0};
        store.entrySet().removeIf(entry -> {
            if (entry.getKey().startsWith(prefix)) {
                removed[0]++;
                return true;
            }
            return false;
        });
        return removed[0];
    }

    /**
     * 当前缓存条目总数，主要用于测试断言。
     */
    public int size() {
        return store.size();
    }

    /**
     * 清空全部缓存条目（主要用于测试场景间隔离）。
     */
    public void clearAll() {
        store.clear();
    }
}
