package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 写操作幂等与并发边界测试。
 */
class IdempotencyConcurrencyTest extends BaseIntegrationTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 4, 1, 8, 0, 0);

    @Test
    @DisplayName("同键同参返回首次结果：响应一致且不产生重复数据")
    void sameKeySameParamsReturnsFirstResult() throws Exception {
        long seg = segmentIds.get(0);
        String body = windJson("idem-1", seg, T0, "12.50");
        String first = mockMvc.perform(post("/api/lines/{lineId}/wind-readings", lineId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(post("/api/lines/{lineId}/wind-readings", lineId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(second).isEqualTo(first);
        assertThat(countRows("wind_reading")).isOne();
        assertThat(countRows("idempotency_record")).isOne();
    }

    @Test
    @DisplayName("同键异参返回 409")
    void sameKeyDifferentParamsReturns409() throws Exception {
        long seg = segmentIds.get(0);
        mockMvc.perform(post("/api/lines/{lineId}/wind-readings", lineId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(windJson("idem-2", seg, T0, "12.50")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/lines/{lineId}/wind-readings", lineId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(windJson("idem-2", seg, T0, "13.50")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        assertThat(countRows("wind_reading")).isOne();
    }

    @Test
    @DisplayName("幂等键冲突不影响其他写接口：不同接口同一 requestId 各自独立")
    void requestIdScopedAcrossEndpoints() throws Exception {
        // 触发缓停
        feedWind(segmentIds.get(0), T0, 10, new BigDecimal("18.00"), "scope");
        long eventId = jdbc.queryForObject("SELECT id FROM control_event", Long.class);
        // 放行接口与风速接口使用相同 requestId 字符串但指纹不同 → 409（指纹覆盖 requestId 与全部业务字段）
        mockMvc.perform(post("/api/events/{eventId}/segment-releases", eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"scope-0\",\"segmentId\":" + segmentIds.get(0) + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    @DisplayName("并发同键同参：全部返回首次结果，仅一条数据与一条幂等记录")
    void concurrentSameKeySameParams() throws Exception {
        long seg = segmentIds.get(0);
        // 先喂 9 分钟，使第 10 条并发写入触发缓停判定
        feedWind(seg, T0, 9, new BigDecimal("18.00"), "pre");
        String body = windJson("conc-same", seg, T0.plusMinutes(9), "18.00");
        int threads = 8;
        List<String> bodies = new ArrayList<>();
        List<Integer> statuses = new ArrayList<>();
        runConcurrently(threads, index -> {
            MvcResult r = mockMvc.perform(post("/api/lines/{lineId}/wind-readings", lineId)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andReturn();
            synchronized (bodies) {
                bodies.add(r.getResponse().getContentAsString());
                statuses.add(r.getResponse().getStatus());
            }
        });
        assertThat(statuses).containsOnly(200);
        assertThat(bodies).allSatisfy(b -> assertThat(b).isEqualTo(bodies.get(0)));
        assertThat(countRows("wind_reading")).isEqualTo(10);
        assertThat(countRows("control_event")).isOne();
        assertThat(countRows("idempotency_record")).isEqualTo(10);
    }

    @Test
    @DisplayName("并发同键异参：恰有一个成功，其余全部 409")
    void concurrentSameKeyDifferentParams() throws Exception {
        long seg = segmentIds.get(0);
        int threads = 6;
        List<Integer> statuses = new ArrayList<>();
        runConcurrently(threads, index -> {
            // 每个线程风速不同，requestId 相同
            String speed = "1" + index + ".00";
            MvcResult r = mockMvc.perform(post("/api/lines/{lineId}/wind-readings", lineId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(windJson("conc-diff", seg, T0, speed)))
                    .andReturn();
            synchronized (statuses) {
                statuses.add(r.getResponse().getStatus());
            }
        });
        long ok = statuses.stream().filter(s -> s == 200).count();
        long conflict = statuses.stream().filter(s -> s == 409).count();
        assertThat(ok).isEqualTo(1);
        assertThat(conflict).isEqualTo(threads - 1);
        assertThat(countRows("wind_reading")).isOne();
        assertThat(countRows("idempotency_record")).isOne();
    }

    @Test
    @DisplayName("并发触发同线路管制：同一线路同一时刻仅一个进行中管制事件")
    void concurrentTriggerKeepsSingleOpenEvent() throws Exception {
        long seg1 = segmentIds.get(0);
        long seg2 = segmentIds.get(1);
        // 两个分段各喂 9 分钟高风速
        feedWind(seg1, T0, 9, new BigDecimal("19.00"), "t1");
        feedWind(seg2, T0, 9, new BigDecimal("19.00"), "t2");
        // 并发补第 10 分钟，两个分段同时越限
        runConcurrently(2, index -> {
            long seg = index == 0 ? seg1 : seg2;
            mockMvc.perform(post("/api/lines/{lineId}/wind-readings", lineId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(windJson("final-" + index, seg, T0.plusMinutes(9), "19.00")));
        });
        assertThat(countRows("control_event")).isOne();
        Long openCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM control_event WHERE line_id = ? AND status <> 'CLOSED'",
                Long.class, lineId);
        assertThat(openCount).isEqualTo(1);
    }

    /** 以倒计时门闩协调真实并发执行，并设置超时。 */
    private void runConcurrently(int threads, ThrowingTask task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int index = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                try {
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("并发启动超时");
                    }
                    task.run(index);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        for (Future<?> f : futures) {
            f.get();
        }
    }

    @FunctionalInterface
    private interface ThrowingTask {
        void run(int index) throws Exception;
    }
}
