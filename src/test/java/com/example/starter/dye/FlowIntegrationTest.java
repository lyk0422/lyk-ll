package com.example.starter.dye;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 全链路流程测试支撑：通过真实 HTTP 入口编排“主数据→配方→排缸→投料→出缸→判定→归档”。
 */
public abstract class FlowIntegrationTest extends AbstractDyeIntegrationTest {

    protected ResultActions postJson(String path, Object body) throws Exception {
        return mockMvc.perform(post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    protected ResultActions getJson(String path) throws Exception {
        return mockMvc.perform(get(path).accept(MediaType.APPLICATION_JSON));
    }

    protected JsonNode getExpect(String path, int expectedStatus) throws Exception {
        String resp = getJson(path).andExpect(status().is(expectedStatus)).andReturn()
                .getResponse().getContentAsString();
        return resp.isBlank() ? null : objectMapper.readTree(resp);
    }

    protected JsonNode postExpect(String path, Object body, int expectedStatus) throws Exception {
        String resp = postJson(path, body).andExpect(status().is(expectedStatus)).andReturn()
                .getResponse().getContentAsString();
        return resp.isBlank() ? null : objectMapper.readTree(resp);
    }

    protected void dyestuff(String code, String stock) throws Exception {
        postExpect("/api/dyestuffs", Map.of(
                "code", code, "name", code + "-名称", "stockGrams", new BigDecimal(stock)), 200);
    }

    protected void vat(String code, String capacityKg) throws Exception {
        postExpect("/api/vats", Map.of("vatCode", code, "capacityKg", new BigDecimal(capacityKg)), 200);
    }

    /** 建草稿→发布，返回已发布配方版本视图。 */
    protected JsonNode publishRecipe(String recipeCode, int liquorRatio,
                                     Map<String, String> concentrationByDye) throws Exception {
        List<Map<String, Object>> items = concentrationByDye.entrySet().stream()
                .map(e -> Map.<String, Object>of("dyestuffCode", e.getKey(),
                        "gramsPerLiter", new BigDecimal(e.getValue())))
                .toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("recipeCode", recipeCode);
        body.put("name", recipeCode + "-名称");
        body.put("liquorRatio", liquorRatio);
        body.put("items", items);
        postExpect("/api/recipes", body, 200);
        return postExpect("/api/recipes/" + recipeCode + "/versions/1/publish", Map.of(), 200);
    }

    protected JsonNode schedule(String runNo, String vatCode, String recipeCode, String fabricKg)
            throws Exception {
        return schedule(runNo, vatCode, recipeCode, null, fabricKg, null);
    }

    protected JsonNode schedule(String runNo, String vatCode, String recipeCode, Integer version,
                                String fabricKg, String mergeWithRunNo) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runNo", runNo);
        body.put("vatCode", vatCode);
        body.put("recipeCode", recipeCode);
        if (version != null) {
            body.put("recipeVersion", version);
        }
        body.put("fabricKg", new BigDecimal(fabricKg));
        if (mergeWithRunNo != null) {
            body.put("mergeWithRunNo", mergeWithRunNo);
        }
        return postExpect("/api/runs", body, 200);
    }

    protected JsonNode colorMatch(String requestId, String sampleCode,
                                  double sL, double sA, double sB) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("sampleCode", sampleCode);
        body.put("stdL", 0);
        body.put("stdA", 0);
        body.put("stdB", 0);
        body.put("sampleL", sL);
        body.put("sampleA", sA);
        body.put("sampleB", sB);
        return postExpect("/api/color-match", body, 200);
    }

    protected JsonNode feed(String requestId, String runNo, Map<String, String> actualGrams,
                            int expectedStatus) throws Exception {
        List<Map<String, Object>> items = actualGrams.entrySet().stream()
                .map(e -> Map.<String, Object>of("dyestuffCode", e.getKey(),
                        "actualGrams", new BigDecimal(e.getValue())))
                .toList();
        return postExpect("/api/feed", Map.of("requestId", requestId, "runNo", runNo, "items", items),
                expectedStatus);
    }

    protected JsonNode measure(String runNo, String deltaE) throws Exception {
        return postExpect("/api/discharge-measure",
                Map.of("runNo", runNo, "deltaE", new BigDecimal(deltaE)), 200);
    }

    protected JsonNode judge(String requestId, String runNo, String baselineRunNo, int expectedStatus)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("runNo", runNo);
        if (baselineRunNo != null) {
            body.put("baselineRunNo", baselineRunNo);
        }
        return postExpect("/api/judge", body, expectedStatus);
    }

    protected JsonNode archive(String requestId, String runNo, int expectedStatus) throws Exception {
        return postExpect("/api/archive", Map.of("requestId", requestId, "runNo", runNo), expectedStatus);
    }

    protected BigDecimal stockOf(String code) {
        return jdbc.queryForObject("select stock_grams from dyestuff where code = ?",
                BigDecimal.class, code);
    }
}
