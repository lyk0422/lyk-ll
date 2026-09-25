package com.example.starter.museum;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * requestId 幂等的端到端（真实 H2 + 事务）验证：
 * 同键同参回放首次结果；同键异参 409；并发同键只执行业务一次。
 */
class IdempotencyWebTest extends MuseumTestBase {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void sameRequestIdSameParamsReplaysFirstResultAndDifferentParamsConflict() throws Exception {
        Map<String, Object> body = Map.of(
                "requestId", "req-apply-1",
                "artifactNo", "A001",
                "borrower", "省博物院",
                "startDate", "2026-01-01",
                "endDate", "2027-01-01",
                "showcaseNo", "S001");

        MvcResult first = mockMvc.perform(post("/api/loans/apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode firstJson = objectMapper.readTree(first.getResponse().getContentAsString());
        String contractNo = firstJson.get("contractNo").asText();

        // 同键同参重放：返回首次合同（同一 contractNo），不重复建合同
        MvcResult replay = mockMvc.perform(post("/api/loans/apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        assertThat(objectMapper.readTree(replay.getResponse().getContentAsString()).get("contractNo").asText())
                .isEqualTo(contractNo);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM loan_contract", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM artifact_active_loan", Integer.class)).isEqualTo(1);

        // 同键异参：409
        Map<String, Object> changed = new java.util.HashMap<>(body);
        changed.put("borrower", "另一展方");
        mockMvc.perform(post("/api/loans/apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(changed)))
                .andExpect(status().isConflict());
    }

    @Test
    void repeatedCloseWithSameRequestIdIsIdempotent() throws Exception {
        String contractNo = createInExhibitionContract();
        mockMvc.perform(post("/api/loans/handover/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody("h-l", contractNo, "OUTBOUND", "LENDER")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/loans/handover/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody("h-b", contractNo, "OUTBOUND", "BORROWER")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/loans/handover/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody("r-l", contractNo, "RETURN", "LENDER")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/loans/handover/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody("r-b", contractNo, "RETURN", "BORROWER")))
                .andExpect(status().isOk());

        String closeBody = objectMapper.writeValueAsString(Map.of(
                "requestId", "req-close-1", "contractNo", contractNo));
        MvcResult c1 = mockMvc.perform(post("/api/loans/close")
                        .contentType(MediaType.APPLICATION_JSON).content(closeBody))
                .andExpect(status().isOk()).andReturn();
        MvcResult c2 = mockMvc.perform(post("/api/loans/close")
                        .contentType(MediaType.APPLICATION_JSON).content(closeBody))
                .andExpect(status().isOk()).andReturn();
        assertThat(c2.getResponse().getContentAsString())
                .isEqualTo(c1.getResponse().getContentAsString());
    }

    @Test
    void concurrentSameRequestIdExecutesBusinessOnce() throws Exception {
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();

        String body = objectMapper.writeValueAsString(Map.of(
                "requestId", "req-concurrent-1",
                "artifactNo", "A003",
                "borrower", "并发展方",
                "startDate", "2026-02-01",
                "endDate", "2026-08-01",
                "showcaseNo", "S003"));

        Future<?>[] futures = new Future[threads];
        for (int i = 0; i < threads; i++) {
            futures[i] = pool.submit(() -> {
                try {
                    ready.countDown();
                    start.await();
                    MvcResult result = mockMvc.perform(post("/api/loans/apply")
                                    .contentType(MediaType.APPLICATION_JSON).content(body))
                            .andReturn();
                    if (result.getResponse().getStatus() == 201) {
                        ok.incrementAndGet();
                    } else if (result.getResponse().getStatus() == 409) {
                        conflict.incrementAndGet();
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return null;
            });
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(ok.get()).isEqualTo(threads);
        assertThat(conflict.get()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM loan_contract", Integer.class)).isEqualTo(1);
    }

    @Test
    void historyIsRecomputableFromPersistedData() throws Exception {
        String contractNo = createInExhibitionContract();
        mockMvc.perform(post("/api/loans/handover/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody("hist-h-l", contractNo, "OUTBOUND", "LENDER")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/loans/handover/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody("hist-h-b", contractNo, "OUTBOUND", "BORROWER")))
                .andExpect(status().isOk());
        sendBadReadingsThenReturnContract(contractNo);
        // 轨迹复算：事件、异常、工单均可读回
        MvcResult history = mockMvc.perform(get("/api/loans/" + contractNo + "/history"))
                .andExpect(status().isOk()).andReturn();
        JsonNode json = objectMapper.readTree(history.getResponse().getContentAsString());
        assertThat(json.get("contract").get("status").asText()).isEqualTo("SUSPENDED");
        assertThat(json.get("alerts").size()).isEqualTo(1);
        assertThat(json.get("workOrders").size()).isEqualTo(1);
        assertThat(json.get("workOrders").get(0).get("deadline").asText()).isNotBlank();
        assertThat(json.get("events").size()).isGreaterThanOrEqualTo(5);
    }

    private String sendBadReadingsThenReturnContract(String contractNoIgnored) throws Exception {
        String[] times = {"2026-03-01T00:00:00Z", "2026-03-01T01:00:00Z", "2026-03-01T02:00:00Z"};
        String alertNo = null;
        for (int i = 0; i < times.length; i++) {
            String body = objectMapper.writeValueAsString(Map.of(
                    "requestId", "req-read-" + i,
                    "showcaseNo", "S001",
                    "sampleTime", times[i],
                    "temperature", 30.00,
                    "humidity", 45.00));
            MvcResult result = mockMvc.perform(post("/api/environment/readings")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk()).andReturn();
            JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
            if (node.get("alertTriggered").asBoolean()) {
                alertNo = node.get("alertNo").asText();
            }
        }
        return alertNo;
    }

    private String confirmBody(String requestId, String contractNo, String type, String party) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "requestId", requestId,
                    "contractNo", contractNo,
                    "type", type,
                    "party", party));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String createInExhibitionContract() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "requestId", "req-seed-apply",
                "artifactNo", "A001",
                "borrower", "省博物院",
                "startDate", "2026-01-01",
                "endDate", "2027-01-01",
                "showcaseNo", "S001"));
        MvcResult result = mockMvc.perform(post("/api/loans/apply")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("contractNo").asText();
    }
}
