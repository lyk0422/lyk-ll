package com.example.starter.payment;

import com.example.starter.payment.repo.DifferenceRepository;
import com.example.starter.payment.service.PaymentService;
import com.example.starter.payment.service.PaymentService.PostCommand;
import com.example.starter.payment.service.ReconService;
import com.example.starter.payment.service.ReconService.ChannelEntry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 对账：三类差异（本地多账/渠道多账/金额差错）、差异唯一核销与处理留痕。
 */
class ReconServiceTest extends AbstractPaymentIntegrationTest {

    @Autowired
    private PaymentService paymentService;
    @Autowired
    private ReconService reconService;
    @Autowired
    private DifferenceRepository diffRepo;

    private static final LocalDate DAY = LocalDate.of(2026, 9, 24);

    private void post(String requestId, String serialNo, long amount) {
        paymentService.post(new PostCommand(requestId, serialNo, "M0001", amount,
                Instant.parse("2026-09-24T10:00:00Z")));
    }

    @Test
    void reconcileProducesThreeDiffTypes() {
        post("r1", "MATCH", 10000);      // 与渠道一致，无差异
        post("r2", "LOCAL_ONLY", 20000); // 本地多账
        post("r3", "MISMATCH", 30000);   // 金额差错

        List<ReconDifference> diffs = reconService.reconcile(DAY, List.of(
                new ChannelEntry("MATCH", 10000),
                new ChannelEntry("MISMATCH", 31000),      // 金额不一致
                new ChannelEntry("CHANNEL_ONLY", 40000)   // 渠道多账
        ));

        assertThat(diffs).hasSize(3);
        Map<String, ReconDifference> bySerial = diffs.stream()
                .collect(Collectors.toMap(ReconDifference::serialNo, Function.identity()));

        ReconDifference localExcess = bySerial.get("LOCAL_ONLY");
        assertThat(localExcess.diffType()).isEqualTo(ReconDifference.LOCAL_EXCESS);
        assertThat(localExcess.localAmount()).isEqualTo(20000);
        assertThat(localExcess.channelAmount()).isNull();

        ReconDifference channelExcess = bySerial.get("CHANNEL_ONLY");
        assertThat(channelExcess.diffType()).isEqualTo(ReconDifference.CHANNEL_EXCESS);
        assertThat(channelExcess.channelAmount()).isEqualTo(40000);
        assertThat(channelExcess.localAmount()).isNull();

        ReconDifference mismatch = bySerial.get("MISMATCH");
        assertThat(mismatch.diffType()).isEqualTo(ReconDifference.AMOUNT_MISMATCH);
        assertThat(mismatch.localAmount()).isEqualTo(30000);
        assertThat(mismatch.channelAmount()).isEqualTo(31000);

        assertThat(diffs).allSatisfy(d -> assertThat(d.status()).isEqualTo(ReconDifference.OPEN));
    }

    @Test
    void reconcileIsIdempotentPerSerial() {
        post("r1", "LOCAL_ONLY", 20000);
        List<ChannelEntry> file = List.of(new ChannelEntry("MATCH_NONE", 1));
        reconService.reconcile(DAY, file);
        reconService.reconcile(DAY, file);
        // 同一流水号差异不重复生成
        assertThat(countRows("recon_difference")).isEqualTo(2);
    }

    @Test
    void resolveWritesAuditAndRejectsDuplicate() {
        post("r1", "LOCAL_ONLY", 20000);
        List<ReconDifference> diffs = reconService.reconcile(DAY, List.of());
        ReconDifference diff = diffs.get(0);

        ReconDifference resolved = reconService.resolve(diff.id(), ReconDifference.WRITE_OFF, "op-1");
        assertThat(resolved.status()).isEqualTo(ReconDifference.RESOLVED);
        assertThat(resolved.resolution()).isEqualTo(ReconDifference.WRITE_OFF);
        assertThat(resolved.resolvedBy()).isEqualTo("op-1");
        assertThat(resolved.resolvedAt()).isNotNull();
        // 处理留痕
        assertThat(diffRepo.countResolutionLogs(diff.id())).isEqualTo(1);

        // 同一差异不允许重复核销
        assertThatThrownBy(() -> reconService.resolve(diff.id(), ReconDifference.REFUND, "op-2"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).httpStatus()).isEqualTo(409);
        assertThat(diffRepo.countResolutionLogs(diff.id())).isEqualTo(1);
    }

    @Test
    void resolveRejectsInvalidResolutionAndUnknownId() {
        post("r1", "LOCAL_ONLY", 20000);
        ReconDifference diff = reconService.reconcile(DAY, List.of()).get(0);

        assertThatThrownBy(() -> reconService.resolve(diff.id(), "NOPE", "op-1"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).httpStatus()).isEqualTo(400);
        assertThatThrownBy(() -> reconService.resolve(999999L, ReconDifference.REPOST, "op-1"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).httpStatus()).isEqualTo(404);
    }
}
