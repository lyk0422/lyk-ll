package com.example.starter.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.example.starter.AbstractDivingIntegrationTest;
import com.example.starter.api.CloseRequest;
import com.example.starter.api.ExitRequest;
import com.example.starter.api.PlanResponse;
import com.example.starter.api.VitalsRequest;
import com.example.starter.api.VitalsResponse;

/**
 * 观察期症状触发加压舱预案并永久冻结结案的 H2 事务测试。
 */
class ChamberIntegrationTest extends AbstractDivingIntegrationTest {

    private String prepareObservingOperation(String prefix, long exitAt) {
        PlanResponse plan = service.apply(
                application(prefix + "-app", "diver-a", "diver-b", 18, 56)).body();
        String opNo = plan.operationNo();
        service.recordSegment(descendSegment(prefix + "-d-a", opNo, "diver-a", 0, 18, 0, 60));
        service.recordSegment(descendSegment(prefix + "-d-b", opNo, "diver-b", 0, 18, 0, 60));
        service.recordSegment(bottomSegment(prefix + "-b-a", opNo, "diver-a", 18, 60, exitAt));
        service.recordSegment(bottomSegment(prefix + "-b-b", opNo, "diver-b", 18, 60, exitAt));
        service.exit(new ExitRequest(prefix + "-exit", opNo, exitAt));
        return opNo;
    }

    @Test
    void symptomActivatesChamberAndFreezesClosureForever() {
        long exitAt = 1_000L;
        String opNo = prepareObservingOperation("ch", exitAt);

        // 观察期内出现症状：启动加压舱预案。
        VitalsResponse symptomatic = service.vitals(new VitalsRequest(
                "ch-vit-sym", opNo, "diver-a", exitAt + 600L, true, "皮肤瘙痒、关节酸痛")).body();
        assertThat(symptomatic.status()).isEqualTo("CHAMBER");
        assertThat(symptomatic.chamberActivated()).isTrue();
        assertThat(symptomatic.late()).isFalse();
        assertThat(count("dive_event", "WHERE event_type = 'CHAMBER_ACTIVATED'")).isEqualTo(1L);
        assertThat(count("active_dive_group", "")).isEqualTo(1L); // 未结案不释放占组

        // 即便恰满/超过 2 小时观察也不得结案。
        expectBusiness(() -> service.close(
                new CloseRequest("ch-close-1", opNo, exitAt + 7_200L)), "CLOSE_FROZEN");
        expectBusiness(() -> service.close(
                new CloseRequest("ch-close-2", opNo, exitAt + 10_000L)), "CLOSE_FROZEN");
        assertThat(count("dive_operation", "WHERE status = 'CLOSED'")).isZero();

        // 已在 CHAMBER 状态后再交体征（含无症状）：不重复激活，状态保持冻结。
        VitalsResponse more = service.vitals(new VitalsRequest(
                "ch-vit-more", opNo, "diver-b", exitAt + 900L, false, null)).body();
        assertThat(more.status()).isEqualTo("CHAMBER");
        assertThat(more.chamberActivated()).isFalse();
        assertThat(count("dive_event", "WHERE event_type = 'CHAMBER_ACTIVATED'")).isEqualTo(1L);

        // 同键同参重放症状体征，返回首次结果。
        VitalsResponse replay = service.vitals(new VitalsRequest(
                "ch-vit-sym", opNo, "diver-a", exitAt + 600L, true, "皮肤瘙痒、关节酸痛")).body();
        assertThat(replay.status()).isEqualTo("CHAMBER");
        assertThat(replay.chamberActivated()).isTrue();
        assertThat(count("vitals_record", "")).isEqualTo(2);
    }

    @Test
    void asymptomaticObservationAllowsClosureExactlyAtTwoHours() {
        long exitAt = 5_000L;
        String opNo = prepareObservingOperation("ok", exitAt);
        service.vitals(new VitalsRequest("ok-vit", opNo, "diver-a", exitAt + 3_600L, false, null));
        // 恰满 2 小时结案。
        var closed = service.close(new CloseRequest("ok-close", opNo, exitAt + 7_200L)).body();
        assertThat(closed.status()).isEqualTo("CLOSED");
        assertThat(count("dive_event", "WHERE event_type = 'CHAMBER_ACTIVATED'")).isZero();
    }
}
