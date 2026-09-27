package com.example.starter.rt.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.rt.domain.PlanStatus;
import com.example.starter.rt.dto.RecordedResponse;
import com.example.starter.rt.dto.RejectionBody;
import com.example.starter.rt.dto.ScheduleItemRequest;
import com.example.starter.rt.dto.ScheduleResponse;
import com.example.starter.rt.repository.PlanRepository.PlanVersionRow;
import com.example.starter.rt.repository.SchedulingRepository;

/**
 * 计划改版与排程门禁联动 H2 测试：改版原子取消未开始排程并释放资源，
 * 已执行分次剂量与实绩不变，不能用失效版本排程治疗；新版本验证后可重新排程执行。
 */
class ScheduleRevisionTest extends SchedulingTestBase {

    private static final java.time.OffsetDateTime T0 =
            java.time.OffsetDateTime.of(2026, 10, 1, 2, 0, 0, 0, java.time.ZoneOffset.UTC);

    @Autowired
    private SchedulingRepository scheduleRepo;

    private ScheduleItemRequest item(int fractionNo, int level, java.time.OffsetDateTime start,
                                     int minutes, String deviceId) {
        return new ScheduleItemRequest(fractionNo, deviceId, level, start, start.plusMinutes(minutes));
    }

    @Test
    @DisplayName("换版批准：BOOKED排程同事务取消释放，EXECUTED实绩与剂量冻结，新版本可重新排程")
    void revisionCancelsPendingKeepsExecuted() {
        approvedVerifiedPlan();
        registerDevice(3);

        // 发布分次1、2（等级1，各10分钟，端点相接）
        assert200(scheduling.publish(new com.example.starter.rt.dto.SchedulePublishRequest(
                PATIENT, PLAN, 1, "RV-PUB", List.of(
                        item(1, 1, T0, 10, DEVICE),
                        item(2, 1, T0.plusMinutes(10), 10, DEVICE)))));
        assertThat(scheduleRepo.countOccupancies()).isEqualTo(2);

        // 窗口内完成分次1
        setClockUtc(LocalDateTime.of(2026, 10, 1, 2, 5, 0));
        assert200(workflow.recordFraction(PATIENT, PLAN, 1, 200L, "RV-FR1"));

        long v1Id = planRepository.findVersion(PATIENT, PLAN, 1).orElseThrow().id();

        // 设计并审核 v2，批准换版
        createPlanVersion(PATIENT, PLAN, 2, TPL, 5000L, 200L, 25,
                4300L, new BigDecimal("30.00"));
        assert200(workflow.audit(PATIENT, PLAN, 2, "RV-AUD2"));
        assert200(workflow.approve(PATIENT, PLAN, 2, "RV-APV2"));

        // v1 失效；守卫指向 v2
        PlanVersionRow v1 = planRepository.findVersion(PATIENT, PLAN, 1).orElseThrow();
        PlanVersionRow v2 = planRepository.findVersion(PATIENT, PLAN, 2).orElseThrow();
        assertThat(v1.status()).isEqualTo(PlanStatus.INVALID.name());
        assertThat(planRepository.findActivePlan(PATIENT).orElseThrow().planVersionId())
                .isEqualTo(v2.id());

        // 排程：分次1 EXECUTED 实绩冻结；分次2 CANCELLED 已释放
        List<ScheduleResponse> all = scheduling.planSchedules(PATIENT, PLAN, null);
        ScheduleResponse f1 = all.stream().filter(s -> s.fractionNo() == 1).findFirst().orElseThrow();
        ScheduleResponse f2 = all.stream().filter(s -> s.fractionNo() == 2).findFirst().orElseThrow();
        assertThat(f1.status()).isEqualTo("EXECUTED");
        assertThat(f1.planVersionId()).isEqualTo(v1Id);
        assertThat(f1.executedFractionId()).isNotNull();
        assertThat(f2.status()).isEqualTo("CANCELLED");
        assertThat(f2.version()).isEqualTo(2);
        assertThat(f2.cancelRequestId()).as("系统级改版取消不占用业务 requestId").isNull();
        assertThat(scheduleRepo.countOccupancies()).isZero();

        // 已执行分次剂量/实绩不变
        var ledger = workflow.fractionLedger(PATIENT, PLAN);
        assertThat(ledger).hasSize(1);
        assertThat(ledger.get(0).fractionNo()).isEqualTo(1);
        assertThat(ledger.get(0).deliveredDoseCgy()).isEqualTo(200L);
        assertThat(ledger.get(0).planVersionId()).isEqualTo(v1Id);

        // 不能用失效版本治疗：分次2旧排程已取消，没有 BOOKED 排程
        setClockUtc(LocalDateTime.of(2026, 10, 1, 2, 15, 0));
        RecordedResponse stale = workflow.recordFraction(PATIENT, PLAN, 2, 200L, "RV-FR2-OLD");
        assertThat(stale.httpStatus()).isEqualTo(422);
        assertThat(((RejectionBody) stale.body()).code()).isEqualTo("SCHEDULE_REQUIRED");

        // v2 首次验证合格后重新排程分次2并执行
        assert200(workflow.verify(PATIENT, PLAN, 2, 200L, 200L, "RV-VER2"));
        assert200(scheduling.publish(new com.example.starter.rt.dto.SchedulePublishRequest(
                PATIENT, PLAN, 2, "RV-PUB2",
                List.of(item(2, 1, T0.plusHours(1), 10, DEVICE)))));
        setClockUtc(LocalDateTime.of(2026, 10, 1, 3, 5, 0));
        assert200(workflow.recordFraction(PATIENT, PLAN, 2, 200L, "RV-FR2-NEW"));

        ScheduleResponse f2Done = scheduling.planSchedules(PATIENT, PLAN, "EXECUTED").stream()
                .filter(s -> s.fractionNo() == 2).findFirst().orElseThrow();
        assertThat(f2Done.planVersionId()).isEqualTo(v2.id());
        assertThat(workflow.fractionLedger(PATIENT, PLAN)).hasSize(2);
    }
}
