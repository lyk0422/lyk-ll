package com.example.starter.survey.it;

import com.example.starter.survey.testsupport.AbstractH2IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 外部检查证据报告：导入（唯一报告号、合成签认、同参不新增/异内容冲突）、
 * 更正生成新修订、修订链查询、requestId 幂等。
 */
class FlagReportFlowTest extends AbstractH2IntegrationTest {

    private static final LocalDate ANNIVERSARY = LocalDate.of(2020, 6, 15);
    private static final LocalDate DELIVERY = LocalDate.of(2020, 3, 1);
    private static final Instant COMPLETED =
            LocalDate.of(2026, 6, 1).atStartOfDay(ZoneOffset.UTC).toInstant();

    private void setUpRuleAndShip() {
        postExpectStatus("/api/flag/rules",
                ruleBody("rule-rp", "SYN", 0, 90,
                        List.of(mapping("FLAG-HULL", "CLASS-HULL"))), 200);
        postExpectStatus("/api/ships",
                shipBody("ship-rp", "IMO-RP", ANNIVERSARY, DELIVERY), 200);
    }

    @Test
    void importMainFlowAndReplay() {
        setUpRuleAndShip();
        JsonNode imported = postExpectStatus("/api/flag/reports",
                reportBody("imp-1", "FR-1001", "IMO-RP", "SYN", COMPLETED,
                        List.of(reportItem("FLAG-HULL", "SATISFACTORY"))), 200);
        assertFalse(imported.get("replayed").asBoolean());
        assertEquals(1, imported.get("data").get("revision").asInt());
        assertEquals("IMO-RP", imported.get("data").get("imo").asText());
        assertEquals(COMPLETED.toString(), imported.get("data").get("completedAt").asText());
        assertEquals(1, imported.get("data").get("items").size());

        // 同 requestId 同参重放。
        JsonNode replay = postExpectStatus("/api/flag/reports",
                reportBody("imp-1", "FR-1001", "IMO-RP", "SYN", COMPLETED,
                        List.of(reportItem("FLAG-HULL", "SATISFACTORY"))), 200);
        assertTrue(replay.get("replayed").asBoolean());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM flag_inspection_report WHERE report_no='FR-1001'",
                Integer.class));
    }

    @Test
    void sameReportNoSameContentNotDuplicatedButDifferentContentConflicts() {
        setUpRuleAndShip();
        postExpectStatus("/api/flag/reports",
                reportBody("imp-a", "FR-2001", "IMO-RP", "SYN", COMPLETED,
                        List.of(reportItem("FLAG-HULL", "SATISFACTORY"))), 200);

        // 不同 requestId、同报告编号、同内容：不新增。
        JsonNode again = postExpectStatus("/api/flag/reports",
                reportBody("imp-b", "FR-2001", "IMO-RP", "SYN", COMPLETED,
                        List.of(reportItem("FLAG-HULL", "SATISFACTORY"))), 200);
        assertEquals(1, again.get("data").get("revision").asInt());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM flag_inspection_report WHERE report_no='FR-2001'",
                Integer.class));

        // 同报告编号、异内容：409 冲突。
        JsonNode conflict = postExpectStatus("/api/flag/reports",
                reportBody("imp-c", "FR-2001", "IMO-RP", "SYN",
                        COMPLETED.plusSeconds(3600),
                        List.of(reportItem("FLAG-HULL", "SATISFACTORY"))), 409);
        assertEquals("REPORT_CONTENT_CONFLICT", conflict.get("code").asText());
        JsonNode conflictItems = postExpectStatus("/api/flag/reports",
                reportBody("imp-d", "FR-2001", "IMO-RP", "SYN", COMPLETED,
                        List.of(reportItem("FLAG-HULL", "MINOR_DEFECT"))), 409);
        assertEquals("REPORT_CONTENT_CONFLICT", conflictItems.get("code").asText());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM flag_inspection_report WHERE report_no='FR-2001'",
                Integer.class));
    }

    @Test
    void importValidationFailures() {
        setUpRuleAndShip();
        // 非合成签认标记 → 422。
        JsonNode badSignoff = postExpectStatus("/api/flag/reports",
                Map.of("requestId", "imp-s1", "reportNo", "FR-3001", "imo", "IMO-RP",
                        "flagCode", "SYN", "completedAt", COMPLETED.toString(),
                        "syntheticSignoff", "REAL-AUTHORITY",
                        "items", List.of(reportItem("FLAG-HULL", "SATISFACTORY"))), 422);
        assertEquals("SYNTHETIC_SIGNOFF_REQUIRED", badSignoff.get("code").asText());

        // 船舶不存在 → 404。
        JsonNode noShip = postExpectStatus("/api/flag/reports",
                reportBody("imp-s2", "FR-3002", "IMO-UNKNOWN", "SYN", COMPLETED,
                        List.of(reportItem("FLAG-HULL", "SATISFACTORY"))), 404);
        assertEquals("SHIP_NOT_FOUND", noShip.get("code").asText());

        // 船旗无互认规则 → 422。
        JsonNode noFlag = postExpectStatus("/api/flag/reports",
                reportBody("imp-s3", "FR-3003", "IMO-RP", "XX", COMPLETED,
                        List.of(reportItem("FLAG-HULL", "SATISFACTORY"))), 422);
        assertEquals("UNKNOWN_FLAG_CODE", noFlag.get("code").asText());

        // 缺完成时刻 → 400；失败均不占键、不落库。
        JsonNode missing = postExpectStatus("/api/flag/reports",
                Map.of("requestId", "imp-s4", "reportNo", "FR-3004", "imo", "IMO-RP",
                        "flagCode", "SYN", "syntheticSignoff", "SYNTHETIC-SIGNOFF",
                        "items", List.of(reportItem("FLAG-HULL", "SATISFACTORY"))), 400);
        assertEquals("VALIDATION_FAILED", missing.get("code").asText());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM flag_inspection_report", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_record WHERE request_id LIKE 'imp-s%'",
                Integer.class));
    }

    @Test
    void correctionCreatesNewRevisionAndRevisionChainQueryable() {
        setUpRuleAndShip();
        postExpectStatus("/api/flag/reports",
                reportBody("imp-c1", "FR-4001", "IMO-RP", "SYN", COMPLETED,
                        List.of(reportItem("FLAG-HULL", "SATISFACTORY"))), 200);

        // 修订号不匹配 → 409。
        JsonNode stale = postExpectStatus("/api/flag/reports/FR-4001/corrections",
                correctBody("cor-x", 2, COMPLETED,
                        List.of(reportItem("FLAG-HULL", "SATISFACTORY"))), 409);
        assertEquals("REPORT_REVISION_CONFLICT", stale.get("code").asText());

        JsonNode corrected = postExpectStatus("/api/flag/reports/FR-4001/corrections",
                correctBody("cor-1", 1, COMPLETED.plusSeconds(7200),
                        List.of(reportItem("FLAG-HULL", "MINOR_DEFECT"))), 200);
        assertEquals(2, corrected.get("data").get("revision").asInt());
        assertEquals("MINOR_DEFECT",
                corrected.get("data").get("items").get(0).get("conclusion").asText());

        // 更正同键重放。
        JsonNode replay = postExpectStatus("/api/flag/reports/FR-4001/corrections",
                correctBody("cor-1", 1, COMPLETED.plusSeconds(7200),
                        List.of(reportItem("FLAG-HULL", "MINOR_DEFECT"))), 200);
        assertTrue(replay.get("replayed").asBoolean());

        // 修订链查询：两个修订都在，旧修订保留。
        JsonNode revisions = get("/api/flag/reports/FR-4001/revisions");
        assertEquals(2, revisions.size());
        assertEquals(1, revisions.get(0).get("revision").asInt());
        assertEquals("SATISFACTORY",
                revisions.get(0).get("items").get(0).get("conclusion").asText());
        assertEquals(2, revisions.get(1).get("revision").asInt());

        // 不存在报告的更正与查询 → 404。
        JsonNode noReport = postExpectStatus("/api/flag/reports/FR-9999/corrections",
                correctBody("cor-9", 1, COMPLETED,
                        List.of(reportItem("FLAG-HULL", "SATISFACTORY"))), 404);
        assertEquals("REPORT_NOT_FOUND", noReport.get("code").asText());
    }
}
