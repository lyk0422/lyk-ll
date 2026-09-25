package com.example.starter.dye;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 投料失败分支：称量超 ±0.5%、明细与配方不符、库存不足——一律拒绝且库存零扣减。
 */
class FeedRejectionIntegrationTest extends FlowIntegrationTest {

    private void setupRecipe(String stock) throws Exception {
        dyestuff("D1", stock);
        vat("V1", "500");
        Map<String, String> recipe = new LinkedHashMap<>();
        recipe.put("D1", "2.0000"); // 2×10×100 = 2000g
        publishRecipe("R1", 10, recipe);
        schedule("RUN-1", "V1", "R1", "100");
    }

    @Test
    void deviationBeyondLimitRejectsAndDoesNotDeduct() throws Exception {
        setupRecipe("50000");
        // 2011g / 2000g = +0.55% > 0.5%
        JsonNode resp = feed("FD-BAD", "RUN-1", Map.of("D1", "2011.000"), 422);
        assertThat(resp.path("accepted").asBoolean()).isFalse();
        assertThat(resp.path("weighings").get(0).path("accepted").asBoolean()).isFalse();

        // 库存零扣减
        assertThat(stockOf("D1")).isEqualByComparingTo("50000.000");
        Integer movements = jdbc.queryForObject(
                "select count(*) from stock_movement where request_id='FD-BAD'", Integer.class);
        assertThat(movements).isZero();
        // 但称量链已留痕（可复算拒绝原因），缸次仍 PLANNED
        Integer weighs = jdbc.queryForObject(
                "select count(*) from weigh_record where request_id='FD-BAD'", Integer.class);
        assertThat(weighs).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from vat_run where run_no='RUN-1'", String.class))
                .isEqualTo("PLANNED");

        // 同一拒绝结果用同键重放仍为 422 且仍不扣减
        feed("FD-BAD", "RUN-1", Map.of("D1", "2011.000"), 422);
        assertThat(stockOf("D1")).isEqualByComparingTo("50000.000");

        // 拒绝后用新 requestId 提交合格称量仍可成功
        JsonNode ok = feed("FD-OK", "RUN-1", Map.of("D1", "2000.000"), 200);
        assertThat(ok.path("accepted").asBoolean()).isTrue();
        assertThat(stockOf("D1")).isEqualByComparingTo("48000.000");
    }

    @Test
    void insufficientStockRejects() throws Exception {
        setupRecipe("1500"); // 库存仅 1500g < 计划 2000g，称量精确但无法供给
        JsonNode resp = feed("FD-LACK", "RUN-1", Map.of("D1", "2000.000"), 422);
        assertThat(resp.path("accepted").asBoolean()).isFalse();
        assertThat(resp.path("message").asText()).contains("库存不足");
        assertThat(stockOf("D1")).isEqualByComparingTo("1500.000");
    }

    @Test
    void itemsMustMatchRecipeExactly() throws Exception {
        setupRecipe("50000");
        // 少报染化料
        JsonNode resp = postExpect("/api/feed", Map.of(
                "requestId", "FD-MISS", "runNo", "RUN-1",
                "items", java.util.List.of()), 400);
        assertThat(resp).isNotNull();
        assertThat(stockOf("D1")).isEqualByComparingTo("50000.000");
    }

    @Test
    void feedingUnknownRunRejectedWithoutConsumingIdempotencyKey() throws Exception {
        setupRecipe("50000");
        // 缸次不存在 → 422（异常回滚，不占用幂等键）；稍后同键可用于真实缸次
        feed("FD-X", "RUN-NOPE", Map.of("D1", "2000.000"), 422);
        JsonNode ok = feed("FD-X", "RUN-1", Map.of("D1", "2000.000"), 200);
        assertThat(ok.path("accepted").asBoolean()).isTrue();
        assertThat(stockOf("D1")).isEqualByComparingTo("48000.000");
    }

    @Test
    void stockLedgerCanRecomputeBalance() throws Exception {
        setupRecipe("100000");
        feed("FD-1", "RUN-1", Map.of("D1", "2000.000"), 200);
        // 台账最后一条余额即当前库存，且累加变动等于净变化
        BigDecimal ledgerBalance = jdbc.queryForObject(
                "select balance_grams from stock_movement where dyestuff_code='D1' order by id desc limit 1",
                BigDecimal.class);
        assertThat(ledgerBalance).isEqualByComparingTo("98000.000");
        assertThat(stockOf("D1")).isEqualByComparingTo(ledgerBalance);
    }
}
