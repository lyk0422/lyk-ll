package com.example.starter.fishery;

import com.example.starter.fishery.api.BusinessRuleException;
import com.example.starter.fishery.api.ConflictException;
import com.example.starter.fishery.api.dto.LandingResponse;
import com.example.starter.fishery.api.dto.ReviewDecisionRequest;
import com.example.starter.fishery.api.dto.ReviewDecisionResponse;
import com.example.starter.fishery.api.dto.SettlementResponse;
import com.example.starter.fishery.domain.ReviewConclusion;
import com.example.starter.fishery.domain.ReviewStatus;
import com.example.starter.fishery.domain.TripStatus;
import com.example.starter.fishery.init.SyntheticDataInitializer;
import com.example.starter.fishery.service.FisheryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 卸渔偏差恰 3% 边界、差异说明与人工复核：复核期间冻结，结论与解冻/核销同事务联动。
 */
@SpringBootTest
class ReviewAndDeviationTest extends AbstractFisheryTest {

    @Autowired
    private FisheryService service;

    private String vessel;

    @BeforeEach
    void setUp() {
        vessel = registerVesselWithQuota(SyntheticDataInitializer.SPECIES_HAIRTAIL, "1000.000");
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    void landingDeviationExactlyThreePercent_isConsistentNoReview() {
        String trip = "T-DEV-EXACT";
        service.declareDeparture(departureRequest(trip, vessel, T0,
                SyntheticDataInitializer.SPECIES_HAIRTAIL, "1000.000"));
        service.submitLogbook(logbookRequest(trip, "Z-D1", T0.plusSeconds(3600),
                List.of(catchLine(SyntheticDataInitializer.SPECIES_HAIRTAIL, "100.000"))));
        // 日志 100，卸渔 103，偏差恰 3% 视为一致
        LandingResponse response = service.reportLanding(landingRequest(trip, T0.plusSeconds(7200),
                List.of(landLine(SyntheticDataInitializer.SPECIES_HAIRTAIL, "103.000")), null));
        assertEquals(0, new BigDecimal("3.0000").compareTo(response.deviationPct()));
        assertFalse(response.reviewTriggered());
        assertEquals(TripStatus.DISCHARGED, response.status());

        SettlementResponse settlement = service.settle(settlementRequest(trip));
        assertEquals(TripStatus.CLOSED, settlement.status());
    }

    @Test
    void landingDeviationOverThreePercentWithoutExplanation_isRejected() {
        String trip = "T-DEV-NOEXP";
        service.declareDeparture(departureRequest(trip, vessel, T0,
                SyntheticDataInitializer.SPECIES_HAIRTAIL, "1000.000"));
        service.submitLogbook(logbookRequest(trip, "Z-D2", T0.plusSeconds(3600),
                List.of(catchLine(SyntheticDataInitializer.SPECIES_HAIRTAIL, "100.000"))));
        BusinessRuleException ex = assertThrows(BusinessRuleException.class,
                () -> service.reportLanding(landingRequest(trip, T0.plusSeconds(7200),
                        List.of(landLine(SyntheticDataInitializer.SPECIES_HAIRTAIL, "103.001")), null)));
        assertTrue(ex.getMessage().contains("差异说明"));
        // 整笔拒绝：无进港记录，航次仍在 DEPARTED，未冻结
        assertEquals("DEPARTED", tripStatus(trip));
        assertFalse(tripFrozen(trip));
    }

    @Test
    void deviationReviewFreezesQuotaAndDecisionConfirmsLandedThenSettlesAtomically() {
        String trip = "T-DEV-REVIEW";
        service.declareDeparture(departureRequest(trip, vessel, T0,
                SyntheticDataInitializer.SPECIES_HAIRTAIL, "1000.000"));
        service.submitLogbook(logbookRequest(trip, "Z-D3", T0.plusSeconds(3600),
                List.of(catchLine(SyntheticDataInitializer.SPECIES_HAIRTAIL, "100.000"))));
        LandingResponse landing = service.reportLanding(landingRequest(trip, T0.plusSeconds(7200),
                List.of(landLine(SyntheticDataInitializer.SPECIES_HAIRTAIL, "110.000")),
                "卸渔时渔获含水量偏高"));
        assertTrue(landing.reviewTriggered());
        assertEquals(TripStatus.IN_REVIEW, landing.status());
        assertTrue(tripFrozen(trip), "复核期间该船配额余额冻结");

        // 冻结期间核销必须被拒绝
        assertThrows(ConflictException.class, () -> service.settle(settlementRequest(trip)));

        Long reviewId = jdbc.queryForObject(
                "SELECT id FROM review_case WHERE trip_code = ? AND status = 'OPEN'",
                Long.class, trip);

        // 复核结论：以卸渔量 110 为准；同事务解冻并核销结案
        ReviewDecisionResponse decision = service.decideReview(reviewId,
                new ReviewDecisionRequest(newRequestId(), ReviewConclusion.CONFIRM_LANDED,
                        "复核确认以卸渔量为准", null));
        assertTrue(decision.settled());
        assertEquals(TripStatus.CLOSED, decision.tripStatus());
        assertEquals(ReviewStatus.CLOSED, decision.reviewCase().status());
        assertFalse(tripFrozen(trip), "结论与冻结解除同事务完成");

        assertEquals(0, usedQuota(vessel, SyntheticDataInitializer.SPECIES_HAIRTAIL)
                .compareTo(new BigDecimal("110.000")), "最终口径按卸渔量 110 核销");
        assertEquals("CLOSED", tripStatus(trip));
    }

    @Test
    void decisionConfirmsLoggedUsesLogWeight() {
        String trip = "T-DEV-LOGGED";
        service.declareDeparture(departureRequest(trip, vessel, T0,
                SyntheticDataInitializer.SPECIES_HAIRTAIL, "1000.000"));
        service.submitLogbook(logbookRequest(trip, "Z-D4", T0.plusSeconds(3600),
                List.of(catchLine(SyntheticDataInitializer.SPECIES_HAIRTAIL, "100.000"))));
        service.reportLanding(landingRequest(trip, T0.plusSeconds(7200),
                List.of(landLine(SyntheticDataInitializer.SPECIES_HAIRTAIL, "110.000")), "网具破损补记"));

        Long reviewId = jdbc.queryForObject(
                "SELECT id FROM review_case WHERE trip_code = ? AND status = 'OPEN'",
                Long.class, trip);
        ReviewDecisionResponse decision = service.decideReview(reviewId,
                new ReviewDecisionRequest(newRequestId(), ReviewConclusion.CONFIRM_LOGGED,
                        "以日志累计量为准", null));
        assertTrue(decision.settled());
        assertEquals(0, usedQuota(vessel, SyntheticDataInitializer.SPECIES_HAIRTAIL)
                .compareTo(new BigDecimal("100.000")), "最终口径按日志量 100 核销");
    }

    @Test
    void adjustedDecisionRequiresAdjustedLines() {
        String trip = "T-DEV-ADJ";
        service.declareDeparture(departureRequest(trip, vessel, T0,
                SyntheticDataInitializer.SPECIES_HAIRTAIL, "1000.000"));
        service.submitLogbook(logbookRequest(trip, "Z-D5", T0.plusSeconds(3600),
                List.of(catchLine(SyntheticDataInitializer.SPECIES_HAIRTAIL, "100.000"))));
        service.reportLanding(landingRequest(trip, T0.plusSeconds(7200),
                List.of(landLine(SyntheticDataInitializer.SPECIES_HAIRTAIL, "120.000")), "计量争议"));
        Long reviewId = jdbc.queryForObject(
                "SELECT id FROM review_case WHERE trip_code = ? AND status = 'OPEN'",
                Long.class, trip);
        assertThrows(BusinessRuleException.class, () -> service.decideReview(reviewId,
                new ReviewDecisionRequest(newRequestId(), ReviewConclusion.ADJUSTED, null, null)));
        // 案件仍未决、仍冻结
        assertEquals("IN_REVIEW", tripStatus(trip));
        assertTrue(tripFrozen(trip));
    }
}
