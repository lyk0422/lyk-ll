package com.example.starter.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.domain.DiffType;
import com.example.starter.dto.CloseBatchRequest;
import com.example.starter.dto.PaymentRequest;
import com.example.starter.dto.ReconRequest;
import com.example.starter.dto.ReconRequest.ReconEntry;
import com.example.starter.dto.ReconResponse;
import com.example.starter.dto.ResolveRequest;
import com.example.starter.entity.ReconDiff;
import com.example.starter.exception.BusinessException;
import com.example.starter.exception.ConflictException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 三类对账差异生成、文件幂等与差异唯一核销的 H2 测试。 */
class ReconciliationIntegrationTest extends AbstractIntegrationTest {

    private static final LocalDate BATCH = LocalDate.of(2026, 9, 20);
    private static final Instant IN_BATCH = Instant.parse("2026-09-20T10:00:00Z");

    @org.springframework.beans.factory.annotation.Autowired
    private PaymentService paymentService;

    @Test
    void generatesThreeDiffTypes() {
        paymentService.postPayment(
                new PaymentRequest("REQ-A", "FLOW-A", "M001", 100_000L, IN_BATCH));
        paymentService.postPayment(
                new PaymentRequest("REQ-B", "FLOW-B", "M001", 200_000L, IN_BATCH));
        paymentService.postPayment(
                new PaymentRequest("REQ-C", "FLOW-C", "M002", 300_000L, IN_BATCH));

        ReconRequest file = new ReconRequest("FILE-1", BATCH, "CH-SYN", List.of(
                new ReconEntry("FLOW-A", "M001", 100_000L),   // 一致
                new ReconEntry("FLOW-B", "M001", 200_050L),   // 金额差错
                new ReconEntry("FLOW-D", "M002", 400_000L))); // 渠道多账
        // FLOW-C 本地有、渠道无 → 本地多账

        ReconResponse response = paymentService.reconcile(file);

        assertThat(response.localExtraCount()).isEqualTo(1);
        assertThat(response.channelExtraCount()).isEqualTo(1);
        assertThat(response.amountMismatchCount()).isEqualTo(1);
        assertThat(response.diffs()).hasSize(3);

        ReconDiff mismatch = findDiff(response, DiffType.AMOUNT_MISMATCH, "FLOW-B");
        assertThat(mismatch.getLocalAmount()).isEqualTo(200_000L);
        assertThat(mismatch.getChannelAmount()).isEqualTo(200_050L);
        assertThat(mismatch.getStatus()).isEqualTo("PENDING");

        ReconDiff localExtra = findDiff(response, DiffType.LOCAL_EXTRA, "FLOW-C");
        assertThat(localExtra.getChannelAmount()).isNull();
        assertThat(localExtra.getLocalAmount()).isEqualTo(300_000L);

        ReconDiff channelExtra = findDiff(response, DiffType.CHANNEL_EXTRA, "FLOW-D");
        assertThat(channelExtra.getLocalAmount()).isNull();
        assertThat(channelExtra.getChannelAmount()).isEqualTo(400_000L);
    }

    @Test
    void repeatedReconWithSameFileDoesNotDuplicateDiffs() {
        paymentService.postPayment(
                new PaymentRequest("REQ-E", "FLOW-E", "M001", 10_000L, IN_BATCH));
        ReconRequest file = new ReconRequest("FILE-2", BATCH, null, List.of());

        paymentService.reconcile(file);
        ReconResponse replay = paymentService.reconcile(file);

        assertThat(replay.localExtraCount()).isEqualTo(1);
        Long rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM recon_diff WHERE batch_date = ?", Long.class, BATCH);
        assertThat(rows).isEqualTo(1L);
    }

    @Test
    void resolveDiffIsUniqueAndLeavesTrail() {
        paymentService.postPayment(
                new PaymentRequest("REQ-F", "FLOW-F", "M001", 10_000L, IN_BATCH));
        ReconResponse recon = paymentService.reconcile(
                new ReconRequest("FILE-3", BATCH, null, List.of()));
        Long diffId = findDiff(recon, DiffType.LOCAL_EXTRA, "FLOW-F").getId();

        ReconDiff resolved = paymentService.resolveDiff(
                diffId, new ResolveRequest("WRITE_OFF", "auditor", "本地多账核销"));

        assertThat(resolved.getStatus()).isEqualTo("RESOLVED");
        assertThat(resolved.getResolution()).isEqualTo("WRITE_OFF");
        assertThat(resolved.getResolvedBy()).isEqualTo("auditor");
        assertThat(resolved.getRemark()).isEqualTo("本地多账核销");
        assertThat(resolved.getResolvedAt()).isNotNull();

        assertThatThrownBy(() -> paymentService.resolveDiff(
                diffId, new ResolveRequest("REFUND", "auditor2", "再次核销")))
                .isInstanceOf(ConflictException.class);

        // 重复核销被拒后，首次处理结果保持不变。
        String resolution = jdbcTemplate.queryForObject(
                "SELECT resolution FROM recon_diff WHERE id = ?", String.class, diffId);
        assertThat(resolution).isEqualTo("WRITE_OFF");
    }

    @Test
    void invalidResolutionRejected() {
        paymentService.postPayment(
                new PaymentRequest("REQ-G", "FLOW-G", "M001", 10_000L, IN_BATCH));
        ReconResponse recon = paymentService.reconcile(
                new ReconRequest("FILE-4", BATCH, null, List.of()));
        Long diffId = findDiff(recon, DiffType.LOCAL_EXTRA, "FLOW-G").getId();

        assertThatThrownBy(() -> paymentService.resolveDiff(
                diffId, new ResolveRequest("UNKNOWN", null, null)))
                .isInstanceOf(BusinessException.class);
    }

    private ReconDiff findDiff(ReconResponse response, DiffType type, String flowNo) {
        return response.diffs().stream()
                .filter(d -> d.getDiffType().equals(type.name()) && d.getChannelFlowNo().equals(flowNo))
                .findFirst()
                .orElseThrow();
    }
}
