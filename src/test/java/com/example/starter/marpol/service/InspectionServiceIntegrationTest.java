package com.example.starter.marpol.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.starter.marpol.MarpolIntegrationTestBase;
import com.example.starter.marpol.MutableClockConfiguration;
import com.example.starter.marpol.support.ApiException;
import com.example.starter.marpol.web.DischargeAmendRequest;
import com.example.starter.marpol.web.DischargeSubmitRequest;
import com.example.starter.marpol.web.InspectionCreateRequest;
import com.example.starter.marpol.web.InspectionDecisionRequest;
import com.example.starter.marpol.web.InspectionItemResponse;
import com.example.starter.marpol.web.InspectionResponse;
import com.example.starter.marpol.web.RecordResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/**
 * 港口国核查：逐条结论、缺陷限期（恰第 14 日有效）、签发终态、
 * 迟到修正不影响结论、复查引用原核查号、重复签发幂等。
 */
class InspectionServiceIntegrationTest extends MarpolIntegrationTestBase {

    private static final Instant EVENT_TIME = Instant.parse("2026-09-01T08:00:00Z");
    private static final LocalDate INSPECT_DATE = LocalDate.of(2026, 9, 10);

    @Autowired
    private EmissionService emissionService;

    @Autowired
    private InspectionService inspectionService;

    private DischargeSubmitRequest oil(String requestId) {
        // 区域外油类：提交时合规，但核查官仍可标注违规（记录客观事实，结论由核查官作出）
        return new DischargeSubmitRequest(requestId, "SHIP-001", "V-2026-01", "OIL",
                new BigDecimal("5"), new BigDecimal("115"), EVENT_TIME,
                new BigDecimal("2.500"), "m3");
    }

    private InspectionResponse createInspection(LocalDate date, String reviewOf) {
        return inspectionService.create(
                new InspectionCreateRequest("SHIP-001", "P-SYN-01", date, reviewOf));
    }

    @Test
    void violationCreatesDefectWithDeadlineInspectDatePlus14() {
        RecordResponse record = emissionService.submit(oil("REQ-V"));
        InspectionResponse inspection = createInspection(INSPECT_DATE, null);

        InspectionItemResponse item = inspectionService.decide(inspection.inspectionNo(),
                new InspectionDecisionRequest(record.recordNo(), "VIOLATION", "油水分离器异常"));
        assertEquals("VIOLATION", item.conclusion());
        assertEquals(1, item.recordVersion());
        assertNotNull(item.defectId());
        assertEquals("OPEN", item.defectStatus());
        assertEquals(INSPECT_DATE.plusDays(14), item.defectDeadline());

        var defects = inspectionService.listDefects(inspection.inspectionNo());
        assertEquals(1, defects.size());
        assertEquals(LocalDate.of(2026, 9, 24), defects.get(0).deadline());
    }

    @Test
    void closingDefectExactlyOn14thDayIsValidBut15thIsRejected() {
        RecordResponse record = emissionService.submit(oil("REQ-D"));
        InspectionResponse inspection = createInspection(INSPECT_DATE, null);
        InspectionItemResponse item = inspectionService.decide(inspection.inspectionNo(),
                new InspectionDecisionRequest(record.recordNo(), "VIOLATION", null));

        // 恰第 14 个自然日（UTC 日界）关闭有效
        MutableClockConfiguration.MutableTimeService clock =
                (MutableClockConfiguration.MutableTimeService) timeService;
        clock.setInstant(LocalDate.of(2026, 9, 24).atStartOfDay()
                .toInstant(java.time.ZoneOffset.UTC));
        var closed = inspectionService.closeDefect(item.defectId());
        assertEquals("CLOSED", closed.status());
        assertNotNull(closed.closedAt());

        // 第二条缺陷：放到第 15 日再关闭，应被拒绝并置 OVERDUE
        RecordResponse second = emissionService.submit(new DischargeSubmitRequest("REQ-D2",
                "SHIP-001", "V-2026-01", "OIL", new BigDecimal("6"), new BigDecimal("115"),
                EVENT_TIME, new BigDecimal("1.000"), "m3"));
        InspectionItemResponse item2 = inspectionService.decide(inspection.inspectionNo(),
                new InspectionDecisionRequest(second.recordNo(), "VIOLATION", null));
        clock.setInstant(LocalDate.of(2026, 9, 25).atStartOfDay()
                .toInstant(java.time.ZoneOffset.UTC));
        ApiException ex = assertThrows(ApiException.class,
                () -> inspectionService.closeDefect(item2.defectId()));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
        assertEquals("OVERDUE", inspectionService.listDefects(inspection.inspectionNo()).stream()
                .filter(d -> d.id().equals(item2.defectId())).findFirst().orElseThrow()
                .status());
    }

    @Test
    void issueIsTerminalAndRepeatedIssueIsIdempotent() {
        RecordResponse record = emissionService.submit(oil("REQ-I"));
        InspectionResponse inspection = createInspection(INSPECT_DATE, null);
        inspectionService.decide(inspection.inspectionNo(),
                new InspectionDecisionRequest(record.recordNo(), "CONFIRM", null));

        InspectionResponse issued = inspectionService.issue(inspection.inspectionNo());
        assertEquals("ISSUED", issued.status());
        assertNotNull(issued.issuedAt());

        // 重复签发幂等成功
        InspectionResponse again = inspectionService.issue(inspection.inspectionNo());
        assertEquals("ISSUED", again.status());
        assertEquals(issued.issuedAt(), again.issuedAt());

        // 签发后修改结论一律拒绝
        ApiException ex = assertThrows(ApiException.class,
                () -> inspectionService.decide(inspection.inspectionNo(),
                        new InspectionDecisionRequest(record.recordNo(), "VIOLATION", null)));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());

        // 新增条目也拒绝
        RecordResponse other = emissionService.submit(oil("REQ-I2"));
        ApiException ex2 = assertThrows(ApiException.class,
                () -> inspectionService.decide(inspection.inspectionNo(),
                        new InspectionDecisionRequest(other.recordNo(), "CONFIRM", null)));
        assertEquals(HttpStatus.CONFLICT, ex2.getStatus());
    }

    @Test
    void lateAmendAfterDecisionDoesNotChangeConclusion() {
        RecordResponse record = emissionService.submit(oil("REQ-L"));
        InspectionResponse inspection = createInspection(INSPECT_DATE, null);
        inspectionService.decide(inspection.inspectionNo(),
                new InspectionDecisionRequest(record.recordNo(), "CONFIRM", null));

        // 核查进行中产生迟到修正：结论已固定在版本 1
        RecordResponse revised = emissionService.amend(new DischargeAmendRequest("REQ-L-A",
                record.recordNo(), 1, "SHIP-001", "V-2026-01", "OIL",
                new BigDecimal("7"), new BigDecimal("115"), EVENT_TIME,
                new BigDecimal("9.000"), "m3"));
        assertEquals(2, revised.version());

        inspectionService.issue(inspection.inspectionNo());
        List<InspectionItemResponse> items =
                inspectionService.listItems(inspection.inspectionNo());
        assertEquals(1, items.size());
        assertEquals(1, items.get(0).recordVersion(), "结论以签发时刻已确认的版本 1 为准");
        assertEquals(record.recordNo(), items.get(0).recordNo());
    }

    @Test
    void changingExistingConclusionBeforeIssueIsRejected() {
        RecordResponse record = emissionService.submit(oil("REQ-C"));
        InspectionResponse inspection = createInspection(INSPECT_DATE, null);
        inspectionService.decide(inspection.inspectionNo(),
                new InspectionDecisionRequest(record.recordNo(), "CONFIRM", null));
        ApiException ex = assertThrows(ApiException.class,
                () -> inspectionService.decide(inspection.inspectionNo(),
                        new InspectionDecisionRequest(record.recordNo(), "VIOLATION", null)));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    @Test
    void reviewAfterIssueReferencesOriginalInspection() {
        RecordResponse record = emissionService.submit(oil("REQ-R"));
        InspectionResponse original = createInspection(INSPECT_DATE, null);
        inspectionService.decide(original.inspectionNo(),
                new InspectionDecisionRequest(record.recordNo(), "VIOLATION", null));
        inspectionService.issue(original.inspectionNo());

        // 签发后补充证据走复查并引用原核查号
        InspectionResponse review = createInspection(INSPECT_DATE.plusDays(3),
                original.inspectionNo());
        assertEquals(original.inspectionNo(), review.reviewOf());
        assertEquals("OPEN", review.status());

        ApiException cannotReviewOpen = assertThrows(ApiException.class,
                () -> createInspection(INSPECT_DATE.plusDays(4), review.inspectionNo()));
        assertEquals(HttpStatus.CONFLICT, cannotReviewOpen.getStatus());
    }

    @Test
    void concurrentAmendAndDecisionPinConclusionToConfirmedVersion() throws Exception {
        RecordResponse record = emissionService.submit(oil("REQ-X"));
        InspectionResponse inspection = createInspection(INSPECT_DATE, null);

        java.util.concurrent.CountDownLatch ready =
                new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch start =
                new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<InspectionItemResponse> decided =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<RecordResponse> amended =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> decideError =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> amendError =
                new java.util.concurrent.atomic.AtomicReference<>();
        Runnable markViolation = () -> {
            ready.countDown();
            try {
                start.await();
                decided.set(inspectionService.decide(inspection.inspectionNo(),
                        new InspectionDecisionRequest(record.recordNo(), "VIOLATION", "并发现场")));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                decideError.set(t);
            }
        };
        Runnable amend = () -> {
            ready.countDown();
            try {
                start.await();
                amended.set(emissionService.amend(new DischargeAmendRequest("REQ-X-A",
                        record.recordNo(), 1, "SHIP-001", "V-2026-01", "OIL",
                        new BigDecimal("7"), new BigDecimal("115"), EVENT_TIME,
                        new BigDecimal("9.000"), "m3")));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                amendError.set(t);
            }
        };
        Thread t1 = new Thread(markViolation, "psc-decide");
        Thread t2 = new Thread(amend, "ship-amend");
        t1.start();
        t2.start();
        assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
        start.countDown();
        t1.join(30000);
        t2.join(30000);

        if (decideError.get() != null) {
            throw new AssertionError("核查标注线程异常", decideError.get());
        }
        if (amendError.get() != null) {
            throw new AssertionError("修正线程异常", amendError.get());
        }
        assertNotNull(decided.get(), "核查结论必须落定");
        assertNotNull(amended.get(), "修正必须成功");
        List<InspectionItemResponse> items =
                inspectionService.listItems(inspection.inspectionNo());
        assertEquals(1, items.size());
        // 结论锁定其确认时刻的版本（v1 或修正后的 v2），之后不再被迟到修正改变
        assertTrue(items.get(0).recordVersion() == 1 || items.get(0).recordVersion() == 2,
                "结论版本应为确认时刻的有效版本，实际 " + items.get(0).recordVersion());
        inspectionService.issue(inspection.inspectionNo());
        Integer pinnedVersion = items.get(0).recordVersion();
        // 再做一次迟到修正，结论版本仍不变
        emissionService.amend(new DischargeAmendRequest("REQ-X-A2",
                record.recordNo(), amended.get().version(), "SHIP-001", "V-2026-01", "OIL",
                new BigDecimal("8"), new BigDecimal("115"), EVENT_TIME,
                new BigDecimal("1.000"), "m3"));
        assertEquals(pinnedVersion,
                inspectionService.listItems(inspection.inspectionNo()).get(0).recordVersion());
        assertEquals("ISSUED", inspectionService.get(inspection.inspectionNo()).status());
    }

    @Test
    void createInspectionRejectsUnknownShipOrPort() {
        ApiException badShip = assertThrows(ApiException.class, () -> inspectionService.create(
                new InspectionCreateRequest("NO-SHIP", "P-SYN-01", INSPECT_DATE, null)));
        assertEquals(HttpStatus.NOT_FOUND, badShip.getStatus());
        ApiException badPort = assertThrows(ApiException.class, () -> inspectionService.create(
                new InspectionCreateRequest("SHIP-001", "NO-PORT", INSPECT_DATE, null)));
        assertEquals(HttpStatus.NOT_FOUND, badPort.getStatus());
    }

    @Test
    void defaultInspectDateDerivedFromClockAndDecisionOnUnknownRecordIs404() {
        InspectionResponse inspection = createInspection(null, null);
        assertEquals(LocalDate.of(2026, 9, 10), inspection.inspectDate());
        ApiException ex = assertThrows(ApiException.class,
                () -> inspectionService.decide(inspection.inspectionNo(),
                        new InspectionDecisionRequest("ER-NOPE", "CONFIRM", null)));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        assertFalse(inspectionService.listItems(inspection.inspectionNo()).contains(null));
    }
}
