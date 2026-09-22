package com.example.starter.route.cache;

import com.example.starter.route.matcher.MatchOutcome;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RouteQueryCache} 单元测试：版本分区命中、版本切换不串键、按版本驱逐。
 */
class RouteQueryCacheTest {

    private final RouteQueryCache cache = new RouteQueryCache();

    private MatchOutcome outcome(String errorCode) {
        return new MatchOutcome(errorCode == null, errorCode,
                List.of(), List.of(), null, null, List.of(), List.of(), 0.0);
    }

    @Test
    void same_version_and_input_hits_cache() {
        MatchOutcome outcome = outcome(null);
        cache.put("v1", "hash-a", outcome);

        Optional<MatchOutcome> hit = cache.get("v1", "hash-a");
        assertTrue(hit.isPresent());
        assertSame(outcome, hit.get());
    }

    @Test
    void different_version_with_same_input_hash_does_not_hit() {
        cache.put("v1", "hash-a", outcome(null));

        assertFalse(cache.get("v2", "hash-a").isPresent());
        assertEquals(1, cache.size());
    }

    @Test
    void deprecating_version_evicts_only_that_version() {
        MatchOutcome v1 = outcome(null);
        MatchOutcome v2 = outcome("PATH_DISCONNECTED");
        cache.put("v1", "hash-a", v1);
        cache.put("v1", "hash-b", v1);
        cache.put("v2", "hash-a", v2);

        int removed = cache.invalidateVersion("v1");

        assertEquals(2, removed);
        assertFalse(cache.get("v1", "hash-a").isPresent());
        assertFalse(cache.get("v1", "hash-b").isPresent());
        // 新版本条目不受影响
        assertTrue(cache.get("v2", "hash-a").isPresent());
        assertEquals(1, cache.size());
    }

    @Test
    void version_prefix_is_boundary_so_similar_names_do_not_collide() {
        cache.put("v1", "h", outcome(null));
        cache.put("v10", "h", outcome(null));

        assertTrue(cache.get("v1", "h").isPresent());
        assertTrue(cache.get("v10", "h").isPresent());
        assertEquals(1, cache.invalidateVersion("v1"));
        assertTrue(cache.get("v10", "h").isPresent());
    }
}
