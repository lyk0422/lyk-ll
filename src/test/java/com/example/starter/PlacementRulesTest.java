package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 治理投放规则：双人登记生效、单人登记不生效、过期药剂拒绝、
 * 投放与库存扣减同事务、库存低于安全线自动生成采购建议并勾稽投放记录。
 */
class PlacementRulesTest extends BaseIntegrationTest {

    @Test
    void singleRegistrarRegistrationDoesNotTakeEffect() throws Exception {
        long caseId = reportCase("A", "rpt-1");
        confirmCase(caseId, "cfm-1");

        long placementId = registerPlacement(caseId, "pl-1", "张三");

        // 单人登记不生效：投放待复核、库存未扣减、处置链未进入监测期
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM treatment_placement WHERE id = " + placementId, String.class));
        assertEquals(0, pesticideQuantity(1).compareTo(new BigDecimal("100.00")));
        assertEquals("CONFIRMED", caseStatus(caseId));
    }

    @Test
    void sameRegistrarConfirmRejected() throws Exception {
        long caseId = reportCase("A", "rpt-1");
        confirmCase(caseId, "cfm-1");
        long placementId = registerPlacement(caseId, "pl-1", "张三");

        // 同一登记人复核视为单人登记，不生效
        postJson("/api/placements/" + placementId + "/confirm",
                Map.of("requestId", "pc-1", "registrar", "张三", "pesticideId", 1,
                        "dosage", 10, "point", "3排-2架"))
                .andExpect(status().isBadRequest());

        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM treatment_placement WHERE id = " + placementId, String.class));
        assertEquals(0, pesticideQuantity(1).compareTo(new BigDecimal("100.00")));
    }

    @Test
    void mismatchedSecondRegistrationRejected() throws Exception {
        long caseId = reportCase("A", "rpt-1");
        confirmCase(caseId, "cfm-1");
        long placementId = registerPlacement(caseId, "pl-1", "张三");

        // 第二登记人用量与首次登记不一致
        postJson("/api/placements/" + placementId + "/confirm",
                Map.of("requestId", "pc-1", "registrar", "李四", "pesticideId", 1,
                        "dosage", 20, "point", "3排-2架"))
                .andExpect(status().isBadRequest());

        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM treatment_placement WHERE id = " + placementId, String.class));
    }

    @Test
    void dualRegistrarConfirmTakesEffectAndDeductsStock() throws Exception {
        long caseId = reportCase("A", "rpt-1");
        confirmCase(caseId, "cfm-1");
        long placementId = registerPlacement(caseId, "pl-1", "张三");
        confirmPlacement(placementId, "pc-1", "李四");

        assertEquals("EFFECTIVE", jdbc.queryForObject(
                "SELECT status FROM treatment_placement WHERE id = " + placementId, String.class));
        assertEquals(0, pesticideQuantity(1).compareTo(new BigDecimal("90.00")));
        assertEquals("TREATED", caseStatus(caseId));
    }

    @Test
    void expiredPesticideRejected() throws Exception {
        long caseId = reportCase("A", "rpt-1");
        confirmCase(caseId, "cfm-1");

        // 药剂 2 已过期，拒绝登记投放
        postJson("/api/cases/" + caseId + "/placements",
                Map.of("requestId", "pl-1", "pesticideId", 2, "dosage", 10,
                        "point", "3排-2架", "registrar", "张三"))
                .andExpect(status().isBadRequest());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM treatment_placement", Long.class));
    }

    @Test
    void insufficientStockRollsBackWholeTransaction() throws Exception {
        long caseId = reportCase("A", "rpt-1");
        confirmCase(caseId, "cfm-1");
        jdbc.update("UPDATE pesticide SET quantity = 5.00 WHERE id = 1");
        long placementId = registerPlacement(caseId, "pl-1", "张三");

        // 库存不足：投放生效与库存扣减整体回滚
        postJson("/api/placements/" + placementId + "/confirm",
                Map.of("requestId", "pc-1", "registrar", "李四", "pesticideId", 1,
                        "dosage", 10, "point", "3排-2架"))
                .andExpect(status().isConflict());

        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM treatment_placement WHERE id = " + placementId, String.class));
        assertEquals(0, pesticideQuantity(1).compareTo(new BigDecimal("5.00")));
        assertEquals("CONFIRMED", caseStatus(caseId));
    }

    @Test
    void lowStockGeneratesPurchaseSuggestionLinkedToPlacement() throws Exception {
        long caseId = reportCase("A", "rpt-1");
        confirmCase(caseId, "cfm-1");
        jdbc.update("UPDATE pesticide SET quantity = 25.00 WHERE id = 1");
        long placementId = registerPlacement(caseId, "pl-1", "张三");
        confirmPlacement(placementId, "pc-1", "李四");

        // 扣减后 15.00 低于安全线 20.00，自动生成采购建议并与本次投放勾稽
        assertEquals(0, pesticideQuantity(1).compareTo(new BigDecimal("15.00")));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM purchase_suggestion", Long.class));
        Map<String, Object> suggestion = jdbc.queryForMap("SELECT * FROM purchase_suggestion");
        assertEquals(placementId, ((Number) suggestion.get("placement_id")).longValue());
        assertEquals(0, ((BigDecimal) suggestion.get("suggested_quantity"))
                .compareTo(new BigDecimal("25.00")));
    }
}
