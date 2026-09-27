package com.example.starter.survey.it;

import com.example.starter.survey.testsupport.AbstractH2IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 互认规则版本管理：版本从 1 开始、expectedVersion 乐观并发、
 * 历史版本不可变可查、requestId 幂等。
 */
class FlagRuleFlowTest extends AbstractH2IntegrationTest {

    private static final List<Map<String, String>> MAPPINGS_V1 = List.of(
            Map.of("flagItemCode", "FLAG-HULL", "classItemCode", "CLASS-HULL"),
            Map.of("flagItemCode", "FLAG-SAFETY", "classItemCode", "CLASS-SAFETY"));

    private JsonNode getExpectStatus(String path, int expectedStatus) {
        ResponseEntity<String> response = rest.getForEntity(url(path), String.class);
        if (response.getStatusCode().value() != expectedStatus) {
            throw new AssertionError("期望 HTTP " + expectedStatus + "，实际 HTTP "
                    + response.getStatusCode().value() + "，响应: " + response.getBody());
        }
        try {
            return new ObjectMapper().readTree(response.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("响应解析失败: " + response.getBody(), e);
        }
    }

    @Test
    void createThenUpdateIncrementsVersion() {
        JsonNode created = postExpectStatus("/api/flag/rules",
                ruleBody("rule-1", "SYN", 0, 90, MAPPINGS_V1), 200);
        assertEquals(1, created.get("data").get("version").asInt());
        assertEquals(90, created.get("data").get("maxEvidenceAgeDays").asInt());
        assertEquals(2, created.get("data").get("mappings").size());

        JsonNode updated = postExpectStatus("/api/flag/rules",
                ruleBody("rule-2", "SYN", 1, 120,
                        List.of(mapping("FLAG-HULL", "CLASS-HULL"),
                                mapping("FLAG-MACH", "CLASS-MACH"))), 200);
        assertEquals(2, updated.get("data").get("version").asInt());
        assertEquals(120, updated.get("data").get("maxEvidenceAgeDays").asInt());

        // 当前版本与版本链查询。
        JsonNode current = getExpectStatus("/api/flag/rules/SYN", 200);
        assertEquals(2, current.get("version").asInt());
        JsonNode versions = getExpectStatus("/api/flag/rules/SYN/versions", 200);
        assertEquals(2, versions.size());
        assertEquals(1, versions.get(0).get("version").asInt());
        assertEquals(90, versions.get(0).get("maxEvidenceAgeDays").asInt(),
                "历史版本保持冻结，不被后续变更改写");
    }

    @Test
    void expectedVersionMismatchConflicts() {
        // 无历史版本时 expectedVersion 必须为 0。
        JsonNode noBaseline = postExpectStatus("/api/flag/rules",
                ruleBody("rule-b1", "SYN", 1, 90, MAPPINGS_V1), 409);
        assertEquals("RULE_VERSION_CONFLICT", noBaseline.get("code").asText());

        postExpectStatus("/api/flag/rules", ruleBody("rule-b2", "SYN", 0, 90, MAPPINGS_V1), 200);
        // 当前版本为 1，携带过期 expectedVersion=0 变更 → 409，不产生新版本。
        JsonNode stale = postExpectStatus("/api/flag/rules",
                ruleBody("rule-b3", "SYN", 0, 60, MAPPINGS_V1), 409);
        assertEquals("RULE_VERSION_CONFLICT", stale.get("code").asText());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM flag_rule_version WHERE flag_code='SYN'", Integer.class));
    }

    @Test
    void sameRequestIdReplaysAndDifferentParamsConflict() {
        JsonNode first = postExpectStatus("/api/flag/rules",
                ruleBody("rule-key", "SYN", 0, 90, MAPPINGS_V1), 200);
        assertFalse(first.get("replayed").asBoolean());

        JsonNode replay = postExpectStatus("/api/flag/rules",
                ruleBody("rule-key", "SYN", 0, 90, MAPPINGS_V1), 200);
        assertTrue(replay.get("replayed").asBoolean());
        assertEquals(1, replay.get("data").get("version").asInt());

        JsonNode conflict = postExpectStatus("/api/flag/rules",
                ruleBody("rule-key", "SYN", 0, 30, MAPPINGS_V1), 409);
        assertEquals("IDEMPOTENCY_FINGERPRINT_MISMATCH", conflict.get("code").asText());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM flag_rule_version WHERE flag_code='SYN'", Integer.class));
    }

    @Test
    void duplicateMappingItemRejectedAsBadRequest() {
        JsonNode dupFlag = postExpectStatus("/api/flag/rules",
                ruleBody("rule-d1", "SYN", 0, 90,
                        List.of(mapping("FLAG-HULL", "CLASS-HULL"),
                                mapping("FLAG-HULL", "CLASS-OTHER"))), 400);
        assertEquals("DUPLICATE_FLAG_ITEM", dupFlag.get("code").asText());

        JsonNode dupClass = postExpectStatus("/api/flag/rules",
                ruleBody("rule-d2", "SYN", 0, 90,
                        List.of(mapping("FLAG-A", "CLASS-HULL"),
                                mapping("FLAG-B", "CLASS-HULL"))), 400);
        assertEquals("DUPLICATE_CLASS_ITEM", dupClass.get("code").asText());

        // 失败不占键：修正参数后同 requestId 可重新受理。
        postExpectStatus("/api/flag/rules",
                ruleBody("rule-d1", "SYN", 0, 90, MAPPINGS_V1), 200);
    }

    @Test
    void invalidParamsRejectedAndUnknownFlagNotFound() {
        // 缺 requestId → 400。
        JsonNode missing = postExpectStatus("/api/flag/rules",
                Map.of("flagCode", "SYN", "expectedVersion", 0,
                        "maxEvidenceAgeDays", 90, "mappings", MAPPINGS_V1), 400);
        assertEquals("VALIDATION_FAILED", missing.get("code").asText());

        JsonNode notFound = getExpectStatus("/api/flag/rules/NOPE", 404);
        assertEquals("RULE_NOT_FOUND", notFound.get("code").asText());
    }
}
