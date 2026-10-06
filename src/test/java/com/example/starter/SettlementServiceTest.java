package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.repo.ManifestRepository;
import com.example.starter.service.ReceiptService;
import com.example.starter.service.SettlementService;
import com.example.starter.service.WeighingService;

/**
 * 日结汇总：按品类汇总进出量，与联单/回执明细可复算，重复查询稳定，跨日不归集。
 */
class SettlementServiceTest extends LedgerTestSupport {

    private static final LocalDate SETTLEMENT_DATE = LocalDate.of(2026, 10, 6);

    @Autowired
    private WeighingService weighingService;

    @Autowired
    private ReceiptService receiptService;

    @Autowired
    private SettlementService settlementService;

    private long registerEntry(String requestId, String category, String facilityCode, BigDecimal grossKg) {
        long vehicleId = createVehicle();
        insertTare(vehicleId, new BigDecimal("8000.000"), clock.instant());
        return weighingService.registerEntry(new WeighingService.EntryCommand(
                requestId, plateOf(vehicleId), "示例小区", category, grossKg, facilityCode, null)).manifestId();
    }

    @Test
    void dailySettlementAggregatesInboundAndOutboundByCategory() {
        // 进场：KITCHEN 3000 + 2000，RECYCLABLE 1000；回执：KITCHEN 3000（0% 偏差一致）
        long kitchenA = registerEntry("REQ-S-1", "KITCHEN", "F-KITCHEN", new BigDecimal("11000.000"));
        registerEntry("REQ-S-2", "KITCHEN", "F-KITCHEN", new BigDecimal("10000.000"));
        registerEntry("REQ-S-3", "RECYCLABLE", "F-RECYCLING", new BigDecimal("9000.000"));
        receiptService.registerReceipt(kitchenA, "REQ-S-R1", new BigDecimal("3000.000"));

        List<ManifestRepository.SettlementRow> rows = settlementService.daily(SETTLEMENT_DATE);

        assertThat(rows).hasSize(2);
        var kitchen = rows.stream().filter(r -> r.category().equals("KITCHEN")).findFirst().orElseThrow();
        assertThat(kitchen.inboundCount()).isEqualTo(2);
        assertThat(kitchen.inboundNetKg()).isEqualByComparingTo("5000.000");
        assertThat(kitchen.outboundCount()).isEqualTo(1);
        assertThat(kitchen.outboundNetKg()).isEqualByComparingTo("3000.000");

        var recyclable = rows.stream().filter(r -> r.category().equals("RECYCLABLE")).findFirst().orElseThrow();
        assertThat(recyclable.inboundCount()).isEqualTo(1);
        assertThat(recyclable.inboundNetKg()).isEqualByComparingTo("1000.000");
        assertThat(recyclable.outboundCount()).isZero();
        assertThat(recyclable.outboundNetKg()).isEqualByComparingTo("0.000");
    }

    @Test
    void dailySettlementIsRecomputableFromDetailsAndStableAcrossQueries() {
        long kitchenA = registerEntry("REQ-SR-1", "KITCHEN", "F-KITCHEN", new BigDecimal("11000.000"));
        registerEntry("REQ-SR-2", "KITCHEN", "F-KITCHEN", new BigDecimal("10000.000"));
        receiptService.registerReceipt(kitchenA, "REQ-SR-R1", new BigDecimal("2900.000"));

        var first = settlementService.daily(SETTLEMENT_DATE);
        var second = settlementService.daily(SETTLEMENT_DATE);
        assertThat(second).isEqualTo(first);

        // 与明细复算口径一致：进场按联单明细、出场按回执明细直接求和
        var kitchen = first.stream().filter(r -> r.category().equals("KITCHEN")).findFirst().orElseThrow();
        BigDecimal detailInbound = jdbc.sql(
                "SELECT COALESCE(SUM(net_kg), 0) FROM manifest WHERE category = 'KITCHEN'")
                .query(BigDecimal.class)
                .single();
        BigDecimal detailOutbound = jdbc.sql("""
                SELECT COALESCE(SUM(r.net_kg), 0) FROM receipt r
                JOIN manifest m ON m.id = r.manifest_id WHERE m.category = 'KITCHEN'
                """)
                .query(BigDecimal.class)
                .single();
        assertThat(kitchen.inboundNetKg()).isEqualByComparingTo(detailInbound);
        assertThat(kitchen.outboundNetKg()).isEqualByComparingTo(detailOutbound);
    }

    @Test
    void manifestsFromOtherDaysAreNotCounted() {
        // 前一自然日（Asia/Shanghai 2026-10-05 23:00）登记的联单不计入 10-06 日结
        clock.setInstant(Instant.parse("2026-10-05T15:00:00Z"));
        registerEntry("REQ-SD-0", "KITCHEN", "F-KITCHEN", new BigDecimal("11000.000"));
        clock.setInstant(Instant.parse("2026-10-06T01:00:00Z"));
        registerEntry("REQ-SD-1", "KITCHEN", "F-KITCHEN", new BigDecimal("12000.000"));

        var day6 = settlementService.daily(SETTLEMENT_DATE);
        assertThat(day6).hasSize(1);
        assertThat(day6.get(0).inboundCount()).isEqualTo(1);
        assertThat(day6.get(0).inboundNetKg()).isEqualByComparingTo("4000.000");

        var day5 = settlementService.daily(LocalDate.of(2026, 10, 5));
        assertThat(day5).hasSize(1);
        assertThat(day5.get(0).inboundNetKg()).isEqualByComparingTo("3000.000");
    }
}
