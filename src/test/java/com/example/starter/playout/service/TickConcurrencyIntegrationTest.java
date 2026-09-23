package com.example.starter.playout.service;

import com.example.starter.playout.web.AsRunView;
import com.example.starter.playout.web.PlaylistItemRequest;
import com.example.starter.playout.web.PlaylistPublishRequest;
import com.example.starter.playout.web.TickRequest;
import com.example.starter.playout.web.TickResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 H2 行锁/事务边界下的 tick 并发测试：重复 tick 不得重复生成记录。
 */
@SpringBootTest
class TickConcurrencyIntegrationTest {

    @Autowired
    private PlayoutService service;

    @Test
    @DisplayName("同一时刻并发 tick：只有一个事务生成记录，其余为空结果，序号无空洞冲突")
    void concurrentIdenticalTicksGenerateOnce() throws Exception {
        String ch = "tick-" + UUID.randomUUID();
        service.publishPlaylist(new PlaylistPublishRequest(ch, "v1", List.of(
                new PlaylistItemRequest("A", "digest-A",
                        Instant.parse("2026-03-01T00:00:00Z"),
                        Instant.parse("2026-03-01T02:00:00Z")))));

        int threads = 8;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<TickResponse>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertThat(go.await(5, TimeUnit.SECONDS)).isTrue();
                    return service.tick(new TickRequest(
                            ch, Instant.parse("2026-03-01T00:05:00Z")));
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            int generated = 0;
            for (Future<TickResponse> future : futures) {
                TickResponse response = future.get(10, TimeUnit.SECONDS);
                generated += response.segments().size();
            }
            assertThat(generated).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        List<AsRunView> all = service.queryAsRun(ch, null, null);
        assertThat(all).hasSize(1);
        assertThat(all.get(0).asRunSequence()).isEqualTo(1L);
        assertThat(all.get(0).itemId()).isEqualTo("A");
    }

    @Test
    @DisplayName("乱序并发 tick：早于水位线的结算失败且不产生记录，水位线单调")
    void outOfOrderTicksRejectedUnderConcurrency() throws Exception {
        String ch = "tick-order-" + UUID.randomUUID();
        service.publishPlaylist(new PlaylistPublishRequest(ch, "v1", List.of(
                new PlaylistItemRequest("A", "digest-A",
                        Instant.parse("2026-03-01T00:00:00Z"),
                        Instant.parse("2026-03-01T02:00:00Z")))));

        // 先把水位线推到 00:10。
        service.tick(new TickRequest(ch, Instant.parse("2026-03-01T00:10:00Z")));

        int threads = 4;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String time = i % 2 == 0
                        ? "2026-03-01T00:09:00Z"
                        : "2026-03-01T00:10:00Z";
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertThat(go.await(5, TimeUnit.SECONDS)).isTrue();
                    return service.tick(new TickRequest(ch, Instant.parse(time)));
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            int conflicts = 0;
            int emptyReplays = 0;
            for (Future<?> future : futures) {
                try {
                    TickResponse response = (TickResponse) future.get(10, TimeUnit.SECONDS);
                    assertThat(response.segments()).isEmpty();
                    emptyReplays++;
                } catch (Exception e) {
                    assertThat(e.getCause()).hasMessageContaining("watermark");
                    conflicts++;
                }
            }
            assertThat(conflicts + emptyReplays).isEqualTo(threads);
            assertThat(conflicts).isGreaterThanOrEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        // 首次结算生成唯一一条 A 段，后续无新增。
        List<AsRunView> all = service.queryAsRun(ch, null, null);
        assertThat(all).hasSize(1);
    }
}
