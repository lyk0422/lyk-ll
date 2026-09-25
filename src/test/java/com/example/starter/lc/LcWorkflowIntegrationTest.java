package com.example.starter.lc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.starter.lc.dto.CreateLcRequest;
import com.example.starter.lc.dto.DiscrepancyItem;
import com.example.starter.lc.dto.DocumentItem;
import com.example.starter.lc.dto.ErrorResponse;
import com.example.starter.lc.dto.LcResponse;
import com.example.starter.lc.dto.OperationResult;
import com.example.starter.lc.dto.PayRequest;
import com.example.starter.lc.dto.PresentDocumentsRequest;
import com.example.starter.lc.dto.RefuseRequest;
import com.example.starter.lc.dto.SubmissionResponse;
import com.example.starter.lc.dto.WaiveDiscrepanciesRequest;
import com.example.starter.lc.service.LcService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 信用证审单业务主流程与失败分支 H2 集成测试。
 */
@SpringBootTest
class LcWorkflowIntegrationTest extends AbstractLcIntegrationTest {

    /** 2026-09-18 周五 10:00 UTC 收到单据，截止时刻为 2026-09-25 周五 17:00 UTC。 */
    private static final Instant PRESENTED_AT = Instant.parse("2026-09-18T10:00:00Z");

    private static final Instant DEADLINE = Instant.parse("2026-09-25T17:00:00Z");

    private static final List<DocumentItem> DOCUMENTS = List.of(
            new DocumentItem("BILL_OF_LADING", "BL-2026-001"),
            new DocumentItem("COMMERCIAL_INVOICE", "INV-2026-009"));

    private static final List<DiscrepancyItem> TWO_DISCREPANCIES = List.of(
            new DiscrepancyItem("BILL_OF_LADING", "装船日期晚于信用证最迟装运日"),
            new DiscrepancyItem("COMMERCIAL_INVOICE", "发票金额与汇票金额不一致"));

    @Autowired
    private LcService lcService;

    @Override
    protected LcService service() {
        return lcService;
    }

    @Test
    void createLcAndQuery() {
        createDefaultLc("req-lc-1");
        LcResponse lc = lcService.getLc(LC_NO);
        assertThat(lc.lcNo()).isEqualTo(LC_NO);
        assertThat(lc.availableAmount()).isEqualByComparingTo("10000.00");
        assertThat(lc.status()).isEqualTo("ACTIVE");
    }

    @Test
    void refusalBeforeDeadlineLocksDocumentsWithAllDiscrepancies() {
        createDefaultLc("req-lc-1");
        SubmissionResponse batch = present("req-present-1", PRESENTED_AT);
        assertThat(batch.status()).isEqualTo("UNDER_REVIEW");
        assertThat(batch.deadlineAt()).isEqualTo(DEADLINE);

        SubmissionResponse refused = asSubmission(lcService.refuse(new RefuseRequest(
                "req-refuse-1", batch.batchNo(),
                Instant.parse("2026-09-25T16:59:59Z"), TWO_DISCREPANCIES)));
        assertThat(refused.status()).isEqualTo("REFUSED");
        assertThat(refused.discrepancies()).hasSize(2);
        assertThat(refused.refusedAt()).isEqualTo(Instant.parse("2026-09-25T16:59:59Z"));
        assertThat(row(batch.batchNo()).status()).isEqualTo("REFUSED");
    }

    @Test
    void refusalAt1700SharpIsInvalidAndDeemedAccepted() {
        createDefaultLc("req-lc-1");
        SubmissionResponse batch = present("req-present-1", PRESENTED_AT);

        OperationResult result = lcService.refuse(new RefuseRequest(
                "req-refuse-late", batch.batchNo(), DEADLINE, TWO_DISCREPANCIES));
        assertThat(result).isInstanceOf(OperationResult.Fresh.class);
        OperationResult.Fresh fresh = (OperationResult.Fresh) result;
        assertThat(fresh.httpStatus()).isEqualTo(422);
        assertThat(((ErrorResponse) fresh.body()).errorCode()).isEqualTo("REFUSAL_TOO_LATE");
        // 拒付无效：未登记任何不符点，批次自动转视为接受。
        assertThat(row(batch.batchNo()).status()).isEqualTo("DEEMED_ACCEPTED");
        assertThat(submissionRepository.findDiscrepancies(batch.batchNo())).isEmpty();
    }

    @Test
    void refusalAfterDeadlineIsInvalidAndDeemedAccepted() {
        createDefaultLc("req-lc-1");
        SubmissionResponse batch = present("req-present-1", PRESENTED_AT);

        OperationResult result = lcService.refuse(new RefuseRequest(
                "req-refuse-late2", batch.batchNo(),
                Instant.parse("2026-09-28T09:00:00Z"), TWO_DISCREPANCIES));
        assertThat(((ErrorResponse) ((OperationResult.Fresh) result).body()).errorCode())
                .isEqualTo("REFUSAL_TOO_LATE");
        assertThat(row(batch.batchNo()).status()).isEqualTo("DEEMED_ACCEPTED");
    }

    @Test
    void noRefusalBeforeDeadlineButQueryAfterDeadlineDeemsAccepted() {
        createDefaultLc("req-lc-1");
        // 2026-09-04 周五收到，截止 2026-09-11 周五 17:00 UTC，运行测试时该时刻必已过去。
        Instant presentedAt = Instant.parse("2026-09-04T10:00:00Z");
        Instant deadline = Instant.parse("2026-09-11T17:00:00Z");
        SubmissionResponse batch = asSubmission(lcService.present(new PresentDocumentsRequest(
                "req-present-1", LC_NO, new BigDecimal("8000.00"), presentedAt, DOCUMENTS)));
        assertThat(batch.deadlineAt()).isEqualTo(deadline);
        assertThat(row(batch.batchNo()).status()).isEqualTo("UNDER_REVIEW");

        SubmissionResponse viewed = lcService.getSubmission(batch.batchNo());
        // 服务时钟为真实当前时间，截止时刻 2026-09-11 已过：查询自动转视为接受。
        assertThat(viewed.status()).isEqualTo("DEEMED_ACCEPTED");
        assertThat(viewed.acceptedAt()).isEqualTo(deadline);
        assertThat(row(batch.batchNo()).status()).isEqualTo("DEEMED_ACCEPTED");
    }

    @Test
    void discrepanciesMustBeListedOnceAndCannotBeSupplemented() {
        createDefaultLc("req-lc-1");
        SubmissionResponse batch = present("req-present-1", PRESENTED_AT);
        asSubmission(lcService.refuse(new RefuseRequest("req-refuse-1", batch.batchNo(),
                Instant.parse("2026-09-24T10:00:00Z"),
                List.of(TWO_DISCREPANCIES.get(0)))));

        // 再次拒付试图补充新不符点：锁定，返回 409，不符点仍只有 1 条。
        assertThatThrownBy(() -> lcService.refuse(new RefuseRequest(
                "req-refuse-2", batch.batchNo(),
                Instant.parse("2026-09-24T11:00:00Z"),
                List.of(TWO_DISCREPANCIES.get(0), TWO_DISCREPANCIES.get(1)))))
                .isInstanceOf(LcBusinessException.class)
                .extracting(e -> ((LcBusinessException) e).getErrorCode())
                .isEqualTo(LcErrorCode.DISCREPANCIES_LOCKED);
        assertThat(submissionRepository.findDiscrepancies(batch.batchNo())).hasSize(1);
        assertThat(row(batch.batchNo()).status()).isEqualTo("REFUSED");
    }

    @Test
    void partialWaiverIsInvalid() {
        createDefaultLc("req-lc-1");
        SubmissionResponse batch = present("req-present-1", PRESENTED_AT);
        asSubmission(lcService.refuse(new RefuseRequest("req-refuse-1", batch.batchNo(),
                Instant.parse("2026-09-24T10:00:00Z"), TWO_DISCREPANCIES)));

        // 仅放弃其中一条：部分放弃无效，批次保持 REFUSED，不符点均未标记放弃。
        assertThatThrownBy(() -> lcService.waive(new WaiveDiscrepanciesRequest(
                "req-waive-partial", batch.batchNo(), APPLICANT,
                Instant.parse("2026-09-24T14:00:00Z"),
                List.of(TWO_DISCREPANCIES.get(0)))))
                .isInstanceOf(LcBusinessException.class)
                .extracting(e -> ((LcBusinessException) e).getErrorCode())
                .isEqualTo(LcErrorCode.PARTIAL_WAIVER);
        assertThat(row(batch.batchNo()).status()).isEqualTo("REFUSED");
        assertThat(submissionRepository.findDiscrepancies(batch.batchNo()))
                .allMatch(d -> !d.waived());
    }

    @Test
    void waiverWithExtraDiscrepancyIsInvalid() {
        createDefaultLc("req-lc-1");
        SubmissionResponse batch = present("req-present-1", PRESENTED_AT);
        asSubmission(lcService.refuse(new RefuseRequest("req-refuse-1", batch.batchNo(),
                Instant.parse("2026-09-24T10:00:00Z"), List.of(TWO_DISCREPANCIES.get(0)))));

        // 夹带未列明的新不符点同样视为未完整覆盖，无效。
        assertThatThrownBy(() -> lcService.waive(new WaiveDiscrepanciesRequest(
                "req-waive-extra", batch.batchNo(), APPLICANT,
                Instant.parse("2026-09-24T14:00:00Z"), TWO_DISCREPANCIES)))
                .isInstanceOf(LcBusinessException.class)
                .extracting(e -> ((LcBusinessException) e).getErrorCode())
                .isEqualTo(LcErrorCode.PARTIAL_WAIVER);
        assertThat(row(batch.batchNo()).status()).isEqualTo("REFUSED");
    }

    @Test
    void fullWaiverAcceptsAndPaymentDeductsAmount() {
        createDefaultLc("req-lc-1");
        SubmissionResponse batch = present("req-present-1", PRESENTED_AT);
        asSubmission(lcService.refuse(new RefuseRequest("req-refuse-1", batch.batchNo(),
                Instant.parse("2026-09-24T10:00:00Z"), TWO_DISCREPANCIES)));

        SubmissionResponse accepted = asSubmission(lcService.waive(new WaiveDiscrepanciesRequest(
                "req-waive-all", batch.batchNo(), APPLICANT,
                Instant.parse("2026-09-24T15:00:00Z"), TWO_DISCREPANCIES)));
        assertThat(accepted.status()).isEqualTo("ACCEPTED");
        assertThat(accepted.discrepancies()).allMatch(SubmissionResponse.DiscrepancyView::waived);

        SubmissionResponse paid = asSubmission(lcService.pay(new PayRequest(
                "req-pay-1", batch.batchNo(), Instant.parse("2026-09-24T16:00:00Z"))));
        assertThat(paid.status()).isEqualTo("PAID");
        assertThat(lcRow(LC_NO).availableAmount()).isEqualByComparingTo("2000.00");
    }

    @Test
    void waiverByWrongApplicantIsRejected() {
        createDefaultLc("req-lc-1");
        SubmissionResponse batch = present("req-present-1", PRESENTED_AT);
        asSubmission(lcService.refuse(new RefuseRequest("req-refuse-1", batch.batchNo(),
                Instant.parse("2026-09-24T10:00:00Z"), TWO_DISCREPANCIES)));

        assertThatThrownBy(() -> lcService.waive(new WaiveDiscrepanciesRequest(
                "req-waive-wrong", batch.batchNo(), "SOME OTHER COMPANY",
                Instant.parse("2026-09-24T15:00:00Z"), TWO_DISCREPANCIES)))
                .isInstanceOf(LcBusinessException.class)
                .extracting(e -> ((LcBusinessException) e).getErrorCode())
                .isEqualTo(LcErrorCode.INVALID_REQUEST);
        assertThat(row(batch.batchNo()).status()).isEqualTo("REFUSED");
    }

    @Test
    void paymentWithExactlySufficientAmountSucceeds() {
        createDefaultLc("req-lc-1", LC_NO, new BigDecimal("8000.00"));
        SubmissionResponse batch = present("req-present-1", PRESENTED_AT, new BigDecimal("8000.00"));
        asSubmission(lcService.refuse(new RefuseRequest("req-refuse-1", batch.batchNo(),
                Instant.parse("2026-09-24T10:00:00Z"), TWO_DISCREPANCIES)));
        asSubmission(lcService.waive(new WaiveDiscrepanciesRequest(
                "req-waive-all", batch.batchNo(), APPLICANT,
                Instant.parse("2026-09-24T15:00:00Z"), TWO_DISCREPANCIES)));

        SubmissionResponse paid = asSubmission(lcService.pay(new PayRequest(
                "req-pay-exact", batch.batchNo(), Instant.parse("2026-09-24T16:00:00Z"))));
        assertThat(paid.status()).isEqualTo("PAID");
        assertThat(lcRow(LC_NO).availableAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    void paymentWithInsufficientAmountIsRejectedWhole() {
        createDefaultLc("req-lc-1", LC_NO, new BigDecimal("8000.00"));
        SubmissionResponse batch = present("req-present-1", PRESENTED_AT, new BigDecimal("8000.01"));
        asSubmission(lcService.refuse(new RefuseRequest("req-refuse-1", batch.batchNo(),
                Instant.parse("2026-09-24T10:00:00Z"), TWO_DISCREPANCIES)));
        asSubmission(lcService.waive(new WaiveDiscrepanciesRequest(
                "req-waive-all", batch.batchNo(), APPLICANT,
                Instant.parse("2026-09-24T15:00:00Z"), TWO_DISCREPANCIES)));

        assertThatThrownBy(() -> lcService.pay(new PayRequest(
                "req-pay-short", batch.batchNo(), Instant.parse("2026-09-24T16:00:00Z"))))
                .isInstanceOf(LcBusinessException.class)
                .extracting(e -> ((LcBusinessException) e).getErrorCode())
                .isEqualTo(LcErrorCode.INSUFFICIENT_AVAILABLE_AMOUNT);
        // 整笔拒绝：额度不扣减，批次未付款。
        assertThat(lcRow(LC_NO).availableAmount()).isEqualByComparingTo("8000.00");
        assertThat(row(batch.batchNo()).status()).isEqualTo("ACCEPTED");
    }

    @Test
    void duplicatePaymentReturnsFirstResultWithoutDoubleDeduction() {
        createDefaultLc("req-lc-1");
        SubmissionResponse batch = present("req-present-1", PRESENTED_AT);
        asSubmission(lcService.refuse(new RefuseRequest("req-refuse-1", batch.batchNo(),
                Instant.parse("2026-09-24T10:00:00Z"), TWO_DISCREPANCIES)));
        asSubmission(lcService.waive(new WaiveDiscrepanciesRequest(
                "req-waive-all", batch.batchNo(), APPLICANT,
                Instant.parse("2026-09-24T15:00:00Z"), TWO_DISCREPANCIES)));

        Instant firstPaidAt = Instant.parse("2026-09-24T16:00:00Z");
        SubmissionResponse first = asSubmission(lcService.pay(new PayRequest(
                "req-pay-1", batch.batchNo(), firstPaidAt)));
        assertThat(first.status()).isEqualTo("PAID");
        assertThat(first.paidAt()).isEqualTo(firstPaidAt);

        // 不同 requestId、甚至不同付款时间重复付款：返回首次结果，不重复扣减。
        SubmissionResponse again = asSubmission(lcService.pay(new PayRequest(
                "req-pay-dup", batch.batchNo(), Instant.parse("2026-09-25T09:00:00Z"))));
        assertThat(again.status()).isEqualTo("PAID");
        assertThat(again.paidAt()).isEqualTo(firstPaidAt);
        assertThat(lcRow(LC_NO).availableAmount()).isEqualByComparingTo("2000.00");
    }

    @Test
    void correctedResubmissionStartsNewBatchAndNewReviewPeriod() {
        createDefaultLc("req-lc-1");
        SubmissionResponse first = present("req-present-1", PRESENTED_AT);
        asSubmission(lcService.refuse(new RefuseRequest("req-refuse-1", first.batchNo(),
                Instant.parse("2026-09-24T10:00:00Z"), TWO_DISCREPANCIES)));
        assertThat(row(first.batchNo()).status()).isEqualTo("REFUSED");

        // 受益人改单重交：新批次、新 deadline；原批次保持拒付终态。
        Instant rePresentedAt = Instant.parse("2026-09-28T09:00:00Z");
        List<DocumentItem> correctedDocs = List.of(
                new DocumentItem("BILL_OF_LADING", "BL-2026-002"),
                new DocumentItem("COMMERCIAL_INVOICE", "INV-2026-010"));
        SubmissionResponse second = asSubmission(lcService.present(new PresentDocumentsRequest(
                "req-present-2", LC_NO, new BigDecimal("8000.00"), rePresentedAt, correctedDocs)));
        assertThat(second.batchNo()).isNotEqualTo(first.batchNo());
        assertThat(second.batchNo()).isEqualTo(LC_NO + "-2");
        // 2026-09-28 周一收到，截止为 2026-10-05 周一 17:00（次日起 5 个工作日）。
        assertThat(second.deadlineAt()).isEqualTo(Instant.parse("2026-10-05T17:00:00Z"));
        assertThat(second.status()).isEqualTo("UNDER_REVIEW");
        assertThat(row(first.batchNo()).status()).isEqualTo("REFUSED");

        // 新批次可独立拒付，原批次不符点不被复用。
        SubmissionResponse refused2 = asSubmission(lcService.refuse(new RefuseRequest(
                "req-refuse-2", second.batchNo(),
                Instant.parse("2026-10-05T16:00:00Z"),
                List.of(new DiscrepancyItem("PACKING_LIST", "装箱单缺失")))));
        assertThat(refused2.status()).isEqualTo("REFUSED");
        assertThat(refused2.discrepancies()).hasSize(1);
    }

    @Test
    void presentationAfterExpiryIsRejected() {
        createDefaultLc("req-lc-1");
        assertThatThrownBy(() -> lcService.present(new PresentDocumentsRequest(
                "req-present-expired", LC_NO, new BigDecimal("100.00"),
                LocalDate.of(2028, 1, 1).atStartOfDay().toInstant(java.time.ZoneOffset.UTC),
                DOCUMENTS)))
                .isInstanceOf(LcBusinessException.class)
                .extracting(e -> ((LcBusinessException) e).getErrorCode())
                .isEqualTo(LcErrorCode.PRESENTATION_EXPIRED);
        assertThat(submissionRepository.countByLc(LC_NO)).isZero();
    }

    @Test
    void payBeforeAcceptanceIsRejected() {
        createDefaultLc("req-lc-1");
        SubmissionResponse batch = present("req-present-1", PRESENTED_AT);
        assertThatThrownBy(() -> lcService.pay(new PayRequest(
                "req-pay-early", batch.batchNo(), Instant.parse("2026-09-24T16:00:00Z"))))
                .isInstanceOf(LcBusinessException.class)
                .extracting(e -> ((LcBusinessException) e).getErrorCode())
                .isEqualTo(LcErrorCode.ILLEGAL_STATE);
        assertThat(lcRow(LC_NO).availableAmount()).isEqualByComparingTo("10000.00");
    }

    private SubmissionResponse present(String requestId, Instant presentedAt) {
        return present(requestId, presentedAt, new BigDecimal("8000.00"));
    }

    private SubmissionResponse present(String requestId, Instant presentedAt, BigDecimal amount) {
        return asSubmission(lcService.present(new PresentDocumentsRequest(
                requestId, LC_NO, amount, presentedAt, DOCUMENTS)));
    }
}
