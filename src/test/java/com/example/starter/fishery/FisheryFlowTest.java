package com.example.starter.fishery;

import com.example.starter.fishery.api.BusinessRuleException;
import com.example.starter.fishery.api.dto.DepartureResponse;
import com.example.starter.fishery.api.dto.LogbookResponse;
import com.example.starter.fishery.api.dto.LandingResponse;
import com.example.starter.fishery.api.dto.ReconcileResponse;
import com.example.starter.fishery.api.dto.SettlementResponse;
import com.example.starter.fishery.domain.TripStatus;
import com.example.starter.fishery.init.SyntheticDataInitializer;
import com.example.starter.fishery.service.FisheryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 主流程、配额恰达/超出整笔拒绝与台账复算（真实 H2 事务，不 mock 数据库边界）。
 */
@SpringBootTest
class FisheryFlowTest extends AbstractFisheryTest {

    @Autowired
    private FisheryService service;

    private String vessel;

    @BeforeEach
    void setUp() {
        vessel = registerVesselWithQuota(SyntheticDataInitializer.SPECIES_HAIRTAIL, "100.000");
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    void fullFlow_departureLogbookLandingSettlementClosed() {
        String trip = "T-FLOW-001";
        DepartureResponse departure = service.declareDeparture(
                departureRequest(trip, vessel, T0, SyntheticDataInitializer.SPECIES_HAIRTAIL, "100.000"));
        assertEquals(TripStatus.DEPARTED, departure.status());
        assertEquals(2026, departure.year());

        LogbookResponse logbook = service.submitLogbook(logbookRequest(trip, "Z-A", T0.plusSeconds(3600),
                List.of(catchLine(SyntheticDataInitializer.SPECIES_HAIRTAIL, "80.000"))));
        assertFalse(logbook.bycatchFlag());
        assertEquals(1, logbook.seqNo());

        LandingResponse landing = service.reportLanding(landingRequest(trip, T0.plusSeconds(7200),
                List.of(landLine(SyntheticDataInitializer.SPECIES_HAIRTAIL, "80.000")), null));
        assertEquals(TripStatus.DISCHARGED, landing.status());
        assertEquals(0, new BigDecimal("0.0000").compareTo(landing.deviationPct()));
        assertFalse(landing.reviewTriggered());

        SettlementResponse settlement = service.settle(settlementRequest(trip));
        assertEquals(TripStatus.CLOSED, settlement.status());
        assertEquals(new BigDecimal("80.000"), settlement.deductedLines().get(0).weightKg());

        assertEquals("CLOSED", tripStatus(trip));
        assertEquals(0, usedQuota(vessel, SyntheticDataInitializer.SPECIES_HAIRTAIL)
                .compareTo(new BigDecimal("80.000")));
        assertEquals(1L, countSettlements(trip));

        ReconcileResponse reconcile = service.reconcile(trip);
        assertTrue(reconcile.ledgerConsistent(), "台账 used_kg 必须等于核销明细合计");
        assertTrue(reconcile.settledAndClosed(), "已扣减必须已结案且日志链连续");
        assertEquals(new BigDecimal("80.000"), reconcile.lines().get(0).deductedKg());
    }

    @Test
    void departureExactlyReachesQuota_isLegal() {
        String trip = "T-EXACT-001";
        DepartureResponse response = service.declareDeparture(
                departureRequest(trip, vessel, T0, SyntheticDataInitializer.SPECIES_HAIRTAIL, "100.000"));
        assertEquals(TripStatus.DEPARTED, response.status());
    }

    @Test
    void departureExceedingQuota_isRejectedWholeAndLeavesNoOpenTrip() {
        BusinessRuleException ex = assertThrows(BusinessRuleException.class, () -> service.declareDeparture(
                departureRequest("T-EXCEED-001", vessel, T0,
                        SyntheticDataInitializer.SPECIES_HAIRTAIL, "100.001")));
        assertTrue(ex.getMessage().contains("超过配额"));

        // 整笔拒绝：航次未落库，渔船可立即申报新航次
        DepartureResponse retry = service.declareDeparture(
                departureRequest("T-EXCEED-002", vessel, T0,
                        SyntheticDataInitializer.SPECIES_HAIRTAIL, "50.000"));
        assertEquals(TripStatus.DEPARTED, retry.status());
        assertEquals("DEPARTED", tripStatus("T-EXCEED-002"));
        assertEquals(BigDecimal.ZERO, usedQuota(vessel, SyntheticDataInitializer.SPECIES_HAIRTAIL));
    }

    @Test
    void secondTripSettlementTogetherWithFirstMustNotExceedQuota() {
        // 第一航次核销 60
        runTripToDischarged(service, vessel, "T-SEQ-001",
                SyntheticDataInitializer.SPECIES_HAIRTAIL, "60.000");
        service.settle(settlementRequest("T-SEQ-001"));
        assertEquals(0, usedQuota(vessel, SyntheticDataInitializer.SPECIES_HAIRTAIL)
                .compareTo(new BigDecimal("60.000")));

        // 第二航次申报恰达剩余 40 合法
        runTripToDischarged(service, vessel, "T-SEQ-002",
                SyntheticDataInitializer.SPECIES_HAIRTAIL, "40.000");
        SettlementResponse settlement = service.settle(settlementRequest("T-SEQ-002"));
        assertEquals(TripStatus.CLOSED, settlement.status());
        assertEquals(0, usedQuota(vessel, SyntheticDataInitializer.SPECIES_HAIRTAIL)
                .compareTo(new BigDecimal("100.000")));

        ReconcileResponse reconcile = service.reconcile("T-SEQ-002");
        assertTrue(reconcile.ledgerConsistent());
    }

    @Test
    void settlementExceedingQuotaRollsBackWithoutClosingTrip() {
        // 第一航次核销 70
        runTripToDischarged(service, vessel, "T-RB-001",
                SyntheticDataInitializer.SPECIES_HAIRTAIL, "70.000");
        service.settle(settlementRequest("T-RB-001"));

        // 第二航次申报 30（70+30 恰达配额合法），但实际日志/卸渔 31；
        // 核销时 70+31>100 必须整笔回滚：航次不结案、余额不变。
        String second = "T-RB-002";
        service.declareDeparture(departureRequest(second, vessel, T0,
                SyntheticDataInitializer.SPECIES_HAIRTAIL, "30.000"));
        service.submitLogbook(logbookRequest(second, "Z-B", T0.plusSeconds(3600),
                List.of(catchLine(SyntheticDataInitializer.SPECIES_HAIRTAIL, "31.000"))));
        service.reportLanding(landingRequest(second, T0.plusSeconds(7200),
                List.of(landLine(SyntheticDataInitializer.SPECIES_HAIRTAIL, "31.000")), null));
        assertThrows(BusinessRuleException.class,
                () -> service.settle(settlementRequest(second)));

        assertEquals("DISCHARGED", tripStatus(second));
        assertEquals(0, usedQuota(vessel, SyntheticDataInitializer.SPECIES_HAIRTAIL)
                .compareTo(new BigDecimal("70.000")));
        assertEquals(0L, countSettlements(second));
    }

    @Test
    void unknownVesselReturnsNotFound() {
        Instant now = Instant.parse("2026-03-14T00:00:00Z");
        com.example.starter.fishery.api.NotFoundException ex = assertThrows(
                com.example.starter.fishery.api.NotFoundException.class,
                () -> service.declareDeparture(departureRequest("T-404", "V-NOPE", now,
                        SyntheticDataInitializer.SPECIES_HAIRTAIL, "1")));
        assertTrue(ex.getMessage().contains("渔船"));
    }
}
