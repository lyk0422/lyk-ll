package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.example.starter.plan.dto.ApproveRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 放疗计划服务 API 与 H2 事务/并发测试。
 * 覆盖：约束恰等限值、超限拒绝、总剂量勾稽、验证恰3%、跳次拒绝告警、
 * 换版原子性、并发唯一批准、同键重放/异参409、结案终态与迟到事件。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PlanApiTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private PlanService planService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper om;

    /** 固定时钟，保证时间相关行为可复现 */
    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(Instant.parse("2026-09-26T08:00:00Z"), ZoneOffset.UTC);
        }
    }

    @BeforeEach
    void cleanDb() {
        jdbc.update("DELETE FROM alert_record");
        jdbc.update("DELETE FROM late_fraction_event");
        jdbc.update("DELETE FROM fraction_record");
        jdbc.update("DELETE FROM closure_record");
        jdbc.update("DELETE FROM verification_record");
        jdbc.update("DELETE FROM approval_record");
        jdbc.update("DELETE FROM review_record");
        jdbc.update("DELETE FROM plan_oar_dose");
        jdbc.update("DELETE FROM plan");
        jdbc.update("DELETE FROM patient");
    }

    // ---------------------------------------------------------------- 工具方法

    /** 恰等/低于限值的合成危及器官剂量 */
    private Map<String, String> oarsAtLimit() {
        Map<String, String> oars = new LinkedHashMap<>();
        oars.put("SPINAL_CORD", "45.00");
        oars.put("BRAINSTEM", "54.00");
        oars.put("HEART", "30.00");
        oars.put("LUNG_LEFT", "20.00");
        oars.put("PAROTID_LEFT", "26.00");
        return oars;
    }

    private long createPlan(String patientNo, String total, int count, String dose,
                            Map<String, String> oars) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("patientNo", patientNo);
        body.put("patientName", "合成患者" + patientNo);
        body.put("prescriptionTotalGy", total);
        body.put("fractionCount", count);
        body.put("fractionDoseGy", dose);
        body.put("oarDoses", oars.entrySet().stream()
                .map(e -> Map.of("organ", e.getKey(), "doseGy", e.getValue())).toList());
        MvcResult res = mvc.perform(post("/api/plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        return om.readTree(res.getResponse().getContentAsString()).get("planId").asLong();
    }

    private JsonNode postJson(String url, Map<String, Object> body, int expectedStatus) throws Exception {
        MvcResult res = mvc.perform(post(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return om.readTree(res.getResponse().getContentAsString());
    }

    private String planStatus(long planId) throws Exception {
        MvcResult res = mvc.perform(get("/api/plans/{id}", planId))
                .andExpect(status().isOk())
                .andReturn();
        return om.readTree(res.getResponse().getContentAsString()).get("status").asText();
    }

    private void reviewPassed(long planId, String requestId) throws Exception {
        postJson("/api/plans/" + planId + "/physics-review",
                Map.of("requestId", requestId, "reviewer", "PHYS_甲"), 200);
    }

    private void approvePassed(long planId, String requestId) throws Exception {
        postJson("/api/plans/" + planId + "/approve",
                Map.of("requestId", requestId, "physician", "DR_乙"), 200);
    }

    private void verifyPassed(long planId, String requestId, String measured) throws Exception {
        postJson("/api/plans/" + planId + "/verify",
                Map.of("requestId", requestId, "measuredDoseGy", measured), 200);
    }

    private void fractionOk(long planId, String requestId, int fractionNo, String dose) throws Exception {
        postJson("/api/plans/" + planId + "/fractions",
                Map.of("requestId", requestId, "fractionNo", fractionNo, "doseGy", dose), 200);
    }

    /** 推进计划至 EXECUTING（完成1次分次） */
    private long driveToExecuting(String patientNo, String tag) throws Exception {
        long planId = createPlan(patientNo, "60.00", 30, "2.00", oarsAtLimit());
        reviewPassed(planId, "rev-" + tag);
        approvePassed(planId, "ap-" + tag);
        verifyPassed(planId, "vf-" + tag, "2.00");
        fractionOk(planId, "fr-" + tag + "-1", 1, "2.00");
        return planId;
    }

    // ---------------------------------------------------------------- 约束校验

    @Test
    void constraintExactlyAtLimitPasses() throws Exception {
        long planId = createPlan("PT-001", "60.00", 30, "2.00", oarsAtLimit());
        JsonNode review = postJson("/api/plans/" + planId + "/physics-review",
                Map.of("requestId", "rev-001", "reviewer", "PHYS_甲"), 200);
        assertThat(review.get("result").asText()).isEqualTo("PASSED");
        assertThat(review.get("violations")).isEmpty();
        assertThat(planStatus(planId)).isEqualTo("PHYSICS_REVIEWED");
    }

    @Test
    void constraintExceededRejects() throws Exception {
        Map<String, String> oars = oarsAtLimit();
        oars.put("SPINAL_CORD", "45.01");
        long planId = createPlan("PT-002", "60.00", 30, "2.00", oars);
        JsonNode review = postJson("/api/plans/" + planId + "/physics-review",
                Map.of("requestId", "rev-002", "reviewer", "PHYS_甲"), 200);
        assertThat(review.get("result").asText()).isEqualTo("REJECTED");
        assertThat(review.get("violations").toString()).contains("OAR_DOSE_EXCEEDED", "SPINAL_CORD");
        assertThat(planStatus(planId)).isEqualTo("DESIGN");
    }

    @Test
    void totalDoseMismatchRejects() throws Exception {
        long planId = createPlan("PT-003", "60.00", 30, "2.01", oarsAtLimit());
        JsonNode review = postJson("/api/plans/" + planId + "/physics-review",
                Map.of("requestId", "rev-003", "reviewer", "PHYS_甲"), 200);
        assertThat(review.get("result").asText()).isEqualTo("REJECTED");
        assertThat(review.get("violations").toString()).contains("TOTAL_DOSE_MISMATCH");
        assertThat(planStatus(planId)).isEqualTo("DESIGN");
    }

    // ---------------------------------------------------------------- 模体验证

    @Test
    void verificationExactly3PercentPasses() throws Exception {
        long planId = createPlan("PT-004", "60.00", 30, "2.00", oarsAtLimit());
        reviewPassed(planId, "rev-004");
        approvePassed(planId, "ap-004");
        JsonNode verify = postJson("/api/plans/" + planId + "/verify",
                Map.of("requestId", "vf-004", "measuredDoseGy", "2.06"), 200);
        assertThat(verify.get("passed").asBoolean()).isTrue();
        assertThat(verify.get("deviationPercent").decimalValue()).isEqualByComparingTo("3.0000");
        assertThat(planStatus(planId)).isEqualTo("VERIFIED");
    }

    @Test
    void verificationOver3PercentRejected() throws Exception {
        long planId = createPlan("PT-005", "60.00", 30, "2.00", oarsAtLimit());
        reviewPassed(planId, "rev-005");
        approvePassed(planId, "ap-005");
        JsonNode verify = postJson("/api/plans/" + planId + "/verify",
                Map.of("requestId", "vf-005", "measuredDoseGy", "2.07"), 200);
        assertThat(verify.get("passed").asBoolean()).isFalse();
        assertThat(verify.get("deviationPercent").decimalValue()).isEqualByComparingTo("3.5000");
        assertThat(planStatus(planId)).isEqualTo("APPROVED");
    }

    // ---------------------------------------------------------------- 分次执行

    @Test
    void skipFractionRejectedAndAlerted() throws Exception {
        long planId = createPlan("PT-006", "60.00", 30, "2.00", oarsAtLimit());
        reviewPassed(planId, "rev-006");
        approvePassed(planId, "ap-006");
        verifyPassed(planId, "vf-006", "2.00");
        JsonNode error = postJson("/api/plans/" + planId + "/fractions",
                Map.of("requestId", "fr-006-skip", "fractionNo", 2, "doseGy", "2.00"), 422);
        assertThat(error.get("message").asText()).contains("跳次执行拒绝");
        MvcResult alerts = mvc.perform(get("/api/plans/{id}/alerts", planId))
                .andExpect(status().isOk()).andReturn();
        JsonNode alertList = om.readTree(alerts.getResponse().getContentAsString());
        assertThat(alertList).hasSize(1);
        assertThat(alertList.get(0).get("alertType").asText()).isEqualTo("FRACTION_SKIP");
        // 顺序分次仍可执行
        fractionOk(planId, "fr-006-1", 1, "2.00");
        assertThat(planStatus(planId)).isEqualTo("EXECUTING");
    }

    @Test
    void fractionDoseMismatchRejected() throws Exception {
        long planId = createPlan("PT-007", "60.00", 30, "2.00", oarsAtLimit());
        reviewPassed(planId, "rev-007");
        approvePassed(planId, "ap-007");
        verifyPassed(planId, "vf-007", "2.00");
        postJson("/api/plans/" + planId + "/fractions",
                Map.of("requestId", "fr-007-1", "fractionNo", 1, "doseGy", "2.50"), 422);
    }

    // ---------------------------------------------------------------- 换版原子性

    @Test
    void reviseInvalidatesOldVersionAtomically() throws Exception {
        long planId = createPlan("PT-008", "60.00", 30, "2.00", oarsAtLimit());
        reviewPassed(planId, "rev-008");
        approvePassed(planId, "ap-008");
        Map<String, Object> reviseBody = new LinkedHashMap<>();
        reviseBody.put("prescriptionTotalGy", "50.00");
        reviseBody.put("fractionCount", 25);
        reviseBody.put("fractionDoseGy", "2.00");
        reviseBody.put("oarDoses", oarsAtLimit().entrySet().stream()
                .map(e -> Map.of("organ", e.getKey(), "doseGy", e.getValue())).toList());
        JsonNode revised = postJson("/api/plans/" + planId + "/revise", reviseBody, 200);
        long newPlanId = revised.get("planId").asLong();
        assertThat(revised.get("version").asInt()).isEqualTo(2);
        assertThat(revised.get("status").asText()).isEqualTo("DESIGN");
        assertThat(revised.get("supersededPlanId").asLong()).isEqualTo(planId);
        // 旧版本即刻失效，不可继续流转
        assertThat(planStatus(planId)).isEqualTo("INVALIDATED");
        postJson("/api/plans/" + planId + "/verify",
                Map.of("requestId", "vf-008-old", "measuredDoseGy", "2.00"), 409);
        // 新版本须重新审核批准
        reviewPassed(newPlanId, "rev-008-v2");
        approvePassed(newPlanId, "ap-008-v2");
        assertThat(planStatus(newPlanId)).isEqualTo("APPROVED");
    }

    // ---------------------------------------------------------------- 并发批准

    @Test
    void concurrentApprovalSamePlanOnlyOneSucceeds() throws Exception {
        long planId = createPlan("PT-009", "60.00", 30, "2.00", oarsAtLimit());
        reviewPassed(planId, "rev-009");
        List<String> results = runConcurrently(
                () -> planService.approve(planId, new ApproveRequest("ap-009-a", "DR_甲")),
                () -> planService.approve(planId, new ApproveRequest("ap-009-b", "DR_乙")));
        assertThat(results).filteredOn("OK"::equals).hasSize(1);
        assertThat(results).filteredOn(r -> r.startsWith("FAIL")).hasSize(1);
        Integer approvals = jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval_record WHERE plan_id=?", Integer.class, planId);
        assertThat(approvals).isEqualTo(1);
        assertThat(planStatus(planId)).isEqualTo("APPROVED");
    }

    @Test
    void concurrentApprovalSamePatientOnlyOneActivePlan() throws Exception {
        long planA = createPlan("PT-010", "60.00", 30, "2.00", oarsAtLimit());
        long planB = createPlan("PT-010", "60.00", 30, "2.00", oarsAtLimit());
        reviewPassed(planA, "rev-010-a");
        reviewPassed(planB, "rev-010-b");
        List<String> results = runConcurrently(
                () -> planService.approve(planA, new ApproveRequest("ap-010-a", "DR_甲")),
                () -> planService.approve(planB, new ApproveRequest("ap-010-b", "DR_乙")));
        assertThat(results).filteredOn("OK"::equals).hasSize(1);
        assertThat(results).filteredOn(r -> r.startsWith("FAIL")).hasSize(1);
        Integer active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM plan p JOIN patient pt ON p.patient_id=pt.id"
                        + " WHERE pt.patient_no='PT-010' AND p.status IN ('APPROVED','VERIFIED','EXECUTING')",
                Integer.class);
        assertThat(active).isEqualTo(1);
    }

    @FunctionalInterface
    private interface ApprovalCall {
        void run();
    }

    /** 并发执行两个批准调用，返回 OK/FAIL:code 结果列表 */
    private List<String> runConcurrently(ApprovalCall first, ApprovalCall second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (ApprovalCall call : List.of(first, second)) {
                futures.add(pool.submit(() -> {
                    gate.await();
                    try {
                        call.run();
                        return "OK";
                    } catch (ApiException e) {
                        return "FAIL:" + e.getCode();
                    }
                }));
            }
            gate.countDown();
            List<String> results = new ArrayList<>();
            for (Future<String> future : futures) {
                results.add(future.get(15, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------------------------------------------------------------- 幂等

    @Test
    void sameRequestIdReplaysAndDifferentParamsConflict() throws Exception {
        long planId = createPlan("PT-011", "60.00", 30, "2.00", oarsAtLimit());
        reviewPassed(planId, "rev-011");
        approvePassed(planId, "ap-011");
        verifyPassed(planId, "vf-011", "2.00");
        JsonNode first = postJson("/api/plans/" + planId + "/fractions",
                Map.of("requestId", "fr-011-1", "fractionNo", 1, "doseGy", "2.00"), 200);
        assertThat(first.get("idempotent").asBoolean()).isFalse();
        // 同键同参：返回首次结果，不产生新分次
        JsonNode replay = postJson("/api/plans/" + planId + "/fractions",
                Map.of("requestId", "fr-011-1", "fractionNo", 1, "doseGy", "2.00"), 200);
        assertThat(replay.get("idempotent").asBoolean()).isTrue();
        assertThat(replay.get("fractionNo").asInt()).isEqualTo(1);
        // 同键异参：409，且不产生新分次
        JsonNode conflict = postJson("/api/plans/" + planId + "/fractions",
                Map.of("requestId", "fr-011-1", "fractionNo", 2, "doseGy", "2.00"), 409);
        assertThat(conflict.get("code").asText()).isEqualTo("CONFLICT");
        Integer fractions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fraction_record WHERE plan_id=?", Integer.class, planId);
        assertThat(fractions).isEqualTo(1);
        // 审核同键异参同样 409
        postJson("/api/plans/" + planId + "/physics-review",
                Map.of("requestId", "rev-011", "reviewer", "PHYS_丙"), 409);
    }

    // ---------------------------------------------------------------- 结案终态

    @Test
    void closureIsTerminalAndLateFractionOnlyRecordedAsEvent() throws Exception {
        long planId = driveToExecuting("PT-012", "012");
        JsonNode closed = postJson("/api/plans/" + planId + "/close",
                Map.of("requestId", "cl-012", "operator", "OP_丁"), 200);
        assertThat(closed.get("status").asText()).isEqualTo("CLOSED");
        assertThat(closed.get("idempotent").asBoolean()).isFalse();
        // 重复结案：幂等成功
        JsonNode reclosed = postJson("/api/plans/" + planId + "/close",
                Map.of("requestId", "cl-012-again", "operator", "OP_丁"), 200);
        assertThat(reclosed.get("status").asText()).isEqualTo("CLOSED");
        assertThat(reclosed.get("idempotent").asBoolean()).isTrue();
        // 结案后迟到分次：只记迟到事件，不入执行台账
        JsonNode late = postJson("/api/plans/" + planId + "/fractions",
                Map.of("requestId", "fr-012-late", "fractionNo", 2, "doseGy", "2.00"), 200);
        assertThat(late.get("status").asText()).isEqualTo("LATE");
        Integer fractions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fraction_record WHERE plan_id=?", Integer.class, planId);
        assertThat(fractions).isEqualTo(1);
        Integer lateEvents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM late_fraction_event WHERE plan_id=?", Integer.class, planId);
        assertThat(lateEvents).isEqualTo(1);
        assertThat(planStatus(planId)).isEqualTo("CLOSED");
    }

    // ---------------------------------------------------------------- 台账复算

    @Test
    void ledgersAreQueryable() throws Exception {
        long planId = driveToExecuting("PT-013", "013");
        mvc.perform(get("/api/plans/{id}/reviews", planId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].result").value("PASSED"));
        mvc.perform(get("/api/plans/{id}/verifications", planId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].passed").value(true));
        mvc.perform(get("/api/plans/{id}/fractions", planId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fractionNo").value(1))
                .andExpect(jsonPath("$[0].status").value("EXECUTED"));
    }
}
