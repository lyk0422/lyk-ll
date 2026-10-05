package com.example.starter.feed;

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
import java.time.ZoneOffset;
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
 * 治疗性用药直报端到端测试：真实 H2（MODE=MySQL）唯一约束、事务与行锁并发。
 * 覆盖：双人确认生效/单人直报不生效、目录外药品拒绝且原出栏日不变、
 * 最晚给药时刻与最大休药天数合并重算、已批准出栏申请冲突自动撤销（同事务、记录可查）、
 * 恰满新休药期当日 24:00 后方可重新申请、requestId 幂等（同键重放/异参 409）、
 * 同批次同时刻唯一进行中直报（顺序与真实并发）、出栏核销终态后迟到直报只记事件并告警。
 */
@SpringBootTest
@AutoConfigureMockMvc
class TreatmentReportIntegrationTest {

    /** 合成兽药目录：头孢噻呋 4 天 / 氟苯尼考 10 天 / 替米考星 12 天。 */
    private static final String VET_CEFT = "合成头孢噻呋注射液";
    private static final String VET_TILM = "合成替米考星注射液";

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
        jdbc.update("DELETE FROM slaughter_revocation");
        jdbc.update("DELETE FROM slaughter_application");
        jdbc.update("DELETE FROM treatment_report");
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

    private MvcResult postJson(String url, Map<String, Object> body) throws Exception {
        return mockMvc.perform(post(url)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void approveSwineEnroFormula(String requestId, String formulaCode) throws Exception {
        mockMvc.perform(post("/api/formulas/submit").contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "requestId", requestId,
                                "formulaCode", formulaCode,
                                "name", "合成配方-" + formulaCode,
                                "targetSpecies", "SWINE",
                                "ingredients", List.of(
                                        Map.of("code", "ING-CORN", "ratio", 0.60),
                                        Map.of("code", "ING-SOY", "ratio", 0.30)),
                                "drugs", List.of(Map.of("code", "DRG-ENRO", "ratio", 0.02))))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/formulas/approve").contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "formulaCode", formulaCode, "version", 1))))
                .andExpect(status().isOk());
    }

    private void createBatch(String batchNo) throws Exception {
        mockMvc.perform(post("/api/batches").contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "batchNo", batchNo, "species", "SWINE"))))
                .andExpect(status().isOk());
    }

    private void produce(String formulaCode, String batchNo, String feedAt) throws Exception {
        mockMvc.perform(post("/api/productions").contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "formulaCode", formulaCode,
                                "version", 1,
                                "batchNo", batchNo,
                                "feedAtUtc", feedAt))))
                .andExpect(status().isOk());
    }

    private JsonNode batch(String batchNo) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchNo)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return readJson(result);
    }

    private Map<String, Object> treatmentBody(String requestId, String batchNo, String drugName,
                                              String administeredAt, List<String> confirmers) {
        return Map.of(
                "requestId", requestId,
                "batchNo", batchNo,
                "drugName", drugName,
                "administeredAtUtc", administeredAt,
                "confirmedBy", confirmers);
    }

    private static LocalDate utcDate(Instant instant) {
        return instant.atZone(ZoneOffset.UTC).toLocalDate();
    }

    private static Instant startOfDayUtc(LocalDate date) {
        return date.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    // ---------- 主流程：双人确认直报触发合并重算 ----------

    @Test
    void treatmentReportRecalculatesWithdrawalByMaxDaysAndLatestTime() throws Exception {
        approveSwineEnroFormula("REQ-TR1-F", "F-TR1");
        createBatch("B-TR1");
        // ENRO 休药 8 天：末次饲喂 2026-09-01 → 届满 09-09，最早出栏 09-10T00:00Z。
        produce("F-TR1", "B-TR1", "2026-09-01T10:00:00Z");
        assertThat(batch("B-TR1").path("withdrawalUntilDate").asText()).isEqualTo("2026-09-09");

        // 治疗直报：替米考星 12 天，给药 2026-09-03T20:00Z（更晚且天数更大）。
        MvcResult result = postJson("/api/treatment-reports",
                treatmentBody("REQ-TR1-T", "B-TR1", VET_TILM,
                        "2026-09-03T20:00:00Z", List.of("keeper-a", "keeper-b")));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = readJson(result);
        assertThat(body.path("accepted").asBoolean()).isTrue();
        assertThat(body.path("late").asBoolean()).isFalse();
        assertThat(body.path("replayed").asBoolean()).isFalse();
        assertThat(body.path("withdrawalDays").asInt()).isEqualTo(12);
        // 合并口径：最晚给药 09-03T20:00Z + 最大休药 12 天 → 届满 09-15，最早出栏 09-16T00:00Z。
        assertThat(body.path("withdrawalUntilDate").asText()).isEqualTo("2026-09-15");
        assertThat(body.path("earliestSlaughterUtc").asText()).isEqualTo("2026-09-16T00:00:00Z");
        assertThat(body.path("governingWithdrawalDays").asInt()).isEqualTo(12);
        assertThat(body.path("revokedSlaughterRequestIds").isEmpty()).isTrue();

        // 原最早出栏日作废：批次视图与直报响应一致。
        JsonNode status = batch("B-TR1");
        assertThat(status.path("lastMedicatedFeedAtUtc").asText()).isEqualTo("2026-09-03T20:00:00Z");
        assertThat(status.path("withdrawalUntilDate").asText()).isEqualTo("2026-09-15");
        assertThat(status.path("earliestSlaughterUtc").asText()).isEqualTo("2026-09-16T00:00:00Z");
        assertThat(status.path("governingWithdrawalDays").asInt()).isEqualTo(12);
    }

    // ---------- 撤销：已批准出栏申请与新最早出栏日冲突时同事务自动撤销 ----------

    @Test
    void conflictingApprovedSlaughterApplicationIsRevokedAndReapplyOnlyAfterNewPeriod() throws Exception {
        approveSwineEnroFormula("REQ-TR2-F", "F-TR2");
        createBatch("B-TR2");
        // 动态时间（相对当前时钟）：末次饲喂 2 天前，ENRO 8 天 → 最早出栏日仍在未来（已批准未核销）。
        Instant now = Instant.now();
        Instant feedAt = now.minus(2, ChronoUnit.DAYS);
        Instant oldEarliest = startOfDayUtc(utcDate(feedAt).plusDays(8).plusDays(1));
        produce("F-TR2", "B-TR2", feedAt.toString());

        // 已批准出栏申请（计划出栏时刻在未来）。
        postJson("/api/slaughter-applications", Map.of(
                "requestId", "REQ-TR2-SL",
                "batchNo", "B-TR2",
                "applyAtUtc", oldEarliest.toString()));
        JsonNode scheduled = batch("B-TR2");
        assertThat(scheduled.path("status").asText()).isEqualTo("SLAUGHTERED");

        // 昨晚兽医加一针替米考星（12 天）：天数从头算，新最早出栏日远晚于已批准时刻。
        Instant administeredAt = now.minus(1, ChronoUnit.DAYS);
        LocalDate newUntil = utcDate(administeredAt).plusDays(12);
        Instant newEarliest = startOfDayUtc(newUntil.plusDays(1));
        assertThat(newEarliest).isAfter(oldEarliest);

        MvcResult result = postJson("/api/treatment-reports",
                treatmentBody("REQ-TR2-T", "B-TR2", VET_TILM,
                        administeredAt.toString(), List.of("keeper-a", "keeper-b")));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = readJson(result);
        assertThat(body.path("accepted").asBoolean()).isTrue();
        assertThat(body.path("withdrawalUntilDate").asText()).isEqualTo(newUntil.toString());
        assertThat(body.path("earliestSlaughterUtc").asText()).isEqualTo(newEarliest.toString());
        assertThat(body.path("revokedSlaughterRequestIds").toString()).contains("REQ-TR2-SL");

        // 撤销与重算同事务生效：批次恢复饲养中、出栏时刻清空、休药期为新口径。
        JsonNode after = batch("B-TR2");
        assertThat(after.path("status").asText()).isEqualTo("FEEDING");
        assertThat(after.path("slaughteredAtUtc").isNull()).isTrue();
        assertThat(after.path("withdrawalUntilDate").asText()).isEqualTo(newUntil.toString());
        assertThat(after.path("earliestSlaughterUtc").asText()).isEqualTo(newEarliest.toString());

        // 撤销记录保留可查（台账状态 + 审计记录）。
        String appStatus = jdbc.queryForObject(
                "SELECT status FROM slaughter_application WHERE request_id = 'REQ-TR2-SL'",
                String.class);
        assertThat(appStatus).isEqualTo("REVOKED");
        MvcResult revocations = mockMvc.perform(get("/api/batches/B-TR2/slaughter-revocations"))
                .andExpect(status().isOk()).andReturn();
        JsonNode revocationList = readJson(revocations);
        assertThat(revocationList.size()).isEqualTo(1);
        assertThat(revocationList.get(0).path("requestId").asText()).isEqualTo("REQ-TR2-SL");
        assertThat(revocationList.get(0).path("revokedSlaughterAtUtc").asText())
                .isEqualTo(oldEarliest.toString());
        assertThat(revocationList.get(0).path("newEarliestSlaughterUtc").asText())
                .isEqualTo(newEarliest.toString());

        // 恰满新休药天数当日 24:00 前重新申请：拒绝。
        MvcResult early = postJson("/api/slaughter-applications", Map.of(
                "requestId", "REQ-TR2-SL2-EARLY",
                "batchNo", "B-TR2",
                "applyAtUtc", newEarliest.minus(1, ChronoUnit.SECONDS).toString()));
        assertThat(early.getResponse().getStatus()).isEqualTo(422);
        assertThat(readJson(early).path("approved").asBoolean()).isFalse();

        // 恰满新休药天数当日 24:00（=新届满日次日 00:00 UTC）后：放行。
        MvcResult ok = postJson("/api/slaughter-applications", Map.of(
                "requestId", "REQ-TR2-SL2-OK",
                "batchNo", "B-TR2",
                "applyAtUtc", newEarliest.toString()));
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(readJson(ok).path("approved").asBoolean()).isTrue();
        assertThat(batch("B-TR2").path("status").asText()).isEqualTo("SLAUGHTERED");
    }

    @Test
    void approvedApplicationWithoutConflictIsKept() throws Exception {
        approveSwineEnroFormula("REQ-TR3-F", "F-TR3");
        createBatch("B-TR3");
        Instant now = Instant.now();
        Instant feedAt = now.minus(2, ChronoUnit.DAYS);
        Instant oldEarliest = startOfDayUtc(utcDate(feedAt).plusDays(8).plusDays(1));
        produce("F-TR3", "B-TR3", feedAt.toString());

        // 已批准申请的计划出栏时刻足够晚（旧最早出栏日 + 30 天）。
        Instant scheduledAt = oldEarliest.plus(30, ChronoUnit.DAYS);
        postJson("/api/slaughter-applications", Map.of(
                "requestId", "REQ-TR3-SL",
                "batchNo", "B-TR3",
                "applyAtUtc", scheduledAt.toString()));

        // 头孢噻呋仅 4 天：新最早出栏日仍早于已批准时刻，不冲突、不撤销。
        MvcResult result = postJson("/api/treatment-reports",
                treatmentBody("REQ-TR3-T", "B-TR3", VET_CEFT,
                        now.minus(1, ChronoUnit.DAYS).toString(), List.of("keeper-a", "keeper-b")));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = readJson(result);
        assertThat(body.path("accepted").asBoolean()).isTrue();
        assertThat(body.path("revokedSlaughterRequestIds").isEmpty()).isTrue();

        JsonNode after = batch("B-TR3");
        assertThat(after.path("status").asText()).isEqualTo("SLAUGHTERED");
        assertThat(after.path("slaughteredAtUtc").asText()).isEqualTo(scheduledAt.toString());

        String appStatus = jdbc.queryForObject(
                "SELECT status FROM slaughter_application WHERE request_id = 'REQ-TR3-SL'",
                String.class);
        assertThat(appStatus).isEqualTo("APPROVED");
        Integer revocationCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM slaughter_revocation WHERE batch_no = 'B-TR3'", Integer.class);
        assertThat(revocationCount).isZero();
    }

    // ---------- 失败分支：单人直报不生效 / 目录外药品拒绝 ----------

    @Test
    void singleOrDuplicatedConfirmerReportIsRejectedAndKeepsOriginalSlaughterDate() throws Exception {
        approveSwineEnroFormula("REQ-TR4-F", "F-TR4");
        createBatch("B-TR4");
        produce("F-TR4", "B-TR4", "2026-09-01T10:00:00Z"); // 届满 09-09

        // 单人直报：不生效。
        MvcResult single = postJson("/api/treatment-reports",
                treatmentBody("REQ-TR4-T1", "B-TR4", VET_TILM,
                        "2026-09-03T20:00:00Z", List.of("keeper-a")));
        assertThat(single.getResponse().getStatus()).isEqualTo(422);
        JsonNode singleBody = readJson(single);
        assertThat(singleBody.path("accepted").asBoolean()).isFalse();
        assertThat(singleBody.path("rejectReason").asText()).contains("两名");

        // 两人但为同一人：同样不生效。
        MvcResult duplicated = postJson("/api/treatment-reports",
                treatmentBody("REQ-TR4-T2", "B-TR4", VET_TILM,
                        "2026-09-03T20:00:00Z", List.of("keeper-a", "keeper-a")));
        assertThat(duplicated.getResponse().getStatus()).isEqualTo(422);
        assertThat(readJson(duplicated).path("accepted").asBoolean()).isFalse();

        // 原出栏日不变，且未落任何直报记录。
        JsonNode status = batch("B-TR4");
        assertThat(status.path("withdrawalUntilDate").asText()).isEqualTo("2026-09-09");
        assertThat(status.path("earliestSlaughterUtc").asText()).isEqualTo("2026-09-10T00:00:00Z");
        Integer reports = jdbc.queryForObject(
                "SELECT COUNT(*) FROM treatment_report WHERE batch_no = 'B-TR4'", Integer.class);
        assertThat(reports).isZero();
    }

    @Test
    void unknownDrugNameIsRejectedAndKeepsOriginalSlaughterDate() throws Exception {
        approveSwineEnroFormula("REQ-TR5-F", "F-TR5");
        createBatch("B-TR5");
        produce("F-TR5", "B-TR5", "2026-09-01T10:00:00Z"); // 届满 09-09

        MvcResult result = postJson("/api/treatment-reports",
                treatmentBody("REQ-TR5-T", "B-TR5", "合成目录外兽药X",
                        "2026-09-03T20:00:00Z", List.of("keeper-a", "keeper-b")));
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        JsonNode body = readJson(result);
        assertThat(body.path("accepted").asBoolean()).isFalse();
        assertThat(body.path("rejectReason").asText()).contains("目录外");

        // 原出栏日不变，未落直报记录；同键重放仍返回首次拒绝。
        JsonNode status = batch("B-TR5");
        assertThat(status.path("withdrawalUntilDate").asText()).isEqualTo("2026-09-09");
        Integer reports = jdbc.queryForObject(
                "SELECT COUNT(*) FROM treatment_report WHERE batch_no = 'B-TR5'", Integer.class);
        assertThat(reports).isZero();

        MvcResult replay = postJson("/api/treatment-reports",
                treatmentBody("REQ-TR5-T", "B-TR5", "合成目录外兽药X",
                        "2026-09-03T20:00:00Z", List.of("keeper-a", "keeper-b")));
        assertThat(replay.getResponse().getStatus()).isEqualTo(422);
        assertThat(readJson(replay).path("replayed").asBoolean()).isTrue();
    }

    // ---------- 幂等：同键同参重放 / 同键异参 409 ----------

    @Test
    void sameRequestSameParamsReplaysFirstResultAndDifferentParamsReturns409() throws Exception {
        approveSwineEnroFormula("REQ-TR6-F", "F-TR6");
        createBatch("B-TR6");
        produce("F-TR6", "B-TR6", "2026-09-01T10:00:00Z");

        Map<String, Object> body = treatmentBody("REQ-TR6-T", "B-TR6", VET_TILM,
                "2026-09-03T20:00:00Z", List.of("keeper-a", "keeper-b"));
        MvcResult first = postJson("/api/treatment-reports", body);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(readJson(first).path("replayed").asBoolean()).isFalse();

        // 同键同参：返回首次结果，不产生第二条直报。
        MvcResult replay = postJson("/api/treatment-reports", body);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        JsonNode replayBody = readJson(replay);
        assertThat(replayBody.path("replayed").asBoolean()).isTrue();
        assertThat(replayBody.path("withdrawalUntilDate").asText()).isEqualTo("2026-09-15");
        Integer reports = jdbc.queryForObject(
                "SELECT COUNT(*) FROM treatment_report WHERE batch_no = 'B-TR6'", Integer.class);
        assertThat(reports).isEqualTo(1);

        // 同键异参（指纹覆盖全部业务字段：换药品）：409 且不改变状态。
        MvcResult conflict = postJson("/api/treatment-reports",
                treatmentBody("REQ-TR6-T", "B-TR6", VET_CEFT,
                        "2026-09-03T20:00:00Z", List.of("keeper-a", "keeper-b")));
        assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
        assertThat(batch("B-TR6").path("withdrawalUntilDate").asText()).isEqualTo("2026-09-15");
        reports = jdbc.queryForObject(
                "SELECT COUNT(*) FROM treatment_report WHERE batch_no = 'B-TR6'", Integer.class);
        assertThat(reports).isEqualTo(1);
    }

    // ---------- 同批次同时刻唯一进行中直报（顺序 + 真实并发） ----------

    @Test
    void sameBatchSameMomentAllowsOnlyOneReportSequentially() throws Exception {
        approveSwineEnroFormula("REQ-TR7-F", "F-TR7");
        createBatch("B-TR7");
        produce("F-TR7", "B-TR7", "2026-09-01T10:00:00Z");

        MvcResult first = postJson("/api/treatment-reports",
                treatmentBody("REQ-TR7-T1", "B-TR7", VET_TILM,
                        "2026-09-03T20:00:00Z", List.of("keeper-a", "keeper-b")));
        assertThat(first.getResponse().getStatus()).isEqualTo(200);

        // 不同 requestId、同批次同给药时刻：唯一约束拒绝（409），状态不变。
        MvcResult second = postJson("/api/treatment-reports",
                treatmentBody("REQ-TR7-T2", "B-TR7", VET_CEFT,
                        "2026-09-03T20:00:00Z", List.of("keeper-c", "keeper-d")));
        assertThat(second.getResponse().getStatus()).isEqualTo(409);
        Integer reports = jdbc.queryForObject(
                "SELECT COUNT(*) FROM treatment_report WHERE batch_no = 'B-TR7'", Integer.class);
        assertThat(reports).isEqualTo(1);
        assertThat(batch("B-TR7").path("withdrawalUntilDate").asText()).isEqualTo("2026-09-15");
    }

    @Test
    void concurrentReportsOnSameBatchSameMomentAllowExactlyOne() throws Exception {
        approveSwineEnroFormula("REQ-TR8-F", "F-TR8");
        createBatch("B-TR8");
        produce("F-TR8", "B-TR8", "2026-09-01T10:00:00Z");

        Map<String, Object> bodyA = treatmentBody("REQ-TR8-A", "B-TR8", VET_TILM,
                "2026-09-03T20:00:00Z", List.of("keeper-a", "keeper-b"));
        Map<String, Object> bodyB = treatmentBody("REQ-TR8-B", "B-TR8", VET_CEFT,
                "2026-09-03T20:00:00Z", List.of("keeper-c", "keeper-d"));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<MvcResult> f1 = pool.submit(() -> {
            start.await();
            return postJson("/api/treatment-reports", bodyA);
        });
        Future<MvcResult> f2 = pool.submit(() -> {
            start.await();
            return postJson("/api/treatment-reports", bodyB);
        });
        start.countDown();
        MvcResult r1 = f1.get(30, TimeUnit.SECONDS);
        MvcResult r2 = f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 恰好一条受理（200），另一条因同批次同时刻唯一约束冲突（409）。
        int ok = (r1.getResponse().getStatus() == 200 ? 1 : 0)
                + (r2.getResponse().getStatus() == 200 ? 1 : 0);
        int conflict = (r1.getResponse().getStatus() == 409 ? 1 : 0)
                + (r2.getResponse().getStatus() == 409 ? 1 : 0);
        assertThat(ok).isEqualTo(1);
        assertThat(conflict).isEqualTo(1);

        Integer reports = jdbc.queryForObject(
                "SELECT COUNT(*) FROM treatment_report WHERE batch_no = 'B-TR8'", Integer.class);
        assertThat(reports).isEqualTo(1);
    }

    @Test
    void concurrentSameKeySameParamsProducesExactlyOneReportAndOneReplay() throws Exception {
        approveSwineEnroFormula("REQ-TR9-F", "F-TR9");
        createBatch("B-TR9");
        produce("F-TR9", "B-TR9", "2026-09-01T10:00:00Z");

        String payload = objectMapper.writeValueAsString(treatmentBody("REQ-TR9-T", "B-TR9", VET_TILM,
                "2026-09-03T20:00:00Z", List.of("keeper-a", "keeper-b")));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<MvcResult> f1 = pool.submit(() -> {
            start.await();
            return mockMvc.perform(post("/api/treatment-reports")
                            .contentType("application/json").content(payload))
                    .andReturn();
        });
        Future<MvcResult> f2 = pool.submit(() -> {
            start.await();
            return mockMvc.perform(post("/api/treatment-reports")
                            .contentType("application/json").content(payload))
                    .andReturn();
        });
        start.countDown();
        MvcResult r1 = f1.get(30, TimeUnit.SECONDS);
        MvcResult r2 = f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(r1.getResponse().getStatus()).isEqualTo(200);
        assertThat(r2.getResponse().getStatus()).isEqualTo(200);
        int replayed = (readJson(r1).path("replayed").asBoolean() ? 1 : 0)
                + (readJson(r2).path("replayed").asBoolean() ? 1 : 0);
        assertThat(replayed).isEqualTo(1);

        Integer reports = jdbc.queryForObject(
                "SELECT COUNT(*) FROM treatment_report WHERE batch_no = 'B-TR9'", Integer.class);
        assertThat(reports).isEqualTo(1);
    }

    // ---------- 出栏核销终态：迟到直报只记事件并告警 ----------

    @Test
    void lateTreatmentReportAfterFinalSlaughterOnlyRecordsEventAndAlerts() throws Exception {
        approveSwineEnroFormula("REQ-TR10-F", "F-TR10");
        createBatch("B-TR10");
        // 动态时间：末次饲喂 20 天前，ENRO 8 天 → 最早出栏日在过去，申请即核销（终态）。
        Instant now = Instant.now();
        Instant feedAt = now.minus(20, ChronoUnit.DAYS);
        Instant pastEarliest = startOfDayUtc(utcDate(feedAt).plusDays(8).plusDays(1));
        produce("F-TR10", "B-TR10", feedAt.toString());
        postJson("/api/slaughter-applications", Map.of(
                "requestId", "REQ-TR10-SL",
                "batchNo", "B-TR10",
                "applyAtUtc", pastEarliest.toString()));
        JsonNode before = batch("B-TR10");
        assertThat(before.path("status").asText()).isEqualTo("SLAUGHTERED");
        String untilBefore = before.path("withdrawalUntilDate").asText();

        // 核销后迟到的治疗直报：受理为迟到事件并告警，终态与休药字段不变。
        MvcResult result = postJson("/api/treatment-reports",
                treatmentBody("REQ-TR10-T", "B-TR10", VET_TILM,
                        now.minus(1, ChronoUnit.DAYS).toString(), List.of("keeper-a", "keeper-b")));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = readJson(result);
        assertThat(body.path("accepted").asBoolean()).isTrue();
        assertThat(body.path("late").asBoolean()).isTrue();
        assertThat(body.path("alerted").asBoolean()).isTrue();
        assertThat(body.path("revokedSlaughterRequestIds").isEmpty()).isTrue();

        JsonNode after = batch("B-TR10");
        assertThat(after.path("status").asText()).isEqualTo("SLAUGHTERED");
        assertThat(after.path("withdrawalUntilDate").asText()).isEqualTo(untilBefore);
        assertThat(after.path("slaughteredAtUtc").asText()).isEqualTo(pastEarliest.toString());

        Integer alerts = jdbc.queryForObject(
                "SELECT COUNT(*) FROM compliance_alert WHERE batch_no = 'B-TR10' "
                        + "AND alert_type = 'LATE_TREATMENT_REPORT'", Integer.class);
        Integer lateReports = jdbc.queryForObject(
                "SELECT COUNT(*) FROM treatment_report WHERE batch_no = 'B-TR10' AND late = TRUE",
                Integer.class);
        Integer revocations = jdbc.queryForObject(
                "SELECT COUNT(*) FROM slaughter_revocation WHERE batch_no = 'B-TR10'", Integer.class);
        assertThat(alerts).isEqualTo(1);
        assertThat(lateReports).isEqualTo(1);
        assertThat(revocations).isZero();
    }
}
