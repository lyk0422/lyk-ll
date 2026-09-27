package com.example.starter.survey.it;

import com.example.starter.survey.testsupport.AbstractH2IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 限定范围互认主流程与业务拒绝分支：
 * 跨船、未知映射、证据缺项、重大缺陷项目、证据过期（恰到截止允许）、
 * 每项目唯一有效绑定、未签发检验限制；更正失效与发证门禁联动。
 */
class RecognitionFlowTest extends AbstractH2IntegrationTest {

    private static final LocalDate ANNIVERSARY = LocalDate.of(2020, 6, 15);
    private static final LocalDate DELIVERY = LocalDate.of(2020, 3, 1);
    /** 默认报告完成时刻：2026-06-01，相对默认时钟 2026-06-15 在 90 天有效期内。 */
    private static final Instant COMPLETED =
            LocalDate.of(2026, 6, 1).atStartOfDay(ZoneOffset.UTC).toInstant();

    private void setUpRule() {
        postExpectStatus("/api/flag/rules",
                ruleBody("rule-rc", "SYN", 0, 90,
                        List.of(mapping("FLAG-HULL", "CLASS-HULL"),
                                mapping("FLAG-SAFETY", "CLASS-SAFETY"))), 200);
    }

    private void registerShip(String reqId, String imo) {
        postExpectStatus("/api/ships", shipBody(reqId, imo, ANNIVERSARY, DELIVERY), 200);
    }

    private long applyAndBoard(String reqPrefix, String imo) {
        JsonNode applied = postExpectStatus("/api/surveys",
                applyBody(reqPrefix + "-ap", imo, "ANNUAL"), 200);
        long surveyId = applied.get("data").get("id").asLong();
        postExpectStatus("/api/surveys/board",
                boardBody(reqPrefix + "-bd", surveyId, LocalDate.of(2026, 6, 15), List.of()),
                200);
        return surveyId;
    }

    private void importReport(String reqId, String reportNo, String imo, Instant completedAt,
                              List<java.util.Map<String, String>> items) {
        postExpectStatus("/api/flag/reports",
                reportBody(reqId, reportNo, imo, "SYN", completedAt, items), 200);
    }

    @Test
    void applyRecognitionMainFlowAndQueries() {
        setUpRule();
        registerShip("ship-m", "IMO-M");
        long surveyId = applyAndBoard("m", "IMO-M");
        importReport("imp-m", "FR-M1", "IMO-M", COMPLETED,
                List.of(reportItem("FLAG-HULL", "SATISFACTORY"),
                        reportItem("FLAG-SAFETY", "SATISFACTORY")));

        JsonNode applied = postExpectStatus("/api/flag/recognitions",
                recognitionBody("rec-m", "FR-M1", surveyId, 1,
                        List.of("CLASS-HULL", "CLASS-SAFETY")), 200);
        assertEquals(2, applied.get("data").get("bindings").size());
        JsonNode first = applied.get("data").get("bindings").get(0);
        assertEquals("ACTIVE", first.get("status").asText());
        assertEquals(1, first.get("reportRevision").asInt());
        assertEquals(1, first.get("ruleVersion").asInt());

        // 互认绑定查询。
        JsonNode bindings = get("/api/surveys/" + surveyId + "/recognitions");
        assertEquals(2, bindings.size());

        // 证据链查询：绑定 + 冻结报告修订（含逐项结论）+ 冻结规则版本（含映射）。
        JsonNode chain = get("/api/surveys/" + surveyId + "/evidence-chain");
        assertEquals(2, chain.get("entries").size());
        JsonNode entry = chain.get("entries").get(0);
        assertEquals("FR-M1", entry.get("report").get("reportNo").asText());
        assertEquals(1, entry.get("report").get("revision").asInt());
        assertEquals(2, entry.get("report").get("items").size());
        assertEquals(1, entry.get("rule").get("version").asInt());
        assertEquals(2, entry.get("rule").get("mappings").size());
        assertEquals(0, chain.get("risks").size());

        // 同键同参重放；同键异参 409。
        JsonNode replay = postExpectStatus("/api/flag/recognitions",
                recognitionBody("rec-m", "FR-M1", surveyId, 1,
                        List.of("CLASS-HULL", "CLASS-SAFETY")), 200);
        assertTrue(replay.get("replayed").asBoolean());
        JsonNode conflict = postExpectStatus("/api/flag/recognitions",
                recognitionBody("rec-m", "FR-M1", surveyId, 1, List.of("CLASS-HULL")), 409);
        assertEquals("IDEMPOTENCY_FINGERPRINT_MISMATCH", conflict.get("code").asText());
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM recognition_binding WHERE survey_id=? AND active_flag='Y'",
                Integer.class, surveyId));
    }

    @Test
    void crossShipReportRejected() {
        setUpRule();
        registerShip("ship-x1", "IMO-X1");
        registerShip("ship-x2", "IMO-X2");
        long surveyId = applyAndBoard("x", "IMO-X1");
        importReport("imp-x", "FR-X1", "IMO-X2", COMPLETED,
                List.of(reportItem("FLAG-HULL", "SATISFACTORY")));

        JsonNode rejected = postExpectStatus("/api/flag/recognitions",
                recognitionBody("rec-x", "FR-X1", surveyId, 1, List.of("CLASS-HULL")), 422);
        assertEquals("CROSS_SHIP_REPORT", rejected.get("code").asText());
    }

    @Test
    void unknownMappingMissingEvidenceAndUnqualifiedItemRejected() {
        setUpRule();
        registerShip("ship-u", "IMO-U");
        long surveyId = applyAndBoard("u", "IMO-U");
        importReport("imp-u1", "FR-U1", "IMO-U", COMPLETED,
                List.of(reportItem("FLAG-HULL", "SATISFACTORY"),
                        reportItem("FLAG-SAFETY", "MAJOR_DEFECT")));

        // 未知映射（规则外的船级项目）→ 422。
        JsonNode unknown = postExpectStatus("/api/flag/recognitions",
                recognitionBody("rec-u1", "FR-U1", surveyId, 1, List.of("CLASS-UNKNOWN")), 422);
        assertEquals("UNKNOWN_ITEM_MAPPING", unknown.get("code").asText());

        // 证据缺项：报告缺少 FLAG-SAFETY 之外的结论项 → 用只有部分项目的报告验证。
        importReport("imp-u2", "FR-U2", "IMO-U", COMPLETED,
                List.of(reportItem("FLAG-HULL", "SATISFACTORY")));
        JsonNode missing = postExpectStatus("/api/flag/recognitions",
                recognitionBody("rec-u2", "FR-U2", surveyId, 1, List.of("CLASS-SAFETY")), 422);
        assertEquals("EVIDENCE_ITEM_MISSING", missing.get("code").asText());

        // 重大缺陷项目不得充当合格项目 → 422。
        JsonNode major = postExpectStatus("/api/flag/recognitions",
                recognitionBody("rec-u3", "FR-U1", surveyId, 1, List.of("CLASS-SAFETY")), 422);
        assertEquals("EVIDENCE_NOT_QUALIFIED", major.get("code").asText());

        // 失败不占键：修正后用同 requestId 重新申请成功。
        postExpectStatus("/api/flag/recognitions",
                recognitionBody("rec-u3", "FR-U1", surveyId, 1, List.of("CLASS-HULL")), 200);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM recognition_binding WHERE survey_id=? AND active_flag='Y'",
                Integer.class, surveyId));
    }

    @Test
    void evidenceExpiryBoundaryExactlyAtDeadlineAllowed() {
        setUpRule();
        registerShip("ship-e", "IMO-E");
        long surveyId = applyAndBoard("e", "IMO-E");
        // 完成时刻 2026-03-17T00:00Z + 90 天 = 2026-06-15T00:00Z，恰为当前时钟 → 允许。
        Instant boundary = LocalDate.of(2026, 3, 17).atStartOfDay(ZoneOffset.UTC).toInstant();
        importReport("imp-e1", "FR-E1", "IMO-E", boundary,
                List.of(reportItem("FLAG-HULL", "SATISFACTORY")));
        postExpectStatus("/api/flag/recognitions",
                recognitionBody("rec-e1", "FR-E1", surveyId, 1, List.of("CLASS-HULL")), 200);

        // 完成时刻早一小时：截止 2026-06-14T23:00Z < 当前 → 拒绝。
        Instant expired = LocalDate.of(2026, 3, 16).atStartOfDay(ZoneOffset.UTC)
                .toInstant().plusSeconds(23 * 3600);
        importReport("imp-e2", "FR-E2", "IMO-E", expired,
                List.of(reportItem("FLAG-SAFETY", "SATISFACTORY")));
        JsonNode rejected = postExpectStatus("/api/flag/recognitions",
                recognitionBody("rec-e2", "FR-E2", surveyId, 1, List.of("CLASS-SAFETY")), 422);
        assertEquals("EVIDENCE_EXPIRED", rejected.get("code").asText());
    }

    @Test
    void duplicateActiveBindingRejected() {
        setUpRule();
        registerShip("ship-d", "IMO-D");
        long surveyId = applyAndBoard("d", "IMO-D");
        importReport("imp-d", "FR-D1", "IMO-D", COMPLETED,
                List.of(reportItem("FLAG-HULL", "SATISFACTORY")));
        postExpectStatus("/api/flag/recognitions",
                recognitionBody("rec-d1", "FR-D1", surveyId, 1, List.of("CLASS-HULL")), 200);

        // 每项目只一个有效绑定：同项目再次申请（新 requestId）→ 422。
        JsonNode dup = postExpectStatus("/api/flag/recognitions",
                recognitionBody("rec-d2", "FR-D1", surveyId, 1, List.of("CLASS-HULL")), 422);
        assertEquals("BINDING_ALREADY_ACTIVE", dup.get("code").asText());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM recognition_binding WHERE survey_id=? AND active_flag='Y'",
                Integer.class, surveyId));
    }

    @Test
    void certifiedSurveyCannotApplyRecognition() {
        setUpRule();
        registerShip("ship-c", "IMO-C");
        long surveyId = applyAndBoard("c", "IMO-C");
        importReport("imp-c", "FR-C1", "IMO-C", COMPLETED,
                List.of(reportItem("FLAG-HULL", "SATISFACTORY")));
        postExpectStatus("/api/certificates/certify", certifyBody("cert-c", surveyId), 200);

        JsonNode rejected = postExpectStatus("/api/flag/recognitions",
                recognitionBody("rec-c", "FR-C1", surveyId, 1, List.of("CLASS-HULL")), 422);
        assertEquals("SURVEY_ALREADY_CERTIFIED", rejected.get("code").asText());
    }

    @Test
    void recognitionDoesNotSkipCertificateGates() {
        setUpRule();
        registerShip("ship-g", "IMO-G");
        // 未登轮检验：互认不跳过登轮门禁。
        JsonNode applied = postExpectStatus("/api/surveys",
                applyBody("g-ap", "IMO-G", "ANNUAL"), 200);
        long surveyId = applied.get("data").get("id").asLong();
        importReport("imp-g", "FR-G1", "IMO-G", COMPLETED,
                List.of(reportItem("FLAG-HULL", "SATISFACTORY")));
        postExpectStatus("/api/flag/recognitions",
                recognitionBody("rec-g", "FR-G1", surveyId, 1, List.of("CLASS-HULL")), 200);
        JsonNode notBoarded = postExpectStatus("/api/certificates/certify",
                certifyBody("cert-g1", surveyId), 409);
        assertEquals("SURVEY_NOT_BOARDED", notBoarded.get("code").asText());

        // 登轮记录重大缺陷：互认不跳过重大缺陷整改门禁。
        postExpectStatus("/api/surveys/board",
                boardBody("g-bd", surveyId, LocalDate.of(2026, 6, 15),
                        List.of(defectInput("MAJOR", "舵机故障", LocalDate.of(2026, 6, 15)))),
                200);
        JsonNode majorOpen = postExpectStatus("/api/certificates/certify",
                certifyBody("cert-g2", surveyId), 422);
        assertEquals("MAJOR_DEFECT_OPEN", majorOpen.get("code").asText());
    }

    @Test
    void correctionInvalidatesBindingAndBlocksCertifyUntilRebound() {
        setUpRule();
        registerShip("ship-i", "IMO-I");
        long surveyId = applyAndBoard("i", "IMO-I");
        importReport("imp-i", "FR-I1", "IMO-I", COMPLETED,
                List.of(reportItem("FLAG-HULL", "SATISFACTORY")));
        postExpectStatus("/api/flag/recognitions",
                recognitionBody("rec-i1", "FR-I1", surveyId, 1, List.of("CLASS-HULL")), 200);

        // 报告更正：生成修订 2，未签发检验的旧互认同事务失效。
        postExpectStatus("/api/flag/reports/FR-I1/corrections",
                correctBody("cor-i", 1, COMPLETED,
                        List.of(reportItem("FLAG-HULL", "SATISFACTORY"))), 200);
        JsonNode bindings = get("/api/surveys/" + surveyId + "/recognitions");
        assertEquals("INVALIDATED", bindings.get(0).get("status").asText());

        // 发证门禁：存在未替代的失效互认 → 422。
        JsonNode blocked = postExpectStatus("/api/certificates/certify",
                certifyBody("cert-i1", surveyId), 422);
        assertEquals("RECOGNITION_INVALIDATED", blocked.get("code").asText());

        // 以新修订重新互认后发证成功。
        postExpectStatus("/api/flag/recognitions",
                recognitionBody("rec-i2", "FR-I1", surveyId, 1, List.of("CLASS-HULL")), 200);
        JsonNode chain = get("/api/surveys/" + surveyId + "/evidence-chain");
        assertEquals(2, chain.get("entries").size());
        JsonNode certified = postExpectStatus("/api/certificates/certify",
                certifyBody("cert-i2", surveyId), 200);
        assertEquals("CERTIFIED", certified.get("data").get("survey").get("status").asText());
    }

    @Test
    void correctionAfterCertifyKeepsHistoryAndRecordsRisk() {
        setUpRule();
        registerShip("ship-h", "IMO-H");
        long surveyId = applyAndBoard("h", "IMO-H");
        importReport("imp-h", "FR-H1", "IMO-H", COMPLETED,
                List.of(reportItem("FLAG-HULL", "SATISFACTORY")));
        postExpectStatus("/api/flag/recognitions",
                recognitionBody("rec-h", "FR-H1", surveyId, 1, List.of("CLASS-HULL")), 200);
        postExpectStatus("/api/certificates/certify", certifyBody("cert-h", surveyId), 200);

        // 已签发后更正：历史绑定不改动，另记风险。
        postExpectStatus("/api/flag/reports/FR-H1/corrections",
                correctBody("cor-h", 1, COMPLETED,
                        List.of(reportItem("FLAG-HULL", "MAJOR_DEFECT"))), 200);
        JsonNode chain = get("/api/surveys/" + surveyId + "/evidence-chain");
        assertEquals("ACTIVE", chain.get("entries").get(0).get("binding").get("status").asText(),
                "已签发历史证据不被改动");
        assertEquals(1, chain.get("entries").get(0).get("binding").get("reportRevision").asInt(),
                "绑定仍冻结在签发时的修订");
        assertEquals(1, chain.get("risks").size());
        assertEquals(2, chain.get("risks").get(0).get("newRevision").asInt());

        // 修订链：两个修订均可查。
        JsonNode revisions = get("/api/flag/reports/FR-H1/revisions");
        assertEquals(2, revisions.size());
    }
}
