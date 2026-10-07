package com.example.starter.service;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.domain.ReviewStatus;
import com.example.starter.dto.ColdChainCorrectRequest;
import com.example.starter.dto.ColdChainReadingInput;
import com.example.starter.dto.ColdChainReadingsSubmitRequest;
import com.example.starter.dto.ColdChainSegmentInput;
import com.example.starter.dto.ColdChainSegmentReadingsInput;
import com.example.starter.dto.ColdChainSegmentRegisterRequest;
import com.example.starter.dto.ColdChainView;
import com.example.starter.dto.CustomsBindRequest;
import com.example.starter.dto.CustomsRegisterRequest;
import com.example.starter.dto.ErrorResponse;
import com.example.starter.dto.IssueRequest;
import com.example.starter.dto.ServiceResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 冷链片段核验主流程与失败分支 H2 测试：
 * 登记冻结运输起止 → 完整证据（2/8 度恰边界、5 分钟恰等间隔）合格冻结版本 →
 * 缺端点/越界拒核且查询展示缺口与越界点 → 更正递增版本使旧温证失效须重核 →
 * 签发门禁同事务检查当前温证与通关精确截止 → 未启用子批保持旧语义。
 */
class ColdChainFlowTest extends AbstractIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant ISSUE_AT = Instant.parse("2026-09-08T00:00:00Z");
    private static final Instant VALID_UNTIL = Instant.parse("2026-12-31T00:00:00Z");
    private static final Instant SEG_START = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant SEG_MID = Instant.parse("2026-09-01T00:10:00Z");
    private static final Instant SEG_END = Instant.parse("2026-09-01T00:20:00Z");
    private static final Instant SUBMIT_AT = Instant.parse("2026-09-01T03:00:00Z");

    // ------------------------------ 合成数据构造 ------------------------------

    private static List<ColdChainSegmentInput> twoSegments() {
        return List.of(
                new ColdChainSegmentInput(1, SEG_START, SEG_MID),
                new ColdChainSegmentInput(2, SEG_MID, SEG_END));
    }

    private static ColdChainReadingInput reading(int seq, Instant at, String temperature) {
        return new ColdChainReadingInput(seq, at, new BigDecimal(temperature));
    }

    /** 两片段完整合格读数：恰 2/8 度边界 + 恰 5 分钟间隔。 */
    private static List<ColdChainSegmentReadingsInput> qualifiedReadings() {
        return List.of(
                new ColdChainSegmentReadingsInput(1, List.of(
                        reading(1, SEG_START, "2.0"),
                        reading(2, SEG_START.plusSeconds(300), "5.0"),
                        reading(3, SEG_MID, "8.0"))),
                new ColdChainSegmentReadingsInput(2, List.of(
                        reading(1, SEG_MID, "4.0"),
                        reading(2, SEG_MID.plusSeconds(300), "6.0"),
                        reading(3, SEG_END, "2.0"))));
    }

    private ColdChainSegmentRegisterRequest registerRequest(String requestId, String batchNo) {
        return new ColdChainSegmentRegisterRequest(requestId, batchNo, twoSegments(), SUBMIT_AT);
    }

    private ColdChainReadingsSubmitRequest submitRequest(String requestId, String batchNo,
                                                         int expectedVersion,
                                                         List<ColdChainSegmentReadingsInput> readings) {
        return new ColdChainReadingsSubmitRequest(requestId, batchNo, expectedVersion,
                readings, SUBMIT_AT);
    }

    /** 申报 → 评定 → 公示期满生效，推进到可签发状态（国内批次，无通关门禁）。 */
    private void makeIssueableDomestic(String declareId, String batchNo) {
        clock.setFixed(T0);
        reviewService.declare(declareRequest(declareId, batchNo,
                allDocuments(), boundaryPassingInspections()));
        reviewService.evaluate(batchNo);
        clock.setFixed(ISSUE_AT);
        reviewService.settlePublicity(batchNo);
    }

    private ColdChainView asColdChainView(ServiceResult result) {
        return jsonSupport.convertValue(result.body(), ColdChainView.class);
    }

    private String errorCode(ServiceResult result) {
        return jsonSupport.convertValue(result.body(), ErrorResponse.class).error();
    }

    private void assertBusiness(org.junit.jupiter.api.function.Executable action,
                                int status, String code) {
        BusinessException ex = assertThrows(BusinessException.class, action);
        assertEquals(status, ex.getHttpStatus());
        assertEquals(code, ex.getError());
    }

    // ------------------------------ 主流程 ------------------------------

    @Test
    void registerFreezesTransportRangeAndStartsAtVersionOne() {
        clock.setFixed(T0);
        reviewService.declare(declareRequest("d-reg", "B-CC-REG",
                allDocuments(), boundaryPassingInspections()));

        ServiceResult registered = coldChainService.registerSegments(registerRequest("cc-reg-1", "B-CC-REG"));
        assertEquals(200, registered.httpStatus());
        ColdChainView view = asColdChainView(registered);
        assertEquals(1, view.version(), "台账版本从 1 起");
        assertNull(view.qualifiedVersion(), "登记后尚未有合格温证");
        assertFalse(view.certificateValid());
        assertEquals(SEG_START, view.transportStartUtc(), "登记冻结完整运输起点");
        assertEquals(SEG_END, view.transportEndUtc(), "登记冻结完整运输终点");
        assertEquals(2, view.segments().size());
        assertTrue(view.gaps().stream().anyMatch(g -> g.contains("片段 1 缺少温度读数")),
                "未提交读数时查询展示缺片缺口");
        assertTrue(view.segments().stream().allMatch(s -> s.readings().isEmpty()),
                "缺样本的片段读数为空列表，不当作零度");

        // 重复登记：409
        assertBusiness(() -> coldChainService.registerSegments(registerRequest("cc-reg-2", "B-CC-REG")),
                409, "COLD_CHAIN_ALREADY_REGISTERED");
    }

    @Test
    void qualifiedEvidenceWithBoundaryTempsAndExactFiveMinutesFreezesVersionAndEnablesIssue() {
        makeIssueableDomestic("d-ok", "B-CC-OK");
        coldChainService.registerSegments(registerRequest("cc-reg-ok", "B-CC-OK"));

        ServiceResult submitted = coldChainService.submitReadings(
                submitRequest("cc-sub-ok", "B-CC-OK", 1, qualifiedReadings()));
        assertEquals(200, submitted.httpStatus());
        ColdChainView view = asColdChainView(submitted);
        assertTrue(view.certificateValid(), "恰 2/8 度边界 + 恰 5 分钟间隔的完整证据合格");
        assertEquals(1, view.qualifiedVersion(), "合格即冻结当前读数版本");
        assertTrue(view.gaps().isEmpty());
        assertTrue(view.violations().isEmpty());

        clock.setFixed(ISSUE_AT);
        ServiceResult issued = reviewService.issue("B-CC-OK", new IssueRequest("issue-cc-ok"));
        assertEquals(200, issued.httpStatus(), "温证合格即可经原签发入口签发");
        assertEquals(ReviewStatus.ISSUED.name(), reviewService.getReview("B-CC-OK").status());
    }

    @Test
    void missingEndpointRejectsQualificationAndBlocksIssueButKeepsEvidenceForQuery() {
        makeIssueableDomestic("d-gap", "B-CC-GAP");
        coldChainService.registerSegments(registerRequest("cc-reg-gap", "B-CC-GAP"));

        // 片段 2 缺终点样本（最后一个样本在 00:15 而非 00:20）。
        List<ColdChainSegmentReadingsInput> missingEnd = List.of(
                qualifiedReadings().get(0),
                new ColdChainSegmentReadingsInput(2, List.of(
                        reading(1, SEG_MID, "4.0"),
                        reading(2, SEG_MID.plusSeconds(300), "6.0"))));
        ServiceResult rejected = coldChainService.submitReadings(
                submitRequest("cc-sub-gap", "B-CC-GAP", 1, missingEnd));
        assertEquals(422, rejected.httpStatus());
        assertEquals("COLD_CHAIN_EVIDENCE_INCOMPLETE", errorCode(rejected));

        // 查询展示缺口，缺样本不当作零度。
        ColdChainView view = coldChainService.getView("B-CC-GAP");
        assertFalse(view.certificateValid());
        assertTrue(view.gaps().stream().anyMatch(g -> g.contains("片段 2") && g.contains("缺少终点")),
                "查询须展示缺端点缺口: " + view.gaps());
        assertTrue(view.segments().stream()
                .flatMap(s -> s.readings().stream())
                .noneMatch(r -> r.temperature().compareTo(BigDecimal.ZERO) == 0),
                "缺样本不得合成零度读数");

        // 签发门禁：无当前合格温证，拒签。
        clock.setFixed(ISSUE_AT);
        ServiceResult blocked = reviewService.issue("B-CC-GAP", new IssueRequest("issue-cc-gap"));
        assertEquals(422, blocked.httpStatus());
        assertEquals("COLD_CHAIN_CERT_INVALID", errorCode(blocked));
        assertEquals(ReviewStatus.PUBLICITY_EFFECTIVE.name(), reviewService.getReview("B-CC-GAP").status(),
                "拒签不得改变审查状态");

        // 失败不占键：同一 requestId 修正参数后可重新提交成功。
        ServiceResult fixed = coldChainService.submitReadings(
                submitRequest("cc-sub-gap", "B-CC-GAP", 1, qualifiedReadings()));
        assertEquals(200, fixed.httpStatus(), "证据不合格 422 不占幂等键，修正后可重试");
        assertTrue(asColdChainView(fixed).certificateValid());
    }

    @Test
    void outOfRangePointRejectsAndQueryShowsViolationNotAverage() {
        makeIssueableDomestic("d-out", "B-CC-OUT");
        coldChainService.registerSegments(registerRequest("cc-reg-out", "B-CC-OUT"));

        // 平均温度约 4.9 度完全正常，但片段 2 有一点 8.5 度越界。
        List<ColdChainSegmentReadingsInput> withViolation = List.of(
                qualifiedReadings().get(0),
                new ColdChainSegmentReadingsInput(2, List.of(
                        reading(1, SEG_MID, "4.0"),
                        reading(2, SEG_MID.plusSeconds(300), "8.5"),
                        reading(3, SEG_END, "2.0"))));
        ServiceResult rejected = coldChainService.submitReadings(
                submitRequest("cc-sub-out", "B-CC-OUT", 1, withViolation));
        assertEquals(422, rejected.httpStatus());
        assertEquals("COLD_CHAIN_READING_OUT_OF_RANGE", errorCode(rejected));

        ColdChainView view = coldChainService.getView("B-CC-OUT");
        assertFalse(view.certificateValid());
        assertEquals(1, view.violations().size(), "查询逐点展示越界点，不按平均温度掩盖");
        var violation = view.violations().get(0);
        assertEquals(2, violation.segmentSeq());
        assertEquals(2, violation.readingSeq());
        assertEquals(0, violation.temperature().compareTo(new BigDecimal("8.5")));
    }

    // ------------------------------ 更正与重核 ------------------------------

    @Test
    void correctionBumpsVersionInvalidatesCertificateAndRequiresReverification() {
        makeIssueableDomestic("d-cor", "B-CC-COR");
        coldChainService.registerSegments(registerRequest("cc-reg-cor", "B-CC-COR"));
        coldChainService.submitReadings(submitRequest("cc-sub-cor", "B-CC-COR", 1, qualifiedReadings()));

        // 更正：版本 1 → 2，旧温证资格失效。
        List<ColdChainSegmentInput> corrected = List.of(
                new ColdChainSegmentInput(1, SEG_START, SEG_MID),
                new ColdChainSegmentInput(2, SEG_MID, SEG_END),
                new ColdChainSegmentInput(3, SEG_END, SEG_END.plusSeconds(600)));
        ServiceResult correctedResult = coldChainService.correct(
                new ColdChainCorrectRequest("cc-cor-1", "B-CC-COR", 1, corrected, SUBMIT_AT.plusSeconds(600)));
        assertEquals(200, correctedResult.httpStatus());
        ColdChainView afterCorrection = asColdChainView(correctedResult);
        assertEquals(2, afterCorrection.version(), "成功更正版本加 1");
        assertEquals(1, afterCorrection.qualifiedVersion(), "旧温证冻结版本保留展示");
        assertFalse(afterCorrection.certificateValid(), "更正使旧温证资格失效");
        assertEquals(SEG_END.plusSeconds(600), afterCorrection.transportEndUtc(), "运输终点按新片段冻结");

        // 旧版本提交读数：409 版本冲突。
        assertBusiness(() -> coldChainService.submitReadings(
                        submitRequest("cc-sub-cor-old", "B-CC-COR", 1, qualifiedReadings())),
                409, "VERSION_CONFLICT");

        // 签发门禁：当前版本无合格温证，拒签。
        clock.setFixed(ISSUE_AT);
        ServiceResult blocked = reviewService.issue("B-CC-COR", new IssueRequest("issue-cc-cor"));
        assertEquals(422, blocked.httpStatus());
        assertEquals("COLD_CHAIN_CERT_INVALID", errorCode(blocked));

        // 重核新版本（片段 3 也需完整读数）后放行。
        List<ColdChainSegmentReadingsInput> newReadings = new ArrayList<>(qualifiedReadings());
        newReadings.add(new ColdChainSegmentReadingsInput(3, List.of(
                reading(1, SEG_END, "3.0"),
                reading(2, SEG_END.plusSeconds(300), "4.0"),
                reading(3, SEG_END.plusSeconds(600), "5.0"))));
        ServiceResult requalified = coldChainService.submitReadings(
                submitRequest("cc-sub-cor-new", "B-CC-COR", 2, newReadings));
        assertEquals(200, requalified.httpStatus());
        assertEquals(2, asColdChainView(requalified).qualifiedVersion(), "重核合格冻结新版本");

        ServiceResult issued = reviewService.issue("B-CC-COR", new IssueRequest("issue-cc-cor-2"));
        assertEquals(200, issued.httpStatus(), "重核合格后可签发");
    }

    @Test
    void staleExpectedVersionRejected409() {
        makeIssueableDomestic("d-ver", "B-CC-VER");
        coldChainService.registerSegments(registerRequest("cc-reg-ver", "B-CC-VER"));
        coldChainService.submitReadings(submitRequest("cc-sub-ver", "B-CC-VER", 1, qualifiedReadings()));
        coldChainService.correct(new ColdChainCorrectRequest("cc-cor-ver", "B-CC-VER", 1,
                twoSegments(), SUBMIT_AT.plusSeconds(60)));

        // 旧版本更正：409，版本不变。
        assertBusiness(() -> coldChainService.correct(new ColdChainCorrectRequest(
                        "cc-cor-ver-stale", "B-CC-VER", 1, twoSegments(), SUBMIT_AT.plusSeconds(120))),
                409, "VERSION_CONFLICT");
        assertEquals(2, coldChainService.getView("B-CC-VER").version(), "旧版 409 不得改变版本");
    }

    @Test
    void frozenEvidenceCannotBeModifiedWithoutCorrection() {
        makeIssueableDomestic("d-frz", "B-CC-FRZ");
        coldChainService.registerSegments(registerRequest("cc-reg-frz", "B-CC-FRZ"));
        coldChainService.submitReadings(submitRequest("cc-sub-frz", "B-CC-FRZ", 1, qualifiedReadings()));

        // 同参新键：视为重放成功。
        ServiceResult replayLike = coldChainService.submitReadings(
                submitRequest("cc-sub-frz-same", "B-CC-FRZ", 1, qualifiedReadings()));
        assertEquals(200, replayLike.httpStatus());

        // 异参新键：证据已冻结，须先走更正入口。
        List<ColdChainSegmentReadingsInput> different = List.of(
                new ColdChainSegmentReadingsInput(1, List.of(
                        reading(1, SEG_START, "3.0"),
                        reading(2, SEG_START.plusSeconds(300), "5.0"),
                        reading(3, SEG_MID, "7.0"))),
                qualifiedReadings().get(1));
        assertBusiness(() -> coldChainService.submitReadings(
                        submitRequest("cc-sub-frz-diff", "B-CC-FRZ", 1, different)),
                422, "COLD_CHAIN_EVIDENCE_FROZEN");
        assertTrue(coldChainService.getView("B-CC-FRZ").certificateValid(), "冻结证据不被直改");
    }

    // ------------------------------ 幂等 ------------------------------

    @Test
    void idempotencyReplaysSameParamsAndRejectsDifferentParams() {
        makeIssueableDomestic("d-idem", "B-CC-IDEM");
        ServiceResult registered = coldChainService.registerSegments(registerRequest("cc-reg-idem", "B-CC-IDEM"));
        assertEquals(200, registered.httpStatus());

        // 同键同参：重放原响应。
        ServiceResult replay = coldChainService.registerSegments(registerRequest("cc-reg-idem", "B-CC-IDEM"));
        assertEquals(200, replay.httpStatus());
        assertEquals(jsonSupport.write(registered.body()), jsonSupport.write(replay.body()),
                "同参成功重放原响应");

        // 同键异参：409，业务状态不变。
        List<ColdChainSegmentInput> oneSegment = List.of(
                new ColdChainSegmentInput(1, SEG_START, SEG_MID));
        ServiceResult mismatch = coldChainService.registerSegments(
                new ColdChainSegmentRegisterRequest("cc-reg-idem", "B-CC-IDEM", oneSegment, SUBMIT_AT));
        assertEquals(409, mismatch.httpStatus());
        assertEquals("IDEMPOTENCY_FINGERPRINT_MISMATCH", errorCode(mismatch));
        assertEquals(2, coldChainService.getView("B-CC-IDEM").segments().size(),
                "异参 409 不得改变已登记片段");
    }

    // ------------------------------ 签发门禁组合 ------------------------------

    @Test
    void controlledImportBatchChecksBothCertificateAndCustomsDeadlineInSameTransaction() {
        // 进口受控子批：温证合格但通关恰截止，仍拒签（不能只信公示合格与温证）。
        customsService.register(new CustomsRegisterRequest("reg-cc-imp", "CI-CC-IMP",
                IMPORT_ENTERPRISE, IMPORT_PRODUCT, ISSUE_AT, 100, T0));
        clock.setFixed(T0);
        prepareIssueableImportBatch("d-cc-imp", "B-CC-IMP");
        clock.setFixed(ISSUE_AT);
        reviewService.settlePublicity("B-CC-IMP");
        customsService.bind(new CustomsBindRequest("bind-cc-imp", "CI-CC-IMP", "B-CC-IMP", 30, 1, T0));
        coldChainService.registerSegments(registerRequest("cc-reg-imp", "B-CC-IMP"));
        coldChainService.submitReadings(submitRequest("cc-sub-imp", "B-CC-IMP", 1, qualifiedReadings()));

        clock.setFixed(ISSUE_AT);
        ServiceResult blocked = reviewService.issue("B-CC-IMP", new IssueRequest("issue-cc-imp"));
        assertEquals(422, blocked.httpStatus());
        assertEquals("CUSTOMS_EXPIRED", errorCode(blocked),
                "温证合格但通关恰截止仍拒签");
        assertEquals(30, customsService.getLedger("CI-CC-IMP").reservedQuantity(), "拒签不得核销");
    }

    @Test
    void nonControlledBatchKeepsOldIssueSemantics() {
        // 未启用冷链的子批：无台账，签发门禁不拦截，保持旧语义。
        makeIssueableDomestic("d-plain", "B-CC-PLAIN");
        clock.setFixed(ISSUE_AT);
        ServiceResult issued = reviewService.issue("B-CC-PLAIN", new IssueRequest("issue-cc-plain"));
        assertEquals(200, issued.httpStatus(), "未启用子批保持旧签发语义");
        assertBusiness(() -> coldChainService.getView("B-CC-PLAIN"),
                404, "COLD_CHAIN_NOT_REGISTERED");
    }

    @Test
    void correctionAfterIssueOnlyAddsRiskEventWithoutChangingCertificate() {
        makeIssueableDomestic("d-late", "B-CC-LATE");
        coldChainService.registerSegments(registerRequest("cc-reg-late", "B-CC-LATE"));
        coldChainService.submitReadings(submitRequest("cc-sub-late", "B-CC-LATE", 1, qualifiedReadings()));
        clock.setFixed(ISSUE_AT);
        assertEquals(200, reviewService.issue("B-CC-LATE", new IssueRequest("issue-cc-late")).httpStatus());

        // 签发后更正：仅登记风险事件，版本与温证不变。
        ServiceResult result = coldChainService.correct(new ColdChainCorrectRequest(
                "cc-cor-late", "B-CC-LATE", 1,
                List.of(new ColdChainSegmentInput(1, SEG_START, SEG_END)), SUBMIT_AT.plusSeconds(600)));
        assertEquals(200, result.httpStatus());
        ColdChainView view = asColdChainView(result);
        assertEquals(1, view.version(), "签发后更正当不得递增版本");
        assertEquals(1, view.qualifiedVersion());
        assertTrue(view.certificateValid(), "旧温证保持不变");
        assertTrue(reviewService.listEvents("B-CC-LATE").stream()
                        .anyMatch(e -> e.get("eventType").equals("COLD_CHAIN_CORRECTION_AFTER_ISSUE")),
                "签发后更正仅登记风险事件");
    }

    @Test
    void terminalReviewRejectsColdChainChanges() {
        makeIssueableDomestic("d-fin", "B-CC-FIN");
        clock.setFixed(ISSUE_AT);
        assertEquals(200, reviewService.issue("B-CC-FIN", new IssueRequest("issue-cc-fin")).httpStatus());

        assertBusiness(() -> coldChainService.registerSegments(registerRequest("cc-reg-fin", "B-CC-FIN")),
                422, "COLD_CHAIN_REVIEW_FINAL");
        assertBusiness(() -> coldChainService.getView("B-CC-FIN"), 404, "COLD_CHAIN_NOT_REGISTERED");
        assertBusiness(() -> coldChainService.registerSegments(registerRequest("cc-reg-none", "B-NO-SUCH")),
                404, "REVIEW_NOT_FOUND");
    }

    @Test
    void invalidSegmentStructureRejected() {
        clock.setFixed(T0);
        reviewService.declare(declareRequest("d-str", "B-CC-STR",
                allDocuments(), boundaryPassingInspections()));

        // 相邻端点未相接。
        List<ColdChainSegmentInput> broken = List.of(
                new ColdChainSegmentInput(1, SEG_START, SEG_MID),
                new ColdChainSegmentInput(2, SEG_MID.plusSeconds(1), SEG_END));
        assertBusiness(() -> coldChainService.registerSegments(
                        new ColdChainSegmentRegisterRequest("cc-reg-str-1", "B-CC-STR", broken, SUBMIT_AT)),
                422, "INVALID_SEGMENT_STRUCTURE");

        // 零长度片段。
        List<ColdChainSegmentInput> zeroLength = List.of(
                new ColdChainSegmentInput(1, SEG_START, SEG_START));
        assertBusiness(() -> coldChainService.registerSegments(
                        new ColdChainSegmentRegisterRequest("cc-reg-str-2", "B-CC-STR", zeroLength, SUBMIT_AT)),
                422, "INVALID_SEGMENT_STRUCTURE");

        // 缺序号读数（服务层防御，HTTP 层由 @NotNull 拦截为 400）。
        coldChainService.registerSegments(registerRequest("cc-reg-str-3", "B-CC-STR"));
        List<ColdChainSegmentReadingsInput> missingSeq = List.of(
                new ColdChainSegmentReadingsInput(1, List.of(
                        new ColdChainReadingInput(null, SEG_START, new BigDecimal("4")),
                        reading(2, SEG_START.plusSeconds(300), "5"),
                        reading(3, SEG_MID, "6"))),
                qualifiedReadings().get(1));
        assertBusiness(() -> coldChainService.submitReadings(
                        submitRequest("cc-sub-str", "B-CC-STR", 1, missingSeq)),
                422, "COLD_CHAIN_MISSING_READING_FIELD");
        assertNotNull(coldChainService.getView("B-CC-STR"));
    }
}
