package com.example.starter.route.service;

import com.example.starter.route.api.PublishMapRequest;
import com.example.starter.route.api.RouteResponse;
import com.example.starter.route.api.RouteSubmitRequest;
import com.example.starter.route.cache.RouteQueryCache;
import com.example.starter.route.domain.ApiException;
import com.example.starter.route.domain.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 基于真实嵌入式 H2（MODE=MySQL）的服务层集成测试：
 * 覆盖建表与唯一约束、地图热更新、请求重放、缓存失效、多候选解释、路径断裂与并发幂等。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:route_service;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@Import(RouteServiceH2Test.FixedClockConfig.class)
class RouteServiceH2Test {

    /** 固定 Asia/Shanghai 时钟，保证时间相关断言确定；独立 Bean 名 + @Primary 避免覆盖冲突 */
    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @org.springframework.context.annotation.Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-09-22T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
        }
    }

    @Autowired
    private RouteService routeService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RouteQueryCache queryCache;

    @BeforeEach
    void cleanDatabaseAndCache() {
        jdbcTemplate.update("DELETE FROM route_match");
        jdbcTemplate.update("DELETE FROM route_candidate");
        jdbcTemplate.update("DELETE FROM route_request");
        jdbcTemplate.update("DELETE FROM map_segment");
        jdbcTemplate.update("DELETE FROM map_node");
        jdbcTemplate.update("DELETE FROM map_version");
        queryCache.clearAll();
    }

    // ------------------------------------------------------------------
    // 地图发布
    // ------------------------------------------------------------------

    @Test
    void publish_then_get_map_returns_snapshot_counts() {
        routeService.publishMap(chainMap("v1", true));

        var view = routeService.getMap("v1");
        assertEquals("v1", view.version());
        assertEquals("ACTIVE", view.status());
        assertEquals(4, view.nodeCount());
        assertEquals(3, view.segmentCount());
        assertNotNull(view.publishedAt());
        assertNull(view.deprecatedAt());
    }

    @Test
    void publish_duplicate_version_is_rejected() {
        routeService.publishMap(chainMap("v1", true));

        ApiException ex = assertThrows(ApiException.class,
                () -> routeService.publishMap(chainMap("v1", true)));
        assertEquals(ErrorCode.VERSION_ALREADY_EXISTS, ex.errorCode());
    }

    @Test
    void publish_topology_with_dangling_node_is_rejected() {
        PublishMapRequest request = new PublishMapRequest("v-bad",
                List.of(new PublishMapRequest.NodeSpec("N1", 0.0, 0.0)),
                List.of(new PublishMapRequest.SegmentSpec("s1", "N1", "NX", 100.0, true)));

        ApiException ex = assertThrows(ApiException.class, () -> routeService.publishMap(request));
        assertEquals(ErrorCode.MAP_TOPOLOGY_INVALID, ex.errorCode());
        // 事务回滚：不留版本行
        assertEquals(0, count("map_version", "version", "v-bad"));
    }

    @Test
    void get_missing_version_returns_not_found() {
        ApiException ex = assertThrows(ApiException.class, () -> routeService.getMap("nope"));
        assertEquals(ErrorCode.MAP_VERSION_NOT_FOUND, ex.errorCode());
    }

    @Test
    void duplicate_segment_id_is_rejected() {
        PublishMapRequest request = new PublishMapRequest("v-dup",
                List.of(new PublishMapRequest.NodeSpec("N1", 0.0, 0.0),
                        new PublishMapRequest.NodeSpec("N2", 100.0, 0.0),
                        new PublishMapRequest.NodeSpec("N3", 200.0, 0.0)),
                List.of(new PublishMapRequest.SegmentSpec("s1", "N1", "N2", 100.0, true),
                        new PublishMapRequest.SegmentSpec("s1", "N2", "N3", 100.0, true)));

        ApiException ex = assertThrows(ApiException.class, () -> routeService.publishMap(request));
        assertEquals(ErrorCode.MAP_TOPOLOGY_INVALID, ex.errorCode());
    }

    // ------------------------------------------------------------------
    // 匹配成功与多候选解释
    // ------------------------------------------------------------------

    @Test
    void matched_route_is_persisted_and_explains_candidates() {
        routeService.publishMap(chainMap("v1", true));

        RouteResponse response = routeService.submitRoute(routeRequest("r1", "v1", 5, 2, 295, 2));

        assertEquals("MATCHED", response.state());
        assertNull(response.errorCode());
        assertEquals("v1", response.boundMapVersion());
        assertEquals("s1", response.match().startSegmentId());
        assertEquals("s3", response.match().endSegmentId());
        assertEquals(List.of("s1", "s2", "s3"), response.match().pathSegmentIds());
        assertEquals(List.of("N1", "N2", "N3", "N4"), response.match().pathNodeIds());
        assertTrue(response.match().totalScore() > 300.0);

        // 候选明细持久化：每个端点 1 个候选，选中标记与评分构成完整
        CandidateAssertions start = firstCandidate(response, "START");
        assertTrue(start.selected);
        assertEquals(100.0, start.connectivityScore, 0.001);
        assertTrue(start.distanceScore > 90.0);
        assertTrue(start.headingScore > 90.0);
        assertTrue(start.rejectReasons.isEmpty());

        assertEquals(1, count("route_request", "request_id", "r1"));
        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM route_candidate WHERE request_id = 'r1'", Integer.class));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM route_match WHERE request_id = 'r1'", Integer.class));
    }

    @Test
    void same_inputs_on_different_request_ids_share_one_cache_entry() {
        routeService.publishMap(chainMap("v1", true));

        routeService.submitRoute(routeRequest("r1", "v1", 5, 2, 295, 2));
        int sizeAfterFirst = queryCache.size();
        routeService.submitRoute(routeRequest("r2", "v1", 5, 2, 295, 2));

        // 同版本同输入只产生一个缓存条目
        assertEquals(1, sizeAfterFirst);
        assertEquals(1, queryCache.size());
        assertTrue(queryCache.get("v1", queryCacheHashOf("r1")).isPresent());
    }

    // ------------------------------------------------------------------
    // 失败分支
    // ------------------------------------------------------------------

    @Test
    void start_no_candidate_is_persisted_as_failed_and_replays_stable() {
        routeService.publishMap(chainMap("v1", true));

        RouteResponse response = routeService.submitRoute(
                routeRequest("r-fail", "v1", 9000, 9000, 295, 2));

        assertEquals("FAILED", response.state());
        assertEquals("START_NO_CANDIDATE", response.errorCode());
        assertNull(response.match());
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM route_match WHERE request_id = 'r-fail'", Integer.class));

        // 同参重放返回同样失败码
        RouteResponse replay = routeService.submitRoute(
                routeRequest("r-fail", "v1", 9000, 9000, 295, 2));
        assertEquals("FAILED", replay.state());
        assertEquals("START_NO_CANDIDATE", replay.errorCode());
    }

    @Test
    void end_no_candidate_has_distinct_code() {
        routeService.publishMap(chainMap("v1", true));

        RouteResponse response = routeService.submitRoute(
                routeRequest("r-fail", "v1", 5, 2, 9000, 9000));

        assertEquals("END_NO_CANDIDATE", response.errorCode());
    }

    @Test
    void invalid_segment_is_rejected_with_explanation() {
        // 半径内只有失效路段 s1
        PublishMapRequest map = new PublishMapRequest("v-invalid",
                List.of(new PublishMapRequest.NodeSpec("N1", 0.0, 0.0),
                        new PublishMapRequest.NodeSpec("N2", 100.0, 0.0)),
                List.of(new PublishMapRequest.SegmentSpec("s1", "N1", "N2", 100.0, false)));
        routeService.publishMap(map);

        RouteResponse response = routeService.submitRoute(
                routeRequest("r-invalid", "v-invalid", 50, 2, 50, 2));

        assertEquals("FAILED", response.state());
        assertEquals("SEGMENT_INVALID", response.errorCode());
        assertTrue(response.startCandidates().stream()
                .anyMatch(c -> c.rejectReasons().contains("SEGMENT_INVALID")));
    }

    @Test
    void path_disconnected_is_rejected_and_candidates_marked_not_connected() {
        routeService.publishMap(disconnectedMap("v-disc"));

        RouteResponse response = routeService.submitRoute(
                routeRequest("r-disc", "v-disc", 5, 2, 295, 2));

        assertEquals("FAILED", response.state());
        assertEquals("PATH_DISCONNECTED", response.errorCode());
        assertTrue(response.startCandidates().stream()
                .allMatch(c -> c.rejectReasons().contains("NOT_CONNECTED")));
        assertTrue(response.endCandidates().stream()
                .allMatch(c -> c.rejectReasons().contains("NOT_CONNECTED")));
    }

    @Test
    void submit_to_missing_version_returns_not_found() {
        ApiException ex = assertThrows(ApiException.class,
                () -> routeService.submitRoute(routeRequest("r-x", "ghost", 5, 2, 295, 2)));
        assertEquals(ErrorCode.MAP_VERSION_NOT_FOUND, ex.errorCode());
    }

    // ------------------------------------------------------------------
    // 重放
    // ------------------------------------------------------------------

    @Test
    void same_param_replay_returns_original_request_and_result() {
        routeService.publishMap(chainMap("v1", true));

        RouteResponse first = routeService.submitRoute(routeRequest("r1", "v1", 5, 2, 295, 2));
        RouteResponse replay = routeService.submitRoute(routeRequest("r1", "v1", 5, 2, 295, 2));
        RouteResponse queried = routeService.getRoute("r1");

        assertEquals(first.createdAt(), replay.createdAt());
        assertEquals(first.boundMapVersion(), replay.boundMapVersion());
        assertEquals(first.match().pathSegmentIds(), replay.match().pathSegmentIds());
        assertEquals(first.match().totalScore(), replay.match().totalScore(), 0.0);
        assertEquals(first.match().pathSegmentIds(), queried.match().pathSegmentIds());
        // 未产生第二条请求或候选
        assertEquals(1, count("route_request", "request_id", "r1"));
        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM route_candidate WHERE request_id = 'r1'", Integer.class));
    }

    @Test
    void different_param_replay_returns_conflict_and_keeps_original() {
        routeService.publishMap(chainMap("v1", true));

        routeService.submitRoute(routeRequest("r1", "v1", 5, 2, 295, 2));
        ApiException ex = assertThrows(ApiException.class,
                () -> routeService.submitRoute(routeRequest("r1", "v1", 6, 2, 295, 2)));
        assertEquals(ErrorCode.REPLAY_PARAM_CONFLICT, ex.errorCode());

        // 原始结果保持不变
        RouteResponse original = routeService.getRoute("r1");
        assertEquals("MATCHED", original.state());
    }

    @Test
    void replay_bound_to_other_version_returns_conflict() {
        routeService.publishMap(chainMap("v1", true));
        routeService.publishMap(chainMap("v2", true));

        routeService.submitRoute(routeRequest("r1", "v1", 5, 2, 295, 2));
        ApiException ex = assertThrows(ApiException.class,
                () -> routeService.submitRoute(routeRequest("r1", "v2", 5, 2, 295, 2)));
        assertEquals(ErrorCode.REPLAY_PARAM_CONFLICT, ex.errorCode());
    }

    @Test
    void get_missing_request_returns_not_found() {
        ApiException ex = assertThrows(ApiException.class, () -> routeService.getRoute("ghost"));
        assertEquals(ErrorCode.REQUEST_NOT_FOUND, ex.errorCode());
    }

    // ------------------------------------------------------------------
    // 热更新与废弃
    // ------------------------------------------------------------------

    @Test
    void hot_publish_new_version_never_changes_executed_request() {
        // v1：完整链；v2：中间路段失效，起终点候选仍在但不连通
        routeService.publishMap(chainMap("v1", true));
        routeService.submitRoute(routeRequest("r-old", "v1", 5, 2, 295, 2));

        routeService.publishMap(chainMap("v2", false));
        RouteResponse onV2 = routeService.submitRoute(routeRequest("r-new", "v2", 5, 2, 295, 2));

        // 新版本结果断裂
        assertEquals("FAILED", onV2.state());
        assertEquals("PATH_DISCONNECTED", onV2.errorCode());

        // 旧请求重放仍是 v1 的成功结果，候选与路径不变
        RouteResponse oldReplay = routeService.submitRoute(routeRequest("r-old", "v1", 5, 2, 295, 2));
        assertEquals("MATCHED", oldReplay.state());
        assertEquals("v1", oldReplay.boundMapVersion());
        assertEquals(List.of("s1", "s2", "s3"), oldReplay.match().pathSegmentIds());

        // 缓存按版本分区，互不命中
        assertFalse(queryCache.get("v1", "other-hash").isPresent());
    }

    @Test
    void deprecated_version_rejects_new_submit_but_replays_history() {
        routeService.publishMap(chainMap("v1", true));
        routeService.submitRoute(routeRequest("r1", "v1", 5, 2, 295, 2));

        var deprecated = routeService.deprecateMap("v1");
        assertEquals("DEPRECATED", deprecated.status());
        assertNotNull(deprecated.deprecatedAt());

        ApiException ex = assertThrows(ApiException.class,
                () -> routeService.submitRoute(routeRequest("r2", "v1", 5, 2, 295, 2)));
        assertEquals(ErrorCode.MAP_VERSION_DEPRECATED, ex.errorCode());

        // 历史请求同参重放仍然返回原结果
        RouteResponse replay = routeService.submitRoute(routeRequest("r1", "v1", 5, 2, 295, 2));
        assertEquals("MATCHED", replay.state());
    }

    @Test
    void deprecation_evicts_only_target_version_cache() {
        routeService.publishMap(chainMap("v1", true));
        routeService.publishMap(chainMap("v2", true));
        routeService.submitRoute(routeRequest("r1", "v1", 5, 2, 295, 2));
        routeService.submitRoute(routeRequest("r2", "v2", 5, 2, 295, 2));
        int totalCacheEntries = queryCache.size();
        assertTrue(totalCacheEntries >= 2);

        routeService.deprecateMap("v1");

        // 输入摘要含地图版本，故 r1(v1) 与 r2(v2) 的哈希不同，需分别取
        String v1Hash = queryCacheHashOf("r1");
        String v2Hash = queryCacheHashOf("r2");
        // v1 缓存被驱逐，v2 缓存不受影响
        assertFalse(queryCache.get("v1", v1Hash).isPresent());
        assertTrue(queryCache.get("v2", v2Hash).isPresent());
        assertEquals(1, queryCache.size());
        // 已执行请求的持久化候选与结果不被废弃操作改动
        assertEquals(2, count("route_candidate", "request_id", "r1"));
        assertEquals(1, count("route_match", "request_id", "r1"));
    }

    // ------------------------------------------------------------------
    // 数据库约束与并发幂等
    // ------------------------------------------------------------------

    @Test
    void duplicate_request_id_insert_hits_unique_constraint() {
        routeService.publishMap(chainMap("v1", true));
        // 手工插入同 requestId 两次，验证 route_request 唯一约束在数据库层生效
        jdbcTemplate.update("INSERT INTO route_request (request_id, map_version, input_hash, "
                + "start_x, start_y, start_heading, end_x, end_y, end_heading, state, created_at) "
                + "VALUES ('dup', 'v1', 'h', 0,0,0,0,0,0, 'MATCHED', CURRENT_TIMESTAMP)");
        assertThrows(DuplicateKeyException.class, () -> jdbcTemplate.update(
                "INSERT INTO route_request (request_id, map_version, input_hash, "
                        + "start_x, start_y, start_heading, end_x, end_y, end_heading, state, created_at) "
                        + "VALUES ('dup', 'v1', 'h2', 0,0,0,0,0,0, 'FAILED', CURRENT_TIMESTAMP)"));
    }

    @Test
    void concurrent_same_param_submits_are_idempotent() throws Exception {
        routeService.publishMap(chainMap("v1", true));
        int threads = 8;
        ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<RouteResponse>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return routeService.submitRoute(routeRequest("r-conc", "v1", 5, 2, 295, 2));
            }));
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();

        List<RouteResponse> responses = new ArrayList<>();
        for (Future<RouteResponse> future : futures) {
            responses.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertEquals(1, count("route_request", "request_id", "r-conc"));
        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM route_candidate WHERE request_id = 'r-conc'", Integer.class));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM route_match WHERE request_id = 'r-conc'", Integer.class));
        for (RouteResponse response : responses) {
            assertEquals("MATCHED", response.state());
            assertEquals(List.of("s1", "s2", "s3"), response.match().pathSegmentIds());
        }
    }

    @Test
    void concurrent_conflicting_submits_yield_single_winner() throws Exception {
        routeService.publishMap(chainMap("v1", true));
        int threads = 8;
        ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final double offset = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                try {
                    // 每个线程使用不同起点 X，构成异参重放
                    return routeService.submitRoute(
                            routeRequest("r-conf", "v1", 5 + offset, 2, 295, 2));
                } catch (ApiException ex) {
                    return ex.errorCode();
                }
            }));
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();

        int matched = 0;
        int conflicts = 0;
        for (Future<?> future : futures) {
            Object result = future.get(30, TimeUnit.SECONDS);
            if (result instanceof RouteResponse response && "MATCHED".equals(response.state())) {
                matched++;
            } else if (result == ErrorCode.REPLAY_PARAM_CONFLICT) {
                conflicts++;
            }
        }
        pool.shutdown();

        assertEquals(1, matched);
        assertEquals(threads - 1, conflicts);
        assertEquals(1, count("route_request", "request_id", "r-conf"));
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private PublishMapRequest chainMap(String version, boolean middleValid) {
        return new PublishMapRequest(version,
                List.of(new PublishMapRequest.NodeSpec("N1", 0.0, 0.0),
                        new PublishMapRequest.NodeSpec("N2", 100.0, 0.0),
                        new PublishMapRequest.NodeSpec("N3", 200.0, 0.0),
                        new PublishMapRequest.NodeSpec("N4", 300.0, 0.0)),
                List.of(new PublishMapRequest.SegmentSpec("s1", "N1", "N2", 100.0, true),
                        new PublishMapRequest.SegmentSpec("s2", "N2", "N3", 100.0, middleValid),
                        new PublishMapRequest.SegmentSpec("s3", "N3", "N4", 100.0, true)));
    }

    private PublishMapRequest disconnectedMap(String version) {
        return new PublishMapRequest(version,
                List.of(new PublishMapRequest.NodeSpec("N1", 0.0, 0.0),
                        new PublishMapRequest.NodeSpec("N2", 100.0, 0.0),
                        new PublishMapRequest.NodeSpec("N3", 200.0, 0.0),
                        new PublishMapRequest.NodeSpec("N4", 300.0, 0.0)),
                List.of(new PublishMapRequest.SegmentSpec("s1", "N1", "N2", 100.0, true),
                        new PublishMapRequest.SegmentSpec("s2", "N3", "N4", 100.0, true)));
    }

    private RouteSubmitRequest routeRequest(String requestId, String version,
                                            double sx, double sy, double ex, double ey) {
        return new RouteSubmitRequest(requestId, version,
                new RouteSubmitRequest.PointInput(sx, sy, 90.0),
                new RouteSubmitRequest.PointInput(ex, ey, 90.0));
    }

    private int count(String table, String column, String value) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM " + table + " WHERE " + column + " = ?",
                Integer.class, value);
        return count == null ? 0 : count;
    }

    private String queryCacheHashOf(String requestId) {
        return jdbcTemplate.queryForObject(
                "SELECT input_hash FROM route_request WHERE request_id = ?",
                String.class, requestId);
    }

    private CandidateAssertions firstCandidate(RouteResponse response, String endpoint) {
        var candidates = "START".equals(endpoint) ? response.startCandidates() : response.endCandidates();
        var first = candidates.getFirst();
        return new CandidateAssertions(first.selected(), first.connectivityScore(),
                first.distanceScore(), first.headingScore(), first.rejectReasons());
    }

    private record CandidateAssertions(boolean selected, double connectivityScore,
                                       double distanceScore, double headingScore,
                                       List<String> rejectReasons) {
    }
}
