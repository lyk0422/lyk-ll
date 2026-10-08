package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 补投规则：一对一引用被召回投放、用量相等、双人签认、库存再次按真实用量扣减、
 * 观察起点重计时、多条被召回投放全部补齐才可开始有效观察。
 */
class ReplacementTest extends BaseIntegrationTest {

    /**
     * 建立一条以批号 1 投放生效、随后批号 1 被召回的待补投处置链，返回 [caseId, placementId]。
     */
    private long[] pendingReplacementCase() throws Exception {
        long caseId = reportCase("A", "rpt-1");
        confirmCase(caseId, "cfm-1");
        long placementId = registerPlacementWithBatch(caseId, "pl-1", 1L, 1L, 10, "张三");
        confirmPlacement(placementId, "pc-1", "李四", 200);
        recallBatch(1L, "rc-1", 1).andExpect(status().isOk());
        return new long[]{caseId, placementId};
    }

    @Test
    void replacementDeductsStockAgainAndRestartsObservation() throws Exception {
        long[] ids = pendingReplacementCase();
        long caseId = ids[0];
        long placementId = ids[1];
        // 首次投放已扣减：药剂 90.00、批号 1 余 50.00
        assertEquals(0, pesticideQuantity(1).compareTo(new BigDecimal("90.00")));

        clock.setInstant(T0.plus(2, ChronoUnit.DAYS));
        MvcResult result = replacePlacement(placementId, "rp-1", 2L, 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EFFECTIVE"))
                .andReturn();
        long newPlacementId = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("placementId").asLong();

        // 旧药剂不退回、原记录不覆写；库存按真实用量再次扣减
        assertEquals(0, pesticideQuantity(1).compareTo(new BigDecimal("80.00")));
        assertEquals(0, batchQuantity(1).compareTo(new BigDecimal("50.00")));
        assertEquals(0, batchQuantity(2).compareTo(new BigDecimal("30.00")));
        assertEquals("REPLACED", jdbc.queryForObject(
                "SELECT recall_status FROM treatment_placement WHERE id = " + placementId,
                String.class));
        assertEquals(2, jdbc.queryForObject(
                "SELECT version FROM treatment_placement WHERE id = " + placementId, Integer.class));
        assertEquals("TREATED", caseStatus(caseId));

        // 对应关系可查询：原投放 -> 补投，补投 -> 原投放
        mockMvc.perform(get("/api/placements/" + placementId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recallStatus").value("REPLACED"))
                .andExpect(jsonPath("$.replacedByPlacementId").value(newPlacementId));
        mockMvc.perform(get("/api/placements/" + newPlacementId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replacesPlacementId").value(placementId))
                .andExpect(jsonPath("$.batchId").value(2))
                .andExpect(jsonPath("$.dosage").value(10.00))
                .andExpect(jsonPath("$.firstRegistrar").value("王五"))
                .andExpect(jsonPath("$.secondRegistrar").value("赵六"));

        // 观察起点为最后一次有效补投时刻（T0+2 天）
        mockMvc.perform(get("/api/cases/" + caseId + "/observation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.observationStart").exists())
                .andExpect(jsonPath("$.pendingReplacementPlacementIds").isEmpty());

        // 自补投时刻恰满 14 天才可申请解除
        clock.setInstant(T0.plus(2, ChronoUnit.DAYS).plus(14, ChronoUnit.DAYS).minusSeconds(1));
        postJson("/api/cases/" + caseId + "/release-application",
                Map.of("requestId", "ra-1", "operator", "库房员"))
                .andExpect(status().isBadRequest());
        clock.setInstant(T0.plus(2, ChronoUnit.DAYS).plus(14, ChronoUnit.DAYS));
        postJson("/api/cases/" + caseId + "/release-application",
                Map.of("requestId", "ra-2", "operator", "库房员"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASE_APPLIED"));
    }

    @Test
    void replacementWithRecalledBatchRejected() throws Exception {
        long[] ids = pendingReplacementCase();
        recallBatch(2L, "rc-2", 1).andExpect(status().isOk());

        replacePlacement(ids[1], "rp-1", 2L, 1).andExpect(status().isConflict());
        assertEquals(0, pesticideQuantity(1).compareTo(new BigDecimal("90.00")));
        assertEquals(0, batchQuantity(2).compareTo(new BigDecimal("40.00")));
        assertEquals("PENDING_REPLACEMENT", caseStatus(ids[0]));
    }

    @Test
    void replacementWithExpiredBatchRejected() throws Exception {
        long[] ids = pendingReplacementCase();
        jdbc.update("INSERT INTO pesticide_batch (id, pesticide_id, batch_no, quantity, expiry_date,"
                + " status, version, created_at) VALUES (9, 1, 'AL-EXP-01', 50.00,"
                + " DATE '2020-06-01', 'ACTIVE', 1, TIMESTAMP '2026-01-01 00:00:00')");

        replacePlacement(ids[1], "rp-1", 9L, 1).andExpect(status().isBadRequest());
        assertEquals(0, batchQuantity(9).compareTo(new BigDecimal("50.00")));
        assertEquals("PENDING_REPLACEMENT", caseStatus(ids[0]));
    }

    @Test
    void replacementWithDifferentPesticideBatchRejected() throws Exception {
        long[] ids = pendingReplacementCase();

        // 批号 3 属药剂 2，与被召回投放的药剂不一致
        replacePlacement(ids[1], "rp-1", 3L, 1).andExpect(status().isBadRequest());
        assertEquals("PENDING_REPLACEMENT", caseStatus(ids[0]));
    }

    @Test
    void replacementWithInsufficientStockRollsBack() throws Exception {
        long[] ids = pendingReplacementCase();
        jdbc.update("UPDATE pesticide_batch SET quantity = 5.00 WHERE id = 2");

        replacePlacement(ids[1], "rp-1", 2L, 1).andExpect(status().isConflict());

        // 整体回滚：库存、召回状态、版本、处置链状态均不变
        assertEquals(0, pesticideQuantity(1).compareTo(new BigDecimal("90.00")));
        assertEquals(0, batchQuantity(2).compareTo(new BigDecimal("5.00")));
        assertEquals("RECALLED", jdbc.queryForObject(
                "SELECT recall_status FROM treatment_placement WHERE id = " + ids[1], String.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT version FROM treatment_placement WHERE id = " + ids[1], Integer.class));
        assertEquals("PENDING_REPLACEMENT", caseStatus(ids[0]));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM treatment_placement", Long.class));
    }

    @Test
    void replacementIsOneToOne() throws Exception {
        long[] ids = pendingReplacementCase();
        replacePlacement(ids[1], "rp-1", 2L, 1).andExpect(status().isOk());

        // 同一被召回投放不得再次补投
        replacePlacement(ids[1], "rp-2", 2L, 2).andExpect(status().isConflict());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM treatment_placement", Long.class));
        assertEquals(0, batchQuantity(2).compareTo(new BigDecimal("30.00")));
    }

    @Test
    void replacementRequiresTwoDistinctRegistrars() throws Exception {
        long[] ids = pendingReplacementCase();

        postJson("/api/placements/" + ids[1] + "/replacement",
                Map.of("requestId", "rp-1", "batchId", 2, "firstRegistrar", "王五",
                        "secondRegistrar", "王五", "expectedVersion", 1))
                .andExpect(status().isBadRequest());
        assertEquals(0, pesticideQuantity(1).compareTo(new BigDecimal("90.00")));
        assertEquals("PENDING_REPLACEMENT", caseStatus(ids[0]));
    }

    @Test
    void replacementStaleVersionRejected() throws Exception {
        long[] ids = pendingReplacementCase();

        replacePlacement(ids[1], "rp-1", 2L, 5).andExpect(status().isConflict());
        assertEquals("PENDING_REPLACEMENT", caseStatus(ids[0]));
        assertEquals(0, batchQuantity(2).compareTo(new BigDecimal("40.00")));
    }

    @Test
    void replacementIdempotentReplayAndConflict() throws Exception {
        long[] ids = pendingReplacementCase();
        MvcResult first = replacePlacement(ids[1], "rp-1", 2L, 1)
                .andExpect(status().isOk())
                .andReturn();
        long newPlacementId = objectMapper.readTree(first.getResponse().getContentAsString())
                .get("placementId").asLong();

        // 同键同参：重放首次响应，不重复扣库存
        replacePlacement(ids[1], "rp-1", 2L, 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.placementId").value(newPlacementId));
        assertEquals(0, batchQuantity(2).compareTo(new BigDecimal("30.00")));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM treatment_placement", Long.class));

        // 同键异参：409
        postJson("/api/placements/" + ids[1] + "/replacement",
                Map.of("requestId", "rp-1", "batchId", 2, "firstRegistrar", "王五",
                        "secondRegistrar", "钱七", "expectedVersion", 1))
                .andExpect(status().isConflict());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM treatment_placement", Long.class));
    }

    @Test
    void allRecalledPlacementsMustBeReplacedBeforeObservation() throws Exception {
        long caseId = reportCase("A", "rpt-1");
        confirmCase(caseId, "cfm-1");
        long p1 = registerPlacementWithBatch(caseId, "pl-1", 1L, 1L, 10, "张三");
        confirmPlacement(p1, "pc-1", "李四", 200);
        long p2 = registerPlacementWithBatch(caseId, "pl-2", 1L, 1L, 10, "张三");
        confirmPlacement(p2, "pc-2", "李四", 200);

        recallBatch(1L, "rc-1", 1).andExpect(status().isOk());
        assertEquals("PENDING_REPLACEMENT", caseStatus(caseId));

        // 第一条补投完成：仍有未补齐投放，观察未有效开始
        replacePlacement(p1, "rp-1", 2L, 1).andExpect(status().isOk());
        assertEquals("PENDING_REPLACEMENT", caseStatus(caseId));
        mockMvc.perform(get("/api/cases/" + caseId + "/observation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.observationStart").doesNotExist())
                .andExpect(jsonPath("$.pendingReplacementPlacementIds[0]").value(p2));
        postJson("/api/cases/" + caseId + "/release-application",
                Map.of("requestId", "ra-1", "operator", "库房员"))
                .andExpect(status().isConflict());

        // 全部补齐后回到监测期
        replacePlacement(p2, "rp-2", 2L, 1).andExpect(status().isOk());
        assertEquals("TREATED", caseStatus(caseId));
        mockMvc.perform(get("/api/cases/" + caseId + "/observation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.observationStart").exists());
    }

    @Test
    void traceAfterReplacementMovesObservationStart() throws Exception {
        long[] ids = pendingReplacementCase();
        long caseId = ids[0];
        replacePlacement(ids[1], "rp-1", 2L, 1).andExpect(status().isOk());

        // 补投后第 5 天出现新增虫迹：观察起点推迟到虫迹时刻
        clock.setInstant(T0.plus(5, ChronoUnit.DAYS));
        postJson("/api/cases/" + caseId + "/traces",
                Map.of("requestId", "tr-1", "description", "墙角新虫迹"))
                .andExpect(status().isOk());

        clock.setInstant(T0.plus(14, ChronoUnit.DAYS));
        postJson("/api/cases/" + caseId + "/release-application",
                Map.of("requestId", "ra-1", "operator", "库房员"))
                .andExpect(status().isBadRequest());

        clock.setInstant(T0.plus(19, ChronoUnit.DAYS));
        postJson("/api/cases/" + caseId + "/release-application",
                Map.of("requestId", "ra-2", "operator", "库房员"))
                .andExpect(status().isOk());
    }

    @Test
    void legacyPlacementWithoutBatchKeepsOriginalSemantics() throws Exception {
        long caseId = reportCase("A", "rpt-1");
        confirmCase(caseId, "cfm-1");
        long placementId = registerPlacement(caseId, "pl-1", "张三");
        confirmPlacement(placementId, "pc-1", "李四");

        // 未启用批号管理的历史投放：批号召回不联动，14 天解除规则不变
        recallBatch(1L, "rc-1", 1).andExpect(status().isOk());
        assertEquals("TREATED", caseStatus(caseId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM recall_impact", Long.class));

        clock.setInstant(T0.plus(14, ChronoUnit.DAYS));
        applyRelease(caseId, "ra-1");
        confirmRelease(caseId, "rcf-1", "DISINFECTION");
        confirmRelease(caseId, "rcf-2", "STORAGE");
        assertEquals("RELEASED", caseStatus(caseId));
    }
}
