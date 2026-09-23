package com.example.starter.costmap.service;

import com.example.starter.costmap.support.AbstractH2IntegrationTest;
import com.example.starter.costmap.web.ApiException;
import com.example.starter.costmap.web.ErrorCode;
import com.example.starter.costmap.web.dto.CreateMapRequest;
import com.example.starter.costmap.web.dto.ObstacleRequest;
import com.example.starter.costmap.web.dto.ObstacleResponse;
import com.example.starter.costmap.web.dto.RegionDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发更新测试：多个来源真实并发更新同一格，验证行锁串行化、
 * 公开决胜规则的确定性结果，以及同一 requestId 并发重放的幂等/409 边界。
 */
class CostmapConcurrencyH2Test extends AbstractH2IntegrationTest {

    private static final int W = 8;
    private static final int H = 8;
    private static final int THREADS = 8;

    @Autowired
    private CostmapService service;

    private String createMap(String mapId) {
        return service.createMap(new CreateMapRequest(mapId, W, H, 0.1d, 0, 0,
                1.0d, 2.0d, 2.0d, null)).mapId();
    }

    @Test
    void concurrentUpdatesToSameCellMergeDeterministically() throws Exception {
        String mapId = createMap("m-concurrent");
        long t = clock().nowMillis();
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ObstacleResponse>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < THREADS; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    // 线程 3 拥有最高优先级；线程 0..2 优先级同为 9 但 eventTime 最晚的是线程 2
                    int priority = idx == 3 ? 10 : (idx <= 2 ? 9 : idx);
                    long eventTime = t + (idx == 2 ? 50_000L : idx);
                    ObstacleRequest req = new ObstacleRequest(
                            "req-" + idx, "source-" + idx, "obstacle-" + idx, priority,
                            "OCCUPY", new RegionDto(4, 4, 4, 4), eventTime, 1_000_000L);
                    return service.applyObstacle(mapId, req);
                }));
            }
            start.countDown();
            for (Future<ObstacleResponse> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        // 最终 (4,4) 必须是致命代价；获胜语句来源确定为 source-3（优先级 10 最高）
        assertThat(service.queryRegion(mapId, new RegionDto(4, 4, 4, 4))
                .cells().get(0).cost()).isEqualTo(254);

        byte[] winnerBlob = jdbcTemplate.queryForObject(
                "SELECT winner_data FROM costmap WHERE id = ?", byte[].class, mapId);
        long winner = java.nio.ByteBuffer.wrap(winnerBlob)
                .order(java.nio.ByteOrder.BIG_ENDIAN)
                .getLong((4 * W + 4) * Long.BYTES);
        assertThat(winner).isGreaterThan(0L);
        String winnerSource = jdbcTemplate.queryForObject(
                "SELECT source_id FROM obstacle_statement WHERE map_id = ? AND seq = ?",
                String.class, mapId, winner);
        assertThat(winnerSource).isEqualTo("source-3");

        // 高优先级来源下线（过期）后，确定性次胜者（同优先级 9 中 eventTime 最晚的 source-2）接管
        jdbcTemplate.update("UPDATE obstacle_statement SET expire_at = ? "
                + "WHERE map_id = ? AND source_id = ?", t - 1L, mapId, "source-3");
        service.purgeExpired(mapId);
        winnerBlob = jdbcTemplate.queryForObject(
                "SELECT winner_data FROM costmap WHERE id = ?", byte[].class, mapId);
        winner = java.nio.ByteBuffer.wrap(winnerBlob)
                .order(java.nio.ByteOrder.BIG_ENDIAN)
                .getLong((4 * W + 4) * Long.BYTES);
        winnerSource = jdbcTemplate.queryForObject(
                "SELECT source_id FROM obstacle_statement WHERE map_id = ? AND seq = ?",
                String.class, mapId, winner);
        assertThat(winnerSource).isEqualTo("source-2");
    }

    @Test
    void concurrentSameRequestSameParamsAppliesExactlyOnce() throws Exception {
        String mapId = createMap("m-idem-concurrent");
        long t = clock().nowMillis();
        int copies = 6;
        ExecutorService pool = Executors.newFixedThreadPool(copies);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ObstacleResponse>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < copies; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    ObstacleRequest req = new ObstacleRequest("same-req", "lidar", "o", 2,
                            "OCCUPY", new RegionDto(1, 1, 1, 1), t, 1_000_000L);
                    return service.applyObstacle(mapId, req);
                }));
            }
            start.countDown();
            Long firstSeq = null;
            int applied = 0;
            int replayed = 0;
            for (Future<ObstacleResponse> f : futures) {
                ObstacleResponse resp = f.get(30, TimeUnit.SECONDS);
                if (resp.replayed()) {
                    replayed++;
                } else {
                    applied++;
                }
                if (firstSeq == null) {
                    firstSeq = resp.statementSeq();
                } else {
                    assertThat(resp.statementSeq()).isEqualTo(firstSeq);
                }
            }
            assertThat(applied).isEqualTo(1);
            assertThat(replayed).isEqualTo(copies - 1);
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM obstacle_statement WHERE map_id = ? AND request_id = ?",
                Integer.class, mapId, "same-req");
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void concurrentSameRequestDifferentParamsYieldsOneSuccessAndConflicts() throws Exception {
        String mapId = createMap("m-idem-409-concurrent");
        long t = clock().nowMillis();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);

        Callable<ObstacleResponse> first = () -> {
            start.await();
            return service.applyObstacle(mapId, new ObstacleRequest("race-req", "lidar", "o", 2,
                    "OCCUPY", new RegionDto(1, 1, 1, 1), t, 1_000_000L));
        };
        Callable<ObstacleResponse> second = () -> {
            start.await();
            return service.applyObstacle(mapId, new ObstacleRequest("race-req", "lidar", "o", 2,
                    "OCCUPY", new RegionDto(2, 2, 2, 2), t, 1_000_000L));
        };

        try {
            Future<ObstacleResponse> f1 = pool.submit(first::call);
            Future<ObstacleResponse> f2 = pool.submit(second::call);
            start.countDown();

            int success = 0;
            int conflict = 0;
            for (Future<ObstacleResponse> f : List.of(f1, f2)) {
                try {
                    f.get(30, TimeUnit.SECONDS);
                    success++;
                } catch (Exception e) {
                    Throwable cause = e.getCause();
                    assertThat(cause).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) cause).errorCode())
                            .isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT);
                    conflict++;
                }
            }
            assertThat(success).isEqualTo(1);
            assertThat(conflict).isEqualTo(1);
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}
