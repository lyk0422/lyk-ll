package com.example.starter;

import com.example.starter.domain.PermitStatus;
import com.example.starter.dto.EvaluationRequest;
import com.example.starter.dto.EvaluationResponse;
import com.example.starter.dto.ReadingResponse;
import com.example.starter.dto.ReviewRequest;
import com.example.starter.dto.ReviewResponse;
import com.example.starter.error.ApiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 振动回传去重、超限冻结生效范围、复核解冻幂等、评估封档终态与迟到事件。
 */
class VibrationFreezeTest extends BaseIntegrationTest {

    @Test
    void readingDedupedByZonePointSampledAt() {
        long permitId = applyApproveFile("d-1", ZONE_EAST, "2026-09-26T08:00:00Z", "10");
        ReadingResponse first = submitReading(permitId, "P1", "2026-09-26T08:00:01Z", "0.5");
        assertTrue(first.accepted());
        assertFalse(first.deduped());
        ReadingResponse dup = submitReading(permitId, "P1", "2026-09-26T08:00:01Z", "0.9");
        assertFalse(dup.accepted());
        assertTrue(dup.deduped());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM vibration_reading", Integer.class));
    }

    @Test
    void overThresholdFreezesMineAndCascades() {
        // 在途申请、已批准未起爆、已备案（历史）各一单；另一矿区一单不受影响
        jdbc.update("INSERT INTO mine (id, name, velocity_threshold, frozen)"
                + " VALUES (90, '备用矿区', 1.000, FALSE)");
        jdbc.update("INSERT INTO blast_zone (id, mine_id, name, max_charge_per_delay)"
                + " VALUES (90, 90, '备用爆区', 50.000)");
        jdbc.update("INSERT INTO monitor_point (zone_id, point_code) VALUES (90, 'P1')");

        long pending = apply("fz-p", ZONE_EAST, "2026-09-26T08:00:00Z", "10").id();
        long approved = applyAndApprove("fz-a", ZONE_EAST, "2026-09-26T09:00:00Z", "10");
        long filed = applyApproveFile("fz-f", ZONE_EAST, "2026-09-26T10:00:00Z", "10");
        long otherMine = applyAndApprove("fz-o", 90L, "2026-09-26T08:00:00Z", "10");

        ReadingResponse response = submitReading(filed, "P1", "2026-09-26T10:00:01Z", "1.5");
        assertTrue(response.freezeTriggered());
        assertTrue(mineFrozen(MINE_ID));

        // 冻结生效范围：在途驳回、已批准未起爆作废、已起爆历史保留、其他矿区不受影响
        assertEquals("REJECTED", statusOf(pending));
        assertEquals("VOID", statusOf(approved));
        assertEquals("FILED", statusOf(filed));
        assertEquals("APPROVED", statusOf(otherMine));
        assertFalse(mineFrozen(90L));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM approved_slot s JOIN blast_permit p ON s.permit_id = p.id"
                        + " WHERE p.zone_id IN (SELECT id FROM blast_zone WHERE mine_id = 1)",
                Integer.class));

        // 冻结期间新申请直接驳回、审批被拒绝
        assertEquals(PermitStatus.REJECTED,
                apply("fz-new", ZONE_EAST, "2026-09-26T11:00:00Z", "10").status());
        ApiException ex = assertThrows(ApiException.class,
                () -> permitService.approve(pending));
        assertEquals(422, ex.getStatus().value());
    }

    @Test
    void underThresholdDoesNotFreeze() {
        long permitId = applyApproveFile("u-1", ZONE_EAST, "2026-09-26T08:00:00Z", "10");
        ReadingResponse response = submitReading(permitId, "P1", "2026-09-26T08:00:01Z", "1.0");
        assertTrue(response.accepted());
        assertFalse(response.freezeTriggered());
        assertFalse(mineFrozen(MINE_ID));
    }

    @Test
    void unfreezeRequiresReviewAndIsIdempotent() {
        // 未冻结时复核被拒绝
        ApiException notFrozen = assertThrows(ApiException.class,
                () -> freezeService.unfreeze(new ReviewRequest("r-0", MINE_ID, "监管员甲")));
        assertEquals(422, notFrozen.getStatus().value());

        long permitId = applyApproveFile("r-1", ZONE_EAST, "2026-09-26T08:00:00Z", "10");
        submitReading(permitId, "P1", "2026-09-26T08:00:01Z", "2.0");
        assertTrue(mineFrozen(MINE_ID));

        ReviewResponse first = freezeService.unfreeze(new ReviewRequest("r-2", MINE_ID, "监管员甲"));
        assertTrue(first.unfrozen());
        assertFalse(mineFrozen(MINE_ID));

        // 同键同参重放：返回首次结果，不重复解冻
        ReviewResponse replay = freezeService.unfreeze(new ReviewRequest("r-2", MINE_ID, "监管员甲"));
        assertEquals(first.id(), replay.id());
        assertFalse(replay.unfrozen());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM freeze_review WHERE request_id = 'r-2'", Integer.class));

        // 同键异参：409
        ApiException conflict = assertThrows(ApiException.class,
                () -> freezeService.unfreeze(new ReviewRequest("r-2", MINE_ID, "监管员乙")));
        assertEquals(409, conflict.getStatus().value());
    }

    @Test
    void evaluationRequiresCompleteReadingsThenSealsTerminally() {
        long permitId = applyApproveFile("e-1", ZONE_EAST, "2026-09-26T08:00:00Z", "10");
        // 东一爆区有 P1/P2 两个测点，只回传 P1 不齐全
        submitReading(permitId, "P1", "2026-09-26T08:00:01Z", "0.5");
        ApiException incomplete = assertThrows(ApiException.class,
                () -> evaluationService.seal(permitId, new EvaluationRequest("e-1-E", "合格")));
        assertEquals(422, incomplete.getStatus().value());

        submitReading(permitId, "P2", "2026-09-26T08:00:02Z", "0.6");
        EvaluationResponse sealed = evaluationService.seal(permitId,
                new EvaluationRequest("e-1-E", "合格"));
        assertTrue(sealed.sealed());
        assertEquals("SEALED", statusOf(permitId));

        // 重复封档：同键重放与新键均幂等成功
        EvaluationResponse replay = evaluationService.seal(permitId,
                new EvaluationRequest("e-1-E", "合格"));
        assertEquals(sealed.id(), replay.id());
        EvaluationResponse secondKey = evaluationService.seal(permitId,
                new EvaluationRequest("e-1-E2", "优秀"));
        assertEquals(sealed.id(), secondKey.id());
        assertEquals("合格", secondKey.conclusion(), "封档为终态，结论不被改写");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM evaluation WHERE permit_id = " + permitId, Integer.class));
    }

    @Test
    void lateReadingAfterSealOnlyRecordsEvent() {
        long permitId = applyApproveFile("l-1", ZONE_WEST, "2026-09-26T08:00:00Z", "10");
        submitReading(permitId, "P1", "2026-09-26T08:00:01Z", "0.5");
        evaluationService.seal(permitId, new EvaluationRequest("l-1-E", "合格"));

        // 封档后迟到读数（即使超限）：只记迟到事件，不改结论、不触发冻结
        ReadingResponse late = submitReading(permitId, "P1", "2026-09-26T08:00:05Z", "9.9");
        assertTrue(late.late());
        assertFalse(late.freezeTriggered());
        assertFalse(mineFrozen(MINE_ID));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM late_event WHERE permit_id = " + permitId, Integer.class));
        assertEquals("合格", jdbc.queryForObject(
                "SELECT conclusion FROM evaluation WHERE permit_id = " + permitId, String.class));
    }
}
