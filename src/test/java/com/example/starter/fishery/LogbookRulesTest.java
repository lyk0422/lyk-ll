package com.example.starter.fishery;

import com.example.starter.fishery.api.dto.LogbookResponse;
import com.example.starter.fishery.init.SyntheticDataInitializer;
import com.example.starter.fishery.service.FisheryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 兼捕保护鱼种比例边界（恰 5% 合法，严格大于才审查）、UTC 日界归属与乱序日志链归位。
 */
@SpringBootTest
class LogbookRulesTest extends AbstractFisheryTest {

    @Autowired
    private FisheryService service;

    private String vessel;

    @BeforeEach
    void setUp() {
        vessel = registerVessel("V-LOG-" + java.util.UUID.randomUUID().toString().substring(0, 6),
                java.util.Map.of(SyntheticDataInitializer.SPECIES_HAIRTAIL, new BigDecimal("1000.000")));
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    void bycatchExactlyFivePercent_isLegalWithoutReview() {
        String trip = "T-BYC-EXACT";
        service.declareDeparture(departureRequestMulti(trip, vessel, T0, List.of(
                new com.example.starter.fishery.api.dto.DepartureRequest.SpeciesWeight(
                        SyntheticDataInitializer.SPECIES_HAIRTAIL, new BigDecimal("1000.000")))));
        // 95kg 普通鱼 + 5kg 保护鱼 = 100kg，兼捕恰 5%，合法且不标注审查
        LogbookResponse response = service.submitLogbook(logbookRequest(trip, "Z-1", T0.plusSeconds(3600),
                List.of(catchLine(SyntheticDataInitializer.SPECIES_HAIRTAIL, "95.000"),
                        catchLine(SyntheticDataInitializer.SPECIES_PROTECTED, "5.000"))));
        assertFalse(response.bycatchFlag(), "兼捕恰 5% 不应标注");
        assertFalse(response.reviewOpened());
        assertFalse(tripFrozen(trip));
    }

    @Test
    void bycatchOverFivePercent_flagsLogAndOpensReviewAndFreezes() {
        String trip = "T-BYC-OVER";
        service.declareDeparture(departureRequestMulti(trip, vessel, T0, List.of(
                new com.example.starter.fishery.api.dto.DepartureRequest.SpeciesWeight(
                        SyntheticDataInitializer.SPECIES_HAIRTAIL, new BigDecimal("1000.000")))));
        // 94.999 + 5.001 = 100，保护鱼占比 5.001% > 5%
        LogbookResponse response = service.submitLogbook(logbookRequest(trip, "Z-1", T0.plusSeconds(3600),
                List.of(catchLine(SyntheticDataInitializer.SPECIES_HAIRTAIL, "94.999"),
                        catchLine(SyntheticDataInitializer.SPECIES_PROTECTED, "5.001"))));
        assertTrue(response.bycatchFlag(), "超 5% 日志须单独标注");
        assertTrue(response.reviewOpened());
        assertTrue(tripFrozen(trip), "审查期间该船配额余额冻结");
        assertEquals("IN_REVIEW", tripStatus(trip));
    }

    @Test
    void logsAroundMidnightAreAssignedByUtcDayAndDoNotDuplicate() {
        String trip = "T-DAYBOUND";
        Instant midnight = Instant.parse("2026-03-14T00:00:00Z");
        service.declareDeparture(departureRequest(trip, vessel, midnight.minusSeconds(60),
                SyntheticDataInitializer.SPECIES_HAIRTAIL, "100.000"));

        // 23:59:30 归属前一日，00:00:30 归属次日，同一渔区不算重复
        LogbookResponse before = service.submitLogbook(logbookRequest(trip, "Z-MID",
                midnight.minusSeconds(30),
                List.of(catchLine(SyntheticDataInitializer.SPECIES_HAIRTAIL, "10.000"))));
        LogbookResponse after = service.submitLogbook(logbookRequest(trip, "Z-MID",
                midnight.plusSeconds(30),
                List.of(catchLine(SyntheticDataInitializer.SPECIES_HAIRTAIL, "20.000"))));

        assertEquals(LocalDate.of(2026, 3, 13), before.logDate());
        assertEquals(LocalDate.of(2026, 3, 14), after.logDate());
    }

    @Test
    void outOfOrderLogsAreReorderedByFishingTime() {
        String trip = "T-OOO";
        service.declareDeparture(departureRequest(trip, vessel, T0,
                SyntheticDataInitializer.SPECIES_HAIRTAIL, "100.000"));
        // 乱序提交：先交 12:00，再交 06:00，再交 18:00，分属不同渔区/日期组合
        LogbookResponse noon = service.submitLogbook(logbookRequest(trip, "Z-NOON",
                T0.plusSeconds(12 * 3600L),
                List.of(catchLine(SyntheticDataInitializer.SPECIES_HAIRTAIL, "10.000"))));
        LogbookResponse morning = service.submitLogbook(logbookRequest(trip, "Z-MORN",
                T0.plusSeconds(6 * 3600L),
                List.of(catchLine(SyntheticDataInitializer.SPECIES_HAIRTAIL, "10.000"))));
        LogbookResponse evening = service.submitLogbook(logbookRequest(trip, "Z-EVE",
                T0.plusSeconds(18 * 3600L),
                List.of(catchLine(SyntheticDataInitializer.SPECIES_HAIRTAIL, "10.000"))));

        // 按作业时刻归位：06:00→1，12:00→2，18:00→3（响应为提交快照，最终链序以台账为准）
        java.util.Map<String, Integer> seqByZone = new java.util.HashMap<>();
        jdbc.query("SELECT zone_code, seq_no FROM logbook WHERE trip_code = ? ORDER BY fishing_at",
                rs -> {
                    seqByZone.put(rs.getString("zone_code"), rs.getInt("seq_no"));
                }, trip);
        assertEquals(1, seqByZone.get("Z-MORN"));
        assertEquals(2, seqByZone.get("Z-NOON"));
        assertEquals(3, seqByZone.get("Z-EVE"));
    }

    @Test
    void duplicateLogSameVesselDayZoneIsRejected() {
        String trip = "T-DUP";
        service.declareDeparture(departureRequest(trip, vessel, T0,
                SyntheticDataInitializer.SPECIES_HAIRTAIL, "100.000"));
        service.submitLogbook(logbookRequest(trip, "Z-D", T0.plusSeconds(3600),
                List.of(catchLine(SyntheticDataInitializer.SPECIES_HAIRTAIL, "10.000"))));
        com.example.starter.fishery.api.ConflictException ex =
                org.junit.jupiter.api.Assertions.assertThrows(
                        com.example.starter.fishery.api.ConflictException.class,
                        () -> service.submitLogbook(logbookRequest(trip, "Z-D", T0.plusSeconds(7200),
                                List.of(catchLine(SyntheticDataInitializer.SPECIES_HAIRTAIL, "11.000")))));
        assertTrue(ex.getMessage().contains("去重"));
    }
}
