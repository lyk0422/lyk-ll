package com.example.starter.tailings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.example.starter.tailings.error.BusinessException;
import com.example.starter.tailings.service.FloodRoutingService;
import com.example.starter.tailings.service.PlanService;
import com.example.starter.tailings.service.ReviewService;
import com.example.starter.tailings.support.IntegrationTestSupport;

/**
 * 复盘归档：指令链完结后归档，归档为终态，重复归档幂等成功。
 */
class ReviewArchiveTest extends IntegrationTestSupport {

    @Test
    void archiveAfterCompletedChain() {
        long pondId = createPond();
        runFullChain(pondId);
        ReviewService.ArchiveResult result = reviewService.archive(rid(), pondId);
        assertFalse(result.replayed());
        assertEquals("ARCHIVED", result.status());
        assertEquals("COMPLETED", jdbc.queryForObject(
                "SELECT status FROM discharge_plan WHERE pond_id = ?", String.class, pondId));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM pond_active_plan WHERE pond_id = ?", Integer.class, pondId));
    }

    @Test
    void repeatedArchiveReturnsIdempotentSuccess() {
        long pondId = createPond();
        runFullChain(pondId);
        ReviewService.ArchiveResult first = reviewService.archive(rid(), pondId);
        ReviewService.ArchiveResult again = reviewService.archive(rid(), pondId);
        assertTrue(again.replayed());
        assertEquals(first.archiveId(), again.archiveId());
        assertEquals("ARCHIVED", again.status());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM review_archive WHERE pond_id = ?", Integer.class, pondId));
    }

    @Test
    void archiveBeforeChainCompleteRejected() {
        long pondId = createPond();
        FloodRoutingService.CalcResult calc = calc(pondId, "900000", "60", "3");
        PlanService.PlanResult plan = planService.create(new PlanService.PlanRequest(
                rid(), calc.calcId(), java.util.List.of(new PlanService.OrificeInput("1#孔", bd("80")))));
        commandService.issue(rid(), plan.planId());
        BusinessException ex = assertThrows(BusinessException.class,
                () -> reviewService.archive(rid(), pondId));
        assertEquals(HttpStatus.CONFLICT, ex.status());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM review_archive WHERE pond_id = ?", Integer.class, pondId));
    }
}
