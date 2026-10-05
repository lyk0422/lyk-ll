package com.example.starter.feed;

import com.example.starter.feed.service.WithdrawalCalculator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 治疗性用药直报端到端测试：真实 H2（MODE=MySQL）建表、唯一约束、事务与行锁并发。
 * 覆盖：直报触发休药期重算（与含药饲喂合并）、已批准预约出栏申请自动撤销（同事务、可查）、
 * 恰满新休药期当日 24:00 后方可重新申请、双人确认、目录外药品拒绝、requestId 幂等、
 * 同批次同时刻唯一直报、并发直报串行合并、出栏核销后迟到直报只记事件并告警。
 */
@SpringBootTest
@AutoConfigureMockMvc
class TreatmentReportIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM idempotent_request");
        jdbc.update("DELETE FROM compliance_alert");
        jdbc.update("DELETE FROM treatment_report_drug");
        jdbc.update("DELETE FROM treatment_report");
        jdbc.update("DELETE FROM slaughter_application");
        jdbc.update("DELETE FROM feeding_record_drug");
        jdbc.update("DELETE FROM feeding_record");
        jdbc.update("DELETE FROM production_drug_snapshot");
        jdbc.update("DELETE FROM production_record");
        jdbc.update("DELETE FROM formula_drug");
        jdbc.update("DELETE FROM formula_ingredient");
        jdbc.update("DELETE FROM feed_formula");
        jdbc.update("DELETE FROM herd_batch");
    }

    // ---------- 工具 ----------

    private org.springframework.test.web.servlet.ResultActions postJson(String url, Map<String, Object> body)
            throws Exception {
        return mockMvc.perform(post(url)
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(body)));
    }

    private JsonNode postJsonBody(String url, Map<String, Object> body) throws Exception {
        MvcResult result = postJson(url, body).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void createBatch(String batchNo, String species) throws Exception {
        mockMvc.perform(post("/api/batches")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "batchNo", batchNo, "species", species))))
                .andExpect(status().isOk());
    }

    private Map<String, Object> reportBody(String requestId, String batchNo, String administeredAt,
                                           List<String> drugNames, List<String> confirmers) {
        return Map.of(
                "requestId", requestId,
                "batchNo", batchNo,
                "administeredAtUtc", administeredAt,
                "drugNames", drugNames,
                "confirmers", confirmers);
    }

    private JsonNode batch(String batchNo) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchNo)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode slaughterTrail(String batchNo) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchNo + "/slaughter-applications"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private long reportCount(String batchNo) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM treatment_report r JOIN herd_batch b ON b.id = r.batch_id "
                        + "WHERE b.batch_no = ?", Long.class, batchNo);
        return count == null ? 0 : count;
    }

    // ---------- 主流程：直报重算 + 冲突预约自动撤销（同事务、可查） ----------

    @Test
    void treatmentReportRecomputesWithdrawalAndRevokesConflictingScheduledApplications() throws Exception {
        createBatch("B-TR-1", "SWINE");
        // 批次明天之后才到出栏窗口：两条未来时刻的出栏申请均批准为 SCHEDULED 预约。
        String nearFuture = Instant.now().plus(2, ChronoUnit.DAYS).toString();
        String farFuture = Instant.now().plus(30, ChronoUnit.DAYS).toString();
        postJson("/api/slaughter-applications", Map.of(
                "requestId", "REQ-TR1-SL-NEAR", "batchNo", "B-TR-1", "applyAtUtc", nearFuture))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approved").value(true))
                .andExpect(jsonPath("$.status").value("FEEDING"));
        postJson("/api/slaughter-applications", Map.of(
                "requestId", "REQ-TR1-SL-FAR", "batchNo", "B-TR-1", "applyAtUtc", farFuture))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approved").value(true));

        // 兽医临时一针：合成氟苯尼考注射液（14 天），给药时刻为当前时刻，休药天数从头算。
        Instant administeredAt = Instant.now();
        LocalDate expectedUntil = WithdrawalCalculator.withdrawalUntilDate(administeredAt, 14);
        Instant expectedEarliest = WithdrawalCalculator.earliestSlaughter(expectedUntil);

        JsonNode report = postJsonBody("/api/treatment-reports",
                reportBody("REQ-TR1-REPORT", "B-TR-1", administeredAt.toString(),
                        List.of("合成氟苯尼考注射液"), List.of("张合成", "李合成")));
        assertThat(report.path("accepted").asBoolean()).isTrue();
        assertThat(report.path("late").asBoolean()).isFalse();
        assertThat(report.path("replayed").asBoolean()).isFalse();
        assertThat(report.path("governingWithdrawalDays").asInt()).isEqualTo(14);
        assertThat(report.path("withdrawalUntilDate").asText()).isEqualTo(expectedUntil.toString());
        assertThat(report.path("earliestSlaughterUtc").asText()).isEqualTo(expectedEarliest.toString());
        // 近预约（2 天后）与新最早出栏日冲突被撤销；远预约（30 天后）不冲突保留。
        assertThat(report.path("revokedApplicationIds")).hasSize(1);

        // 批次休药字段已按新口径写回，原最早出栏日作废。
        JsonNode status = batch("B-TR-1");
        assertThat(status.path("withdrawalUntilDate").asText()).isEqualTo(expectedUntil.toString());
        assertThat(status.path("governingWithdrawalDays").asInt()).isEqualTo(14);
        assertThat(status.path("earliestSlaughterUtc").asText()).isEqualTo(expectedEarliest.toString());
        assertThat(status.path("status").asText()).isEqualTo("FEEDING");

        // 撤销记录保留可查：近预约 REVOKED 且带撤销原因/时刻，远预约仍 SCHEDULED。
        JsonNode trail = slaughterTrail("B-TR-1");
        JsonNode apps = trail.path("applications");
        assertThat(apps).hasSize(2);
        JsonNode near = apps.get(0);
        JsonNode far = apps.get(1);
        assertThat(near.path("requestId").asText()).isEqualTo("REQ-TR1-SL-NEAR");
        assertThat(near.path("status").asText()).isEqualTo("REVOKED");
        assertThat(near.path("revokedAtUtc").isTextual()).isTrue();
        assertThat(near.path("revokeReason").asText()).contains("自动撤销");
        assertThat(report.path("revokedApplicationIds").get(0).asLong()).isEqualTo(near.path("id").asLong());
        assertThat(far.path("requestId").asText()).isEqualTo("REQ-TR1-SL-FAR");
        assertThat(far.path("status").asText()).isEqualTo("SCHEDULED");
        assertThat(far.path("revokedAtUtc").isNull()).isTrue();
    }

    // ---------- 直报与含药饲喂统一合并口径 ----------

    @Test
    void treatmentReportMergesWithMedicatedFeedWithdrawal() throws Exception {
        // 含药配方投产：ENRO 8 天，末次饲喂 2026-09-01 → 原届满日 09-09。
        postJson("/api/formulas/submit", Map.of(
                "requestId", "REQ-MG-F", "formulaCode", "F-MG", "name", "合成配方-MG",
                "targetSpecies", "SWINE",
                "ingredients", List.of(Map.of("code", "ING-CORN", "ratio", 0.7)),
                "drugs", List.of(Map.of("code", "DRG-ENRO", "ratio", 0.02))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/formulas/approve").contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("formulaCode", "F-MG", "version", 1))))
                .andExpect(status().isOk());
        createBatch("B-MG", "SWINE");
        postJson("/api/productions", Map.of(
                "formulaCode", "F-MG", "version", 1, "batchNo", "B-MG",
                "feedAtUtc", "2026-09-01T10:00:00Z"))
                .andExpect(status().isOk());
        assertThat(batch("B-MG").path("withdrawalUntilDate").asText()).isEqualTo("2026-09-09");

        // 直报：合成替米考星注射液 10 天，给药 2026-09-03 → 合并后最晚时刻 09-03、最大天数 10 → 届满 09-13。
        postJson("/api/treatment-reports", reportBody("REQ-MG-R", "B-MG", "2026-09-03T08:00:00Z",
                List.of("合成替米考星注射液"), List.of("张合成", "李合成")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true));

        JsonNode status = batch("B-MG");
        assertThat(status.path("lastMedicatedFeedAtUtc").asText()).isEqualTo("2026-09-03T08:00:00Z");
        assertThat(status.path("governingWithdrawalDays").asInt()).isEqualTo(10);
        assertThat(status.path("withdrawalUntilDate").asText()).isEqualTo("2026-09-13");
        assertThat(status.path("earliestSlaughterUtc").asText()).isEqualTo("2026-09-14T00:00:00Z");
    }

    // ---------- 恰满新休药天数当日 24:00 后方可重新申请出栏 ----------

    @Test
    void reapplicationAllowedOnlyAfterNewWithdrawalExactlyFulfilled() throws Exception {
        createBatch("B-REAPPLY", "SWINE");
        // 直报：合成头孢噻呋注射液 4 天，给药 2026-09-20 → 届满 09-24，新最早出栏 09-25T00:00Z。
        postJson("/api/treatment-reports", reportBody("REQ-RA-R", "B-REAPPLY", "2026-09-20T08:00:00Z",
                List.of("合成头孢噻呋注射液"), List.of("张合成", "李合成")))
                .andExpect(status().isOk());
        JsonNode status = batch("B-REAPPLY");
        assertThat(status.path("withdrawalUntilDate").asText()).isEqualTo("2026-09-24");
        assertThat(status.path("earliestSlaughterUtc").asText()).isEqualTo("2026-09-25T00:00:00Z");

        // 届满日当日 24:00 前（09-24T23:59:59Z）重新申请：拒绝。
        MvcResult early = postJson("/api/slaughter-applications", Map.of(
                "requestId", "REQ-RA-EARLY", "batchNo", "B-REAPPLY",
                "applyAtUtc", "2026-09-24T23:59:59Z")).andReturn();
        assertThat(early.getResponse().getStatus()).isEqualTo(422);
        assertThat(batch("B-REAPPLY").path("status").asText()).isEqualTo("FEEDING");

        // 恰满当日 24:00（09-25T00:00Z）重新申请：批准并核销出栏。
        postJson("/api/slaughter-applications", Map.of(
                "requestId", "REQ-RA-OK", "batchNo", "B-REAPPLY",
                "applyAtUtc", "2026-09-25T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approved").value(true))
                .andExpect(jsonPath("$.status").value("SLAUGHTERED"));
    }

    // ---------- 双人确认：单人直报不生效 ----------

    @Test
    void singleConfirmerReportDoesNotTakeEffect() throws Exception {
        createBatch("B-2P", "SWINE");
        // 两名确认人同名（实为单人）：422 拒绝受理，不产生任何状态变更。
        postJson("/api/treatment-reports", reportBody("REQ-2P-SAME", "B-2P", "2026-09-20T08:00:00Z",
                List.of("合成头孢噻呋注射液"), List.of("张合成", "张合成")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.accepted").value(false))
                .andExpect(jsonPath("$.rejectReason").value(org.hamcrest.Matchers.containsString("两名不同")));
        // 只报一名确认人：参数校验 400。
        postJson("/api/treatment-reports", reportBody("REQ-2P-ONE", "B-2P", "2026-09-20T08:00:00Z",
                List.of("合成头孢噻呋注射液"), List.of("张合成")))
                .andExpect(status().isBadRequest());

        assertThat(reportCount("B-2P")).isZero();
        JsonNode status = batch("B-2P");
        assertThat(status.path("withdrawalUntilDate").isNull()).isTrue();
        assertThat(status.path("governingWithdrawalDays").isNull()).isTrue();
    }

    // ---------- 目录外药品名称拒绝受理且不得改变原出栏日 ----------

    @Test
    void unknownDrugNameRejectedAndKeepsOriginalWithdrawal() throws Exception {
        createBatch("B-UNK", "SWINE");
        // 先建立有效直报：头孢噻呋 4 天，给药 2026-09-01 → 届满 09-05。
        postJson("/api/treatment-reports", reportBody("REQ-UNK-OK", "B-UNK", "2026-09-01T08:00:00Z",
                List.of("合成头孢噻呋注射液"), List.of("张合成", "李合成")))
                .andExpect(status().isOk());
        assertThat(batch("B-UNK").path("withdrawalUntilDate").asText()).isEqualTo("2026-09-05");

        // 目录外药品名称：422 拒绝受理，原出栏日不变。
        postJson("/api/treatment-reports", reportBody("REQ-UNK-BAD", "B-UNK", "2026-09-02T08:00:00Z",
                List.of("合成目录外神药"), List.of("张合成", "李合成")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.accepted").value(false))
                .andExpect(jsonPath("$.rejectReason").value(org.hamcrest.Matchers.containsString("目录外")));

        assertThat(reportCount("B-UNK")).isEqualTo(1);
        JsonNode status = batch("B-UNK");
        assertThat(status.path("withdrawalUntilDate").asText()).isEqualTo("2026-09-05");
        assertThat(status.path("governingWithdrawalDays").asInt()).isEqualTo(4);
        assertThat(status.path("earliestSlaughterUtc").asText()).isEqualTo("2026-09-06T00:00:00Z");
    }

    // ---------- requestId 幂等：同键重放 / 异参 409 ----------

    @Test
    void sameRequestSameParamsReplaysFirstResult() throws Exception {
        createBatch("B-IDEM", "SWINE");
        Map<String, Object> body = reportBody("REQ-TR-IDEM", "B-IDEM", "2026-09-20T08:00:00Z",
                List.of("合成替米考星注射液"), List.of("张合成", "李合成"));
        JsonNode first = postJsonBody("/api/treatment-reports", body);
        assertThat(first.path("accepted").asBoolean()).isTrue();
        assertThat(first.path("replayed").asBoolean()).isFalse();

        JsonNode replay = postJsonBody("/api/treatment-reports", body);
        assertThat(replay.path("replayed").asBoolean()).isTrue();
        assertThat(replay.path("accepted").asBoolean()).isTrue();
        assertThat(replay.path("withdrawalUntilDate").asText())
                .isEqualTo(first.path("withdrawalUntilDate").asText());
        assertThat(reportCount("B-IDEM")).isEqualTo(1);
    }

    @Test
    void sameRequestDifferentParamsReturns409AndChangesNothing() throws Exception {
        createBatch("B-CONF", "SWINE");
        postJson("/api/treatment-reports", reportBody("REQ-TR-CONF", "B-CONF", "2026-09-20T08:00:00Z",
                List.of("合成头孢噻呋注射液"), List.of("张合成", "李合成")))
                .andExpect(status().isOk());

        // 同键异参：换成另一种药。
        postJson("/api/treatment-reports", reportBody("REQ-TR-CONF", "B-CONF", "2026-09-20T08:00:00Z",
                List.of("合成氟苯尼考注射液"), List.of("张合成", "李合成")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("不一致")));

        assertThat(reportCount("B-CONF")).isEqualTo(1);
        // 状态不被改变：仍为首个直报的 4 天口径。
        assertThat(batch("B-CONF").path("governingWithdrawalDays").asInt()).isEqualTo(4);
    }

    // ---------- 同一批次同时刻只允许一条直报 ----------

    @Test
    void sameBatchSameMomentAllowsOnlyOneReport() throws Exception {
        createBatch("B-SAME", "SWINE");
        postJson("/api/treatment-reports", reportBody("REQ-SAME-1", "B-SAME", "2026-09-20T08:00:00Z",
                List.of("合成头孢噻呋注射液"), List.of("张合成", "李合成")))
                .andExpect(status().isOk());
        // 不同 requestId、同批次同给药时刻：409。
        postJson("/api/treatment-reports", reportBody("REQ-SAME-2", "B-SAME", "2026-09-20T08:00:00Z",
                List.of("合成替米考星注射液"), List.of("王合成", "赵合成")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("同一给药时刻")));
        assertThat(reportCount("B-SAME")).isEqualTo(1);
    }

    // ---------- 并发直报：行锁串行化，合并口径唯一 ----------

    @Test
    void concurrentReportsOnSameBatchSerializeAndMergeUniquely() throws Exception {
        createBatch("B-CONC", "SWINE");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<?> f1 = pool.submit(() -> {
            start.await();
            postJson("/api/treatment-reports", reportBody("REQ-CONC-1", "B-CONC", "2026-09-20T08:00:00Z",
                    List.of("合成头孢噻呋注射液"), List.of("张合成", "李合成")))
                    .andExpect(status().isOk());
            return null;
        });
        Future<?> f2 = pool.submit(() -> {
            start.await();
            postJson("/api/treatment-reports", reportBody("REQ-CONC-2", "B-CONC", "2026-09-22T08:00:00Z",
                    List.of("合成氟苯尼考注射液"), List.of("王合成", "赵合成")))
                    .andExpect(status().isOk());
            return null;
        });
        start.countDown();
        f1.get(30, TimeUnit.SECONDS);
        f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 合并口径唯一：最晚给药 09-22，最大休药 14 天 → 届满 10-06，最早出栏 10-07T00:00Z。
        JsonNode status = batch("B-CONC");
        assertThat(status.path("lastMedicatedFeedAtUtc").asText()).isEqualTo("2026-09-22T08:00:00Z");
        assertThat(status.path("governingWithdrawalDays").asInt()).isEqualTo(14);
        assertThat(status.path("withdrawalUntilDate").asText()).isEqualTo("2026-10-06");
        assertThat(status.path("earliestSlaughterUtc").asText()).isEqualTo("2026-10-07T00:00:00Z");
        assertThat(reportCount("B-CONC")).isEqualTo(2);
    }

    @Test
    void concurrentSameMomentReportsAllowExactlyOne() throws Exception {
        createBatch("B-CONC-SAME", "SWINE");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        java.util.List<Future<MvcResult>> futures = new java.util.ArrayList<>();
        for (int i = 1; i <= 2; i++) {
            String requestId = "REQ-CS-" + i;
            futures.add(pool.submit(() -> {
                start.await();
                return postJson("/api/treatment-reports",
                        reportBody(requestId, "B-CONC-SAME", "2026-09-20T08:00:00Z",
                                List.of("合成头孢噻呋注射液"), List.of("张合成", "李合成")))
                        .andReturn();
            }));
        }
        start.countDown();
        int ok = 0;
        int conflict = 0;
        for (Future<MvcResult> f : futures) {
            int statusCode = f.get(30, TimeUnit.SECONDS).getResponse().getStatus();
            if (statusCode == 200) {
                ok++;
            } else if (statusCode == 409) {
                conflict++;
            }
        }
        pool.shutdown();
        assertThat(ok).isEqualTo(1);
        assertThat(conflict).isEqualTo(1);
        assertThat(reportCount("B-CONC-SAME")).isEqualTo(1);
    }

    // ---------- 出栏核销终态：迟到直报只记事件并告警 ----------

    @Test
    void lateReportAfterSlaughterOnlyRecordsEventAndAlerts() throws Exception {
        createBatch("B-LATE-TR", "SWINE");
        // 无休药义务批次立即核销出栏（终态）。
        postJson("/api/slaughter-applications", Map.of(
                "requestId", "REQ-LTR-SL", "batchNo", "B-LATE-TR",
                "applyAtUtc", "2026-09-10T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SLAUGHTERED"));

        // 迟到直报：只记迟到事件并告警，终态与休药字段不变。
        postJson("/api/treatment-reports", reportBody("REQ-LTR-R", "B-LATE-TR", "2026-09-11T08:00:00Z",
                List.of("合成氟苯尼考注射液"), List.of("张合成", "李合成")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.late").value(true))
                .andExpect(jsonPath("$.alerted").value(true));

        JsonNode status = batch("B-LATE-TR");
        assertThat(status.path("status").asText()).isEqualTo("SLAUGHTERED");
        assertThat(status.path("withdrawalUntilDate").isNull()).isTrue();
        assertThat(status.path("governingWithdrawalDays").isNull()).isTrue();

        Integer alerts = jdbc.queryForObject(
                "SELECT COUNT(*) FROM compliance_alert WHERE batch_no = 'B-LATE-TR' "
                        + "AND alert_type = 'LATE_TREATMENT_REPORT'", Integer.class);
        Integer lateReports = jdbc.queryForObject(
                "SELECT COUNT(*) FROM treatment_report r JOIN herd_batch b ON b.id = r.batch_id "
                        + "WHERE b.batch_no = 'B-LATE-TR' AND r.late = TRUE", Integer.class);
        assertThat(alerts).isEqualTo(1);
        assertThat(lateReports).isEqualTo(1);
    }
}
