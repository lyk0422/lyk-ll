package com.example.starter.tailings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.example.starter.tailings.error.BusinessException;
import com.example.starter.tailings.service.FloodRoutingService;
import com.example.starter.tailings.support.IntegrationTestSupport;

/**
 * 调洪演算：库容恰等临界、双指标任一判险、恰等下限临界、幂等重放与同键异参409、可复算。
 */
class FloodRoutingServiceTest extends IntegrationTestSupport {

    @Test
    void safeWhenFloodBelowAvailableCapacity() {
        long pondId = createPond();
        FloodRoutingService.CalcResult result = calc(pondId, "700000", "60", "3");
        assertEquals("SAFE", result.capacityVerdict());
        assertEquals("SAFE", result.verdict());
        assertEquals(0, bd("800000.000").compareTo(result.availableCapacity()));
    }

    @Test
    void criticalWhenFloodExactlyEqualsAvailableCapacity() {
        long pondId = createPond();
        FloodRoutingService.CalcResult result = calc(pondId, "800000", "60", "3");
        assertEquals("CRITICAL", result.capacityVerdict());
        assertEquals("CRITICAL", result.verdict());
    }

    @Test
    void mustDischargeWhenFloodExceedsAvailableCapacity() {
        long pondId = createPond();
        FloodRoutingService.CalcResult result = calc(pondId, "800001", "60", "3");
        assertEquals("MUST_DISCHARGE", result.capacityVerdict());
        assertEquals("MUST_DISCHARGE", result.verdict());
    }

    @Test
    void dangerWhenDryBeachBelowMin() {
        long pondId = createPond();
        FloodRoutingService.CalcResult result = calc(pondId, "100", "49.999", "3");
        assertEquals("DANGER", result.safetyVerdict());
        assertEquals("DANGER", result.verdict());
    }

    @Test
    void dangerWhenPhreaticBelowMin() {
        long pondId = createPond();
        FloodRoutingService.CalcResult result = calc(pondId, "100", "60", "1.999");
        assertEquals("DANGER", result.safetyVerdict());
        assertEquals("DANGER", result.verdict());
    }

    @Test
    void criticalWhenIndicatorExactlyAtMin() {
        long pondId = createPond();
        FloodRoutingService.CalcResult dryBeachAtMin = calc(pondId, "100", "50", "3");
        assertEquals("CRITICAL", dryBeachAtMin.safetyVerdict());
        assertEquals("CRITICAL", dryBeachAtMin.verdict());
        FloodRoutingService.CalcResult phreaticAtMin = calc(pondId, "100", "60", "2");
        assertEquals("CRITICAL", phreaticAtMin.safetyVerdict());
        assertEquals("CRITICAL", phreaticAtMin.verdict());
    }

    @Test
    void idempotentReplayReturnsFirstResult() {
        long pondId = createPond();
        String requestId = rid();
        FloodRoutingService.CalcRequest request = new FloodRoutingService.CalcRequest(
                requestId, pondId, bd("190"), bd("900000"), bd("60"), bd("3"));
        FloodRoutingService.CalcResult first = floodRoutingService.calculate(request);
        FloodRoutingService.CalcResult replay = floodRoutingService.calculate(request);
        assertEquals(first.calcId(), replay.calcId());
        assertEquals(first.verdict(), replay.verdict());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM flood_calc WHERE request_id = ?", Integer.class, requestId));
    }

    @Test
    void sameKeyDifferentParamsReturns409() {
        long pondId = createPond();
        String requestId = rid();
        floodRoutingService.calculate(new FloodRoutingService.CalcRequest(
                requestId, pondId, bd("190"), bd("900000"), bd("60"), bd("3")));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> floodRoutingService.calculate(new FloodRoutingService.CalcRequest(
                        requestId, pondId, bd("190"), bd("950000"), bd("60"), bd("3"))));
        assertEquals(HttpStatus.CONFLICT, ex.status());
    }

    @Test
    void snapshotRecheckIsConsistent() {
        long pondId = createPond();
        FloodRoutingService.CalcResult result = calc(pondId, "900000", "60", "3");
        FloodRoutingService.RecheckResult recheck = floodRoutingService.recheck(result.calcId());
        assertTrue(recheck.consistent());
        assertEquals(result.verdict(), recheck.recomputedVerdict());
        String snapshot = floodRoutingService.get(result.calcId()).snapshot();
        assertTrue(snapshot.contains("availableCapacity"));
        assertTrue(snapshot.contains("storageCurve"));
    }
}
