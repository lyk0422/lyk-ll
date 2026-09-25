package com.example.starter.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.payment.AbstractIntegrationTest;
import com.example.starter.payment.domain.DiffType;
import com.example.starter.payment.domain.ResolveAction;
import com.example.starter.payment.dto.ChannelEntry;
import com.example.starter.payment.dto.DiffResolveRequest;
import com.example.starter.payment.dto.DiffView;
import com.example.starter.payment.dto.PaymentRequest;
import com.example.starter.payment.dto.ReconRequest;
import com.example.starter.payment.support.BusinessException;

/**
 * 渠道对账三类差异与差异唯一核销的 H2 事务测试。
 */
class ReconServiceIntegrationTest extends AbstractIntegrationTest {

    private static final String DAY = "2026-09-24";

    @Autowired
    private PaymentService paymentService;
    @Autowired
    private SettlementService settlementService;
    @Autowired
    private ReconService reconService;

    private Instant utc(int hour) {
        return java.time.LocalDate.of(2026, 9, 24).atTime(hour, 0, 0).toInstant(ZoneOffset.UTC);
    }

    private void post(String rid, String serial, String merchant, String amount) {
        paymentService.post(new PaymentRequest(rid, serial, merchant, new BigDecimal(amount), utc(10)));
    }

    private ReconRequest recon(ChannelEntry... entries) {
        return new ReconRequest(DAY, List.of(entries));
    }

    private ChannelEntry entry(String serial, String merchant, String amount) {
        return new ChannelEntry(serial, merchant, new BigDecimal(amount));
    }

    @Test
    void reconcilesThreeDiffTypesAndMatches() {
        post("r1", "S_LOCAL_EXTRA", "M001", "100.00");      // 渠道无 -> 本地多账
        post("r2", "S_MATCH", "M001", "50.00");            // 双方一致 -> 匹配
        post("r3", "S_MISMATCH", "M002", "80.00");         // 金额不同 -> 差错

        var result = reconService.reconcile(recon(
                entry("S_MATCH", "M001", "50.00"),
                entry("S_MISMATCH", "M002", "70.00"),
                entry("S_CHANNEL_EXTRA", "M001", "30.00"))); // 本地无 -> 渠道多账

        assertThat(result.matched()).isEqualTo(1);
        assertThat(result.localExtra()).isEqualTo(1);
        assertThat(result.channelExtra()).isEqualTo(1);
        assertThat(result.amountMismatch()).isEqualTo(1);
        assertThat(result.totalCompared()).isEqualTo(4);

        var diffs = reconService.listDiffs(DAY);
        assertThat(diffs).hasSize(3);
        assertThat(diffs).extracting(DiffView::diffType)
                .containsExactlyInAnyOrder("LOCAL_EXTRA", "CHANNEL_EXTRA", "AMOUNT_MISMATCH");

        DiffView mismatch = diff(diffs, "S_MISMATCH");
        assertThat(mismatch.localAmount()).isEqualByComparingTo("80.00");
        assertThat(mismatch.channelAmount()).isEqualByComparingTo("70.00");
        assertThat(mismatch.status()).isEqualTo("OPEN");
    }

    @Test
    void duplicateReconRejected() {
        post("r1", "S1", "M001", "100.00");
        reconService.reconcile(recon(entry("S1", "M001", "100.00")));
        assertThatThrownBy(() -> reconService.reconcile(recon(entry("S1", "M001", "100.00"))))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus().value()).isEqualTo(409));
    }

    @Test
    void resolvingLocalExtraWritesOffOnceAndKeepsTrace() {
        post("r1", "S1", "M001", "100.00");
        reconService.reconcile(recon(entry("S2", "M001", "10.00"))); // S1 本地多账
        DiffView diff = diff(reconService.listDiffs(DAY), "S1");

        var resp = reconService.resolve(diff.id(),
                new DiffResolveRequest(ResolveAction.WRITE_OFF, "op01", "核销本地多账"));
        assertThat(resp.replayed()).isFalse();
        assertThat(resp.status()).isEqualTo("RESOLVED");
        assertThat(resp.action()).isEqualTo("WRITE_OFF");

        // 留痕一条。
        Integer logs = jdbc.queryForObject("SELECT COUNT(1) FROM recon_diff_log WHERE diff_id = ?",
                Integer.class, diff.id());
        assertThat(logs).isEqualTo(1);

        // 重复核销：返回首次结果，不新增留痕。
        var again = reconService.resolve(diff.id(),
                new DiffResolveRequest(ResolveAction.WRITE_OFF, "op02", "再次核销"));
        assertThat(again.replayed()).isTrue();
        assertThat(again.action()).isEqualTo("WRITE_OFF");
        Integer logsAfter = jdbc.queryForObject("SELECT COUNT(1) FROM recon_diff_log WHERE diff_id = ?",
                Integer.class, diff.id());
        assertThat(logsAfter).isEqualTo(1);
    }

    @Test
    void resolvingChannelExtraSupplementsLocalTxn() {
        settlementService.closeBatch(DAY);
        // 在已封账批次直接构造本地无、渠道有的差异。
        var result = reconService.reconcile(recon(entry("SUP1", "M001", "120.00")));
        assertThat(result.channelExtra()).isEqualTo(1);
        DiffView diff = diff(reconService.listDiffs(DAY), "SUP1");

        var resp = reconService.resolve(diff.id(),
                new DiffResolveRequest(ResolveAction.SUPPLEMENT, "op01", "补记渠道多账"));
        assertThat(resp.status()).isEqualTo("RESOLVED");

        // 补记生成一笔 SUPPLEMENT 本地交易，金额取渠道侧，费率按商户当前费率（0.6% -> 0.72）。
        var rows = jdbc.queryForMap("SELECT amount, fee, status, source, batch_date FROM payment_transaction "
                + "WHERE channel_serial_no = 'SUP1'");
        assertThat(rows.get("AMOUNT")).isEqualTo(new BigDecimal("120.00"));
        assertThat(rows.get("FEE")).isEqualTo(new BigDecimal("0.72"));
        assertThat(rows.get("STATUS")).isEqualTo("POSTED");
        assertThat(rows.get("SOURCE")).isEqualTo("SUPPLEMENT");
        assertThat(rows.get("BATCH_DATE")).isEqualTo(DAY);
    }

    @Test
    void resolvingMismatchWithRefund() {
        post("r1", "S1", "M001", "80.00");
        reconService.reconcile(recon(entry("S1", "M001", "70.00")));
        DiffView diff = diff(reconService.listDiffs(DAY), "S1");

        var resp = reconService.resolve(diff.id(),
                new DiffResolveRequest(ResolveAction.REFUND, "op01", "差错退款"));
        assertThat(resp.action()).isEqualTo("REFUND");
        assertThat(resp.status()).isEqualTo("RESOLVED");
    }

    @Test
    void actionMustMatchDiffType() {
        post("r1", "S1", "M001", "100.00");
        reconService.reconcile(recon(entry("S2", "M001", "10.00"))); // S1 本地多账
        DiffView diff = diff(reconService.listDiffs(DAY), "S1");

        // 本地多账不能补记/退款。
        assertThatThrownBy(() -> reconService.resolve(diff.id(),
                new DiffResolveRequest(ResolveAction.SUPPLEMENT, "op01", null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus().value()).isEqualTo(400));
        assertThat(reconService.listDiffs(DAY).get(0).status()).isEqualTo("OPEN");
    }

    @Test
    void resolveUnknownDiffReturns404() {
        assertThatThrownBy(() -> reconService.resolve(99999L,
                new DiffResolveRequest(ResolveAction.WRITE_OFF, "op01", null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus().value()).isEqualTo(404));
    }

    private DiffView diff(List<DiffView> diffs, String serial) {
        return diffs.stream().filter(d -> d.channelSerialNo().equals(serial)).findFirst().orElseThrow();
    }
}
