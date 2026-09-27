package com.example.starter.survey.it;

import com.example.starter.survey.testsupport.AbstractH2IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 船旗国检查证据限定范围互认：主流程与失败分支（H2 实际库验证）。
 * 覆盖：规则初始化、报告导入（重复/冲突/签认）、互认申请全部拒绝分支、
 * 报告更正（版本、失效、风险）、证据链查询与发证门禁集成。
 */
class FlagRecognitionFlowTest extends AbstractH2IntegrationTest {

    private static final LocalDate ANNIVERSARY = LocalDate.of(2020, 6, 15);
    private static final LocalDate DELIVERY = LocalDate.of(2020, 3, 1);
    private static final String ATTEST = "SYNTHETIC-ATTEST";
    private static final String FLAG = "SYN-FLAG";
    private static final String COMPLETED_AT = "2026-06-10T00:00:00Z";

    // ---- 请求体构造 ----

    private Map<String, Object> importBody(String requestId, String reportNo, String imo,
                                           String completedAt,
                                           List<Map<String, Object>> items) {
        return Map.of("requestId", requestId, "reportNo", reportNo, "shipImo", imo,
                "flagCode", FLAG, "completedAt", completedAt,
                "attestation", ATTEST, "items", items);
    }

    private Map<String, Object> item(String code, String conclusion) {
        return Map.of("itemCode", code, "conclusion", conclusion);
    }

    private Map<String, Object> correctBody(String requestId, String reportNo, int expectedVersion,
                                            String completedAt, List<Map<String, Object>> items) {
        return Map.of("requestId", requestId, "reportNo", reportNo,
                "expectedVersion", expectedVersion, "completedAt", completedAt,
                "attestation", ATTEST, "items", items);
    }

    private Map<String, Object> recognitionBody(String requestId, String reportNo,
                                                long surveyId, String itemCode) {
        return Map.of("requestId", requestId, "reportNo", reportNo,
                "surveyId", surveyId, "ruleVersion", 1, "itemCode", itemCode);
    }

    private long registerAndApply(String suffix, String imo) {
        postExpectStatus("/api/ships", shipBody("ship-" + suffix, imo, ANNIVERSARY, DELIVERY), 200);
        JsonNode applied = postExpectStatus("/api/surveys",
                applyBody("apply-" + suffix, imo, "ANNUAL"), 200);
        return applied.get("data").get("id").asLong();
    }

    private void importHullPass(String requestId, String reportNo, String imo) {
        postExpectStatus("/api/flag/reports",
                importBody(requestId, reportNo, imo, COMPLETED_AT,
                        List.of(item("FLAG-HULL", "PASS"))), 200);
    }

    // ---- 规则初始化 ----

    @Test
    void syntheticRuleVersionInitialized() {
        JsonNode rules = get("/api/flag/rules");
        assertEquals(1, rules.size(), "初始化恰好一版合成规则");
        JsonNode rule = rules.get(0);
        assertEquals(1, rule.get("version").asInt());
        assertEquals(FLAG, rule.get("flagCode").asText());
        assertEquals(90, rule.get("maxEvidenceAgeDays").asInt());
        assertEquals(3, rule.get("items").size());
        JsonNode byVersion = get("/api/flag/rules/1");
        assertEquals(FLAG, byVersion.get("flagCode").asText());
        ResponseEntity<String> missing = rest.getForEntity(url("/api/flag/rules/99"), String.class);
        assertEquals(404, missing.getStatusCode().value(), "未知规则版本 404");
    }

    // ---- 主流程：导入 → 互认 → 登轮 → 发证 → 证据链 ----

    @Test
    void importRecognizeBoardCertifyMainFlow() {
        setClockDate(LocalDate.of(2026, 6, 15));
        long surveyId = registerAndApply("m1", "IMO-FM1");
        importHullPass("imp-m1", "FR-M1", "IMO-FM1");

        JsonNode recognition = postExpectStatus("/api/flag/recognitions",
                recognitionBody("rec-m1", "FR-M1", surveyId, "FLAG-HULL"), 200);
        JsonNode recData = recognition.get("data");
        assertFalse(recognition.get("replayed").asBoolean());
        assertEquals("ACTIVE", recData.get("status").asText());
        assertEquals(1, recData.get("revision").asInt(), "冻结当前报告修订");
        assertEquals(1, recData.get("ruleVersion").asInt(), "冻结规则版本");
        assertEquals("FR-M1", recData.get("reportNo").asText());

        postExpectStatus("/api/surveys/board",
                boardBody("bd-m1", surveyId, LocalDate.of(2026, 6, 15), List.of()), 200);
        postExpectStatus("/api/certificates/certify", certifyBody("cert-m1", surveyId), 200);

        JsonNode chain = get("/api/flag/surveys/" + surveyId + "/evidence-chain");
        assertEquals("CERTIFIED", chain.get("survey").get("status").asText());
        assertEquals(1, chain.get("recognitions").size());
        assertEquals("ACTIVE", chain.get("recognitions").get(0).get("status").asText());
        assertEquals(0, chain.get("riskNotes").size());

        JsonNode report = get("/api/flag/reports/FR-M1");
        assertEquals(1, report.get("currentRevision").asInt());
        assertEquals("IMO-FM1", report.get("shipImo").asText());
        assertEquals(COMPLETED_AT, report.get("revision").get("completedAt").asText());
    }

    // ---- 报告导入：重复 / 冲突 / 签认 / 幂等 ----

    @Test
    void duplicateImportSameContentDoesNotCreateNew() {
        importHullPass("imp-d1", "FR-D1", "IMO-FD1");
        JsonNode again = postExpectStatus("/api/flag/reports",
                importBody("imp-d2", "FR-D1", "IMO-FD1", COMPLETED_AT,
                        List.of(item("FLAG-HULL", "PASS"))), 200);
        assertTrue(again.get("data").get("existing").asBoolean(), "同参重复导入不新增");
        Integer reports = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flag_report WHERE report_no='FR-D1'", Integer.class);
        Integer revisions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flag_report_revision r JOIN flag_report p ON r.report_id=p.id"
                        + " WHERE p.report_no='FR-D1'", Integer.class);
        assertEquals(1, reports);
        assertEquals(1, revisions, "重复导入不产生新修订");
    }

    @Test
    void importSameReportNoDifferentContentConflict() {
        importHullPass("imp-c1", "FR-C1", "IMO-FC1");
        JsonNode conflict = postExpectStatus("/api/flag/reports",
                importBody("imp-c2", "FR-C1", "IMO-FC1", "2026-06-11T00:00:00Z",
                        List.of(item("FLAG-HULL", "PASS"))), 409);
        assertEquals("REPORT_CONTENT_CONFLICT", conflict.get("code").asText());
    }

    @Test
    void importWithNonSyntheticAttestationRejected() {
        Map<String, Object> body = Map.of("requestId", "imp-a1", "reportNo", "FR-A1",
                "shipImo", "IMO-FA1", "flagCode", FLAG, "completedAt", COMPLETED_AT,
                "attestation", "REAL-AUTHORITY-SEAL", "items", List.of(item("FLAG-HULL", "PASS")));
        JsonNode rejected = postExpectStatus("/api/flag/reports", body, 422);
        assertEquals("SYNTHETIC_ATTESTATION_REQUIRED", rejected.get("code").asText());
        Integer reports = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flag_report WHERE report_no='FR-A1'", Integer.class);
        assertEquals(0, reports, "业务拒绝不占键不落库");
    }

    @Test
    void importSameRequestIdReplaysAndDifferentParamsConflict() {
        Map<String, Object> body = importBody("imp-r1", "FR-R1", "IMO-FR1", COMPLETED_AT,
                List.of(item("FLAG-HULL", "PASS")));
        JsonNode first = postExpectStatus("/api/flag/reports", body, 200);
        assertFalse(first.get("replayed").asBoolean());
        JsonNode replay = postExpectStatus("/api/flag/reports", body, 200);
        assertTrue(replay.get("replayed").asBoolean(), "同键同参重放首次结果");
        assertEquals(first.get("data").get("id").asLong(), replay.get("data").get("id").asLong());

        Map<String, Object> different = importBody("imp-r1", "FR-R2", "IMO-FR1", COMPLETED_AT,
                List.of(item("FLAG-HULL", "PASS")));
        JsonNode conflict = postExpectStatus("/api/flag/reports", different, 409);
        assertEquals("IDEMPOTENCY_FINGERPRINT_MISMATCH", conflict.get("code").asText());
    }

    // ---- 互认申请：全部拒绝分支 ----

    @Test
    void crossShipRecognitionRejected() {
        setClockDate(LocalDate.of(2026, 6, 15));
        long surveyId = registerAndApply("x1", "IMO-FX1");
        importHullPass("imp-x1", "FR-X1", "IMO-OTHER-SHIP");
        JsonNode rejected = postExpectStatus("/api/flag/recognitions",
                recognitionBody("rec-x1", "FR-X1", surveyId, "FLAG-HULL"), 422);
        assertEquals("CROSS_SHIP_EVIDENCE", rejected.get("code").asText());
    }

    @Test
    void unknownMappingRejected() {
        setClockDate(LocalDate.of(2026, 6, 15));
        long surveyId = registerAndApply("u1", "IMO-FU1");
        postExpectStatus("/api/flag/reports",
                importBody("imp-u1", "FR-U1", "IMO-FU1", COMPLETED_AT,
                        List.of(item("FLAG-HULL", "PASS"), item("FLAG-UNKNOWN", "PASS"),
                                item("FLAG-MACH", "PASS"))), 200);
        JsonNode unmapped = postExpectStatus("/api/flag/recognitions",
                recognitionBody("rec-u1", "FR-U1", surveyId, "FLAG-UNKNOWN"), 422);
        assertEquals("UNKNOWN_MAPPING", unmapped.get("code").asText());
        // FLAG-MACH 映射到 INTERMEDIATE，与 ANNUAL 检验类型不符
        JsonNode mismatched = postExpectStatus("/api/flag/recognitions",
                recognitionBody("rec-u2", "FR-U1", surveyId, "FLAG-MACH"), 422);
        assertEquals("UNKNOWN_MAPPING", mismatched.get("code").asText());
    }

    @Test
    void missingEvidenceItemRejected() {
        setClockDate(LocalDate.of(2026, 6, 15));
        long surveyId = registerAndApply("e1", "IMO-FE1");
        // 报告只有 FLAG-MACH 结论，缺少映射到 ANNUAL 的 FLAG-HULL 项
        postExpectStatus("/api/flag/reports",
                importBody("imp-e1", "FR-E1", "IMO-FE1", COMPLETED_AT,
                        List.of(item("FLAG-MACH", "PASS"))), 200);
        JsonNode rejected = postExpectStatus("/api/flag/recognitions",
                recognitionBody("rec-e1", "FR-E1", surveyId, "FLAG-HULL"), 422);
        assertEquals("EVIDENCE_ITEM_MISSING", rejected.get("code").asText(),
                "映射存在但报告缺该项结论：证据缺项");
    }

    @Test
    void majorDefectOrFailedItemNotQualified() {
        setClockDate(LocalDate.of(2026, 6, 15));
        long surveyId = registerAndApply("q1", "IMO-FQ1");
        postExpectStatus("/api/flag/reports",
                importBody("imp-q1", "FR-Q1", "IMO-FQ1", COMPLETED_AT,
                        List.of(item("FLAG-HULL", "MAJOR_DEFECT"))), 200);
        JsonNode major = postExpectStatus("/api/flag/recognitions",
                recognitionBody("rec-q1", "FR-Q1", surveyId, "FLAG-HULL"), 422);
        assertEquals("EVIDENCE_NOT_QUALIFIED", major.get("code").asText(),
                "有重大缺陷的报告不得当合格项目");

        postExpectStatus("/api/flag/reports",
                importBody("imp-q2", "FR-Q2", "IMO-FQ1", COMPLETED_AT,
                        List.of(item("FLAG-HULL", "FAIL"))), 200);
        JsonNode failed = postExpectStatus("/api/flag/recognitions",
                recognitionBody("rec-q2", "FR-Q2", surveyId, "FLAG-HULL"), 422);
        assertEquals("EVIDENCE_NOT_QUALIFIED", failed.get("code").asText());
    }

    @Test
    void evidenceExactlyAtDeadlineAllowedAndAfterDeadlineRejected() {
        // 完成时刻 2026-06-10T00:00Z + 90 天 = 2026-09-08T00:00Z（恰到截止允许）
        long surveyId = registerAndApply("t1", "IMO-FT1");
        importHullPass("imp-t1", "FR-T1", "IMO-FT1");
        setClockDate(LocalDate.of(2026, 9, 8));
        JsonNode ok = postExpectStatus("/api/flag/recognitions",
                recognitionBody("rec-t1", "FR-T1", surveyId, "FLAG-HULL"), 200);
        assertEquals("ACTIVE", ok.get("data").get("status").asText());

        long surveyId2 = registerAndApply("t2", "IMO-FT2");
        importHullPass("imp-t2", "FR-T2", "IMO-FT2");
        setClockDate(LocalDate.of(2026, 9, 9));
        JsonNode expired = postExpectStatus("/api/flag/recognitions",
                recognitionBody("rec-t2", "FR-T2", surveyId2, "FLAG-HULL"), 422);
        assertEquals("EVIDENCE_EXPIRED", expired.get("code").asText());
    }

    @Test
    void duplicateRecognitionOnSameItemConflict() {
        setClockDate(LocalDate.of(2026, 6, 15));
        long surveyId = registerAndApply("d1", "IMO-FDUP");
        importHullPass("imp-d1", "FR-DUP", "IMO-FDUP");
        postExpectStatus("/api/flag/recognitions",
                recognitionBody("rec-d1", "FR-DUP", surveyId, "FLAG-HULL"), 200);
        JsonNode conflict = postExpectStatus("/api/flag/recognitions",
                recognitionBody("rec-d2", "FR-DUP", surveyId, "FLAG-HULL"), 409);
        assertEquals("RECOGNITION_EXISTS", conflict.get("code").asText(),
                "每个项目只一个有效绑定");
    }

    @Test
    void recognitionOnCertifiedSurveyRejected() {
        setClockDate(LocalDate.of(2026, 6, 15));
        long surveyId = registerAndApply("s1", "IMO-FS1");
        postExpectStatus("/api/surveys/board",
                boardBody("bd-s1", surveyId, LocalDate.of(2026, 6, 15), List.of()), 200);
        postExpectStatus("/api/certificates/certify", certifyBody("cert-s1", surveyId), 200);
        importHullPass("imp-s1", "FR-S1", "IMO-FS1");
        JsonNode rejected = postExpectStatus("/api/flag/recognitions",
                recognitionBody("rec-s1", "FR-S1", surveyId, "FLAG-HULL"), 422);
        assertEquals("SURVEY_ALREADY_CERTIFIED", rejected.get("code").asText());
    }

    @Test
    void recognitionReferencesNotFound() {
        setClockDate(LocalDate.of(2026, 6, 15));
        long surveyId = registerAndApply("n1", "IMO-FN1");
        importHullPass("imp-n1", "FR-N1", "IMO-FN1");
        postExpectStatus("/api/flag/recognitions",
                recognitionBody("rec-n1", "FR-MISSING", surveyId, "FLAG-HULL"), 404);
        postExpectStatus("/api/flag/recognitions",
                recognitionBody("rec-n2", "FR-N1", 999999L, "FLAG-HULL"), 404);
        JsonNode ruleMissing = postExpectStatus("/api/flag/recognitions",
                Map.of("requestId", "rec-n3", "reportNo", "FR-N1", "surveyId", surveyId,
                        "ruleVersion", 99, "itemCode", "FLAG-HULL"), 404);
        assertEquals("RULE_NOT_FOUND", ruleMissing.get("code").asText());
    }

    @Test
    void recognitionValidationFailures() {
        postExpectStatus("/api/flag/recognitions",
                Map.of("reportNo", "FR-V1", "surveyId", 1, "ruleVersion", 1,
                        "itemCode", "FLAG-HULL"), 400);
        postExpectStatus("/api/flag/reports",
                Map.of("requestId", "imp-v1", "reportNo", "FR-V1"), 400);
    }

    // ---- 报告更正 ----

    @Test
    void correctGeneratesNewRevisionAndInvalidatesOpenSurveyRecognition() {
        setClockDate(LocalDate.of(2026, 6, 15));
        long surveyId = registerAndApply("k1", "IMO-FK1");
        importHullPass("imp-k1", "FR-K1", "IMO-FK1");
        postExpectStatus("/api/flag/recognitions",
                recognitionBody("rec-k1", "FR-K1", surveyId, "FLAG-HULL"), 200);

        JsonNode corrected = postExpectStatus("/api/flag/reports/correct",
                correctBody("cor-k1", "FR-K1", 1, "2026-06-12T00:00:00Z",
                        List.of(item("FLAG-HULL", "PASS"))), 200);
        assertEquals(2, corrected.get("data").get("currentRevision").asInt(),
                "更正成功版本加 1");

        JsonNode revisions = get("/api/flag/reports/FR-K1/revisions");
        assertEquals(2, revisions.size(), "更正生成新修订，历史保留");

        JsonNode chain = get("/api/flag/surveys/" + surveyId + "/evidence-chain");
        JsonNode rec = chain.get("recognitions").get(0);
        assertEquals("INVALIDATED", rec.get("status").asText(),
                "未签发检验的旧互认原子失效");
        assertFalse(rec.get("invalidatedAt").isNull());
        assertEquals(0, chain.get("riskNotes").size());

        // 失效后同项目可重新绑定到新修订
        JsonNode rebound = postExpectStatus("/api/flag/recognitions",
                recognitionBody("rec-k2", "FR-K1", surveyId, "FLAG-HULL"), 200);
        assertEquals(2, rebound.get("data").get("revision").asInt());
    }

    @Test
    void correctWithWrongExpectedVersionConflict() {
        importHullPass("imp-w1", "FR-W1", "IMO-FW1");
        JsonNode conflict = postExpectStatus("/api/flag/reports/correct",
                correctBody("cor-w1", "FR-W1", 5, "2026-06-12T00:00:00Z",
                        List.of(item("FLAG-HULL", "PASS"))), 409);
        assertEquals("VERSION_CONFLICT", conflict.get("code").asText());
        JsonNode report = get("/api/flag/reports/FR-W1");
        assertEquals(1, report.get("currentRevision").asInt(), "版本冲突不产生新修订");
    }

    @Test
    void correctOnCertifiedSurveyKeepsRecognitionAndRecordsRisk() {
        setClockDate(LocalDate.of(2026, 6, 15));
        long surveyId = registerAndApply("r1", "IMO-FRISK");
        importHullPass("imp-r1", "FR-RISK", "IMO-FRISK");
        postExpectStatus("/api/flag/recognitions",
                recognitionBody("rec-r1", "FR-RISK", surveyId, "FLAG-HULL"), 200);
        postExpectStatus("/api/surveys/board",
                boardBody("bd-r1", surveyId, LocalDate.of(2026, 6, 15), List.of()), 200);
        postExpectStatus("/api/certificates/certify", certifyBody("cert-r1", surveyId), 200);

        postExpectStatus("/api/flag/reports/correct",
                correctBody("cor-r1", "FR-RISK", 1, "2026-06-12T00:00:00Z",
                        List.of(item("FLAG-HULL", "PASS"))), 200);

        JsonNode chain = get("/api/flag/surveys/" + surveyId + "/evidence-chain");
        JsonNode rec = chain.get("recognitions").get(0);
        assertEquals("ACTIVE", rec.get("status").asText(), "已签发历史证据不改动");
        assertEquals(1, chain.get("riskNotes").size(), "另记风险");
        JsonNode risk = chain.get("riskNotes").get(0);
        assertEquals(1, risk.get("oldRevision").asInt());
        assertEquals(2, risk.get("newRevision").asInt());
    }

    @Test
    void correctedReportRecognitionInvalidationDoesNotBlockCertify() {
        // 互认只替代完成证明，不跳过签发门禁；失效后检验按原流程发证。
        setClockDate(LocalDate.of(2026, 6, 15));
        long surveyId = registerAndApply("g1", "IMO-FG1");
        importHullPass("imp-g1", "FR-G1", "IMO-FG1");
        postExpectStatus("/api/flag/recognitions",
                recognitionBody("rec-g1", "FR-G1", surveyId, "FLAG-HULL"), 200);
        postExpectStatus("/api/surveys/board",
                boardBody("bd-g1", surveyId, LocalDate.of(2026, 6, 15), List.of()), 200);
        postExpectStatus("/api/flag/reports/correct",
                correctBody("cor-g1", "FR-G1", 1, "2026-06-12T00:00:00Z",
                        List.of(item("FLAG-HULL", "PASS"))), 200);
        postExpectStatus("/api/certificates/certify", certifyBody("cert-g1", surveyId), 200);
        JsonNode chain = get("/api/flag/surveys/" + surveyId + "/evidence-chain");
        assertEquals("CERTIFIED", chain.get("survey").get("status").asText());
        assertEquals("INVALIDATED", chain.get("recognitions").get(0).get("status").asText());
    }

    @Test
    void correctIdempotencyReplayAndConflict() {
        importHullPass("imp-i1", "FR-I1", "IMO-FI1");
        Map<String, Object> body = correctBody("cor-i1", "FR-I1", 1, "2026-06-12T00:00:00Z",
                List.of(item("FLAG-HULL", "PASS")));
        JsonNode first = postExpectStatus("/api/flag/reports/correct", body, 200);
        assertFalse(first.get("replayed").asBoolean());
        JsonNode replay = postExpectStatus("/api/flag/reports/correct", body, 200);
        assertTrue(replay.get("replayed").asBoolean(), "同键同参重放首次冻结结果");
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM flag_report_revision r JOIN flag_report p ON r.report_id=p.id"
                        + " WHERE p.report_no='FR-I1'", Integer.class).intValue(),
                "重放不产生额外修订");

        Map<String, Object> different = correctBody("cor-i1", "FR-I1", 1, "2026-06-13T00:00:00Z",
                List.of(item("FLAG-HULL", "PASS")));
        JsonNode conflict = postExpectStatus("/api/flag/reports/correct", different, 409);
        assertEquals("IDEMPOTENCY_FINGERPRINT_MISMATCH", conflict.get("code").asText());
    }
}
