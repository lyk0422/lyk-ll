package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import com.example.starter.service.ManifestService;
import com.example.starter.service.ReceiptService;
import com.example.starter.service.SettlementService;
import com.example.starter.service.WeighingService;
import com.example.starter.support.BizException;
import com.example.starter.support.TestClockConfig;

/**
 * 受控联单回执：设施标识 + 去向版本校验、旧版本迟到回执仅存证据、旧入口不得绕过去向校验。
 */
class ControlledReceiptTest extends LedgerTestSupport {

    @Autowired
    private WeighingService weighingService;

    @Autowired
    private ManifestService manifestService;

    @Autowired
    private ReceiptService receiptService;

    @Autowired
    private SettlementService settlementService;

    /**
     * 登记一张净重 10000kg 的执行中联单（毛重 18000 - 皮重 8000），去向 F-INCINERATION。
     */
    private long registerInTransitManifest(String requestId) {
        long vehicleId = createVehicle();
        insertTare(vehicleId, new BigDecimal("8000.000"), clock.instant());
        return weighingService.registerEntry(new WeighingService.EntryCommand(
                requestId, plateOf(vehicleId), "示例小区", "OTHER",
                new BigDecimal("18000.000"), "F-INCINERATION", null)).manifestId();
    }

    @Test
    void lateReceiptFromOldFacilityDoesNotCloseReroutedManifest() {
        long manifestId = registerInTransitManifest("REQ-CR-ENTRY");
        manifestService.reroute(manifestId, "REQ-CR-RR", "F-INCINERATION-2", 1);

        // 旧设施的迟到回执（声称版本 1）：仅存证据，不闭环
        var late = receiptService.registerReceipt(manifestId, "REQ-CR-LATE",
                new BigDecimal("10000.000"), "F-INCINERATION", 1);

        assertThat(late.result()).isEqualTo("LATE_EVIDENCE");
        assertThat(late.deviationPct()).isNull();
        assertThat(manifestStatus(manifestId)).isEqualTo("IN_TRANSIT");

        // 新设施按当前版本 2 回执：10100kg，偏差 1%，按原 2% 规则入账闭环
        var receipt = receiptService.registerReceipt(manifestId, "REQ-CR-OK",
                new BigDecimal("10100.000"), "F-INCINERATION-2", 2);
        assertThat(receipt.result()).isEqualTo("MATCH");
        assertThat(receipt.deviationPct()).isEqualByComparingTo("1.000000");
        assertThat(manifestStatus(manifestId)).isEqualTo("RECEIVED");

        // 查询：完整去向链 + 迟到证据链
        var detail = manifestService.detail(manifestId);
        assertThat(detail.destinations()).hasSize(2);
        assertThat(detail.receipts()).hasSize(1);
        assertThat(detail.receipts().get(0).facilityCode()).isEqualTo("F-INCINERATION-2");
        assertThat(detail.receipts().get(0).destinationVersion()).isEqualTo(2);
        assertThat(detail.lateReceipts()).hasSize(1);
        assertThat(detail.lateReceipts().get(0).facilityCode()).isEqualTo("F-INCINERATION");
        assertThat(detail.lateReceipts().get(0).destinationVersion()).isEqualTo(1);

        // 日结：迟到证据不增加接收量，只有效回执计入出场
        var rows = settlementService.daily(LocalDate.of(2026, 10, 6));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).outboundCount()).isEqualTo(1);
        assertThat(rows.get(0).outboundNetKg()).isEqualByComparingTo("10100.000");
    }

    @Test
    void lateReceiptIsIdempotentAndConflictOnDifferentParams() {
        long manifestId = registerInTransitManifest("REQ-CR-IDEM-ENTRY");
        manifestService.reroute(manifestId, "REQ-CR-IDEM-RR", "F-INCINERATION-2", 1);

        var first = receiptService.registerReceipt(manifestId, "REQ-CR-IDEM-LATE",
                new BigDecimal("9900.000"), "F-INCINERATION", 1);
        var replay = receiptService.registerReceipt(manifestId, "REQ-CR-IDEM-LATE",
                new BigDecimal("9900.000"), "F-INCINERATION", 1);

        assertThat(first.result()).isEqualTo("LATE_EVIDENCE");
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.receiptId()).isEqualTo(first.receiptId());

        assertThatThrownBy(() -> receiptService.registerReceipt(manifestId, "REQ-CR-IDEM-LATE",
                        new BigDecimal("8888.000"), "F-INCINERATION", 1))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("IDEMPOTENCY_CONFLICT");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        // 证据仍然只有一条
        assertThat(manifestService.detail(manifestId).lateReceipts()).hasSize(1);
    }

    @Test
    void legacyReceiptCallOnReroutedManifestIsRejected() {
        long manifestId = registerInTransitManifest("REQ-CR-LEG-ENTRY");
        manifestService.reroute(manifestId, "REQ-CR-LEG-RR", "F-INCINERATION-2", 1);

        // 旧回执入口不得绕过受控联单的去向校验
        assertThatThrownBy(() -> receiptService.registerReceipt(manifestId, "REQ-CR-LEG-R",
                        new BigDecimal("10000.000")))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("DESTINATION_CONTROL_REQUIRED");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
        assertThat(manifestStatus(manifestId)).isEqualTo("IN_TRANSIT");
    }

    @Test
    void incompleteDestinationControlIsRejected() {
        long manifestId = registerInTransitManifest("REQ-CR-INC-ENTRY");

        assertThatThrownBy(() -> receiptService.registerReceipt(manifestId, "REQ-CR-INC-1",
                        new BigDecimal("10000.000"), "F-INCINERATION", null))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getCode()).isEqualTo("DESTINATION_CONTROL_INCOMPLETE"));
        assertThatThrownBy(() -> receiptService.registerReceipt(manifestId, "REQ-CR-INC-2",
                        new BigDecimal("10000.000"), null, 1))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getCode()).isEqualTo("DESTINATION_CONTROL_INCOMPLETE"));
    }

    @Test
    void wrongFacilityAtCurrentVersionIsLateEvidence() {
        long manifestId = registerInTransitManifest("REQ-CR-WRONG-ENTRY");
        manifestService.reroute(manifestId, "REQ-CR-WRONG-RR", "F-INCINERATION-2", 1);

        // 版本恰为当前版本 2，但设施不匹配：仅存证据
        var late = receiptService.registerReceipt(manifestId, "REQ-CR-WRONG-R",
                new BigDecimal("10000.000"), "F-RECYCLING", 2);

        assertThat(late.result()).isEqualTo("LATE_EVIDENCE");
        assertThat(manifestStatus(manifestId)).isEqualTo("IN_TRANSIT");
    }

    @Test
    void receiptFromUnknownFacilityIsRejected() {
        long manifestId = registerInTransitManifest("REQ-CR-UNK-ENTRY");

        assertThatThrownBy(() -> receiptService.registerReceipt(manifestId, "REQ-CR-UNK-R",
                        new BigDecimal("10000.000"), "F-NOPE", 1))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getCode()).isEqualTo("FACILITY_NOT_IN_CATALOG"));
    }

    @Test
    void controlledReceiptOnUnReroutedManifestClosesNormally() {
        long manifestId = registerInTransitManifest("REQ-CR-V1-ENTRY");

        // 未改投联单也可走受控调用：设施与版本 1 匹配即按 2% 规则入账
        var result = receiptService.registerReceipt(manifestId, "REQ-CR-V1-R",
                new BigDecimal("10200.000"), "F-INCINERATION", 1);

        assertThat(result.result()).isEqualTo("MATCH");
        assertThat(result.deviationPct()).isEqualByComparingTo("2.000000");
        assertThat(manifestStatus(manifestId)).isEqualTo("RECEIVED");
    }

    @Test
    void legacyReceiptOnUnReroutedManifestStillWorks() {
        long manifestId = registerInTransitManifest("REQ-CR-OLD-ENTRY");

        var result = receiptService.registerReceipt(manifestId, "REQ-CR-OLD-R",
                new BigDecimal("10000.000"));

        assertThat(result.result()).isEqualTo("MATCH");
        assertThat(result.facilityCode()).isNull();
        assertThat(result.destinationVersion()).isNull();
        assertThat(manifestStatus(manifestId)).isEqualTo("RECEIVED");
    }

    @Test
    void lateReceiptAfterManifestClosedIsStillOnlyEvidence() {
        long manifestId = registerInTransitManifest("REQ-CR-AFTER-ENTRY");
        manifestService.reroute(manifestId, "REQ-CR-AFTER-RR", "F-INCINERATION-2", 1);
        receiptService.registerReceipt(manifestId, "REQ-CR-AFTER-OK",
                new BigDecimal("10000.000"), "F-INCINERATION-2", 2);

        // 闭环后旧设施迟到回执：仍只存证据，状态不变
        var late = receiptService.registerReceipt(manifestId, "REQ-CR-AFTER-LATE",
                new BigDecimal("10000.000"), "F-INCINERATION", 1);

        assertThat(late.result()).isEqualTo("LATE_EVIDENCE");
        assertThat(manifestStatus(manifestId)).isEqualTo("RECEIVED");
        assertThat(manifestService.detail(manifestId).lateReceipts()).hasSize(1);
    }

    @Test
    void deviationAboveTwoPercentOnControlledReceiptGoesToException() {
        long manifestId = registerInTransitManifest("REQ-CR-EX-ENTRY");
        manifestService.reroute(manifestId, "REQ-CR-EX-RR", "F-INCINERATION-2", 1);

        // 10300kg，偏差 3% > 2%，转异常核查
        var result = receiptService.registerReceipt(manifestId, "REQ-CR-EX-R",
                new BigDecimal("10300.000"), "F-INCINERATION-2", 2);

        assertThat(result.result()).isEqualTo("EXCEPTION");
        assertThat(manifestStatus(manifestId)).isEqualTo("EXCEPTION");
    }

    @Test
    void settlementIgnoresLateEvidence() {
        long manifestId = registerInTransitManifest("REQ-CR-SET-ENTRY");
        manifestService.reroute(manifestId, "REQ-CR-SET-RR", "F-INCINERATION-2", 1);
        receiptService.registerReceipt(manifestId, "REQ-CR-SET-LATE",
                new BigDecimal("10000.000"), "F-INCINERATION", 1);

        var rows = settlementService.daily(LocalDate.ofInstant(TestClockConfig.BASE_INSTANT,
                java.time.ZoneId.of("Asia/Shanghai")));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).inboundCount()).isEqualTo(1);
        assertThat(rows.get(0).outboundCount()).isZero();
        assertThat(rows.get(0).outboundNetKg()).isEqualByComparingTo("0.000");
    }
}
