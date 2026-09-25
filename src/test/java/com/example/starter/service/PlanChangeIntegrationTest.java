package com.example.starter.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.example.starter.AbstractDivingIntegrationTest;
import com.example.starter.api.PlanChangeRequest;
import com.example.starter.api.PlanResponse;
import com.example.starter.api.SegmentResponse;
import com.example.starter.service.DivingService.ServiceResult;

/**
 * 方案变更与已生成记录冻结在同一事务内完成的 H2 事务测试。
 */
class PlanChangeIntegrationTest extends AbstractDivingIntegrationTest {

    @Test
    void planChangeFreezesOldRecordsAndLedgerAtomically() {
        PlanResponse v1 = service.apply(
                application("app-pc", "diver-a", "diver-b", 18, 56)).body();
        String opNo = v1.operationNo();
        long opId = jdbc.queryForObject("SELECT id FROM DIVE_OPERATION WHERE operation_no = ?",
                Long.class, opNo);

        // v1 下成对完成下潜 + 水底，产生 4 条 EFFECTIVE 记录与 4 行台账。
        service.recordSegment(descendSegment("pc-d-a", opNo, "diver-a", 0, 18, 0, 60));
        service.recordSegment(descendSegment("pc-d-b", opNo, "diver-b", 0, 18, 0, 60));
        service.recordSegment(bottomSegment("pc-b-a", opNo, "diver-a", 18, 60, 180));
        service.recordSegment(bottomSegment("pc-b-b", opNo, "diver-b", 18, 60, 180));
        assertThat(count("dive_segment", "WHERE pair_state = 'EFFECTIVE'")).isEqualTo(4L);
        assertThat(count("diver_time_ledger", "")).isEqualTo(4L);

        // 方案变更：18m/56 → 20m/45，同一事务冻结旧记录并删除台账。
        ServiceResult<PlanResponse> changed = service.changePlan(
                new PlanChangeRequest("pc-1", opNo, 20, 45));
        assertThat(changed.httpStatus()).isEqualTo(200);
        PlanResponse v2 = changed.body();
        assertThat(v2.planVersion()).isEqualTo(2);
        assertThat(v2.depthM()).isEqualTo(20);
        assertThat(v2.frozenSegmentCount()).isEqualTo(4);

        // 旧版本记录全部 FROZEN，不得沿用；配对组与台账清空。
        assertThat(count("dive_segment", "WHERE pair_state = 'FROZEN'")).isEqualTo(4L);
        assertThat(count("dive_segment", "WHERE pair_state = 'EFFECTIVE'")).isZero();
        assertThat(count("segment_pair", "")).isZero();
        assertThat(count("diver_time_ledger", "")).isZero();
        // 旧方案停用，仅 v2 生效；潜次主表同步到新深度新版本。
        assertThat(count("dive_plan", "WHERE active = TRUE AND version = 2")).isEqualTo(1L);
        assertThat(count("dive_plan", "WHERE active = TRUE AND version = 1")).isZero();
        Map<String, Object> op = operationById(opId);
        assertThat(op.get("depth_m")).isEqualTo(20);
        assertThat(op.get("plan_version")).isEqualTo(2);

        // 变更后按新方案重新成对记录可生效（深度 20），台账重建。
        service.recordSegment(descendSegment("pc2-d-a", opNo, "diver-a", 0, 20, 200, 260));
        SegmentResponse paired = service.recordSegment(
                descendSegment("pc2-d-b", opNo, "diver-b", 0, 20, 200, 260)).body();
        assertThat(paired.planVersion()).isEqualTo(2);
        assertThat(paired.effective()).isTrue();
        assertThat(count("diver_time_ledger", "")).isEqualTo(2L);

        // 同键同参重放：返回首次 v2 结果（含 frozenSegmentCount=4）。
        PlanResponse replay = service.changePlan(
                new PlanChangeRequest("pc-1", opNo, 20, 45)).body();
        assertThat(replay.planVersion()).isEqualTo(2);
        assertThat(replay.frozenSegmentCount()).isEqualTo(4);
        assertThat(count("dive_plan", "")).isEqualTo(2L);

        // 同键异参 409，状态保持 v2。
        expectBusiness(() -> service.changePlan(
                new PlanChangeRequest("pc-1", opNo, 24, 30)), "IDEMPOTENT_FINGERPRINT_MISMATCH");
        assertThat(operationById(opId).get("plan_version")).isEqualTo(2);
    }

    @Test
    void invalidPlanChangeRollsBackWithoutTouchingOldRecords() {
        PlanResponse v1 = service.apply(
                application("app-pc2", "diver-a", "diver-b", 18, 30)).body();
        String opNo = v1.operationNo();
        service.recordSegment(descendSegment("pc2x-a", opNo, "diver-a", 0, 18, 0, 60));
        service.recordSegment(descendSegment("pc2x-b", opNo, "diver-b", 0, 18, 0, 60));

        // 20m/46 超过该档 NDL(45)：整体回滚，旧记录仍 EFFECTIVE、台账保留、版本不变。
        expectBusiness(() -> service.changePlan(new PlanChangeRequest("pc-bad", opNo, 20, 46)),
                "NDL_EXCEEDED");
        assertThat(count("dive_segment", "WHERE pair_state = 'EFFECTIVE'")).isEqualTo(2L);
        assertThat(count("dive_segment", "WHERE frozen = TRUE")).isZero();
        assertThat(count("diver_time_ledger", "")).isEqualTo(2L);
        assertThat(count("dive_plan", "")).isEqualTo(1L);
    }

    @Test
    void planChangeRejectedAfterExit() {
        PlanResponse v1 = service.apply(
                application("app-pc3", "diver-a", "diver-b", 18, 30)).body();
        String opNo = v1.operationNo();
        service.recordSegment(descendSegment("pc3-a", opNo, "diver-a", 0, 18, 0, 60));
        service.recordSegment(descendSegment("pc3-b", opNo, "diver-b", 0, 18, 0, 60));
        service.exit(new com.example.starter.api.ExitRequest("pc3-exit", opNo, 60L));
        expectBusiness(() -> service.changePlan(new PlanChangeRequest("pc3-change", opNo, 20, 40)),
                "PLAN_CHANGE_NOT_ALLOWED");
    }
}
