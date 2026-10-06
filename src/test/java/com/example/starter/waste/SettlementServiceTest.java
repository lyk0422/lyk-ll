package com.example.starter.waste;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 日结汇总：按品类汇总进出量、与联单明细可复算、重复查询稳定。
 */
class SettlementServiceTest extends WasteLedgerTestSupport {

    @Autowired
    private WeighingService weighingService;

    @Autowired
    private ReceiptService receiptService;

    @Autowired
    private SettlementService settlementService;

    @Autowired
    private SettlementRepository settlementRepository;

    private long register(String plate, String category, String grossKg) {
        givenVehicle(plate, "8000", LocalDateTime.now());
        return weighingService.registerEntry(
                entryRequest(newRequestId("entry"), plate, category, FACILITY_KITCHEN, grossKg, null))
                .manifestId();
    }

    @Test
    @DisplayName("日结按品类汇总进出量且与明细可复算")
    void dailySettlementAggregatesAndRecomputable() {
        long m1 = register(newPlate(), "厨余垃圾", "9000");
        long m2 = register(newPlate(), "厨余垃圾", "8500");
        long m3 = register(newPlate(), "可回收物", "10000");
        receiptService.registerReceipt(m1, new ReceiptRequest(newRequestId("receipt"), new BigDecimal("1000")));
        receiptService.registerReceipt(m2, new ReceiptRequest(newRequestId("receipt"), new BigDecimal("500")));
        receiptService.registerReceipt(m3, new ReceiptRequest(newRequestId("receipt"), new BigDecimal("2100")));

        DailySettlementResponse settlement = settlementService.daily(null);
        assertEquals(LocalDate.now(), settlement.date());
        Map<String, DailySettlementResponse.Item> byCategory = settlement.items().stream()
                .collect(Collectors.toMap(DailySettlementResponse.Item::category, Function.identity()));

        DailySettlementResponse.Item kitchen = byCategory.get("厨余垃圾");
        assertEquals(0, new BigDecimal("1500.000").compareTo(kitchen.inboundKg()));
        assertEquals(0, new BigDecimal("1500.000").compareTo(kitchen.outboundKg()));
        assertEquals(2, kitchen.manifestCount());
        assertEquals(2, kitchen.receiptCount());

        DailySettlementResponse.Item recyclable = byCategory.get("可回收物");
        assertEquals(0, new BigDecimal("2000.000").compareTo(recyclable.inboundKg()));
        assertEquals(0, new BigDecimal("2100.000").compareTo(recyclable.outboundKg()));

        LocalDate today = LocalDate.now();
        BigDecimal recomputed = settlementRepository.sumManifestNet(
                "厨余垃圾", today.atStartOfDay(), today.plusDays(1).atStartOfDay());
        assertEquals(0, recomputed.compareTo(kitchen.inboundKg()));
    }

    @Test
    @DisplayName("日结重复查询结果稳定")
    void dailySettlementStableAcrossQueries() {
        long m1 = register(newPlate(), "厨余垃圾", "9000");
        receiptService.registerReceipt(m1, new ReceiptRequest(newRequestId("receipt"), new BigDecimal("1000")));

        DailySettlementResponse first = settlementService.daily(null);
        DailySettlementResponse second = settlementService.daily(null);

        assertEquals(first, second);
    }

    @Test
    @DisplayName("无业务的日期日结为空汇总")
    void dailySettlementEmptyForQuietDay() {
        DailySettlementResponse settlement = settlementService.daily(LocalDate.now().minusDays(1));
        assertTrue(settlement.items().isEmpty());
    }
}
