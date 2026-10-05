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
 * 饲料合规服务端到端测试：真实 H2（MODE=MySQL）建表、唯一约束、事务回滚与行锁并发。
 * 覆盖：上限恰等合法、禁忌拒绝、种类错配、休药期恰满边界、版本冻结不追溯、
 * 多配方并发投产休药期合并、requestId 同键重放/异参 409、出栏终态与迟到告警。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FeedComplianceIntegrationTest {

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

    private Map<String, Object> drug(String code, double ratio) {
        return Map.of("code", code, "ratio", ratio);
    }

    private Map<String, Object> ingredient(String code, double ratio) {
        return Map.of("code", code, "ratio", ratio);
    }

    private Map<String, Object> submitBody(String requestId, String formulaCode, String species,
                                          List<Map<String, Object>> drugs) {
        return Map.of(
                "requestId", requestId,
                "formulaCode", formulaCode,
                "name", "合成配方-" + formulaCode,
                "targetSpecies", species == null ? "" : species,
                "ingredients", List.of(ingredient("ING-CORN", 0.60), ingredient("ING-SOY", 0.30)),
                "drugs", drugs);
    }

    private Map<String, Object> submitBodyNoDrug(String requestId, String formulaCode) {
        return Map.of(
                "requestId", requestId,
                "formulaCode", formulaCode,
                "name", "合成无药配方-" + formulaCode,
                "ingredients", List.of(ingredient("ING-CORN", 0.70), ingredient("ING-WHEAT", 0.25)),
                "drugs", List.of());
    }

    private void approve(String formulaCode, int version) throws Exception {
        mockMvc.perform(post("/api/formulas/approve")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "formulaCode", formulaCode, "version", version))))
                .andExpect(status().isOk());
    }

    private void createBatch(String batchNo, String species) throws Exception {
        mockMvc.perform(post("/api/batches")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "batchNo", batchNo, "species", species))))
                .andExpect(status().isOk());
    }

    private void produce(String formulaCode, int version, String batchNo, String feedAt) throws Exception {
        mockMvc.perform(post("/api/productions")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "formulaCode", formulaCode,
                                "version", version,
                                "batchNo", batchNo,
                                "feedAtUtc", feedAt))))
                .andExpect(status().isOk());
    }

    private JsonNode batch(String batchNo) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchNo)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    // ---------- 主流程与失败分支 ----------

    @Test
    void drugRatioExactlyAtLimitIsLegal() throws Exception {
        // ENRO 上限 0.02：恰等于上限合法。
        Map<String, Object> body = submitBody("REQ-EXACT-1", "F-EXACT", "CHICKEN",
                List.of(drug("DRG-ENRO", 0.020000)));
        postJson("/api/formulas/submit", body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.rejectReason").doesNotExist());
    }

    @Test
    void drugRatioAboveLimitIsRejected() throws Exception {
        Map<String, Object> body = submitBody("REQ-OVER-1", "F-OVER", "CHICKEN",
                List.of(drug("DRG-ENRO", 0.020001)));
        postJson("/api/formulas/submit", body)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectReason").value(org.hamcrest.Matchers.containsString("超过最大添加比例")));
    }

    @Test
    void contraindicationPairIsRejected() throws Exception {
        // ENRO 与 TYLO 为禁忌对（两者均允许 SWINE，排除种类干扰）。
        Map<String, Object> body = submitBody("REQ-CONTRA-1", "F-CONTRA", "SWINE",
                List.of(drug("DRG-ENRO", 0.01), drug("DRG-TYLO", 0.005)));
        postJson("/api/formulas/submit", body)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.rejectReason").value(org.hamcrest.Matchers.containsString("配伍禁忌")));
    }

    @Test
    void medicatedFormulaWithoutSpeciesIsRejected() throws Exception {
        String json = objectMapper.writeValueAsString(Map.of(
                "requestId", "REQ-NOSP",
                "formulaCode", "F-NOSP",
                "name", "无种类含药方",
                "ingredients", List.of(ingredient("ING-CORN", 0.7)),
                "drugs", List.of(drug("DRG-AMOX", 0.01))));
        mockMvc.perform(post("/api/formulas/submit").contentType("application/json").content(json))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.rejectReason").value(org.hamcrest.Matchers.containsString("目标动物种类")));
    }

    @Test
    void speciesMismatchIsRejected() throws Exception {
        // TYLO 仅允许 SWINE，绑定 CHICKEN 属错配。
        Map<String, Object> body = submitBody("REQ-SP-1", "F-SP", "CHICKEN",
                List.of(drug("DRG-TYLO", 0.005)));
        postJson("/api/formulas/submit", body)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.rejectReason").value(org.hamcrest.Matchers.containsString("不适用")));
    }

    @Test
    void rejectedVersionCannotBeApprovedAndNewVersionValidatedIndependently() throws Exception {
        // v1 超上限拒绝。
        postJson("/api/formulas/submit",
                submitBody("REQ-RJ-1", "F-VER", "CHICKEN", List.of(drug("DRG-ENRO", 0.09))))
                .andExpect(status().isUnprocessableEntity());

        // 批准拒绝版本 → 422。
        mockMvc.perform(post("/api/formulas/approve").contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("formulaCode", "F-VER", "version", 1))))
                .andExpect(status().isUnprocessableEntity());

        // v2 修改为恰等上限，独立校验通过。
        postJson("/api/formulas/submit",
                submitBody("REQ-RJ-2", "F-VER", "CHICKEN", List.of(drug("DRG-ENRO", 0.02))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        approve("F-VER", 2);

        mockMvc.perform(get("/api/formulas/F-VER/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versions[0].version").value(1))
                .andExpect(jsonPath("$.versions[0].status").value("REJECTED"))
                .andExpect(jsonPath("$.versions[1].version").value(2))
                .andExpect(jsonPath("$.versions[1].status").value("APPROVED"));
    }

    // ---------- 休药期恰满边界 ----------

    @Test
    void withdrawalExactBoundarySlaughterRule() throws Exception {
        postJson("/api/formulas/submit",
                submitBody("REQ-WD-1", "F-WD", "CHICKEN", List.of(drug("DRG-ENRO", 0.02))))
                .andExpect(status().isOk());
        approve("F-WD", 1);
        createBatch("B-WD", "CHICKEN");
        // 末次饲喂 2026-09-01（UTC），ENRO 休药 8 天 → 届满日 09-09，最早出栏 09-10T00:00Z。
        produce("F-WD", 1, "B-WD", "2026-09-01T10:00:00Z");

        JsonNode status = batch("B-WD");
        assertThat(status.path("withdrawalUntilDate").asText()).isEqualTo("2026-09-09");
        assertThat(status.path("earliestSlaughterUtc").asText()).isEqualTo("2026-09-10T00:00:00Z");
        assertThat(status.path("governingWithdrawalDays").asInt()).isEqualTo(8);

        // 届满日当日 23:59:59Z 申请：拒绝并提示最早合法出栏时刻。
        MvcResult early = postJson("/api/slaughter-applications", Map.of(
                "requestId", "REQ-SL-EARLY",
                "batchNo", "B-WD",
                "applyAtUtc", "2026-09-09T23:59:59Z")).andReturn();
        assertThat(early.getResponse().getStatus()).isEqualTo(422);
        JsonNode earlyBody = objectMapper.readTree(early.getResponse().getContentAsString());
        assertThat(earlyBody.path("approved").asBoolean()).isFalse();
        assertThat(earlyBody.path("earliestSlaughterUtc").asText()).isEqualTo("2026-09-10T00:00:00Z");
        assertThat(batch("B-WD").path("status").asText()).isEqualTo("FEEDING");

        // 恰满当日 24:00（=09-10T00:00Z）申请：放行。
        postJson("/api/slaughter-applications", Map.of(
                "requestId", "REQ-SL-OK",
                "batchNo", "B-WD",
                "applyAtUtc", "2026-09-10T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approved").value(true))
                .andExpect(jsonPath("$.status").value("SLAUGHTERED"));
    }

    // ---------- 版本冻结不追溯 ----------

    @Test
    void approvedVersionFrozenAndLaterVersionDoesNotRetroactivelyReduceObligation() throws Exception {
        // v1：TYLO 14 天（SWINE），恰等上限合法。
        postJson("/api/formulas/submit",
                submitBody("REQ-FRZ-1", "F-FRZ", "SWINE", List.of(drug("DRG-TYLO", 0.01))))
                .andExpect(status().isOk());
        approve("F-FRZ", 1);
        createBatch("B-FRZ-1", "SWINE");
        produce("F-FRZ", 1, "B-FRZ-1", "2026-09-01T10:00:00Z");
        assertThat(batch("B-FRZ-1").path("withdrawalUntilDate").asText()).isEqualTo("2026-09-15");

        // v2：换成休药更短的 AMOX 7 天；独立校验、独立批准。
        postJson("/api/formulas/submit",
                submitBody("REQ-FRZ-2", "F-FRZ", "SWINE", List.of(drug("DRG-AMOX", 0.02))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        approve("F-FRZ", 2);

        // v1 已投产版本的义务不追溯减轻：B-FRZ-1 仍为 14 天口径。
        JsonNode unchanged = batch("B-FRZ-1");
        assertThat(unchanged.path("governingWithdrawalDays").asInt()).isEqualTo(14);
        assertThat(unchanged.path("withdrawalUntilDate").asText()).isEqualTo("2026-09-15");

        // v2 在另一批次独立产生 7 天义务。
        createBatch("B-FRZ-2", "SWINE");
        produce("F-FRZ", 2, "B-FRZ-2", "2026-09-01T10:00:00Z");
        assertThat(batch("B-FRZ-2").path("withdrawalUntilDate").asText()).isEqualTo("2026-09-08");
    }

    // ---------- 多配方并发投产：休药期合并（真实并发 + 行锁） ----------

    @Test
    void concurrentProductionOfTwoMedicatedFormulasMergesWithdrawalUniquely() throws Exception {
        postJson("/api/formulas/submit",
                submitBody("REQ-CC-1", "F-ENRO", "SWINE", List.of(drug("DRG-ENRO", 0.02))))
                .andExpect(status().isOk());
        approve("F-ENRO", 1);
        postJson("/api/formulas/submit",
                submitBody("REQ-CC-2", "F-TYLO", "SWINE", List.of(drug("DRG-TYLO", 0.01))))
                .andExpect(status().isOk());
        approve("F-TYLO", 1);
        createBatch("B-MERGE", "SWINE");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<?> f1 = pool.submit(() -> {
            start.await();
            produce("F-ENRO", 1, "B-MERGE", "2026-09-01T08:00:00Z");
            return null;
        });
        Future<?> f2 = pool.submit(() -> {
            start.await();
            produce("F-TYLO", 1, "B-MERGE", "2026-09-03T20:00:00Z");
            return null;
        });
        start.countDown();
        f1.get(30, TimeUnit.SECONDS);
        f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        JsonNode merged = batch("B-MERGE");
        // 合并口径唯一：最晚末次饲喂 09-03T20:00Z，最大休药天数 14，届满日 09-17，最早出栏 09-18T00:00Z。
        assertThat(merged.path("lastMedicatedFeedAtUtc").asText()).isEqualTo("2026-09-03T20:00:00Z");
        assertThat(merged.path("governingWithdrawalDays").asInt()).isEqualTo(14);
        assertThat(merged.path("withdrawalUntilDate").asText()).isEqualTo("2026-09-17");
        assertThat(merged.path("earliestSlaughterUtc").asText()).isEqualTo("2026-09-18T00:00:00Z");

        Integer productions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM production_record pr JOIN herd_batch b ON b.id = pr.batch_id "
                        + "WHERE b.batch_no = 'B-MERGE'", Integer.class);
        Integer snapshots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM production_drug_snapshot s "
                        + "JOIN production_record p ON p.id = s.production_id "
                        + "JOIN herd_batch b ON b.id = p.batch_id WHERE b.batch_no = 'B-MERGE'", Integer.class);
        assertThat(productions).isEqualTo(2);
        assertThat(snapshots).isEqualTo(2);
    }

    // ---------- requestId 幂等：同键重放 / 异参 409 ----------

    @Test
    void sameRequestSameParamsReplaysFirstResultWithoutCreatingExtraVersion() throws Exception {
        Map<String, Object> body = submitBody("REQ-IDEM-1", "F-IDEM", "CHICKEN",
                List.of(drug("DRG-AMOX", 0.02)));
        JsonNode first = postJsonBody("/api/formulas/submit", body);
        assertThat(first.path("version").asInt()).isEqualTo(1);
        assertThat(first.path("replayed").asBoolean()).isFalse();

        JsonNode replay = postJsonBody("/api/formulas/submit", body);
        assertThat(replay.path("version").asInt()).isEqualTo(1);
        assertThat(replay.path("replayed").asBoolean()).isTrue();

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM feed_formula WHERE formula_code = 'F-IDEM'", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void sameRequestDifferentParamsReturns409AndChangesNothing() throws Exception {
        Map<String, Object> first = submitBody("REQ-CONF-1", "F-CONF", "CHICKEN",
                List.of(drug("DRG-AMOX", 0.02)));
        postJson("/api/formulas/submit", first).andExpect(status().isOk());

        // 同键异参：药物比例不同。
        Map<String, Object> changed = submitBody("REQ-CONF-1", "F-CONF", "CHICKEN",
                List.of(drug("DRG-AMOX", 0.03)));
        postJson("/api/formulas/submit", changed)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("不一致")));

        // 状态不被改变：仍只有首个版本。
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM feed_formula WHERE formula_code = 'F-CONF'", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void concurrentSameKeySameParamsProducesExactlyOneVersionAndOneReplay() throws Exception {
        Map<String, Object> body = submitBody("REQ-CONC-IDEM", "F-CIDEM", "CHICKEN",
                List.of(drug("DRG-AMOX", 0.02)));
        String payload = objectMapper.writeValueAsString(body);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        java.util.List<Future<MvcResult>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < 2; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return mockMvc.perform(post("/api/formulas/submit")
                                .contentType("application/json").content(payload))
                        .andReturn();
            }));
        }
        start.countDown();
        int okCount = 0;
        int replayCount = 0;
        for (Future<MvcResult> f : futures) {
            MvcResult r = f.get(30, TimeUnit.SECONDS);
            assertThat(r.getResponse().getStatus()).isEqualTo(200);
            JsonNode json = objectMapper.readTree(r.getResponse().getContentAsString());
            assertThat(json.path("version").asInt()).isEqualTo(1);
            okCount++;
            if (json.path("replayed").asBoolean()) {
                replayCount++;
            }
        }
        pool.shutdown();
        assertThat(okCount).isEqualTo(2);
        assertThat(replayCount).isEqualTo(1);

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM feed_formula WHERE formula_code = 'F-CIDEM'", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void rejectedSlaughterIsIdempotentAndThenApprovedWithNewRequest() throws Exception {
        postJson("/api/formulas/submit",
                submitBody("REQ-SL2-1", "F-SL2", "CHICKEN", List.of(drug("DRG-AMOX", 0.02))))
                .andExpect(status().isOk());
        approve("F-SL2", 1);
        createBatch("B-SL2", "CHICKEN");
        produce("F-SL2", 1, "B-SL2", "2026-09-01T10:00:00Z"); // 届满 09-08

        Map<String, Object> early = Map.of(
                "requestId", "REQ-SL2-EARLY",
                "batchNo", "B-SL2",
                "applyAtUtc", "2026-09-05T00:00:00Z");
        MvcResult r1 = postJson("/api/slaughter-applications", early).andReturn();
        MvcResult r2 = postJson("/api/slaughter-applications", early).andReturn();
        assertThat(r1.getResponse().getStatus()).isEqualTo(422);
        // 同键同参重放仍返回首次拒绝。
        assertThat(r2.getResponse().getStatus()).isEqualTo(422);
        assertThat(batch("B-SL2").path("status").asText()).isEqualTo("FEEDING");

        // 期满后用新 requestId 申请成功。
        postJson("/api/slaughter-applications", Map.of(
                "requestId", "REQ-SL2-OK",
                "batchNo", "B-SL2",
                "applyAtUtc", "2026-09-09T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approved").value(true));
    }

    // ---------- 出栏终态：重复出栏幂等成功 + 迟到告警 ----------

    @Test
    void slaughterIsTerminalReplaySucceedsAndLateFeedingAlertsWithoutStateChange() throws Exception {
        postJson("/api/formulas/submit",
                submitBody("REQ-LATE-1", "F-LATE", "CHICKEN", List.of(drug("DRG-ENRO", 0.02))))
                .andExpect(status().isOk());
        approve("F-LATE", 1);
        createBatch("B-LATE", "CHICKEN");
        produce("F-LATE", 1, "B-LATE", "2026-09-01T10:00:00Z");

        Map<String, Object> slaughter = Map.of(
                "requestId", "REQ-LATE-SL",
                "batchNo", "B-LATE",
                "applyAtUtc", "2026-09-10T00:00:00Z");
        postJson("/api/slaughter-applications", slaughter).andExpect(status().isOk());
        // 同键重复出栏：幂等成功。
        MvcResult replay = postJson("/api/slaughter-applications", slaughter).andReturn();
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        JsonNode replayBody = objectMapper.readTree(replay.getResponse().getContentAsString());
        assertThat(replayBody.path("approved").asBoolean()).isTrue();
        assertThat(replayBody.path("replayed").asBoolean()).isTrue();
        assertThat(replayBody.path("status").asText()).isEqualTo("SLAUGHTERED");

        // 出栏后迟到的含药饲喂：只记迟到事件并告警，终态与休药字段不变。
        JsonNode before = batch("B-LATE");
        String untilBefore = before.path("withdrawalUntilDate").asText();
        String feedJson = objectMapper.writeValueAsString(Map.of(
                "formulaCode", "F-LATE",
                "version", 1,
                "batchNo", "B-LATE",
                "feedAtUtc", "2026-09-11T08:00:00Z"));
        mockMvc.perform(post("/api/feedings").contentType("application/json").content(feedJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.late").value(true))
                .andExpect(jsonPath("$.alerted").value(true));

        JsonNode after = batch("B-LATE");
        assertThat(after.path("status").asText()).isEqualTo("SLAUGHTERED");
        assertThat(after.path("withdrawalUntilDate").asText()).isEqualTo(untilBefore);

        Integer alerts = jdbc.queryForObject(
                "SELECT COUNT(*) FROM compliance_alert WHERE batch_no = 'B-LATE' "
                        + "AND alert_type = 'LATE_MEDICATED_FEEDING'", Integer.class);
        Integer lateFeedings = jdbc.queryForObject(
                "SELECT COUNT(*) FROM feeding_record f JOIN herd_batch b ON b.id = f.batch_id "
                        + "WHERE b.batch_no = 'B-LATE' AND f.late = TRUE", Integer.class);
        assertThat(alerts).isEqualTo(1);
        assertThat(lateFeedings).isEqualTo(1);
    }

    // ---------- 投产前置条件与无药配方 ----------

    @Test
    void productionRequiresApprovalAndMatchingSpecies() throws Exception {
        postJson("/api/formulas/submit",
                submitBody("REQ-PRE-1", "F-PRE", "CHICKEN", List.of(drug("DRG-ENRO", 0.02))))
                .andExpect(status().isOk());
        // 未批准即投产 → 422。
        createBatch("B-PRE-1", "CHICKEN");
        mockMvc.perform(post("/api/productions").contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "formulaCode", "F-PRE", "version", 1,
                                "batchNo", "B-PRE-1", "feedAtUtc", "2026-09-01T10:00:00Z"))))
                .andExpect(status().isUnprocessableEntity());

        approve("F-PRE", 1);
        // 种类错配投产 → 422。
        createBatch("B-PRE-2", "SWINE");
        mockMvc.perform(post("/api/productions").contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "formulaCode", "F-PRE", "version", 1,
                                "batchNo", "B-PRE-2", "feedAtUtc", "2026-09-01T10:00:00Z"))))
                .andExpect(status().isUnprocessableEntity());

        // 重复投产同一配方版本 → 409。
        produce("F-PRE", 1, "B-PRE-1", "2026-09-01T10:00:00Z");
        mockMvc.perform(post("/api/productions").contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "formulaCode", "F-PRE", "version", 1,
                                "batchNo", "B-PRE-1", "feedAtUtc", "2026-09-02T10:00:00Z"))))
                .andExpect(status().isConflict());
    }

    @Test
    void drugFreeFormulaHasNoWithdrawalAndCanSlaughterAnytime() throws Exception {
        postJson("/api/formulas/submit", submitBodyNoDrug("REQ-PLAIN-1", "F-PLAIN"))
                .andExpect(status().isOk());
        approve("F-PLAIN", 1);
        createBatch("B-PLAIN", "CATTLE");
        produce("F-PLAIN", 1, "B-PLAIN", "2026-09-01T10:00:00Z");

        JsonNode status = batch("B-PLAIN");
        assertThat(status.path("withdrawalUntilDate").isMissingNode()
                || status.path("withdrawalUntilDate").isNull()).isTrue();
        assertThat(status.path("governingWithdrawalDays").isNull()).isTrue();

        postJson("/api/slaughter-applications", Map.of(
                "requestId", "REQ-PLAIN-SL",
                "batchNo", "B-PLAIN",
                "applyAtUtc", "2026-09-01T10:05:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approved").value(true));
    }

    @Test
    void subsequentFeedingExtendsWithdrawalByMergeRule() throws Exception {
        postJson("/api/formulas/submit",
                submitBody("REQ-EX-1", "F-EX", "CHICKEN", List.of(drug("DRG-ENRO", 0.02))))
                .andExpect(status().isOk());
        approve("F-EX", 1);
        createBatch("B-EX", "CHICKEN");
        produce("F-EX", 1, "B-EX", "2026-09-01T10:00:00Z"); // 届满 09-09

        // 09-05 又一次含药饲喂：最晚饲喂日变为 09-05 → 届满 09-13。
        mockMvc.perform(post("/api/feedings").contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "formulaCode", "F-EX", "version", 1,
                                "batchNo", "B-EX", "feedAtUtc", "2026-09-05T09:00:00Z"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.late").value(false));

        JsonNode status = batch("B-EX");
        assertThat(status.path("lastMedicatedFeedAtUtc").asText()).isEqualTo("2026-09-05T09:00:00Z");
        assertThat(status.path("withdrawalUntilDate").asText()).isEqualTo("2026-09-13");
        assertThat(status.path("earliestSlaughterUtc").asText()).isEqualTo("2026-09-14T00:00:00Z");
    }
}
