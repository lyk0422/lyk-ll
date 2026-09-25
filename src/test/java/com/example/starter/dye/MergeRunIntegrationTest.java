package com.example.starter.dye;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并缸规则：同配方同浴比允许；不同配方或同配方不同浴比拒绝（422）。
 */
class MergeRunIntegrationTest extends FlowIntegrationTest {

    private void baseData() throws Exception {
        dyestuff("D1", "90000");
        dyestuff("D2", "90000");
        vat("V1", "500");
        vat("V2", "500");
        Map<String, String> r1 = new LinkedHashMap<>();
        r1.put("D1", "2.0000");
        publishRecipe("R1", 10, r1);
        Map<String, String> r2 = new LinkedHashMap<>();
        r2.put("D2", "1.0000");
        publishRecipe("R2", 10, r2);
    }

    @Test
    void sameRecipeSameRatioMergeAllowed() throws Exception {
        baseData();
        JsonNode a = schedule("RUN-A", "V1", "R1", "10");
        assertThat(a.path("mergedRunNo").isNull() || a.path("mergedRunNo").asText().isBlank()).isTrue();

        JsonNode b = schedule("RUN-B", "V2", "R1", null, "10", "RUN-A");
        assertThat(b.path("mergedRunNo").asText()).isEqualTo("RUN-A");

        // 伙伴缸次反向记录并缸关系
        JsonNode aAfter = getExpect("/api/runs/RUN-A", 200);
        assertThat(aAfter.path("mergedRunNo").asText()).isEqualTo("RUN-B");
    }

    @Test
    void differentRecipeMergeRejected() throws Exception {
        baseData();
        schedule("RUN-A", "V1", "R1", "10");
        // RUN-B 用 R2 并向 R1 的缸次 → 422
        JsonNode err = postExpect("/api/runs", mergeBody("RUN-B", "V2", "R2", null, "10", "RUN-A"), 422);
        assertThat(err.path("message").asText()).contains("配方不同");
        // 被拒缸次不留痕
        assertThat(jdbc.queryForObject("select count(*) from vat_run where run_no='RUN-B'",
                Integer.class)).isZero();
    }

    @Test
    void sameRecipeDifferentLiquorRatioMergeRejected() throws Exception {
        baseData();
        // R1 派生浴比 8 的 V2
        Map<String, Object> v2 = new LinkedHashMap<>();
        v2.put("liquorRatio", 8);
        v2.put("items", List.of(Map.of("dyestuffCode", "D1",
                "gramsPerLiter", new java.math.BigDecimal("2.0000"))));
        postExpect("/api/recipes/R1/versions", v2, 200);
        postExpect("/api/recipes/R1/versions/2/publish", Map.of(), 200);

        schedule("RUN-A", "V1", "R1", 1, "10", null);
        JsonNode err = postExpect("/api/runs", mergeBody("RUN-B", "V2", "R1", 2, "10", "RUN-A"), 422);
        assertThat(err.path("message").asText()).contains("浴比不同");
    }

    private Map<String, Object> mergeBody(String runNo, String vat, String recipe, Integer version,
                                          String fabricKg, String mergeWith) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runNo", runNo);
        body.put("vatCode", vat);
        body.put("recipeCode", recipe);
        if (version != null) {
            body.put("recipeVersion", version);
        }
        body.put("fabricKg", new java.math.BigDecimal(fabricKg));
        body.put("mergeWithRunNo", mergeWith);
        return body;
    }
}
