package com.example.starter.dye;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配方发布冻结与版本保留：发布后不能原地改；修改生成新版本，旧版本原样保留且仍可被缸次引用。
 */
class RecipeVersionIntegrationTest extends FlowIntegrationTest {

    private Map<String, String> recipe(String d1, String d2) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("D1", d1);
        m.put("D2", d2);
        return m;
    }

    @Test
    void publishFreezesAndNewVersionKeepsOld() throws Exception {
        dyestuff("D1", "50000");
        dyestuff("D2", "50000");
        publishRecipe("R1", 10, recipe("2.0000", "1.0000"));

        // V1 已发布；直接再对同编码建草稿冲突
        Map<String, Object> createAgain = new LinkedHashMap<>();
        createAgain.put("recipeCode", "R1");
        createAgain.put("name", "重复");
        createAgain.put("liquorRatio", 10);
        createAgain.put("items", List.of(Map.of("dyestuffCode", "D1",
                "gramsPerLiter", new java.math.BigDecimal("2.0000"))));
        postExpect("/api/recipes", createAgain, 409);

        // 派生 V2（改浓度）
        Map<String, Object> v2 = new LinkedHashMap<>();
        v2.put("items", List.of(
                Map.of("dyestuffCode", "D1", "gramsPerLiter", new java.math.BigDecimal("2.5000")),
                Map.of("dyestuffCode", "D2", "gramsPerLiter", new java.math.BigDecimal("1.0000"))));
        JsonNode createdV2 = postExpect("/api/recipes/R1/versions", v2, 200);
        assertThat(createdV2.path("version").asInt()).isEqualTo(2);
        assertThat(createdV2.path("status").asText()).isEqualTo("DRAFT");

        // 旧版本 V1 仍在、仍冻结、浓度未被改动
        JsonNode v1 = getExpect("/api/recipes/R1/versions/1", 200);
        assertThat(v1.path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(v1.path("items").get(0).path("gramsPerLiter").doubleValue()).isEqualTo(2.0);

        // 发布 V2 后，最新发布版本切换，V1 仍可显式引用
        postExpect("/api/recipes/R1/versions/2/publish", Map.of(), 200);
        vat("V1", "500");
        vat("V2", "500");
        JsonNode runOnV1 = schedule("RUN-A", "V1", "R1", 1, "10", null);
        assertThat(runOnV1.path("recipeVersion").asInt()).isEqualTo(1);
        JsonNode runOnV2 = schedule("RUN-B", "V2", "R1", null, "10", null);
        assertThat(runOnV2.path("recipeVersion").asInt()).isEqualTo(2);

        // 列表含两个版本
        JsonNode list = getExpect("/api/recipes", 200);
        assertThat(list.isArray()).isTrue();
        assertThat(list).hasSize(2);
    }

    @Test
    void newVersionWithoutPublishedBaseRejected() throws Exception {
        dyestuff("D1", "50000");
        // 仅草稿、未发布即派生新版本 → 422
        Map<String, Object> create = new LinkedHashMap<>();
        create.put("recipeCode", "R2");
        create.put("name", "草稿");
        create.put("liquorRatio", 8);
        create.put("items", List.of(Map.of("dyestuffCode", "D1",
                "gramsPerLiter", new java.math.BigDecimal("1.0000"))));
        postExpect("/api/recipes", create, 200);
        Map<String, Object> nv = Map.of("items", List.of(Map.of("dyestuffCode", "D1",
                "gramsPerLiter", new java.math.BigDecimal("1.2000"))));
        postExpect("/api/recipes/R2/versions", nv, 422);
    }
}
