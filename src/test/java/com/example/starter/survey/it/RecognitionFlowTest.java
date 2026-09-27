package com.example.starter.survey.it;

import com.example.starter.survey.testsupport.AbstractH2IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 船旗国检查报告互认主流程与失败分支（H2 内存库，真实 SQL/事务，非 mock）：
 * 规则版本、报告导入/更正、互认绑定、发证门禁、证据链、幂等与各类拒绝语义。
 */
class RecognitionFlowTest extends AbstractH2IntegrationTest {

    private static final LocalDate ANNIVERSARY = LocalDate.of(2020, 6, 15);
    private static final LocalDate DELIVERY = LocalDate.of(2020, 3, 1);
    private static final String IMO = "IMO-REC";
    private static final String FLAG = "SYN-PA";
    private static final String FIRE_ITEM = "FIRE-SAFETY-ANNUAL";
    private static final String HULL_ITEM = "HULL-INTERMEDIATE";

    private void registerShip(String reqId, String imo) {
        postExpectStatus("/api/ships",
                shipBody(reqId, imo, ANNIVERSARY, DELIVERY), 200);
    }

    private void publishRuleV1(String reqId, int maxDays) {
        JsonNode resp = postExpectStatus("/api/recognition/rules",
                publishRuleBody(reqId, 0, maxDays, List.of(FLAG, "SYN-XH"), List.of(
                        ruleMapping(FIRE_ITEM, "ANNUAL", "FIRE_SAFETY"),
                        ruleMapping("LIFESAVING-ANNUAL", "ANNUAL", "LIFESAVING"),
                        ruleMapping(HULL_ITEM, "INTERMEDIATE", "HULL"))), 200);
        assertEquals(1, resp.get("data").get("version").asInt());
    }

    private long applyAndBoard(String applyReq, String boardReq) {
        setClockDate(LocalDate.of(2026, 6, 15));
        JsonNode applied = postExpectStatus("/api/surveys",
                applyBody(applyReq, IMO, "ANNUAL"), 200);
        long surveyId = applied.get("data").get("id").asLong();
        postExpectStatus("/api/surveys/board",
                boardBody(boardReq, surveyId, LocalDate.of(2026, 6, 15), List.of()), 200);
        return surveyId;
    }

    @Test
    void ruleVersionStartsAtOneAndIncrements() {
        JsonNode v1 = postExpectStatus("/api/recognition/rules",
                publishRuleBody("rule-1", 0, 90, List.of(FLAG),
                        List.of(ruleMapping(FIRE_ITEM, "ANNUAL", "FIRE_SAFETY"))), 200);
        assertEquals(1, v1.get("data").get("version").asInt());
        assertFalse(v1.get("replayed").asBoolean());

        // 过期 expectedVersion → 409，不产生新版本。
        JsonNode stale = postExpectStatus("/api/recognition/rules",
                publishRuleBody("rule-2", 0, 60, List.of(FLAG),
                        List.of(ruleMapping(FIRE_ITEM, "ANNUAL", "FIRE_SAFETY"))), 409);
        assertEquals("RULE_VERSION_CONFLICT", stale.get("code").asText());

        JsonNode v2 = postExpectStatus("/api/recognition/rules",
                publishRuleBody("rule-3", 1, 60, List.of(FLAG),
                        List.of(ruleMapping(FIRE_ITEM, "ANNUAL", "FIRE_SAFETY"))), 200);
        assertEquals(2, v2.get("data").get("version").asInt());
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM recognition_rule_version", Integer.class));
    }

    @Test
    void bindRecognizedEvidenceThenCertifySucceeds() {
        registerShip("ship-1", IMO);
        publishRuleV1("rule-main", 90);
        long surveyId = applyAndBoard("apply-1", "board-1");

        JsonNode report = postExpectStatus("/api/recognition/reports",
                importReportBody("rpt-1", "RPT-MAIN", 0, IMO, FLAG,
                        "2026-05-01T00:00:00Z", SYNTH_MARKER,
                        List.of(Map.of("itemCode", FIRE_ITEM, "result", "PASS", "note", "ok"))),
                200);
        assertEquals(1, report.get("data").get("revisionNo").asInt());
        assertEquals("CURRENT", report.get("data").get("status").asText());

        JsonNode binding = postExpectStatus("/api/recognition/bindings",
                bindBody("bind-1", surveyId, "RPT-MAIN", 1, FIRE_ITEM), 200);
        assertEquals("ACTIVE", binding.get("data").get("status").asText());
        assertEquals("FIRE_SAFETY", binding.get("data").get("classItemCode").asText());
        assertEquals(1, binding.get("data").get("revisionNo").asInt());

        // 互认不替代登轮门禁，但登轮已完成；证据冻结一致 → 发证成功，绑定保持有效。
        JsonNode certify = postExpectStatus("/api/certificates/certify",
                certifyBody("cert-1", surveyId), 200);
        assertEquals(1, certify.get("data").get("certificate").get("version").asInt());
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT status FROM recognition_binding WHERE request_id='bind-1'",
                String.class));
    }

    @Test
    void recognitionDoesNotSkipBoardingGate() {
        registerShip("ship-nb", IMO);
        publishRuleV1("rule-nb", 90);
        setClockDate(LocalDate.of(2026, 6, 15));
        JsonNode applied = postExpectStatus("/api/surveys",
                applyBody("apply-nb", IMO, "ANNUAL"), 200);
        long surveyId = applied.get("data").get("id").asLong();
        postExpectStatus("/api/recognition/reports",
                importReportBody("rpt-nb", "RPT-NB", 0, IMO, FLAG,
                        "2026-05-01T00:00:00Z", SYNTH_MARKER,
                        List.of(Map.of("itemCode", FIRE_ITEM, "result", "PASS", "note", ""))),
                200);
        postExpectStatus("/api/recognition/bindings",
                bindBody("bind-nb", surveyId, "RPT-NB", 1, FIRE_ITEM), 200);
        // 未登轮：互认不能跳过原签发门禁。
        JsonNode resp = postExpectStatus("/api/certificates/certify",
                certifyBody("cert-nb", surveyId), 409);
        assertEquals("SURVEY_NOT_BOARDED", resp.get("code").asText());
    }

    @Test
    void crossShipEvidenceRejected() {
        registerShip("ship-a", IMO);
        registerShip("ship-b", "IMO-OTHER");
        publishRuleV1("rule-x", 90);
        // 为另一艘船建立进行中年度检验
        setClockDate(LocalDate.of(2026, 6, 15));
        JsonNode applied = postExpectStatus("/api/surveys",
                applyBody("apply-other", "IMO-OTHER", "ANNUAL"), 200);
        long otherId = applied.get("data").get("id").asLong();

        postExpectStatus("/api/recognition/reports",
                importReportBody("rpt-x", "RPT-X", 0, IMO, FLAG,
                        "2026-05-01T00:00:00Z", SYNTH_MARKER,
                        List.of(Map.of("itemCode", FIRE_ITEM, "result", "PASS", "note", ""))),
                200);
        JsonNode resp = postExpectStatus("/api/recognition/bindings",
                bindBody("bind-x", otherId, "RPT-X", 1, FIRE_ITEM), 422);
        assertEquals("REPORT_SHIP_MISMATCH", resp.get("code").asText());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM recognition_binding", Integer.class));
    }

    @Test
    void unknownMappingMissingItemAndBadMarkerRejected() {
        registerShip("ship-f", IMO);
        publishRuleV1("rule-f", 90);
        setClockDate(LocalDate.of(2026, 6, 15));
        JsonNode applied = postExpectStatus("/api/surveys",
                applyBody("apply-f", IMO, "ANNUAL"), 200);
        long surveyId = applied.get("data").get("id").asLong();

        // 非合成签认标记 → 422，且不访问真实机构。
        JsonNode badMarker = postExpectStatus("/api/recognition/reports",
                importReportBody("rpt-bm", "RPT-BM", 0, IMO, FLAG,
                        "2026-05-01T00:00:00Z", "REAL-AUTHORITY-SEAL",
                        List.of(Map.of("itemCode", FIRE_ITEM, "result", "PASS", "note", ""))),
                422);
        assertEquals("UNRECOGNIZED_SIGNATURE_MARKER", badMarker.get("code").asText());

        postExpectStatus("/api/recognition/reports",
                importReportBody("rpt-f", "RPT-F", 0, IMO, FLAG,
                        "2026-05-01T00:00:00Z", SYNTH_MARKER,
                        List.of(Map.of("itemCode", "LIFESAVING-ANNUAL", "result", "PASS",
                                "note", ""))),
                200);
        // 未知映射项目。
        JsonNode unknown = postExpectStatus("/api/recognition/bindings",
                bindBody("bind-u", surveyId, "RPT-F", 1, "NO-SUCH-ITEM"), 422);
        assertEquals("ITEM_MAPPING_NOT_FOUND", unknown.get("code").asText());
        // 证据缺项：映射存在但报告无该逐项结论。
        JsonNode missing = postExpectStatus("/api/recognition/bindings",
                bindBody("bind-m", surveyId, "RPT-F", 1, FIRE_ITEM), 422);
        assertEquals("EVIDENCE_ITEM_MISSING", missing.get("code").asText());
    }

    @Test
    void evidenceValidityBoundaryExactDeadlineAllowed() {
        registerShip("ship-d", IMO);
        publishRuleV1("rule-d", 90);
        setClockDate(LocalDate.of(2026, 6, 15));
        JsonNode applied = postExpectStatus("/api/surveys",
                applyBody("apply-d", IMO, "ANNUAL"), 200);
        long surveyId = applied.get("data").get("id").asLong();
        List<Map<String, Object>> items = List.of(
                Map.of("itemCode", FIRE_ITEM, "result", "PASS", "note", ""));

        // 完成时刻 2026-03-17T00:00Z + 90 天 = 2026-06-15T00:00Z = now，恰到截止允许。
        postExpectStatus("/api/recognition/reports",
                importReportBody("rpt-ok", "RPT-OK", 0, IMO, FLAG,
                        "2026-03-17T00:00:00Z", SYNTH_MARKER, items), 200);
        JsonNode ok = postExpectStatus("/api/recognition/bindings",
                bindBody("bind-ok", surveyId, "RPT-OK", 1, FIRE_ITEM), 200);
        assertEquals("ACTIVE", ok.get("data").get("status").asText());

        // 再早一天完成即超期：now > 完成时刻 + 90 天，拒绝。
        postExpectStatus("/api/recognition/reports",
                importReportBody("rpt-old", "RPT-OLD", 0, IMO, FLAG,
                        "2026-03-16T00:00:00Z", SYNTH_MARKER, items), 200);
        JsonNode expired = postExpectStatus("/api/recognition/bindings",
                bindBody("bind-old", surveyId, "RPT-OLD", 1, FIRE_ITEM), 422);
        assertEquals("EVIDENCE_EXPIRED", expired.get("code").asText());
    }

    @Test
    void failingAndMajorDefectItemsCannotBeRecognized() {
        registerShip("ship-q", IMO);
        publishRuleV1("rule-q", 90);
        setClockDate(LocalDate.of(2026, 6, 15));
        postExpectStatus("/api/recognition/reports",
                importReportBody("rpt-q", "RPT-Q", 0, IMO, FLAG,
                        "2026-05-01T00:00:00Z", SYNTH_MARKER,
                        List.of(Map.of("itemCode", FIRE_ITEM, "result", "FAIL", "note", ""))),
                200);
        JsonNode survey = postExpectStatus("/api/surveys",
                applyBody("apply-q", IMO, "ANNUAL"), 200);
        long surveyId = survey.get("data").get("id").asLong();
        JsonNode fail = postExpectStatus("/api/recognition/bindings",
                bindBody("bind-fail", surveyId, "RPT-Q", 1, FIRE_ITEM), 422);
        assertEquals("EVIDENCE_ITEM_NOT_PASSING", fail.get("code").asText());

        // 含重大缺陷项的整份报告不得当合格项目（即使目标项 PASS）。
        postExpectStatus("/api/recognition/reports",
                importReportBody("rpt-maj", "RPT-MAJ", 0, IMO, FLAG,
                        "2026-05-01T00:00:00Z", SYNTH_MARKER, List.of(
                        Map.of("itemCode", FIRE_ITEM, "result", "PASS", "note", ""),
                        Map.of("itemCode", "LIFESAVING-ANNUAL", "result", "MAJOR_DEFECT",
                                "note", ""))),
                200);
        JsonNode major = postExpectStatus("/api/recognition/bindings",
                bindBody("bind-maj", surveyId, "RPT-MAJ", 1, FIRE_ITEM), 422);
        assertEquals("REPORT_HAS_MAJOR_DEFECT", major.get("code").asText());
    }

    @Test
    void typeMismatchAndUnknownFlagAndRuleRejected() {
        registerShip("ship-t", IMO);
        publishRuleV1("rule-t", 90);
        setClockDate(LocalDate.of(2026, 6, 15));
        JsonNode survey = postExpectStatus("/api/surveys",
                applyBody("apply-t", IMO, "ANNUAL"), 200);
        long surveyId = survey.get("data").get("id").asLong();

        postExpectStatus("/api/recognition/reports",
                importReportBody("rpt-t", "RPT-T", 0, IMO, "SYN-PA",
                        "2026-05-01T00:00:00Z", SYNTH_MARKER,
                        List.of(Map.of("itemCode", HULL_ITEM, "result", "PASS", "note", ""))),
                200);
        JsonNode mismatch = postExpectStatus("/api/recognition/bindings",
                bindBody("bind-t", surveyId, "RPT-T", 1, HULL_ITEM), 422);
        assertEquals("ITEM_TYPE_MISMATCH", mismatch.get("code").asText());

        // 未承认船旗。
        postExpectStatus("/api/recognition/reports",
                importReportBody("rpt-flag", "RPT-FLAG", 0, IMO, "XX-UNKNOWN",
                        "2026-05-01T00:00:00Z", SYNTH_MARKER,
                        List.of(Map.of("itemCode", FIRE_ITEM, "result", "PASS", "note", ""))),
                200);
        JsonNode flag = postExpectStatus("/api/recognition/bindings",
                bindBody("bind-flag", surveyId, "RPT-FLAG", 1, FIRE_ITEM), 422);
        assertEquals("FLAG_NOT_RECOGNIZED", flag.get("code").asText());

        // 不存在的规则版本 → 404。
        JsonNode noRule = postExpectStatus("/api/recognition/bindings",
                bindBody("bind-norule", surveyId, "RPT-T", 99, HULL_ITEM), 404);
        assertEquals("RULE_VERSION_NOT_FOUND", noRule.get("code").asText());
    }

    @Test
    void correctionInvalidatesUncertifiedBindingAtomically() {
        registerShip("ship-c", IMO);
        publishRuleV1("rule-c", 90);
        setClockDate(LocalDate.of(2026, 6, 15));
        JsonNode survey = postExpectStatus("/api/surveys",
                applyBody("apply-c", IMO, "ANNUAL"), 200);
        long surveyId = survey.get("data").get("id").asLong();
        postExpectStatus("/api/recognition/reports",
                importReportBody("rpt-c1", "RPT-C", 0, IMO, FLAG,
                        "2026-05-01T00:00:00Z", SYNTH_MARKER,
                        List.of(Map.of("itemCode", FIRE_ITEM, "result", "PASS", "note", "v1"))),
                200);
        JsonNode binding = postExpectStatus("/api/recognition/bindings",
                bindBody("bind-c", surveyId, "RPT-C", 1, FIRE_ITEM), 200);
        long bindingId = binding.get("data").get("id").asLong();

        // 异内容更正 → 新修订 2，旧修订 SUPERSEDED，未签发检验旧绑定原子失效。
        JsonNode rev2 = postExpectStatus("/api/recognition/reports",
                importReportBody("rpt-c2", "RPT-C", 1, IMO, FLAG,
                        "2026-05-02T00:00:00Z", SYNTH_MARKER,
                        List.of(Map.of("itemCode", FIRE_ITEM, "result", "PASS", "note", "v2"))),
                200);
        assertEquals(2, rev2.get("data").get("revisionNo").asInt());
        assertEquals("INVALIDATED", jdbc.queryForObject(
                "SELECT status FROM recognition_binding WHERE id=?", String.class, bindingId));
        assertEquals("REPORT_REVISED", jdbc.queryForObject(
                "SELECT invalidated_reason FROM recognition_binding WHERE id=?",
                String.class, bindingId));
        assertEquals("SUPERSEDED", jdbc.queryForObject(
                "SELECT status FROM external_report WHERE report_no='RPT-C' AND revision_no=1",
                String.class));
        // 未签发 → 不记风险。
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM recognition_risk", Integer.class));

        // 旧 expectedRevision → 409。
        JsonNode conflict = postExpectStatus("/api/recognition/reports",
                importReportBody("rpt-c3", "RPT-C", 1, IMO, FLAG,
                        "2026-05-03T00:00:00Z", SYNTH_MARKER,
                        List.of(Map.of("itemCode", FIRE_ITEM, "result", "PASS", "note", "v3"))),
                409);
        assertEquals("REPORT_REVISION_CONFLICT", conflict.get("code").asText());

        // 可对新修订重新绑定，同项目再次只有一个有效绑定。
        JsonNode rebind = postExpectStatus("/api/recognition/bindings",
                bindBody("bind-c2", surveyId, "RPT-C", 1, FIRE_ITEM), 200);
        assertEquals(2, rebind.get("data").get("revisionNo").asInt());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM recognition_binding WHERE survey_id=? AND active_flag='Y'",
                Integer.class, surveyId));
    }

    @Test
    void correctionAfterCertificationKeepsHistoryAndRecordsRisk() {
        registerShip("ship-h", IMO);
        publishRuleV1("rule-h", 90);
        long surveyId = applyAndBoard("apply-h", "board-h");
        postExpectStatus("/api/recognition/reports",
                importReportBody("rpt-h1", "RPT-H", 0, IMO, FLAG,
                        "2026-05-01T00:00:00Z", SYNTH_MARKER,
                        List.of(Map.of("itemCode", FIRE_ITEM, "result", "PASS", "note", "v1"))),
                200);
        postExpectStatus("/api/recognition/bindings",
                bindBody("bind-h", surveyId, "RPT-H", 1, FIRE_ITEM), 200);
        postExpectStatus("/api/certificates/certify",
                certifyBody("cert-h", surveyId), 200);

        JsonNode rev2 = postExpectStatus("/api/recognition/reports",
                importReportBody("rpt-h2", "RPT-H", 1, IMO, FLAG,
                        "2026-05-02T00:00:00Z", SYNTH_MARKER,
                        List.of(Map.of("itemCode", FIRE_ITEM, "result", "FAIL", "note", "v2"))),
                200);
        assertEquals(2, rev2.get("data").get("revisionNo").asInt());
        // 已签发历史证据不改：绑定不失效、旧修订仍可查、证书仍有效；另记风险。
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT status FROM recognition_binding WHERE request_id='bind-h'",
                String.class));
        assertEquals("SUPERSEDED", jdbc.queryForObject(
                "SELECT status FROM external_report WHERE report_no='RPT-H' AND revision_no=1",
                String.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM certificate WHERE valid_flag='Y'", Integer.class));
        JsonNode risks = get("/api/recognition/evidence/RPT-H");
        assertEquals(1, risks.get("risks").size());
        assertEquals("CERTIFIED_HISTORY_REVISED",
                risks.get("risks").get(0).get("reason").asText());
        assertEquals(surveyId, risks.get("risks").get(0).get("surveyId").asLong());
    }

    @Test
    void sameContentReimportDoesNotAddRevision() {
        registerShip("ship-s", IMO);
        publishRuleV1("rule-s", 90);
        var body = importReportBody("rpt-s1", "RPT-S", 0, IMO, FLAG,
                "2026-05-01T00:00:00Z", SYNTH_MARKER,
                List.of(Map.of("itemCode", FIRE_ITEM, "result", "PASS", "note", "")));
        JsonNode first = postExpectStatus("/api/recognition/reports", body, 200);
        long firstId = first.get("data").get("id").asLong();

        // 同参同键重放。
        JsonNode replay = postExpectStatus("/api/recognition/reports", body, 200);
        assertTrue(replay.get("replayed").asBoolean());
        // 新键但内容完全相同、expectedRevision 正确：也不新增修订。
        JsonNode again = postExpectStatus("/api/recognition/reports",
                importReportBody("rpt-s2", "RPT-S", 1, IMO, FLAG,
                        "2026-05-01T00:00:00Z", SYNTH_MARKER,
                        List.of(Map.of("itemCode", FIRE_ITEM, "result", "PASS", "note", ""))),
                200);
        assertEquals(firstId, again.get("data").get("id").asLong());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM external_report WHERE report_no='RPT-S'", Integer.class));
    }

    @Test
    void failedRequestDoesNotOccupyIdempotencyKey() {
        registerShip("ship-i", IMO);
        publishRuleV1("rule-i", 90);
        setClockDate(LocalDate.of(2026, 6, 15));
        JsonNode survey = postExpectStatus("/api/surveys",
                applyBody("apply-i", IMO, "ANNUAL"), 200);
        long surveyId = survey.get("data").get("id").asLong();
        postExpectStatus("/api/recognition/reports",
                importReportBody("rpt-i", "RPT-I", 0, IMO, FLAG,
                        "2026-03-16T00:00:00Z", SYNTH_MARKER,
                        List.of(Map.of("itemCode", FIRE_ITEM, "result", "PASS", "note", ""))),
                200);
        // 首次用该 requestId 因证据超期失败（422），不占键。
        postExpectStatus("/api/recognition/bindings",
                bindBody("bind-i", surveyId, "RPT-I", 1, FIRE_ITEM), 422);
        // 报告在有效期内重新出修订后，用同一 requestId 同参引用……改为有效报告仍失败，
        // 因此这里用同一 requestId 引用另一份有效报告验证键未被占用。
        postExpectStatus("/api/recognition/reports",
                importReportBody("rpt-i2", "RPT-I2", 0, IMO, FLAG,
                        "2026-05-01T00:00:00Z", SYNTH_MARKER,
                        List.of(Map.of("itemCode", FIRE_ITEM, "result", "PASS", "note", ""))),
                200);
        JsonNode ok = postExpectStatus("/api/recognition/bindings",
                bindBody("bind-i", surveyId, "RPT-I2", 1, FIRE_ITEM), 200);
        assertEquals("ACTIVE", ok.get("data").get("status").asText());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_record WHERE request_id='bind-i'"
                        + " AND http_status=0", Integer.class));
    }

    @Test
    void sameKeyDifferentParamsConflicts() {
        registerShip("ship-k", IMO);
        publishRuleV1("rule-k", 90);
        setClockDate(LocalDate.of(2026, 6, 15));
        JsonNode survey = postExpectStatus("/api/surveys",
                applyBody("apply-k", IMO, "ANNUAL"), 200);
        long surveyId = survey.get("data").get("id").asLong();
        postExpectStatus("/api/recognition/reports",
                importReportBody("rpt-k", "RPT-K", 0, IMO, FLAG,
                        "2026-05-01T00:00:00Z", SYNTH_MARKER, List.of(
                        Map.of("itemCode", FIRE_ITEM, "result", "PASS", "note", ""),
                        Map.of("itemCode", "LIFESAVING-ANNUAL", "result", "PASS", "note", ""))),
                200);
        postExpectStatus("/api/recognition/bindings",
                bindBody("bind-k", surveyId, "RPT-K", 1, FIRE_ITEM), 200);
        JsonNode conflict = postExpectStatus("/api/recognition/bindings",
                bindBody("bind-k", surveyId, "RPT-K", 1, "LIFESAVING-ANNUAL"), 409);
        assertEquals("IDEMPOTENCY_FINGERPRINT_MISMATCH", conflict.get("code").asText());
    }

    @Test
    void illegalParamsReturn400AndMissingObjects404() {
        registerShip("ship-v", IMO);
        // 映射项目代码重复 → 400。
        JsonNode dupMapping = postExpectStatus("/api/recognition/rules",
                publishRuleBody("rule-dup", 0, 90, List.of(FLAG), List.of(
                        ruleMapping(FIRE_ITEM, "ANNUAL", "FIRE_SAFETY"),
                        ruleMapping(FIRE_ITEM, "ANNUAL", "OTHER"))), 400);
        assertEquals("DUPLICATE_MAPPING_ITEM", dupMapping.get("code").asText());
        // 报告逐项项目代码重复 → 400。
        JsonNode dupItem = postExpectStatus("/api/recognition/reports",
                importReportBody("rpt-dup", "RPT-DUP", 0, IMO, FLAG,
                        "2026-05-01T00:00:00Z", SYNTH_MARKER, List.of(
                        Map.of("itemCode", FIRE_ITEM, "result", "PASS", "note", ""),
                        Map.of("itemCode", FIRE_ITEM, "result", "FAIL", "note", ""))), 400);
        assertEquals("DUPLICATE_REPORT_ITEM", dupItem.get("code").asText());
        // bean validation：requestId 为空 → 400。
        JsonNode invalid = postExpectStatus("/api/recognition/reports",
                importReportBody("", "RPT-INVALID", 0, IMO, FLAG,
                        "2026-05-01T00:00:00Z", SYNTH_MARKER,
                        List.of(Map.of("itemCode", FIRE_ITEM, "result", "PASS", "note", ""))),
                400);
        assertEquals("VALIDATION_FAILED", invalid.get("code").asText());
        // 不存在的报告/检验/证据链 → 404。
        assertEquals("REPORT_NOT_FOUND", get404Code("/api/recognition/reports/NOPE"));
        assertEquals("REPORT_NOT_FOUND", get404Code("/api/recognition/evidence/NOPE"));
        JsonNode noSurvey = postExpectStatus("/api/recognition/bindings",
                bindBody("bns", 999_999L, "RPT-DUP", 1, FIRE_ITEM), 404);
        assertEquals("SURVEY_NOT_FOUND", noSurvey.get("code").asText());
    }

    private String get404Code(String path) {
        org.springframework.http.ResponseEntity<String> resp =
                rest.getForEntity(url(path), String.class);
        assertEquals(404, resp.getStatusCode().value());
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(resp.getBody()).get("code").asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void evidenceChainContainsRevisionsBindingsAndRisks() {
        registerShip("ship-e", IMO);
        publishRuleV1("rule-e", 90);
        setClockDate(LocalDate.of(2026, 6, 15));
        JsonNode survey = postExpectStatus("/api/surveys",
                applyBody("apply-e", IMO, "ANNUAL"), 200);
        long surveyId = survey.get("data").get("id").asLong();
        postExpectStatus("/api/recognition/reports",
                importReportBody("rpt-e1", "RPT-E", 0, IMO, FLAG,
                        "2026-05-01T00:00:00Z", SYNTH_MARKER,
                        List.of(Map.of("itemCode", FIRE_ITEM, "result", "PASS", "note", "v1"))),
                200);
        postExpectStatus("/api/recognition/bindings",
                bindBody("bind-e", surveyId, "RPT-E", 1, FIRE_ITEM), 200);
        postExpectStatus("/api/recognition/reports",
                importReportBody("rpt-e2", "RPT-E", 1, IMO, FLAG,
                        "2026-05-02T00:00:00Z", SYNTH_MARKER,
                        List.of(Map.of("itemCode", FIRE_ITEM, "result", "PASS", "note", "v2"))),
                200);

        JsonNode chain = get("/api/recognition/evidence/RPT-E");
        assertEquals("RPT-E", chain.get("reportNo").asText());
        assertEquals(2, chain.get("revisions").size());
        assertEquals(1, chain.get("revisions").get(0).get("revisionNo").asInt());
        assertEquals(2, chain.get("revisions").get(1).get("revisionNo").asInt());
        assertEquals(1, chain.get("revisions").get(0).get("items").size());
        assertEquals(1, chain.get("bindings").size());
        assertEquals("INVALIDATED", chain.get("bindings").get(0).get("status").asText());

        JsonNode bySurvey = get("/api/recognition/bindings?surveyId=" + surveyId);
        assertEquals(1, bySurvey.size());
    }
}
