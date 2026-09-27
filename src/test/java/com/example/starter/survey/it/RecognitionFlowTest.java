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
 * 船旗国检查报告互认主流程与失败分支（H2 真实库验证）：
 * 规则初始化、报告导入/更正、互认申请、发证门禁、证据链查询。
 */
class RecognitionFlowTest extends AbstractH2IntegrationTest {

    private static final LocalDate ANNIVERSARY = LocalDate.of(2020, 6, 15);
    private static final LocalDate DELIVERY = LocalDate.of(2020, 3, 1);
    private static final List<String> RULE_ITEMS =
            List.of("HULL_STRUCTURE", "MACHINERY", "SAFETY_EQUIPMENT");
    private static final Instant COMPLETED_AT =
            LocalDate.of(2026, 6, 10).atTime(10, 0).toInstant(ZoneOffset.UTC);

    private void createRule(String reqId, int version, int maxDays, List<String> items) {
        postExpectStatus("/api/recognition/rules",
                ruleBody(reqId, version, "SYN-FL", maxDays, items), 200);
    }

    private void importReport(String reqId, String reportNo, String imo,
                              Instant completedAt, List<String> conclusions) {
        List<java.util.Map<String, Object>> items = java.util.stream.IntStream
                .range(0, RULE_ITEMS.size())
                .mapToObj(i -> reportItem(RULE_ITEMS.get(i), conclusions.get(i)))
                .toList();
        postExpectStatus("/api/recognition/reports",
                importReportBody(reqId, reportNo, imo, "SYN-FL", completedAt, items), 200);
    }

    private long applySurvey(String reqId, String imo) {
        JsonNode applied = postExpectStatus("/api/surveys",
                applyBody(reqId, imo, "ANNUAL"), 200);
        return applied.get("data").get("id").asLong();
    }

    @Test
    void fullRecognitionFlowCertifiesWithMappedItemProof() {
        registerShipAndRule();
        importReport("imp-1", "RPT-001", "IMO-R1", COMPLETED_AT,
                List.of("PASS", "PASS", "PASS"));
        long surveyId = applySurvey("ap-1", "IMO-R1");
        // 登轮只完成两项，SAFETY_EQUIPMENT 由互认替代完成证明。
        postExpectStatus("/api/surveys/board",
                boardBody("bd-1", surveyId, LocalDate.of(2026, 6, 15), List.of(),
                        List.of("HULL_STRUCTURE", "MACHINERY")), 200);

        JsonNode recognized = postExpectStatus("/api/recognition/apply",
                applyRecognitionBody("rec-1", surveyId, "RPT-001", 1), 200);
        assertEquals(3, recognized.get("data").get("bindings").size());
        assertEquals(1, recognized.get("data").get("reportRevision").asInt());
        assertEquals(1, recognized.get("data").get("ruleVersion").asInt());

        JsonNode bindings = get("/api/recognition/bindings?surveyId=" + surveyId);
        assertEquals(3, bindings.size());
        bindings.forEach(b -> assertEquals("ACTIVE", b.get("status").asText()));

        JsonNode certified = postExpectStatus("/api/certificates/certify",
                certifyBody("cert-1", surveyId), 200);
        assertEquals("CERTIFIED", certified.get("data").get("survey").get("status").asText());
        assertEquals("VALID", certified.get("data").get("certificate").get("status").asText());
    }

    @Test
    void recognitionDoesNotSkipMajorDefectGate() {
        registerShipAndRule();
        importReport("imp-2", "RPT-002", "IMO-R1", COMPLETED_AT,
                List.of("PASS", "PASS", "PASS"));
        long surveyId = applySurvey("ap-2", "IMO-R1");
        // 登轮无项目完成（全部依赖互认），但记录重大缺陷。
        postExpectStatus("/api/surveys/board",
                boardBody("bd-2", surveyId, LocalDate.of(2026, 6, 15),
                        List.of(defectInput("MAJOR", "主机故障", LocalDate.of(2026, 6, 15))),
                        List.of()), 200);
        postExpectStatus("/api/recognition/apply",
                applyRecognitionBody("rec-2", surveyId, "RPT-002", 1), 200);
        // 互认不能跳过重大缺陷整改门禁。
        JsonNode rejected = postExpectStatus("/api/certificates/certify",
                certifyBody("cert-2", surveyId), 422);
        assertEquals("MAJOR_DEFECT_OPEN", rejected.get("code").asText());
    }

    @Test
    void missingItemCompletionProofBlocksCertify() {
        registerShipAndRule();
        long surveyId = applySurvey("ap-3", "IMO-R1");
        // 登轮只完成一项，无互认：缺少其余必需项目的完成证明。
        postExpectStatus("/api/surveys/board",
                boardBody("bd-3", surveyId, LocalDate.of(2026, 6, 15), List.of(),
                        List.of("HULL_STRUCTURE")), 200);
        JsonNode rejected = postExpectStatus("/api/certificates/certify",
                certifyBody("cert-3", surveyId), 422);
        assertEquals("ITEM_COMPLETION_MISSING", rejected.get("code").asText());
        assertTrue(rejected.get("message").asText().contains("MACHINERY"));
    }

    @Test
    void crossShipRecognitionRejected() {
        registerShipAndRule();
        postExpectStatus("/api/ships", shipBody("ship-r2", "IMO-R2", ANNIVERSARY, DELIVERY), 200);
        importReport("imp-4", "RPT-004", "IMO-R1", COMPLETED_AT,
                List.of("PASS", "PASS", "PASS"));
        long surveyId = applySurvey("ap-4", "IMO-R2");
        JsonNode rejected = postExpectStatus("/api/recognition/apply",
                applyRecognitionBody("rec-4", surveyId, "RPT-004", 1), 422);
        assertEquals("CROSS_SHIP", rejected.get("code").asText());
    }

    @Test
    void unknownMappingMissingItemAndUnqualifiedItemRejected() {
        registerShipAndRule();
        postExpectStatus("/api/ships", shipBody("ship-r5b", "IMO-R5B", ANNIVERSARY, DELIVERY), 200);
        postExpectStatus("/api/ships", shipBody("ship-r5c", "IMO-R5C", ANNIVERSARY, DELIVERY), 200);
        // 未知映射：报告含规则外项目。
        postExpectStatus("/api/recognition/reports",
                importReportBody("imp-5a", "RPT-005A", "IMO-R1", "SYN-FL", COMPLETED_AT,
                        List.of(reportItem("HULL_STRUCTURE", "PASS"),
                                reportItem("MACHINERY", "PASS"),
                                reportItem("SAFETY_EQUIPMENT", "PASS"),
                                reportItem("RADIO_EQUIPMENT", "PASS"))), 200);
        long surveyA = applySurvey("ap-5a", "IMO-R1");
        JsonNode unknown = postExpectStatus("/api/recognition/apply",
                applyRecognitionBody("rec-5a", surveyA, "RPT-005A", 1), 422);
        assertEquals("UNKNOWN_ITEM_MAPPING", unknown.get("code").asText());

        // 证据缺项：报告缺少 SAFETY_EQUIPMENT 结论。
        postExpectStatus("/api/recognition/reports",
                importReportBody("imp-5b", "RPT-005B", "IMO-R5B", "SYN-FL", COMPLETED_AT,
                        List.of(reportItem("HULL_STRUCTURE", "PASS"),
                                reportItem("MACHINERY", "PASS"))), 200);
        long surveyB = applySurvey("ap-5b", "IMO-R5B");
        JsonNode missing = postExpectStatus("/api/recognition/apply",
                applyRecognitionBody("rec-5b", surveyB, "RPT-005B", 1), 422);
        assertEquals("EVIDENCE_ITEM_MISSING", missing.get("code").asText());

        // 重大缺陷项目不得当合格项目。
        postExpectStatus("/api/recognition/reports",
                importReportBody("imp-5c", "RPT-005C", "IMO-R5C", "SYN-FL", COMPLETED_AT,
                        List.of(reportItem("HULL_STRUCTURE", "PASS"),
                                reportItem("MACHINERY", "PASS"),
                                reportItem("SAFETY_EQUIPMENT", "MAJOR_DEFECT"))), 200);
        long surveyC = applySurvey("ap-5c", "IMO-R5C");
        JsonNode unqualified = postExpectStatus("/api/recognition/apply",
                applyRecognitionBody("rec-5c", surveyC, "RPT-005C", 1), 422);
        assertEquals("ITEM_NOT_QUALIFIED", unqualified.get("code").asText());
    }

    @Test
    void evidenceValidityDeadlineExactAllowedAndExceededRejected() {
        registerShipAndRule();
        postExpectStatus("/api/ships", shipBody("ship-r6b", "IMO-R6B", ANNIVERSARY, DELIVERY), 200);
        // 恰到截止：完成时刻 + 90 天 == 当前时刻，允许。
        Instant exactDeadline = LocalDate.of(2026, 3, 17)
                .atStartOfDay(ZoneOffset.UTC).toInstant();
        importReport("imp-6a", "RPT-006A", "IMO-R1", exactDeadline,
                List.of("PASS", "PASS", "PASS"));
        long surveyA = applySurvey("ap-6a", "IMO-R1");
        postExpectStatus("/api/recognition/apply",
                applyRecognitionBody("rec-6a", surveyA, "RPT-006A", 1), 200);

        // 超期 1 秒：拒绝。
        Instant oneSecondOver = exactDeadline.minusSeconds(1);
        importReport("imp-6b", "RPT-006B", "IMO-R6B", oneSecondOver,
                List.of("PASS", "PASS", "PASS"));
        long surveyB = applySurvey("ap-6b", "IMO-R6B");
        JsonNode expired = postExpectStatus("/api/recognition/apply",
                applyRecognitionBody("rec-6b", surveyB, "RPT-006B", 1), 422);
        assertEquals("EVIDENCE_EXPIRED", expired.get("code").asText());
    }

    @Test
    void nonSyntheticAttestationRejected() {
        registerShipAndRule();
        var body = new java.util.HashMap<String, Object>(
                importReportBody("imp-7", "RPT-007", "IMO-R1", "SYN-FL", COMPLETED_AT,
                        List.of(reportItem("HULL_STRUCTURE", "PASS"),
                                reportItem("MACHINERY", "PASS"),
                                reportItem("SAFETY_EQUIPMENT", "PASS"))));
        body.put("attestation", "REAL-AUTHORITY-SEAL");
        JsonNode rejected = postExpectStatus("/api/recognition/reports", body, 422);
        assertEquals("NON_SYNTHETIC_ATTESTATION", rejected.get("code").asText());
    }

    @Test
    void duplicateReportSameContentNotAddedDifferentContentConflicts() {
        registerShipAndRule();
        List<java.util.Map<String, Object>> items = List.of(
                reportItem("HULL_STRUCTURE", "PASS"),
                reportItem("MACHINERY", "PASS"),
                reportItem("SAFETY_EQUIPMENT", "PASS"));
        JsonNode first = postExpectStatus("/api/recognition/reports",
                importReportBody("imp-8a", "RPT-008", "IMO-R1", "SYN-FL", COMPLETED_AT, items), 200);
        long reportId = first.get("data").get("id").asLong();

        // 同 requestId 同参：重放首次结果。
        JsonNode replay = postExpectStatus("/api/recognition/reports",
                importReportBody("imp-8a", "RPT-008", "IMO-R1", "SYN-FL", COMPLETED_AT, items), 200);
        assertTrue(replay.get("replayed").asBoolean());

        // 不同 requestId 同编号同内容：不新增，返回已有报告。
        JsonNode same = postExpectStatus("/api/recognition/reports",
                importReportBody("imp-8b", "RPT-008", "IMO-R1", "SYN-FL", COMPLETED_AT, items), 200);
        assertFalse(same.get("replayed").asBoolean());
        assertEquals(reportId, same.get("data").get("id").asLong());
        assertEquals(1, get("/api/recognition/reports/RPT-008/revisions").size());

        // 同编号异内容：409。
        JsonNode conflict = postExpectStatus("/api/recognition/reports",
                importReportBody("imp-8c", "RPT-008", "IMO-R1", "SYN-FL", COMPLETED_AT,
                        List.of(reportItem("HULL_STRUCTURE", "PASS"),
                                reportItem("MACHINERY", "FAIL"),
                                reportItem("SAFETY_EQUIPMENT", "PASS"))), 409);
        assertEquals("REPORT_CONTENT_CONFLICT", conflict.get("code").asText());
    }

    @Test
    void correctionInvalidatesPendingSurveyRecognitionAndCertifyBlocked() {
        registerShipAndRule();
        importReport("imp-9", "RPT-009", "IMO-R1", COMPLETED_AT,
                List.of("PASS", "PASS", "PASS"));
        long surveyId = applySurvey("ap-9", "IMO-R1");
        postExpectStatus("/api/surveys/board",
                boardBody("bd-9", surveyId, LocalDate.of(2026, 6, 15), List.of(),
                        List.of()), 200);
        postExpectStatus("/api/recognition/apply",
                applyRecognitionBody("rec-9", surveyId, "RPT-009", 1), 200);

        // expectedVersion 不匹配：409。
        JsonNode versionConflict = postExpectStatus("/api/recognition/reports/correct",
                correctReportBody("cor-9x", "RPT-009", 5, COMPLETED_AT,
                        List.of(reportItem("HULL_STRUCTURE", "PASS"),
                                reportItem("MACHINERY", "PASS"),
                                reportItem("SAFETY_EQUIPMENT", "PASS"))), 409);
        assertEquals("VERSION_CONFLICT", versionConflict.get("code").asText());

        // 更正成功：新修订 revision=2、version=2，旧互认原子失效。
        JsonNode corrected = postExpectStatus("/api/recognition/reports/correct",
                correctReportBody("cor-9", "RPT-009", 1, COMPLETED_AT,
                        List.of(reportItem("HULL_STRUCTURE", "PASS"),
                                reportItem("MACHINERY", "PASS"),
                                reportItem("SAFETY_EQUIPMENT", "PASS"))), 200);
        assertEquals(2, corrected.get("data").get("revision").asInt());
        assertEquals(2, corrected.get("data").get("version").asInt());

        JsonNode bindings = get("/api/recognition/bindings?surveyId=" + surveyId);
        assertEquals(3, bindings.size());
        bindings.forEach(b -> assertEquals("INVALIDATED", b.get("status").asText()));

        // 旧互认失效后不得发证。
        JsonNode blocked = postExpectStatus("/api/certificates/certify",
                certifyBody("cert-9", surveyId), 422);
        assertEquals("RECOGNITION_INVALIDATED", blocked.get("code").asText());

        // 重新申请互认（冻结修订 2）后可发证。
        JsonNode reapply = postExpectStatus("/api/recognition/apply",
                applyRecognitionBody("rec-9b", surveyId, "RPT-009", 1), 200);
        assertEquals(2, reapply.get("data").get("reportRevision").asInt());
        postExpectStatus("/api/certificates/certify", certifyBody("cert-9b", surveyId), 200);
    }

    @Test
    void correctionKeepsCertifiedHistoryAndRecordsRisk() {
        registerShipAndRule();
        importReport("imp-10", "RPT-010", "IMO-R1", COMPLETED_AT,
                List.of("PASS", "PASS", "PASS"));
        long surveyId = applySurvey("ap-10", "IMO-R1");
        postExpectStatus("/api/surveys/board",
                boardBody("bd-10", surveyId, LocalDate.of(2026, 6, 15), List.of(),
                        List.of()), 200);
        postExpectStatus("/api/recognition/apply",
                applyRecognitionBody("rec-10", surveyId, "RPT-010", 1), 200);
        postExpectStatus("/api/certificates/certify", certifyBody("cert-10", surveyId), 200);

        // 更正：已签发检验的历史互认不改，另记风险。
        postExpectStatus("/api/recognition/reports/correct",
                correctReportBody("cor-10", "RPT-010", 1, COMPLETED_AT,
                        List.of(reportItem("HULL_STRUCTURE", "PASS"),
                                reportItem("MACHINERY", "PASS"),
                                reportItem("SAFETY_EQUIPMENT", "PASS"))), 200);

        JsonNode bindings = get("/api/recognition/bindings?surveyId=" + surveyId);
        assertEquals(3, bindings.size());
        bindings.forEach(b -> assertEquals("ACTIVE", b.get("status").asText()));

        JsonNode chain = get("/api/recognition/reports/RPT-010/chain");
        assertEquals(2, chain.get("revisions").size());
        assertEquals(3, chain.get("bindings").size());
        assertEquals(3, chain.get("risks").size());
        assertTrue(chain.get("risks").get(0).get("note").asText().contains("已签发检验"));

        // 已签发检验不得再申请互认。
        JsonNode rejected = postExpectStatus("/api/recognition/apply",
                applyRecognitionBody("rec-10b", surveyId, "RPT-010", 1), 422);
        assertEquals("SURVEY_ALREADY_CERTIFIED", rejected.get("code").asText());
    }

    @Test
    void oneActiveBindingPerItemAndQueriesWork() {
        registerShipAndRule();
        importReport("imp-11", "RPT-011", "IMO-R1", COMPLETED_AT,
                List.of("PASS", "PASS", "PASS"));
        long surveyId = applySurvey("ap-11", "IMO-R1");
        postExpectStatus("/api/recognition/apply",
                applyRecognitionBody("rec-11", surveyId, "RPT-011", 1), 200);
        // 每个项目只一个有效绑定：重复申请（新 requestId）409。
        JsonNode duplicate = postExpectStatus("/api/recognition/apply",
                applyRecognitionBody("rec-11b", surveyId, "RPT-011", 1), 409);
        assertEquals("BINDING_EXISTS", duplicate.get("code").asText());

        // 查询：规则列表、当前修订、修订链、证据链。
        JsonNode rules = get("/api/recognition/rules");
        assertEquals(1, rules.size());
        assertEquals("SYN-FL", rules.get(0).get("flagCode").asText());
        assertEquals(90, rules.get(0).get("maxEvidenceDays").asInt());

        JsonNode active = get("/api/recognition/reports/RPT-011");
        assertEquals(1, active.get("revision").asInt());
        assertEquals("ACTIVE", active.get("status").asText());

        JsonNode chain = get("/api/recognition/reports/RPT-011/chain");
        assertEquals(1, chain.get("revisions").size());
        assertEquals(3, chain.get("bindings").size());
        assertEquals(0, chain.get("risks").size());

        // 不存在的对象：404。
        postExpectStatus("/api/recognition/apply",
                applyRecognitionBody("rec-404", surveyId, "RPT-NONE", 1), 404);
        assertEquals("REPORT_NOT_FOUND",
                get404("/api/recognition/reports/RPT-NONE").get("code").asText());
    }

    private JsonNode get404(String path) {
        var response = rest.getForEntity(url(path), String.class);
        assertEquals(404, response.getStatusCode().value());
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(response.getBody());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void registerShipAndRule() {
        postExpectStatus("/api/ships", shipBody("ship-r1", "IMO-R1", ANNIVERSARY, DELIVERY), 200);
        createRule("rule-1", 1, 90, RULE_ITEMS);
    }
}
