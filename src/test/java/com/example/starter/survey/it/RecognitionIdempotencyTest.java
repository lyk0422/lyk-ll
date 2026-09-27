package com.example.starter.survey.it;

import com.example.starter.survey.testsupport.AbstractH2IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 互认写操作幂等与指纹边界：
 * 同键同参重放首次冻结结果；同键异参 409；失败不占键；
 * 指纹结构化无歧义（含分隔符文本的不同参数组合不得碰撞）。
 */
class RecognitionIdempotencyTest extends AbstractH2IntegrationTest {

    private static final LocalDate ANNIVERSARY = LocalDate.of(2020, 6, 15);
    private static final LocalDate DELIVERY = LocalDate.of(2020, 3, 1);
    private static final List<String> RULE_ITEMS =
            List.of("HULL_STRUCTURE", "MACHINERY", "SAFETY_EQUIPMENT");
    private static final Instant COMPLETED_AT =
            LocalDate.of(2026, 6, 10).atTime(10, 0).toInstant(ZoneOffset.UTC);

    private void registerShipAndRule() {
        postExpectStatus("/api/ships", shipBody("ship-i1", "IMO-I1", ANNIVERSARY, DELIVERY), 200);
        postExpectStatus("/api/recognition/rules",
                ruleBody("rule-i1", 1, "SYN-FL", 90, RULE_ITEMS), 200);
    }

    @Test
    void sameKeySameParamsReplaysFirstFrozenResult() {
        registerShipAndRule();
        var body = importReportBody("imp-replay", "RPT-REPLAY", "IMO-I1", "SYN-FL",
                COMPLETED_AT,
                List.of(reportItem("HULL_STRUCTURE", "PASS"),
                        reportItem("MACHINERY", "PASS"),
                        reportItem("SAFETY_EQUIPMENT", "PASS")));
        JsonNode first = postExpectStatus("/api/recognition/reports", body, 200);
        assertFalse(first.get("replayed").asBoolean());
        JsonNode replay = postExpectStatus("/api/recognition/reports", body, 200);
        assertTrue(replay.get("replayed").asBoolean());
        assertEquals(first.get("data").get("id").asLong(),
                replay.get("data").get("id").asLong(), "重放返回首次冻结结果");
        assertEquals(1, get("/api/recognition/reports/RPT-REPLAY/revisions").size());
    }

    @Test
    void sameKeyDifferentParamsConflicts() {
        registerShipAndRule();
        postExpectStatus("/api/recognition/reports",
                importReportBody("imp-diff", "RPT-DIFF-1", "IMO-I1", "SYN-FL", COMPLETED_AT,
                        List.of(reportItem("HULL_STRUCTURE", "PASS"),
                                reportItem("MACHINERY", "PASS"),
                                reportItem("SAFETY_EQUIPMENT", "PASS"))), 200);
        // 同 requestId 异参（报告编号不同）：409。
        JsonNode conflict = postExpectStatus("/api/recognition/reports",
                importReportBody("imp-diff", "RPT-DIFF-2", "IMO-I1", "SYN-FL", COMPLETED_AT,
                        List.of(reportItem("HULL_STRUCTURE", "PASS"),
                                reportItem("MACHINERY", "PASS"),
                                reportItem("SAFETY_EQUIPMENT", "PASS"))), 409);
        assertEquals("IDEMPOTENCY_FINGERPRINT_MISMATCH", conflict.get("code").asText());
        // 异参请求不得产生新报告。
        var notFound = rest.getForEntity(url("/api/recognition/reports/RPT-DIFF-2"),
                String.class);
        assertEquals(404, notFound.getStatusCode().value());
        assertTrue(String.valueOf(notFound.getBody()).contains("REPORT_NOT_FOUND"));
    }

    @Test
    void failedRequestDoesNotHoldKey() {
        registerShipAndRule();
        postExpectStatus("/api/ships", shipBody("ship-i2", "IMO-I2", ANNIVERSARY, DELIVERY), 200);
        // 先用 requestId 触发业务失败（非合成签认 422）。
        var bad = new java.util.HashMap<String, Object>(
                importReportBody("imp-fail", "RPT-FAIL", "IMO-I1", "SYN-FL", COMPLETED_AT,
                        List.of(reportItem("HULL_STRUCTURE", "PASS"),
                                reportItem("MACHINERY", "PASS"),
                                reportItem("SAFETY_EQUIPMENT", "PASS"))));
        bad.put("attestation", "REAL-AUTHORITY-SEAL");
        postExpectStatus("/api/recognition/reports", bad, 422);
        // 同 requestId 修正参数后重发：失败不占键，应作为首次受理成功。
        JsonNode ok = postExpectStatus("/api/recognition/reports",
                importReportBody("imp-fail", "RPT-FAIL", "IMO-I1", "SYN-FL", COMPLETED_AT,
                        List.of(reportItem("HULL_STRUCTURE", "PASS"),
                                reportItem("MACHINERY", "PASS"),
                                reportItem("SAFETY_EQUIPMENT", "PASS"))), 200);
        assertFalse(ok.get("replayed").asBoolean());
    }

    @Test
    void fingerprintIsStructuredAndUnambiguousWithSeparators() {
        registerShipAndRule();
        // 若指纹为简单拼接，"AB"+"CD" 与 "ABC"+"D" 会碰撞；结构化指纹必须区分。
        postExpectStatus("/api/recognition/reports",
                importReportBody("imp-collision", "AB", "IMO-I1", "CD", COMPLETED_AT,
                        List.of(reportItem("HULL_STRUCTURE", "PASS"),
                                reportItem("MACHINERY", "PASS"),
                                reportItem("SAFETY_EQUIPMENT", "PASS"))), 200);
        JsonNode conflict = postExpectStatus("/api/recognition/reports",
                importReportBody("imp-collision", "ABC", "IMO-I1", "D", COMPLETED_AT,
                        List.of(reportItem("HULL_STRUCTURE", "PASS"),
                                reportItem("MACHINERY", "PASS"),
                                reportItem("SAFETY_EQUIPMENT", "PASS"))), 409);
        assertEquals("IDEMPOTENCY_FINGERPRINT_MISMATCH", conflict.get("code").asText(),
                "含分隔符文本的不同参数组合不得指纹碰撞");
    }

    @Test
    void correctionReplayAndVersionBump() {
        registerShipAndRule();
        postExpectStatus("/api/recognition/reports",
                importReportBody("imp-ver", "RPT-VER", "IMO-I1", "SYN-FL", COMPLETED_AT,
                        List.of(reportItem("HULL_STRUCTURE", "PASS"),
                                reportItem("MACHINERY", "PASS"),
                                reportItem("SAFETY_EQUIPMENT", "PASS"))), 200);
        var correct = correctReportBody("cor-ver", "RPT-VER", 1, COMPLETED_AT,
                List.of(reportItem("HULL_STRUCTURE", "PASS"),
                        reportItem("MACHINERY", "PASS"),
                        reportItem("SAFETY_EQUIPMENT", "PASS")));
        JsonNode first = postExpectStatus("/api/recognition/reports/correct", correct, 200);
        assertEquals(2, first.get("data").get("version").asInt(), "成功更正版本 +1");
        // 同键同参重放：冻结的首次结果（版本仍为 2，不再新增修订）。
        JsonNode replay = postExpectStatus("/api/recognition/reports/correct", correct, 200);
        assertTrue(replay.get("replayed").asBoolean());
        assertEquals(2, replay.get("data").get("version").asInt());
        assertEquals(2, get("/api/recognition/reports/RPT-VER/revisions").size());
    }
}
