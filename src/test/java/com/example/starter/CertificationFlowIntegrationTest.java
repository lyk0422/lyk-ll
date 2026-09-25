package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.starter.service.ApiException;
import com.example.starter.service.CertificationService;
import com.example.starter.support.AppClock;
import com.example.starter.web.dto.DeclareLotRequest;
import com.example.starter.web.dto.FieldInspectionRequest;
import com.example.starter.web.dto.LabInspectionRequest;
import com.example.starter.web.dto.RevokeLotRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 种子认证主流程、失败分支、幂等边界与 H2 事务/并发测试。
 * 不使用 mock：全部断言落到真实 H2（MODE=MySQL）中的最终数据。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CertificationFlowIntegrationTest {

    private static final Instant BASE_TIME = Instant.parse("2026-09-01T08:00:00Z");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private CertificationService service;
    @Autowired
    private AppClock appClock;

    private int seq;

    @BeforeEach
    void cleanDbAndClock() {
        jdbc.update("DELETE FROM certification_audit");
        jdbc.update("DELETE FROM certificate");
        jdbc.update("DELETE FROM field_inspection");
        jdbc.update("DELETE FROM lab_inspection");
        jdbc.update("DELETE FROM idempotent_request");
        jdbc.update("DELETE FROM seed_lot");
        appClock.setClock(Clock.fixed(BASE_TIME, ZoneOffset.UTC));
    }

    @AfterEach
    void resetClock() {
        appClock.reset();
    }

    private String lotNo() {
        return "LOT-IT-" + (++seq);
    }

    private void setClock(String instant) {
        appClock.setClock(Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    private DeclareLotRequest declareRequest(String lotNo, String grade, int labels,
                                             String requestId) {
        return new DeclareLotRequest(requestId, lotNo, "V-RICE-001", "P-A-01",
                grade, labels, "合成申报人");
    }

    private ResultActions declareViaApi(String lotNo, String grade, int labels, String requestId)
            throws Exception {
        return mockMvc.perform(post("/api/lots/declarations")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(
                        declareRequest(lotNo, grade, labels, requestId))));
    }

    private ResultActions fieldViaApi(String lotNo, String purity, String round,
                                      String requestId) throws Exception {
        FieldInspectionRequest body = new FieldInspectionRequest(requestId, lotNo,
                new BigDecimal(purity), round, "合成田间检验员");
        return mockMvc.perform(post("/api/lots/" + lotNo + "/field-inspections")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions labViaApi(String lotNo, String rate, String round,
                                    String requestId) throws Exception {
        LabInspectionRequest body = new LabInspectionRequest(requestId, lotNo,
                new BigDecimal(rate), round, "合成室内检测员");
        return mockMvc.perform(post("/api/lots/" + lotNo + "/lab-inspections")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions certifyViaApi(String lotNo) throws Exception {
        return mockMvc.perform(post("/api/lots/" + lotNo + "/certifications"));
    }

    private ResultActions revokeViaApi(String lotNo, String reason, String operator)
            throws Exception {
        return mockMvc.perform(post("/api/lots/" + lotNo + "/revocation")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(new RevokeLotRequest(reason, operator))));
    }

    private JsonNode json(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private int count(String table, String where, Object... args) {
        Integer value = jdbc.queryForObject(
                "SELECT COUNT(1) FROM " + table + " WHERE " + where, Integer.class, args);
        return value == null ? 0 : value;
    }

    /** 服务层结果归一为首响应该有的 JSON 文本（视图或同键重放体）。 */
    private String normalizeResult(Object result) throws Exception {
        if (result instanceof CertificationService.ReplayedRequest replayed) {
            return replayed.body().toString();
        }
        return objectMapper.writeValueAsString(result);
    }

    // ============================ 主流程与边界 ============================

    @Test
    @DisplayName("原种双检恰好达标 → 发原种证；标签核减为 0；重复发证 409")
    void originalBoundaryIssueAndDeductLabels() throws Exception {
        String lotNo = lotNo();
        declareViaApi(lotNo, "ORIGINAL", 500, "REQ-DEC-1").andExpect(status().isOk());

        ResultActions field = fieldViaApi(lotNo, "99.900", "FIRST", "REQ-FIELD-1");
        field.andExpect(status().isOk());
        assertThat(json(field).path("verdict").asText()).isEqualTo("PASS");
        assertThat(json(field).path("retestable").asBoolean()).isFalse();

        ResultActions lab = labViaApi(lotNo, "90.000", "FIRST", "REQ-LAB-1");
        lab.andExpect(status().isOk());
        assertThat(json(lab).path("verdict").asText()).isEqualTo("PASS");

        ResultActions certify = certifyViaApi(lotNo);
        certify.andExpect(status().isOk());
        JsonNode certJson = json(certify);
        assertThat(certJson.path("certificate").path("grade").asText()).isEqualTo("ORIGINAL");
        assertThat(certJson.path("certificate").path("certificateNo").asText())
                .startsWith("SEED-20260901-LOT-IT-");
        assertThat(certJson.path("certificate").path("deductedLabels").asInt()).isEqualTo(500);
        assertThat(certJson.path("lot").path("availableLabels").asInt()).isZero();
        assertThat(certJson.path("lot").path("status").asText()).isEqualTo("CERTIFIED");

        // 重复发证 → 409，证书仍只有一条，标签不再变动
        certifyViaApi(lotNo).andExpect(status().isConflict());
        assertThat(count("certificate", "lot_no = ?", lotNo)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT available_labels FROM seed_lot WHERE lot_no = ?",
                Integer.class, lotNo)).isZero();
    }

    @Test
    @DisplayName("不得越级：纯度达原种、发芽率仅达良种 → 发良种证；任一低于良种门槛拒绝发证")
    void cannotSkipGrade() throws Exception {
        String lotNo = lotNo();
        declareViaApi(lotNo, "ORIGINAL", 200, "REQ-DEC-2").andExpect(status().isOk());
        fieldViaApi(lotNo, "99.900", "FIRST", "REQ-FIELD-2").andExpect(status().isOk());
        labViaApi(lotNo, "85.000", "FIRST", "REQ-LAB-2").andExpect(status().isOk());

        ResultActions certify = certifyViaApi(lotNo);
        certify.andExpect(status().isOk());
        assertThat(json(certify).path("certificate").path("grade").asText())
                .isEqualTo("IMPROVED");
        assertThat(json(certify).path("lot").path("availableLabels").asInt()).isZero();

        // 另一批次：纯度 98.999（低于良种 99.0 门槛）→ 不发证
        String badLot = lotNo();
        declareViaApi(badLot, "IMPROVED", 100, "REQ-DEC-2B").andExpect(status().isOk());
        fieldViaApi(badLot, "98.999", "FIRST", "REQ-FIELD-2B").andExpect(status().isOk());
        labViaApi(badLot, "90.000", "FIRST", "REQ-LAB-2B").andExpect(status().isOk());
        ResultActions rejected = certifyViaApi(badLot);
        rejected.andExpect(status().isUnprocessableEntity());
        assertThat(json(rejected).path("code").asText()).isEqualTo("NOT_CERTIFIABLE");
        // 事务边界：拒绝发证不落证书、不核减标签、状态仍待定
        assertThat(count("certificate", "lot_no = ?", badLot)).isZero();
        assertThat(jdbc.queryForObject("SELECT available_labels FROM seed_lot WHERE lot_no = ?",
                Integer.class, badLot)).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT status FROM seed_lot WHERE lot_no = ?",
                String.class, badLot)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("一项不合格一项未回：批次保持待定，发证 422，不合格结论带可复检标记与截止日")
    void pendingWhenOneFailOneMissing() throws Exception {
        String lotNo = lotNo();
        declareViaApi(lotNo, "ORIGINAL", 300, "REQ-DEC-3").andExpect(status().isOk());

        ResultActions field = fieldViaApi(lotNo, "99.899", "FIRST", "REQ-FIELD-3");
        field.andExpect(status().isOk());
        JsonNode fieldJson = json(field);
        assertThat(fieldJson.path("verdict").asText()).isEqualTo("FAIL");
        assertThat(fieldJson.path("retestable").asBoolean()).isTrue();
        assertThat(fieldJson.path("retestDeadline").asText()).startsWith("2026-09-16T00:00:00Z");

        // 室内检测未回 → 发证被拒
        ResultActions certify = certifyViaApi(lotNo);
        certify.andExpect(status().isUnprocessableEntity());
        assertThat(json(certify).path("code").asText()).isEqualTo("LAB_RESULT_MISSING");
        assertThat(jdbc.queryForObject("SELECT status FROM seed_lot WHERE lot_no = ?",
                String.class, lotNo)).isEqualTo("PENDING");
    }

    // ============================ 幂等 ============================

    @Test
    @DisplayName("申报幂等：同键同参返回首次结果且仅一批次；同键异参 409 且不产生新检验记录")
    void idempotencySameKeyAndConflict() throws Exception {
        String lotNo = lotNo();
        String firstBody = json(declareViaApi(lotNo, "ORIGINAL", 120, "REQ-IDEM-1")
                .andExpect(status().isOk())).toString();
        String secondBody = json(declareViaApi(lotNo, "ORIGINAL", 120, "REQ-IDEM-1")
                .andExpect(status().isOk())).toString();
        assertThat(secondBody).isEqualTo(firstBody);
        assertThat(count("seed_lot", "lot_no = ?", lotNo)).isEqualTo(1);
        assertThat(count("idempotent_request", "request_id = ?", "REQ-IDEM-1")).isEqualTo(1);

        fieldViaApi(lotNo, "99.900", "FIRST", "REQ-IDEM-F").andExpect(status().isOk());
        // 同 requestId 不同纯度 → 409
        ResultActions conflict = fieldViaApi(lotNo, "99.500", "FIRST", "REQ-IDEM-F");
        conflict.andExpect(status().isConflict());
        assertThat(json(conflict).path("code").asText())
                .isEqualTo("IDEMPOTENT_PARAM_MISMATCH");
        // 409 不产生新检验记录：仍只有一条且纯度为首次值
        assertThat(count("field_inspection", "lot_no = ?", lotNo)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT purity FROM field_inspection WHERE lot_no = ? AND inspect_round = 1",
                BigDecimal.class, lotNo)).isEqualByComparingTo("99.900");
    }

    // ============================ 复检 ============================

    @Test
    @DisplayName("复检第 15 日（UTC 日界）受理；复检覆盖首检且首检保留可查")
    void retestOnDay15OverridesFirst() throws Exception {
        String lotNo = lotNo();
        declareViaApi(lotNo, "ORIGINAL", 400, "REQ-DEC-4").andExpect(status().isOk());
        fieldViaApi(lotNo, "99.899", "FIRST", "REQ-FIELD-4").andExpect(status().isOk());

        // 第 15 日当日受理
        setClock("2026-09-16T23:59:59Z");
        ResultActions retest = fieldViaApi(lotNo, "99.950", "RETEST", "REQ-FIELD-4-R");
        retest.andExpect(status().isOk());
        JsonNode retestJson = json(retest);
        assertThat(retestJson.path("round").asInt()).isEqualTo(2);
        assertThat(retestJson.path("verdict").asText()).isEqualTo("PASS");
        assertThat(retestJson.path("effective").asBoolean()).isTrue();

        // 唯一有效结论；首检记录保留但已失效
        assertThat(count("field_inspection", "lot_no = ? AND effective = TRUE", lotNo))
                .isEqualTo(1);
        assertThat(count("field_inspection", "lot_no = ?", lotNo)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT result FROM field_inspection WHERE lot_no = ? AND inspect_round = 1",
                String.class, lotNo)).isEqualTo("FAIL");
        assertThat(jdbc.queryForObject(
                "SELECT effective FROM field_inspection WHERE lot_no = ? AND inspect_round = 1",
                Boolean.class, lotNo)).isFalse();

        // 详情接口可同时查到失效首检与有效复检
        ResultActions detail = mockMvc.perform(get("/api/lots/" + lotNo));
        detail.andExpect(status().isOk());
        JsonNode rows = json(detail).path("fieldInspections");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).path("round").asInt()).isEqualTo(1);
        assertThat(rows.get(0).path("effective").asBoolean()).isFalse();
        assertThat(rows.get(1).path("effective").asBoolean()).isTrue();

        // 复检合格后补齐室内检测 → 按复检结论发原种证
        labViaApi(lotNo, "90.000", "FIRST", "REQ-LAB-4").andExpect(status().isOk());
        JsonNode certJson = json(certifyViaApi(lotNo).andExpect(status().isOk()));
        assertThat(certJson.path("certificate").path("grade").asText()).isEqualTo("ORIGINAL");
    }

    @Test
    @DisplayName("复检第 16 日（跨 UTC 日界）逾期拒绝；复检以一次为限")
    void retestExpiredAndOnlyOnce() throws Exception {
        String lotNo = lotNo();
        declareViaApi(lotNo, "ORIGINAL", 400, "REQ-DEC-5").andExpect(status().isOk());
        fieldViaApi(lotNo, "99.000", "FIRST", "REQ-FIELD-5").andExpect(status().isOk());

        // 第 16 日 00:00 逾期
        setClock("2026-09-17T00:00:00Z");
        ResultActions expired = fieldViaApi(lotNo, "99.950", "RETEST", "REQ-FIELD-5-R1");
        expired.andExpect(status().isUnprocessableEntity());
        assertThat(json(expired).path("code").asText()).isEqualTo("RETEST_EXPIRED");
        assertThat(count("field_inspection", "lot_no = ? AND inspect_round = 2", lotNo)).isZero();

        // 另一批次验证“复检以一次为限”：第 10 日复检一次后再复检 → 409
        String another = lotNo();
        declareViaApi(another, "ORIGINAL", 400, "REQ-DEC-5B").andExpect(status().isOk());
        fieldViaApi(another, "99.000", "FIRST", "REQ-FIELD-5B").andExpect(status().isOk());
        setClock("2026-09-11T00:00:00Z");
        fieldViaApi(another, "99.000", "RETEST", "REQ-FIELD-5B-R1")
                .andExpect(status().isOk());
        ResultActions twice = fieldViaApi(another, "99.999", "RETEST", "REQ-FIELD-5B-R2");
        twice.andExpect(status().isConflict());
        assertThat(count("field_inspection", "lot_no = ? AND inspect_round = 2", another))
                .isEqualTo(1);
    }

    // ============================ 并发 ============================

    @Test
    @DisplayName("并发对同批次发证：仅一次成功，唯一证书，标签恰好核减一次")
    void concurrentCertificationOnlyOneSucceeds() throws Exception {
        String lotNo = lotNo();
        declareViaApi(lotNo, "ORIGINAL", 600, "REQ-DEC-6").andExpect(status().isOk());
        fieldViaApi(lotNo, "99.900", "FIRST", "REQ-FIELD-6").andExpect(status().isOk());
        labViaApi(lotNo, "90.000", "FIRST", "REQ-LAB-6").andExpect(status().isOk());

        int threads = 8;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    try {
                        barrier.await(10, TimeUnit.SECONDS);
                        service.certify(lotNo);
                        ok.incrementAndGet();
                    } catch (ApiException e) {
                        conflict.incrementAndGet();
                    } catch (InterruptedException | BrokenBarrierException
                             | java.util.concurrent.TimeoutException e) {
                        throw new AssertionError("并发协调失败", e);
                    }
                }));
            }
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(ok.get()).isEqualTo(1);
        assertThat(conflict.get()).isEqualTo(threads - 1);
        assertThat(count("certificate", "lot_no = ?", lotNo)).isEqualTo(1);
        assertThat(count("certificate", "lot_no = ? AND status = 'VALID'", lotNo)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT available_labels FROM seed_lot WHERE lot_no = ?",
                Integer.class, lotNo)).isZero();
        assertThat(count("certification_audit",
                "lot_no = ? AND action = 'CERT_ISSUED'", lotNo)).isEqualTo(1);
    }

    @Test
    @DisplayName("并发同键同参申报：均返回首次结果，仅生成一个批次")
    void concurrentSameRequestIdDeclareOnce() throws Exception {
        int threads = 6;
        String lotNo = lotNo();
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return normalizeResult(service.declare(declareRequest(lotNo, "IMPROVED",
                            80, "REQ-CONCUR-DEC")));
                }));
            }
            String first = futures.get(0).get(30, TimeUnit.SECONDS);
            for (Future<String> future : futures) {
                assertThat(future.get(30, TimeUnit.SECONDS)).isEqualTo(first);
            }
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(count("seed_lot", "lot_no = ?", lotNo)).isEqualTo(1);
        assertThat(count("idempotent_request", "request_id = ?", "REQ-CONCUR-DEC")).isEqualTo(1);
    }

    @Test
    @DisplayName("并发提交两次复检：一笔生效一笔 409，仅一条有效复检结论")
    void concurrentRetestOnlyOneEffective() throws Exception {
        String lotNo = lotNo();
        declareViaApi(lotNo, "ORIGINAL", 300, "REQ-DEC-7").andExpect(status().isOk());
        fieldViaApi(lotNo, "99.500", "FIRST", "REQ-FIELD-7").andExpect(status().isOk());
        setClock("2026-09-05T12:00:00Z");

        int threads = 2;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                final String requestId = "REQ-FIELD-7-R" + i;
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    try {
                        service.submitField(new FieldInspectionRequest(requestId, lotNo,
                                new BigDecimal("99.950"), "RETEST", "合成田间检验员"));
                        return 200;
                    } catch (ApiException e) {
                        return e.getStatus().value();
                    }
                }));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(count("field_inspection", "lot_no = ? AND inspect_round = 2", lotNo))
                .isEqualTo(1);
        assertThat(count("field_inspection", "lot_no = ? AND effective = TRUE", lotNo))
                .isEqualTo(1);
    }

    // ============================ 注销终态 ============================

    @Test
    @DisplayName("注销终态：证书同步作废并留痕；重复注销幂等；注销后检验复检发证全拒绝")
    void revokeIsTerminalAndIdempotent() throws Exception {
        String lotNo = lotNo();
        declareViaApi(lotNo, "ORIGINAL", 250, "REQ-DEC-8").andExpect(status().isOk());
        fieldViaApi(lotNo, "99.900", "FIRST", "REQ-FIELD-8").andExpect(status().isOk());
        labViaApi(lotNo, "90.000", "FIRST", "REQ-LAB-8").andExpect(status().isOk());
        certifyViaApi(lotNo).andExpect(status().isOk());

        ResultActions revoke = revokeViaApi(lotNo, "合成注销原因-质量召回", "合成管理员");
        revoke.andExpect(status().isOk());
        JsonNode revokeJson = json(revoke);
        assertThat(revokeJson.path("revokedNow").asBoolean()).isTrue();
        assertThat(revokeJson.path("lot").path("status").asText()).isEqualTo("CANCELLED");
        assertThat(revokeJson.path("revokedCertificate").path("status").asText())
                .isEqualTo("REVOKED");

        // 重复注销 → 幂等成功（200，revokedNow=false），留痕不重复
        ResultActions again = revokeViaApi(lotNo, "合成注销原因-质量召回", "合成管理员");
        again.andExpect(status().isOk());
        assertThat(json(again).path("revokedNow").asBoolean()).isFalse();

        // 注销后检验、复检、发证一律拒绝
        fieldViaApi(lotNo, "99.900", "FIRST", "REQ-AFTER-F")
                .andExpect(status().isUnprocessableEntity());
        labViaApi(lotNo, "99.900", "FIRST", "REQ-AFTER-L")
                .andExpect(status().isUnprocessableEntity());
        certifyViaApi(lotNo).andExpect(status().isUnprocessableEntity());

        // 终态与留痕落库核验
        assertThat(jdbc.queryForObject("SELECT status FROM seed_lot WHERE lot_no = ?",
                String.class, lotNo)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM certificate WHERE lot_no = ?", String.class, lotNo))
                .isEqualTo("REVOKED");
        assertThat(count("certification_audit",
                "lot_no = ? AND action = 'LOT_REVOKED'", lotNo)).isEqualTo(1);
        assertThat(count("certification_audit",
                "lot_no = ? AND action = 'CERT_REVOKED'", lotNo)).isEqualTo(1);
        assertThat(count("certification_audit",
                "lot_no = ? AND action = 'CERT_ISSUED'", lotNo)).isEqualTo(1);
    }

    @Test
    @DisplayName("未发证批次注销：无证书可作废但留痕，后续检验拒绝")
    void revokePendingLotRejectsFurtherInspection() throws Exception {
        String lotNo = lotNo();
        declareViaApi(lotNo, "IMPROVED", 90, "REQ-DEC-9").andExpect(status().isOk());
        revokeViaApi(lotNo, "合成注销原因-申报撤销", "合成管理员").andExpect(status().isOk());

        ResultActions blocked = fieldViaApi(lotNo, "99.000", "FIRST", "REQ-FIELD-9");
        blocked.andExpect(status().isUnprocessableEntity());
        assertThat(json(blocked).path("code").asText()).isEqualTo("LOT_CANCELLED");
        assertThat(count("field_inspection", "lot_no = ?", lotNo)).isZero();
        assertThat(count("certificate", "lot_no = ?", lotNo)).isZero();
        assertThat(count("certification_audit",
                "lot_no = ? AND action = 'LOT_REVOKED'", lotNo)).isEqualTo(1);
    }
}
