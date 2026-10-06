package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
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
 * 并发与幂等边界：同 requestId 并发只处理一次；并发投诉恰触发一次事件；并发解除只成功一次。
 */
class ConcurrencyIdempotencyTest extends BaseIntegrationTest {

    private List<MvcResult> runConcurrently(List<Callable<MvcResult>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<MvcResult>> synchronizedTasks = tasks.stream()
                .<Callable<MvcResult>>map(task -> () -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    return task.call();
                })
                .toList();
        try {
            List<Future<MvcResult>> futures = synchronizedTasks.stream().map(pool::submit).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<MvcResult> results = new ArrayList<>();
            for (Future<MvcResult> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private Callable<MvcResult> complaintTask(String requestId, String station, String sampledAt) {
        return () -> {
            String json = objectMapper.writeValueAsString(Map.of(
                    "requestId", requestId,
                    "stationId", station,
                    "frequencyMhz", "514.250",
                    "sampledAt", sampledAt,
                    "fieldStrengthDbuv", "68.5",
                    "evidenceRef", "EV-" + requestId));
            return mockMvc.perform(post("/api/complaints")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json))
                    .andReturn();
        };
    }

    @Test
    @DisplayName("并发同 requestId 同参数：全部返回首次结果，只登记一次")
    void concurrentSameRequestIdProcessedOnce() throws Exception {
        List<Callable<MvcResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tasks.add(complaintTask("conc-same", "ST-1", "2026-01-01T10:00:00Z"));
        }
        List<MvcResult> results = runConcurrently(tasks);

        String firstBody = results.get(0).getResponse().getContentAsString();
        for (MvcResult result : results) {
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(result.getResponse().getContentAsString()).isEqualTo(firstBody);
        }
        assertThat(countRows("complaint")).isEqualTo(1);
        assertThat(countRows("idempotency_record")).isEqualTo(1);
    }

    @Test
    @DisplayName("3 个不同监测站并发投诉：恰触发一次干扰事件")
    void concurrentThirdStationTriggersExactlyOneEvent() throws Exception {
        String sampledAt = "2026-01-01T10:00:00Z";
        List<MvcResult> results = runConcurrently(List.of(
                complaintTask("conc-t1", "ST-1", sampledAt),
                complaintTask("conc-t2", "ST-2", sampledAt),
                complaintTask("conc-t3", "ST-3", sampledAt)));

        int created = 0;
        for (MvcResult result : results) {
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            var json = objectMapper.readTree(result.getResponse().getContentAsString());
            if (json.get("eventCreated").asBoolean()) {
                created++;
            }
        }
        assertThat(created).isEqualTo(1);
        assertThat(countRows("complaint")).isEqualTo(3);
        assertThat(countRows("interference_event")).isEqualTo(1);
    }

    @Test
    @DisplayName("并发解除同一发射机限功率：仅一次成功，复测与确认记录唯一")
    void concurrentReleaseOnlyOneSucceeds() throws Exception {
        long eventId = createEventViaComplaints("514.250", "crel");
        reviewEvent(eventId, "crel-review", "CONFIRMED_INTERFERENCE");
        mockMvc.perform(post("/api/events/" + eventId + "/close")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("closedBy", "closer-1"))))
                .andReturn();
        long beta = transmitterIdOf("TX-BETA");

        List<Callable<MvcResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            final int n = i;
            tasks.add(() -> {
                String json = objectMapper.writeValueAsString(Map.of(
                        "operationStaffId", "op-" + n,
                        "monitoringStaffId", "mon-" + n,
                        "retestResult", "NO_INTERFERENCE",
                        "retestSampledAt", clock.instant().toString(),
                        "retestEvidenceRef", "RETEST-EV-" + n));
                return mockMvc.perform(post("/api/transmitters/" + beta + "/release")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json))
                        .andReturn();
            });
        }
        List<MvcResult> results = runConcurrently(tasks);

        long successes = results.stream()
                .filter(r -> r.getResponse().getStatus() == 200).count();
        assertThat(successes).isEqualTo(1);
        assertThat(countRows("retest_record")).isEqualTo(1);
        assertThat(countRows("release_confirmation")).isEqualTo(1);
        Integer executing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM power_directive WHERE transmitter_id = ? AND status = 'EXECUTING'",
                Integer.class, beta);
        assertThat(executing).isZero();
    }
}
