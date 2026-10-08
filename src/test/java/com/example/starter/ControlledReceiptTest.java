package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import com.example.starter.service.ManifestQueryService;
import com.example.starter.service.ReceiptService;
import com.example.starter.service.RerouteService;
import com.example.starter.service.SettlementService;
import com.example.starter.service.WeighingService;
import com.example.starter.support.BizException;

/**
 * 受控联单回执：改投后必须提供设施标识与去向版本，恰为当前版本且设施匹配才按 2% 规则入账；
 * 旧版本迟到回执仅存证据，不闭环、不增加接收量；未改投联单兼容旧式调用。
 */
class ControlledReceiptTest extends LedgerTestSupport {

    @Autowired
    private WeighingService weighingService;

    @Autowired
    private RerouteService rerouteService;

    @Autowired
    private ReceiptService receiptService;

    @Autowired
    private SettlementService settlementService;

    @Autowired
    private ManifestQueryService manifestQueryService;

    /**
     * 登记 OTHER 品类、净重 10000kg、去向 F-INCINERATION 的在途联单，并改投 F-INCINERATION-2（版本 2）。
     */
    private long registerAndReroute(String suffix) {
        long vehicleId = createVehicle();
        insertTare(vehicleId, new BigDecimal("8000.000"), clock.instant());
        long manifestId = weighingService.registerEntry(new WeighingService.EntryCommand(
                "REQ-CR-ENTRY-" + suffix, plateOf(vehicleId), "示例小区", "OTHER",
                new BigDecimal("18000.000"), "F-INCINERATION", null)).manifestId();
        rerouteService.reroute(new RerouteService.RerouteCommand(
                "REQ-CR-RR-" + suffix, manifestId, "F-INCINERATION-2", 1));
        return manifestId;
    }

    @Test
    void controlledManifestReceiptWithoutDestinationIsRejected() {
        long manifestId = registerAndReroute("1");

        assertThatThrownBy(() -> receiptService.registerReceipt(
                manifestId, "REQ-CR-R1", new BigDecimal("10000.000")))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("RECEIPT_DESTINATION_REQUIRED");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
        assertThat(manifestStatus(manifestId)).isEqualTo("IN_TRANSIT");
    }

    @Test
    void lateReceiptFromOldFacilityIsEvidenceOnly() {
        long manifestId = registerAndReroute("2");

        // 旧设施的迟到回执（版本 1）：仅存证据
        var late = receiptService.registerReceipt(manifestId, "REQ-CR-LATE-2",
                new BigDecimal("10000.000"), "F-INCINERATION", 1);

        assertThat(late.result()).isEqualTo("LATE_EVIDENCE");
        assertThat(late.deviationPct()).isNull();
        assertThat(manifestStatus(manifestId)).isEqualTo("IN_TRANSIT");

        // 不增加接收量：日结出场为 0
        var rows = settlementService.daily(LocalDate.of(2026, 10, 6));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).outboundCount()).isZero();
        assertThat(rows.get(0).outboundNetKg()).isEqualByComparingTo("0.000");

        // 新设施当前版本回执仍可按 2% 规则入账
        var booked = receiptService.registerReceipt(manifestId, "REQ-CR-BOOK-2",
                new BigDecimal("10200.000"), "F-INCINERATION-2", 2);
        assertThat(booked.result()).isEqualTo("MATCH");
        assertThat(manifestStatus(manifestId)).isEqualTo("RECEIVED");
    }

    @Test
    void receiptWithStaleVersionIsEvidenceOnly() {
        long manifestId = registerAndReroute("3");

        // 设施匹配但版本过旧（当前 2，声明 1）
        var late = receiptService.registerReceipt(manifestId, "REQ-CR-LATE-3",
                new BigDecimal("10000.000"), "F-INCINERATION-2", 1);

        assertThat(late.result()).isEqualTo("LATE_EVIDENCE");
        assertThat(manifestStatus(manifestId)).isEqualTo("IN_TRANSIT");
    }

    @Test
    void receiptWithWrongFacilityIsEvidenceOnly() {
        long manifestId = registerAndReroute("3B");

        // 版本正确但设施不匹配
        var late = receiptService.registerReceipt(manifestId, "REQ-CR-LATE-3B",
                new BigDecimal("10000.000"), "F-INCINERATION", 2);

        assertThat(late.result()).isEqualTo("LATE_EVIDENCE");
        assertThat(manifestStatus(manifestId)).isEqualTo("IN_TRANSIT");
    }

    @Test
    void lateReceiptIsIdempotentAndConflictOnDifferentParams() {
        long manifestId = registerAndReroute("4");
        var first = receiptService.registerReceipt(manifestId, "REQ-CR-LATE-4",
                new BigDecimal("10000.000"), "F-INCINERATION", 1);

        var replay = receiptService.registerReceipt(manifestId, "REQ-CR-LATE-4",
                new BigDecimal("10000.000"), "F-INCINERATION", 1);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.receiptId()).isEqualTo(first.receiptId());
        assertThat(replay.result()).isEqualTo("LATE_EVIDENCE");

        assertThatThrownBy(() -> receiptService.registerReceipt(manifestId, "REQ-CR-LATE-4",
                new BigDecimal("9999.000"), "F-INCINERATION", 1))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void bookedReceiptOnControlledManifestFollowsTwoPercentRule() {
        long manifestId = registerAndReroute("5");

        // 净重 10000，回执 10201 -> 偏差 2.01% > 2%，转异常
        var result = receiptService.registerReceipt(manifestId, "REQ-CR-EX-5",
                new BigDecimal("10201.000"), "F-INCINERATION-2", 2);

        assertThat(result.result()).isEqualTo("EXCEPTION");
        assertThat(manifestStatus(manifestId)).isEqualTo("EXCEPTION");
    }

    @Test
    void legacyCallStillWorksForNeverReroutedManifest() {
        long vehicleId = createVehicle();
        insertTare(vehicleId, new BigDecimal("8000.000"), clock.instant());
        long manifestId = weighingService.registerEntry(new WeighingService.EntryCommand(
                "REQ-CR-ENTRY-6", plateOf(vehicleId), "示例小区", "OTHER",
                new BigDecimal("18000.000"), "F-INCINERATION", null)).manifestId();

        // 未改投：旧式调用（无设施/版本）直接入账
        var result = receiptService.registerReceipt(manifestId, "REQ-CR-R6", new BigDecimal("10200.000"));

        assertThat(result.result()).isEqualTo("MATCH");
        assertThat(result.facilityCode()).isNull();
        assertThat(result.destinationVersion()).isNull();
        assertThat(manifestStatus(manifestId)).isEqualTo("RECEIVED");
    }

    @Test
    void queryShowsFullDestinationChainAndLateEvidence() {
        long manifestId = registerAndReroute("7");
        receiptService.registerReceipt(manifestId, "REQ-CR-LATE-7A",
                new BigDecimal("10000.000"), "F-INCINERATION", 1);
        receiptService.registerReceipt(manifestId, "REQ-CR-LATE-7B",
                new BigDecimal("10050.000"), "F-INCINERATION-2", 1);
        receiptService.registerReceipt(manifestId, "REQ-CR-BOOK-7",
                new BigDecimal("10000.000"), "F-INCINERATION-2", 2);

        var detail = manifestQueryService.detail(manifestId);

        assertThat(detail.destinationVersion()).isEqualTo(2);
        assertThat(detail.currentFacilityCode()).isEqualTo("F-INCINERATION-2");
        assertThat(detail.status()).isEqualTo("RECEIVED");
        assertThat(detail.netKg()).isEqualByComparingTo("10000.000");
        assertThat(detail.destinations()).extracting(
                ManifestQueryService.DestinationView::facilityCode)
                .containsExactly("F-INCINERATION", "F-INCINERATION-2");
        assertThat(detail.destinations()).extracting(
                ManifestQueryService.DestinationView::version)
                .containsExactly(1, 2);
        assertThat(detail.lateReceipts()).hasSize(2);
        assertThat(detail.lateReceipts()).extracting(
                ManifestQueryService.LateReceiptView::requestId)
                .containsExactly("REQ-CR-LATE-7A", "REQ-CR-LATE-7B");
    }
}
