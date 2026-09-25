package com.example.starter.dye;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 主流程端到端：打小样对色（恰 0.8 放行）→ 放大配方 → 排缸 → 投料（恰 0.5%）→
 * 出缸测色 → 缸差判定合格 → 归档；并验证库存原子扣减、同键重放与归档终态。
 */
class MainFlowIntegrationTest extends FlowIntegrationTest {

    /** 单染化料配方：D1 浓度 2 g/L、浴比 10、织物 100kg → 计划 2000g。 */
    private void prepareSingleRecipe() throws Exception {
        dyestuff("D1", "50000");
        vat("V1", "500");
        Map<String, String> recipe = new LinkedHashMap<>();
        recipe.put("D1", "2.0000");
        publishRecipe("R1", 10, recipe);
    }

    @Test
    void fullFlow_color08_weigh05percent_passAndArchive() throws Exception {
        prepareSingleRecipe();

        // 1) 打小样对色：恰 0.8，合格放行
        JsonNode color = colorMatch("CM-1", "S1", 0.8, 0, 0);
        assertThat(color.path("deltaE").doubleValue()).isEqualTo(0.8);
        assertThat(color.path("passed").asBoolean()).isTrue();

        // 2) 排缸
        JsonNode run = schedule("RUN-1", "V1", "R1", "100");
        assertThat(run.path("status").asText()).isEqualTo("PLANNED");
        assertThat(run.path("liquorRatio").asInt()).isEqualTo(10);

        // 3) 投料：实际 2010g，恰 +0.5%，合法
        JsonNode feed = feed("FD-1", "RUN-1", Map.of("D1", "2010.000"), 200);
        assertThat(feed.path("accepted").asBoolean()).isTrue();
        assertThat(feed.path("weighings").get(0).path("deviationRatio").doubleValue())
                .isCloseTo(0.005, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(feed.path("weighings").get(0).path("plannedGrams").doubleValue())
                .isCloseTo(2000.0, org.assertj.core.data.Offset.offset(1e-9));

        // 库存原子扣减：50000 - 2010 = 47990
        assertThat(stockOf("D1")).isEqualByComparingTo("47990.000");
        // 台账留痕可复算
        BigDecimal ledgerSum = jdbc.queryForObject(
                "select coalesce(sum(change_grams),0) from stock_movement where dyestuff_code='D1'",
                BigDecimal.class);
        assertThat(ledgerSum).isEqualByComparingTo("-2010.000");

        // 缸次进入执行中
        JsonNode running = getExpect("/api/runs/RUN-1", 200);
        assertThat(running.path("status").asText()).isEqualTo("RUNNING");

        // 4) 出缸测色 ΔE=0.3
        JsonNode measure = measure("RUN-1", "0.3");
        assertThat(measure.path("status").asText()).isEqualTo("DISCHARGED");
        assertThat(measure.path("lateEvent").asBoolean()).isFalse();

        // 5) 缸差判定：无同配方历史缸，以标准样为基准，0.3 ≤ 0.8 合格
        JsonNode judge = judge("JD-1", "RUN-1", null, 200);
        assertThat(judge.path("disposition").asText()).isEqualTo("PASS");
        assertThat(judge.path("status").asText()).isEqualTo("DISCHARGED");

        // 6) 归档终态
        JsonNode archive = archive("AR-1", "RUN-1", 200);
        assertThat(archive.path("disposition").asText()).isEqualTo("ARCHIVED");
        assertThat(archive.path("status").asText()).isEqualTo("ARCHIVED");

        // 归档后染缸释放，可再排新缸
        JsonNode vat = getExpect("/api/vats/V1", 200);
        assertThat(vat.path("activeRunNo").isNull() || vat.path("activeRunNo").asText().isBlank())
                .isTrue();
    }

    @Test
    void sameKeyReplay_returnsFirstResult_andDoesNotDeductTwice() throws Exception {
        prepareSingleRecipe();
        colorMatch("CM-9", "S9", 0.2, 0, 0);
        schedule("RUN-9", "V1", "R1", "100");

        JsonNode first = feed("FD-9", "RUN-9", Map.of("D1", "2000.000"), 200);
        assertThat(first.path("accepted").asBoolean()).isTrue();
        assertThat(stockOf("D1")).isEqualByComparingTo("48000.000");

        // 同键同参重放：返回首次结果，库存不二次扣减
        JsonNode replay = feed("FD-9", "RUN-9", Map.of("D1", "2000.000"), 200);
        assertThat(replay.path("accepted").asBoolean()).isTrue();
        assertThat(stockOf("D1")).isEqualByComparingTo("48000.000");
        Integer movementCount = jdbc.queryForObject(
                "select count(*) from stock_movement where request_id='FD-9'", Integer.class);
        assertThat(movementCount).isEqualTo(1);

        // 同键异参：409，库存不变
        JsonNode conflict = feed("FD-9", "RUN-9", Map.of("D1", "2001.000"), 409);
        assertThat(conflict.path("status").asInt()).isEqualTo(409);
        assertThat(stockOf("D1")).isEqualByComparingTo("48000.000");
    }

    @Test
    void colorMatchSameKeyReplay_andConflict() throws Exception {
        prepareSingleRecipe();
        JsonNode first = colorMatch("CM-X", "SX", 0.1, 0, 0);
        assertThat(first.path("passed").asBoolean()).isTrue();
        // 同参重放
        JsonNode replay = colorMatch("CM-X", "SX", 0.1, 0, 0);
        assertThat(replay.path("deltaE").doubleValue()).isEqualTo(0.1);
        // 异参 409
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", "CM-X");
        body.put("sampleCode", "SX");
        body.put("stdL", 0);
        body.put("stdA", 0);
        body.put("stdB", 0);
        body.put("sampleL", 0.9);
        body.put("sampleA", 0);
        body.put("sampleB", 0);
        postExpect("/api/color-match", body, 409);
    }
}
