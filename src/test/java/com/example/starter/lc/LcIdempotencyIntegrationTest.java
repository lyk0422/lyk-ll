package com.example.starter.lc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.starter.lc.dto.DiscrepancyItem;
import com.example.starter.lc.dto.DocumentItem;
import com.example.starter.lc.dto.OperationResult;
import com.example.starter.lc.dto.PayRequest;
import com.example.starter.lc.dto.PresentDocumentsRequest;
import com.example.starter.lc.dto.RefuseRequest;
import com.example.starter.lc.dto.SubmissionResponse;
import com.example.starter.lc.dto.WaiveDiscrepanciesRequest;
import com.example.starter.lc.service.LcService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * requestId 幂等重放与异参冲突 H2 集成测试。
 */
@SpringBootTest
class LcIdempotencyIntegrationTest extends AbstractLcIntegrationTest {

    private static final Instant PRESENTED_AT = Instant.parse("2026-09-18T10:00:00Z");

    private static final List<DocumentItem> DOCUMENTS = List.of(
            new DocumentItem("BILL_OF_LADING", "BL-2026-001"),
            new DocumentItem("COMMERCIAL_INVOICE", "INV-2026-009"));

    private static final List<DiscrepancyItem> DISCREPANCIES = List.of(
            new DiscrepancyItem("BILL_OF_LADING", "装船日期晚于信用证最迟装运日"),
            new DiscrepancyItem("COMMERCIAL_INVOICE", "发票金额与汇票金额不一致"));

    @Autowired
    private LcService lcService;

    @Override
    protected LcService service() {
        return lcService;
    }

    @Test
    void sameKeySameParamsReplaysFirstPresentResult() {
        createDefaultLc("req-lc-1");
        PresentDocumentsRequest request = new PresentDocumentsRequest(
                "req-present-1", LC_NO, new BigDecimal("8000.00"), PRESENTED_AT, DOCUMENTS);

        OperationResult first = lcService.present(request);
        SubmissionResponse firstBatch = asSubmission(first);
        OperationResult replay = lcService.present(request);

        assertThat(replay).isInstanceOf(OperationResult.Replay.class);
        OperationResult.Replay replayed = (OperationResult.Replay) replay;
        assertThat(replayed.httpStatus()).isEqualTo(201);
        assertThat(replayed.json()).contains(firstBatch.batchNo());
        // 重放不产生第二个批次。
        assertThat(submissionRepository.countByLc(LC_NO)).isEqualTo(1);
    }

    @Test
    void replayWithReorderedDiscrepanciesIsStillSameParams() {
        createDefaultLc("req-lc-1");
        SubmissionResponse batch = asSubmission(lcService.present(new PresentDocumentsRequest(
                "req-present-1", LC_NO, new BigDecimal("8000.00"), PRESENTED_AT, DOCUMENTS)));
        RefuseRequest ordered = new RefuseRequest("req-refuse-1", batch.batchNo(),
                Instant.parse("2026-09-24T10:00:00Z"), DISCREPANCIES);
        asSubmission(lcService.refuse(ordered));

        List<DiscrepancyItem> reversed = List.of(DISCREPANCIES.get(1), DISCREPANCIES.get(0));
        OperationResult replay = lcService.refuse(new RefuseRequest("req-refuse-1",
                batch.batchNo(), Instant.parse("2026-09-24T10:00:00Z"), reversed));
        assertThat(replay).isInstanceOf(OperationResult.Replay.class);
        assertThat(submissionRepository.findDiscrepancies(batch.batchNo())).hasSize(2);
    }

    @Test
    void sameKeyDifferentParamsConflictsWithoutChangingState() {
        createDefaultLc("req-lc-1");
        asSubmission(lcService.present(new PresentDocumentsRequest(
                "req-present-1", LC_NO, new BigDecimal("8000.00"), PRESENTED_AT, DOCUMENTS)));

        // 同 requestId、不同支款金额：409，不新增批次。
        assertThatThrownBy(() -> lcService.present(new PresentDocumentsRequest(
                "req-present-1", LC_NO, new BigDecimal("9000.00"), PRESENTED_AT, DOCUMENTS)))
                .isInstanceOf(LcBusinessException.class)
                .extracting(e -> ((LcBusinessException) e).getErrorCode())
                .isEqualTo(LcErrorCode.IDEMPOTENCY_CONFLICT);
        assertThat(submissionRepository.countByLc(LC_NO)).isEqualTo(1);
        assertThat(row(LC_NO + "-1").drawAmount()).isEqualByComparingTo("8000.00");
    }

    @Test
    void sameKeyAcrossOperationsConflicts() {
        createDefaultLc("req-lc-1");
        SubmissionResponse batch = asSubmission(lcService.present(new PresentDocumentsRequest(
                "shared-key", LC_NO, new BigDecimal("8000.00"), PRESENTED_AT, DOCUMENTS)));
        assertThatThrownBy(() -> lcService.refuse(new RefuseRequest("shared-key",
                batch.batchNo(), Instant.parse("2026-09-24T10:00:00Z"), DISCREPANCIES)))
                .isInstanceOf(LcBusinessException.class)
                .extracting(e -> ((LcBusinessException) e).getErrorCode())
                .isEqualTo(LcErrorCode.IDEMPOTENCY_CONFLICT);
        assertThat(row(batch.batchNo()).status()).isEqualTo("UNDER_REVIEW");
    }

    @Test
    void deterministicBusinessFailureIsAlsoReplayed() {
        createDefaultLc("req-lc-1");
        SubmissionResponse batch = asSubmission(lcService.present(new PresentDocumentsRequest(
                "req-present-1", LC_NO, new BigDecimal("8000.00"), PRESENTED_AT, DOCUMENTS)));
        // 首次交单占用审单中：第二个批次 409（确定性业务失败）。
        PresentDocumentsRequest concurrent = new PresentDocumentsRequest(
                "req-present-blocked", LC_NO, new BigDecimal("100.00"),
                PRESENTED_AT.plusSeconds(60),
                List.of(new DocumentItem("PACKING_LIST", "PL-1")));
        assertThatThrownBy(() -> lcService.present(concurrent))
                .isInstanceOf(LcBusinessException.class)
                .extracting(e -> ((LcBusinessException) e).getErrorCode())
                .isEqualTo(LcErrorCode.ALREADY_UNDER_REVIEW);

        // 首个批次终态后，用同键重放仍返回首次的 409，而不会突然受理。
        OperationResult replay = lcService.present(concurrent);
        assertThat(replay).isInstanceOf(OperationResult.Replay.class);
        assertThat(((OperationResult.Replay) replay).httpStatus()).isEqualTo(409);
        assertThat(submissionRepository.countByLc(LC_NO)).isEqualTo(1);
    }

    @Test
    void lateRefusalFailureIsReplayedAndStaysDeemedAccepted() {
        createDefaultLc("req-lc-1");
        SubmissionResponse batch = asSubmission(lcService.present(new PresentDocumentsRequest(
                "req-present-1", LC_NO, new BigDecimal("8000.00"), PRESENTED_AT, DOCUMENTS)));
        RefuseRequest late = new RefuseRequest("req-refuse-late", batch.batchNo(),
                Instant.parse("2026-09-25T17:00:00Z"), DISCREPANCIES);
        OperationResult first = lcService.refuse(late);
        assertThat(((OperationResult.Fresh) first).httpStatus()).isEqualTo(422);

        OperationResult replay = lcService.refuse(late);
        assertThat(replay).isInstanceOf(OperationResult.Replay.class);
        assertThat(((OperationResult.Replay) replay).httpStatus()).isEqualTo(422);
        assertThat(row(batch.batchNo()).status()).isEqualTo("DEEMED_ACCEPTED");
    }

    @Test
    void successfulWaiverAndPaymentAreIdempotent() {
        createDefaultLc("req-lc-1");
        SubmissionResponse batch = asSubmission(lcService.present(new PresentDocumentsRequest(
                "req-present-1", LC_NO, new BigDecimal("8000.00"), PRESENTED_AT, DOCUMENTS)));
        asSubmission(lcService.refuse(new RefuseRequest("req-refuse-1", batch.batchNo(),
                Instant.parse("2026-09-24T10:00:00Z"), DISCREPANCIES)));

        WaiveDiscrepanciesRequest waive = new WaiveDiscrepanciesRequest(
                "req-waive-1", batch.batchNo(), APPLICANT,
                Instant.parse("2026-09-24T15:00:00Z"), DISCREPANCIES);
        asSubmission(lcService.waive(waive));
        assertThat(lcService.waive(waive)).isInstanceOf(OperationResult.Replay.class);
        assertThat(row(batch.batchNo()).status()).isEqualTo("ACCEPTED");

        PayRequest pay = new PayRequest("req-pay-1", batch.batchNo(),
                Instant.parse("2026-09-24T16:00:00Z"));
        asSubmission(lcService.pay(pay));
        assertThat(lcService.pay(pay)).isInstanceOf(OperationResult.Replay.class);
        // 同键重放不重复核减额度。
        assertThat(lcRow(LC_NO).availableAmount()).isEqualByComparingTo("2000.00");
    }
}
