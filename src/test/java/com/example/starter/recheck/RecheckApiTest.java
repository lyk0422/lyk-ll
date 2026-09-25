package com.example.starter.recheck;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.temporal.ChronoUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import com.example.starter.BaseIntegrationTest;
import com.example.starter.TestClockConfig;
import com.example.starter.common.ApiException;

/**
 * 复检申诉测试：7 日边界、撤销原子性、维持原判、并发唯一受理与结案拒绝。
 */
class RecheckApiTest extends BaseIntegrationTest {

    @Autowired
    private RecheckService recheckService;

    /** 造一条超限率恰 30% 的罚款档处罚，返回处罚编号。 */
    private long createFinePenalty(String plateNo) throws Exception {
        String weigh = """
                {"requestId":"rw-%s","plateNo":"%s","stationId":"ST01","weighedAt":"2026-09-20T08:00:00Z",
                 "axleType":"AXLE_2","weighType":"DYNAMIC","totalWeightKg":23400}
                """.formatted(plateNo, plateNo);
        MvcResult weighResult = mockMvc.perform(post("/api/weigh-records")
                        .contentType(MediaType.APPLICATION_JSON).content(weigh))
                .andExpect(status().isCreated()).andReturn();
        long weighId = Long.parseLong(com.jayway.jsonpath.JsonPath.read(
                weighResult.getResponse().getContentAsString(), "$.id").toString());

        String penalty = "{\"requestId\":\"rp-%s\",\"weighRecordId\":%d}".formatted(plateNo, weighId);
        MvcResult penaltyResult = mockMvc.perform(post("/api/penalties")
                        .contentType(MediaType.APPLICATION_JSON).content(penalty))
                .andExpect(status().isCreated()).andReturn();
        return Long.parseLong(com.jayway.jsonpath.JsonPath.read(
                penaltyResult.getResponse().getContentAsString(), "$.id").toString());
    }

    private String recheckJson(String requestId, long penaltyId, int staticWeightKg) {
        return "{\"requestId\":\"%s\",\"penaltyId\":%d,\"staticWeightKg\":%d}"
                .formatted(requestId, penaltyId, staticWeightKg);
    }

    @Test
    @DisplayName("复检第 7 日提交受理，第 8 日逾期拒绝")
    void recheckWindowDayBoundary() throws Exception {
        long penaltyId = createFinePenalty("苏C00001");
        long penaltyId2 = createFinePenalty("苏C00002");
        // 恰第 7 日（UTC 日界）：受理
        clock.set(TestClockConfig.START.plus(7, ChronoUnit.DAYS));
        mockMvc.perform(post("/api/rechecks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(recheckJson("rc-7d", penaltyId, 17000)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.outcome", is("OVERTURNED")));

        // 第 8 日：逾期拒绝
        clock.set(TestClockConfig.START.plus(8, ChronoUnit.DAYS));
        mockMvc.perform(post("/api/rechecks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(recheckJson("rc-8d", penaltyId2, 17000)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("RECHECK_OVERDUE")));
        Long rechecks = jdbc.queryForObject(
                "SELECT COUNT(*) FROM recheck_request WHERE penalty_id = ?", Long.class, penaltyId2);
        Assertions.assertEquals(0L, rechecks);
    }

    @Test
    @DisplayName("复检推翻原处罚：同一事务内撤销原决定、退还罚款记分并生成撤销文书")
    void overturnRevokesAtomically() throws Exception {
        long penaltyId = createFinePenalty("苏C00003");
        mockMvc.perform(post("/api/rechecks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(recheckJson("rc-ot", penaltyId, 17000)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.outcome", is("OVERTURNED")))
                .andExpect(jsonPath("$.revocationDocumentId", notNullValue()));

        // 不得罚款已退而原决定仍生效：原决定必须为 REVOKED
        String status = jdbc.queryForObject(
                "SELECT status FROM penalty_decision WHERE id = ?", String.class, penaltyId);
        Assertions.assertEquals("REVOKED", status);
        // 撤销文书退还罚款与记分
        var doc = jdbc.queryForMap(
                "SELECT refunded_fine, refunded_points FROM revocation_document WHERE penalty_id = ?",
                penaltyId);
        Assertions.assertEquals(0, new java.math.BigDecimal("2000.00")
                .compareTo((java.math.BigDecimal) doc.get("REFUNDED_FINE")));
        Assertions.assertEquals(3, ((Number) doc.get("REFUNDED_POINTS")).intValue());
    }

    @Test
    @DisplayName("静态复检仍超限且同档：维持原处罚，不生成撤销文书")
    void upheldKeepsOriginalPenalty() throws Exception {
        long penaltyId = createFinePenalty("苏C00004");
        mockMvc.perform(post("/api/rechecks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(recheckJson("rc-up", penaltyId, 23400)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.outcome", is("UPHELD")))
                .andExpect(jsonPath("$.revocationDocumentId", nullValue()));

        String status = jdbc.queryForObject(
                "SELECT status FROM penalty_decision WHERE id = ?", String.class, penaltyId);
        Assertions.assertEquals("ACTIVE", status);
        Long docs = jdbc.queryForObject("SELECT COUNT(*) FROM revocation_document", Long.class);
        Assertions.assertEquals(0L, docs);
    }

    @Test
    @DisplayName("静态复检降档：撤销原处罚并按静态结果作出替代处罚")
    void overturnToLowerTierIssuesReplacement() throws Exception {
        long penaltyId = createFinePenalty("苏C00005");
        // 静态复检 19800 -> 超限率 0.10，卸载放行档，与原罚款档不一致
        mockMvc.perform(post("/api/rechecks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(recheckJson("rc-low", penaltyId, 19800)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.outcome", is("OVERTURNED")))
                .andExpect(jsonPath("$.staticOverRate", is(0.10)))
                .andExpect(jsonPath("$.replacementPenaltyId", notNullValue()));

        String replacementMeasure = jdbc.queryForObject(
                "SELECT measure FROM penalty_decision WHERE id = 2", String.class);
        Assertions.assertEquals("UNLOAD", replacementMeasure);
    }

    @Test
    @DisplayName("复检幂等：同键同参返回首次结果，同键异参 409")
    void recheckIdempotency() throws Exception {
        long penaltyId = createFinePenalty("苏C00006");
        String body = recheckJson("rc-idem", penaltyId, 17000);
        mockMvc.perform(post("/api/rechecks")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", is(1)));

        mockMvc.perform(post("/api/rechecks")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(1)))
                .andExpect(jsonPath("$.idempotentReplay", is(true)));

        mockMvc.perform(post("/api/rechecks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(recheckJson("rc-idem", penaltyId, 18000)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("IDEMPOTENCY_CONFLICT")));
    }

    @Test
    @DisplayName("同一案件并发复检申请唯一受理")
    void concurrentRecheckOnlyOneAccepted() throws Exception {
        long penaltyId = createFinePenalty("苏C00007");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<Object> resultA = new AtomicReference<>();
        AtomicReference<Object> resultB = new AtomicReference<>();

        Runnable taskA = () -> applyConcurrent(ready, go, resultA, "rc-con-a", penaltyId);
        Runnable taskB = () -> applyConcurrent(ready, go, resultB, "rc-con-b", penaltyId);
        Future<?> futureA = pool.submit(taskA);
        Future<?> futureB = pool.submit(taskB);
        Assertions.assertTrue(ready.await(10, TimeUnit.SECONDS));
        go.countDown();
        futureA.get(15, TimeUnit.SECONDS);
        futureB.get(15, TimeUnit.SECONDS);
        pool.shutdownNow();

        int successes = (resultA.get() instanceof RecheckResponse ? 1 : 0)
                + (resultB.get() instanceof RecheckResponse ? 1 : 0);
        int conflicts = (isAlreadyAccepted(resultA.get()) ? 1 : 0)
                + (isAlreadyAccepted(resultB.get()) ? 1 : 0);
        Assertions.assertEquals(1, successes, "并发复检应恰有一次受理成功");
        Assertions.assertEquals(1, conflicts, "另一并发复检应被拒绝");
        Long rechecks = jdbc.queryForObject(
                "SELECT COUNT(*) FROM recheck_request WHERE penalty_id = ?", Long.class, penaltyId);
        Assertions.assertEquals(1L, rechecks);
    }

    private void applyConcurrent(CountDownLatch ready, CountDownLatch go,
            AtomicReference<Object> sink, String requestId, long penaltyId) {
        try {
            ready.countDown();
            go.await(10, TimeUnit.SECONDS);
            sink.set(recheckService.apply(new RecheckApplyRequest(requestId, penaltyId, 17000)));
        } catch (ApiException e) {
            sink.set(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sink.set(e);
        }
    }

    private boolean isAlreadyAccepted(Object result) {
        return result instanceof ApiException ex && "RECHECK_ALREADY_ACCEPTED".equals(ex.getCode());
    }

    @Test
    @DisplayName("案件已结案不再受理复检，返回 409")
    void closedCaseRejectsRecheck() throws Exception {
        long penaltyId = createFinePenalty("苏C00008");
        mockMvc.perform(post("/api/cases/1/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("CLOSED")));

        mockMvc.perform(post("/api/rechecks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(recheckJson("rc-closed", penaltyId, 17000)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("CASE_CLOSED")));
    }
}
