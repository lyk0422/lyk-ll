package com.example.starter.service;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.dto.ColdChainLedgerView;
import com.example.starter.dto.ColdChainSubmitRequest;
import com.example.starter.dto.CustomsBindRequest;
import com.example.starter.dto.CustomsLedgerView;
import com.example.starter.dto.CustomsRegisterRequest;
import com.example.starter.dto.IssueRequest;
import com.example.starter.dto.ReadingInput;
import com.example.starter.dto.SegmentInput;
import com.example.starter.dto.ServiceResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 冷链运输片段核验 H2 集成测试：
 * 两片段完整合格后签发、漏一个端点拒签、补录/更正使旧温证失效须重核、
 * 版本冲突 409、requestId 幂等（同参重放/异参 409/失败不占键）、
 * 签发后补录仅加风险不回改旧证书、未启用子批保持旧语义。
 */
class ColdChainFlowTest extends AbstractIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant ISSUE_AT = Instant.parse("2026-09-08T00:00:00Z");
    private static final Instant VALID_UNTIL = Instant.parse("2026-12-31T00:00:00Z");
    /** 运输区间 [2026-08-31T10:00, 10:20]，提交时刻 10:30。 */
    private static final Instant TRANSPORT_START = Instant.parse("2026-08-31T10:00:00Z");
    private static final Instant SUBMITTED_AT = Instant.parse("2026-08-31T10:30:00Z");

    private ColdChainLedgerView asLedgerView(ServiceResult result) {
        return jsonSupport.convertValue(result.body(), ColdChainLedgerView.class);
    }

    /** 登记通关单 + 可签发进口子批 + 绑定预占 30 支，推进到公示期满生效。 */
    private void prepareBoundIssueableBatch(String suffix) {
        clock.setFixed(T0);
        customsService.register(new CustomsRegisterRequest("cc-reg-" + suffix, "CI-CC-" + suffix,
                IMPORT_ENTERPRISE, IMPORT_PRODUCT, VALID_UNTIL, 100, T0));
        prepareIssueableImportBatch("cc-d-" + suffix, "B-CC-" + suffix);
        clock.setFixed(ISSUE_AT);
        reviewService.settlePublicity("B-CC-" + suffix);
        customsService.bind(new CustomsBindRequest("cc-bind-" + suffix, "CI-CC-" + suffix,
                "B-CC-" + suffix, 30, 1, T0));
    }

    private ColdChainSubmitRequest submitRequest(String requestId, int expectedVersion,
                                                 List<SegmentInput> segments) {
        return new ColdChainSubmitRequest(requestId, expectedVersion, SUBMITTED_AT, segments);
    }

    @Test
    void twoSegmentsQualifiedThenIssueSucceeds() {
        prepareBoundIssueableBatch("ok");
        ServiceResult submitted = coldChainService.submit("B-CC-ok",
                submitRequest("cc-sub-ok", 0, qualifiedTwoSegments(TRANSPORT_START)));
        assertEquals(200, submitted.httpStatus());
        ColdChainLedgerView ledger = asLedgerView(submitted);
        assertEquals("QUALIFIED", ledger.status());
        assertEquals(1, ledger.version());
        assertEquals(1, ledger.certVersion());
        assertTrue(ledger.certValid());
        assertEquals(TRANSPORT_START, ledger.transportStart());
        assertEquals(TRANSPORT_START.plusSeconds(1200), ledger.transportEnd());
        assertEquals(2, ledger.segments().size());
        assertTrue(ledger.violations().isEmpty());

        ServiceResult issued = reviewService.issue("B-CC-ok", new IssueRequest("cc-issue-ok"));
        assertEquals(200, issued.httpStatus(), "温证合格且通关未过期应签发成功");
        assertTrue(reviewService.listEvents("B-CC-ok").stream()
                        .anyMatch(e -> e.get("eventType").equals("COLDCHAIN_CERT_VERIFIED")),
                "签发须登记温证核验事件");
        CustomsLedgerView customs = customsService.getLedger("CI-CC-ok");
        assertEquals(30, customs.writtenOffQuantity(), "签发后核销 30 支");
    }

    @Test
    void missingOneEndSampleBlocksIssueUntilCorrected() {
        prepareBoundIssueableBatch("gap");
        // 第二片缺终点样本：证据登记成功但温证不合格。
        List<SegmentInput> segments = new ArrayList<>(qualifiedTwoSegments(TRANSPORT_START));
        SegmentInput second = segments.get(1);
        List<ReadingInput> broken = second.readings().subList(0, second.readings().size() - 1);
        segments.set(1, new SegmentInput(2, second.start(), second.end(), broken));

        ServiceResult submitted = coldChainService.submit("B-CC-gap",
                submitRequest("cc-sub-gap", 0, segments));
        assertEquals(200, submitted.httpStatus());
        ColdChainLedgerView ledger = asLedgerView(submitted);
        assertEquals("UNQUALIFIED", ledger.status());
        assertFalse(ledger.certValid());
        assertEquals(1, ledger.violations().size());
        assertEquals("MISSING_END_SAMPLE", ledger.violations().get(0).code(),
                "缺终点样本须如实记缺口，不按零度补齐");
        assertEquals(second.end(), ledger.violations().get(0).at());

        ServiceResult blocked = reviewService.issue("B-CC-gap", new IssueRequest("cc-issue-gap"));
        assertEquals(422, blocked.httpStatus(), "漏一个端点必须拒签");

        // 补录更正：携带当前版本 1，完整证据重核合格，版本前进到 2。
        ServiceResult corrected = coldChainService.submit("B-CC-gap",
                submitRequest("cc-sub-gap-fix", 1, qualifiedTwoSegments(TRANSPORT_START)));
        assertEquals(200, corrected.httpStatus());
        ColdChainLedgerView fixed = asLedgerView(corrected);
        assertEquals("QUALIFIED", fixed.status());
        assertEquals(2, fixed.version());
        assertEquals(2, fixed.certVersion());
        assertTrue(fixed.certValid());

        ServiceResult issued = reviewService.issue("B-CC-gap", new IssueRequest("cc-issue-gap-2"));
        assertEquals(200, issued.httpStatus(), "更正重核合格后应签发成功");
    }

    @Test
    void unqualifiedSupplementInvalidatesOldCertAndRequiresReverification() {
        prepareBoundIssueableBatch("inv");
        coldChainService.submit("B-CC-inv",
                submitRequest("cc-sub-inv", 0, qualifiedTwoSegments(TRANSPORT_START)));

        // 补录一份越界证据（9.5℃）：旧温证资格失效，必须重核。
        List<SegmentInput> outOfRange = new ArrayList<>(qualifiedTwoSegments(TRANSPORT_START));
        SegmentInput first = outOfRange.get(0);
        List<ReadingInput> readings = new ArrayList<>(first.readings());
        readings.set(1, new ReadingInput(2, readings.get(1).at(), new BigDecimal("9.5")));
        outOfRange.set(0, new SegmentInput(1, first.start(), first.end(), readings));

        ServiceResult supplemented = coldChainService.submit("B-CC-inv",
                submitRequest("cc-sub-inv-2", 1, outOfRange));
        ColdChainLedgerView ledger = asLedgerView(supplemented);
        assertEquals("UNQUALIFIED", ledger.status());
        assertEquals(2, ledger.version());
        assertEquals(1, ledger.certVersion(), "历史合格版本保留但不等于当前版本");
        assertFalse(ledger.certValid(), "旧温证资格已失效");
        assertEquals("TEMPERATURE_OUT_OF_RANGE", ledger.violations().get(0).code());

        ServiceResult blocked = reviewService.issue("B-CC-inv", new IssueRequest("cc-issue-inv"));
        assertEquals(422, blocked.httpStatus(), "补录后未重核合格不得签发");

        // 更正为合格证据（版本 3）后签发成功。
        coldChainService.submit("B-CC-inv",
                submitRequest("cc-sub-inv-3", 2, qualifiedTwoSegments(TRANSPORT_START)));
        ServiceResult issued = reviewService.issue("B-CC-inv", new IssueRequest("cc-issue-inv-2"));
        assertEquals(200, issued.httpStatus());
        ColdChainLedgerView finalLedger = coldChainService.getLedger("B-CC-inv");
        assertEquals(3, finalLedger.version());
        assertEquals(3, finalLedger.certVersion());
    }

    @Test
    void staleExpectedVersionConflict409AndCreateRequiresZero() {
        prepareBoundIssueableBatch("ver");
        // 未登记时期望版本非 0：409。
        BusinessException createConflict = assertThrows(BusinessException.class, () ->
                coldChainService.submit("B-CC-ver",
                        submitRequest("cc-sub-ver-bad", 1, qualifiedTwoSegments(TRANSPORT_START))));
        assertEquals(409, createConflict.getHttpStatus());
        assertEquals("VERSION_CONFLICT", createConflict.getError());

        coldChainService.submit("B-CC-ver",
                submitRequest("cc-sub-ver", 0, qualifiedTwoSegments(TRANSPORT_START)));
        // 旧版本 0 再登记：409。
        BusinessException stale = assertThrows(BusinessException.class, () ->
                coldChainService.submit("B-CC-ver",
                        submitRequest("cc-sub-ver-stale", 0, qualifiedTwoSegments(TRANSPORT_START))));
        assertEquals(409, stale.getHttpStatus());
        assertEquals("VERSION_CONFLICT", stale.getError());
        // 台账仍是版本 1，未被冲突请求改动。
        assertEquals(1, coldChainService.getLedger("B-CC-ver").version());
    }

    @Test
    void idempotencyReplayMismatch409AndFailureDoesNotOccupyKey() {
        prepareBoundIssueableBatch("idem");
        ServiceResult first = coldChainService.submit("B-CC-idem",
                submitRequest("cc-sub-idem", 0, qualifiedTwoSegments(TRANSPORT_START)));
        assertEquals(200, first.httpStatus());

        // 同 requestId 同参：重放首次响应，版本不前进。
        ServiceResult replay = coldChainService.submit("B-CC-idem",
                submitRequest("cc-sub-idem", 0, qualifiedTwoSegments(TRANSPORT_START)));
        assertEquals(200, replay.httpStatus());
        assertEquals(1, asLedgerView(replay).version());
        assertEquals(1, coldChainService.getLedger("B-CC-idem").version());

        // 同 requestId 异参（改提交时刻）：409 且业务状态不变。
        ColdChainSubmitRequest mismatched = new ColdChainSubmitRequest("cc-sub-idem", 0,
                SUBMITTED_AT.plusSeconds(60), qualifiedTwoSegments(TRANSPORT_START));
        ServiceResult conflict = coldChainService.submit("B-CC-idem", mismatched);
        assertEquals(409, conflict.httpStatus());
        assertEquals(1, coldChainService.getLedger("B-CC-idem").version());

        // 失败不占键：版本冲突的 requestId 回滚释放，修正版本后同键可成功。
        assertThrows(BusinessException.class, () ->
                coldChainService.submit("B-CC-idem",
                        submitRequest("cc-sub-idem-reuse", 5, qualifiedTwoSegments(TRANSPORT_START))));
        ServiceResult reused = coldChainService.submit("B-CC-idem",
                submitRequest("cc-sub-idem-reuse", 1, qualifiedTwoSegments(TRANSPORT_START)));
        assertEquals(200, reused.httpStatus(), "失败请求不占键，同 requestId 修正参数后可成功");
        assertEquals(2, asLedgerView(reused).version());
    }

    @Test
    void postIssueSupplementOnlyAddsRiskWithoutTouchingCertOrWriteOff() {
        prepareBoundIssueableBatch("post");
        coldChainService.submit("B-CC-post",
                submitRequest("cc-sub-post", 0, qualifiedTwoSegments(TRANSPORT_START)));
        ServiceResult issued = reviewService.issue("B-CC-post", new IssueRequest("cc-issue-post"));
        assertEquals(200, issued.httpStatus());

        // 签发后补录：仅登记风险事件，版本/温证/核销不变。
        ServiceResult late = coldChainService.submit("B-CC-post",
                submitRequest("cc-sub-post-late", 1, qualifiedTwoSegments(TRANSPORT_START)));
        assertEquals(200, late.httpStatus());
        ColdChainLedgerView ledger = asLedgerView(late);
        assertEquals(1, ledger.version(), "签发后补录不得推进版本");
        assertEquals(1, ledger.certVersion());
        assertTrue(ledger.certValid());
        assertTrue(reviewService.listEvents("B-CC-post").stream()
                        .anyMatch(e -> e.get("eventType").equals("COLDCHAIN_SUPPLEMENT_AFTER_ISSUE")),
                "签发后补录须登记风险事件");
        CustomsLedgerView customs = customsService.getLedger("CI-CC-post");
        assertEquals(30, customs.writtenOffQuantity(), "核销数量保持不变");
        assertEquals(0, customs.reservedQuantity());
    }

    @Test
    void unenabledSubbatchKeepsOldSemantics() {
        prepareBoundIssueableBatch("off");
        // 未登记冷链台账：原签发入口不加冷链门禁，旧语义不变。
        ServiceResult issued = reviewService.issue("B-CC-off", new IssueRequest("cc-issue-off"));
        assertEquals(200, issued.httpStatus());
        assertFalse(reviewService.listEvents("B-CC-off").stream()
                .anyMatch(e -> e.get("eventType").equals("COLDCHAIN_CERT_VERIFIED")));

        // 未启用子批查询冷链台账：404。
        BusinessException notEnabled = assertThrows(BusinessException.class, () ->
                coldChainService.getLedger("B-CC-off"));
        assertEquals(404, notEnabled.getHttpStatus());
        assertEquals("COLDCHAIN_NOT_ENABLED", notEnabled.getError());
    }

    @Test
    void boundaryTemps2And8AndExact5MinIntervalQualified() {
        prepareBoundIssueableBatch("bnd");
        // 单片段 10 分钟，样本恰 2℃/8℃/2℃，间隔恰 5 分钟。
        SegmentInput segment = new SegmentInput(1, TRANSPORT_START, TRANSPORT_START.plusSeconds(600),
                List.of(
                        new ReadingInput(1, TRANSPORT_START, new BigDecimal("2.0")),
                        new ReadingInput(2, TRANSPORT_START.plusSeconds(300), new BigDecimal("8.0")),
                        new ReadingInput(3, TRANSPORT_START.plusSeconds(600), new BigDecimal("2"))));
        ServiceResult submitted = coldChainService.submit("B-CC-bnd",
                submitRequest("cc-sub-bnd", 0, List.of(segment)));
        ColdChainLedgerView ledger = asLedgerView(submitted);
        assertEquals("QUALIFIED", ledger.status(), "恰 2℃/8℃ 边界与恰 5 分钟间隔应合格");
        assertTrue(ledger.certValid());
    }

    @Test
    void interval5MinPlus1SecondUnqualified() {
        prepareBoundIssueableBatch("int");
        // 单片段 10 分钟，仅两端样本且间隔 601 秒：超限。
        Instant start = TRANSPORT_START;
        Instant end = TRANSPORT_START.plusSeconds(601);
        SegmentInput segment = new SegmentInput(1, start, end, List.of(
                new ReadingInput(1, start, new BigDecimal("5.0")),
                new ReadingInput(2, end, new BigDecimal("5.0"))));
        ServiceResult submitted = coldChainService.submit("B-CC-int",
                submitRequest("cc-sub-int", 0, List.of(segment)));
        ColdChainLedgerView ledger = asLedgerView(submitted);
        assertEquals("UNQUALIFIED", ledger.status());
        assertEquals("SAMPLE_INTERVAL_EXCEEDED", ledger.violations().get(0).code());
        assertEquals("601", ledger.violations().get(0).value());
    }

    @Test
    void readingAfterSubmissionUnqualified() {
        prepareBoundIssueableBatch("fut");
        // 提交时刻早于运输终点：末端读数晚于提交时刻。
        ColdChainSubmitRequest request = new ColdChainSubmitRequest("cc-sub-fut", 0,
                TRANSPORT_START.plusSeconds(600), qualifiedTwoSegments(TRANSPORT_START));
        ServiceResult submitted = coldChainService.submit("B-CC-fut", request);
        ColdChainLedgerView ledger = asLedgerView(submitted);
        assertEquals("UNQUALIFIED", ledger.status());
        assertTrue(ledger.violations().stream()
                .anyMatch(v -> v.code().equals("READING_AFTER_SUBMISSION")));
    }
}
