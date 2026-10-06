package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.dto.AssessmentView;
import com.example.starter.dto.CompleteAssessmentRequest;
import com.example.starter.dto.CreateDefectRequest;
import com.example.starter.dto.CreateRepairOrderRequest;
import com.example.starter.dto.CreateReinspectionRequest;
import com.example.starter.dto.DefectResponse;
import com.example.starter.dto.RecurrenceStatView;
import com.example.starter.dto.RepairOrderView;
import com.example.starter.model.DefectLevel;
import com.example.starter.model.ReinspectionResult;
import com.example.starter.service.AssessmentService;
import com.example.starter.service.DefectService;
import com.example.starter.service.PowerLimitService;
import com.example.starter.service.QueryService;
import com.example.starter.service.RepairService;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 同类缺陷复发与根因评估测试：90 日内恰第 2 次触发，评估期间维持限功率。
 */
class RecurrenceTest extends BaseIntegrationTest {

    @Autowired
    private DefectService defectService;

    @Autowired
    private RepairService repairService;

    @Autowired
    private AssessmentService assessmentService;

    @Autowired
    private QueryService queryService;

    private String rid() {
        return UUID.randomUUID().toString();
    }

    private DefectResponse registerCrack(long bladeId) {
        return defectService.register(new CreateDefectRequest(
                rid(), bladeId, "叶中", DefectLevel.SURFACE, "CRACK", null));
    }

    @Test
    void secondSameTypeDefectWithin90DaysTriggersAssessment() {
        DefectResponse first = registerCrack(1L);
        assertThat(first.assessmentTriggered()).isFalse();

        clock.advance(Duration.ofDays(30));
        DefectResponse second = registerCrack(1L);
        assertThat(second.assessmentTriggered()).isTrue();
        assertThat(second.assessmentId()).isNotNull();
        // 评估期间维持限功率（即使两次都是表面级缺陷）
        assertThat(second.powerLimitActive()).isTrue();
        assertThat(queryService.powerLimits(1L).get(0).reason())
                .isEqualTo(PowerLimitService.REASON_ROOT_CAUSE_ASSESSMENT);

        // 第 3 次不再重复触发新评估
        clock.advance(Duration.ofDays(30));
        DefectResponse third = registerCrack(1L);
        assertThat(third.assessmentTriggered()).isFalse();
        assertThat(queryService.assessments(1L)).hasSize(1);

        AssessmentView assessment = queryService.assessments(1L).get(0);
        assertThat(assessment.triggerCount()).isEqualTo(2);
        assertThat(assessment.status()).isEqualTo("IN_PROGRESS");
        assertThat(assessment.defectType()).isEqualTo("CRACK");
    }

    @Test
    void sameTypeBeyond90DaysDoesNotTrigger() {
        registerCrack(1L);
        clock.advance(Duration.ofDays(91));
        DefectResponse second = registerCrack(1L);
        assertThat(second.assessmentTriggered()).isFalse();
        assertThat(queryService.assessments(1L)).isEmpty();
    }

    @Test
    void differentTypeDoesNotTrigger() {
        registerCrack(1L);
        clock.advance(Duration.ofDays(10));
        DefectResponse other = defectService.register(new CreateDefectRequest(
                rid(), 1L, "叶尖", DefectLevel.SURFACE, "EROSION", null));
        assertThat(other.assessmentTriggered()).isFalse();
    }

    @Test
    void powerLimitHeldDuringAssessmentEvenAfterRepairClosed() {
        // 结构级缺陷 → 限功率 + 维修单
        DefectResponse structural = defectService.register(new CreateDefectRequest(
                rid(), 1L, "叶根", DefectLevel.STRUCTURAL, "CRACK", null));
        RepairOrderView order = repairService.createOrder(
                new com.example.starter.dto.CreateRepairOrderRequest(rid(), structural.id()));

        // 同类缺陷第 2 次 → 触发根因评估
        clock.advance(Duration.ofDays(1));
        DefectResponse second = registerCrack(1L);
        assertThat(second.assessmentTriggered()).isTrue();

        // 维修闭环复检合格：结构级事由消除，但评估进行中，限功率维持
        clock.advance(Duration.ofDays(2));
        repairService.reinspect(order.id(), new CreateReinspectionRequest(rid(), ReinspectionResult.PASS));
        // 限功率链：结构缺陷区间已结束，根因评估区间仍生效
        assertThat(queryService.powerLimits(1L)).hasSize(2);
        var activeLimit = queryService.powerLimits(1L).stream()
                .filter(l -> l.active()).findFirst().orElseThrow();
        assertThat(activeLimit.reason()).isEqualTo(PowerLimitService.REASON_ROOT_CAUSE_ASSESSMENT);

        // 评估完成后才撤销限功率
        AssessmentView completed = assessmentService.complete(
                second.assessmentId(), new CompleteAssessmentRequest(rid()));
        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertThat(completed.powerLimitActive()).isFalse();
        assertThat(queryService.powerLimits(1L)).hasSize(2);
        assertThat(queryService.powerLimits(1L)).allMatch(l -> !l.active());
    }

    @Test
    void recurrenceStatsAreRecomputableAndStable() {
        registerCrack(1L);
        clock.advance(Duration.ofDays(10));
        registerCrack(1L);
        clock.advance(Duration.ofDays(10));
        defectService.register(new CreateDefectRequest(
                rid(), 1L, "叶尖", DefectLevel.SURFACE, "EROSION", null));

        List<RecurrenceStatView> first = queryService.recurrence(1L, 90);
        List<RecurrenceStatView> second = queryService.recurrence(1L, 90);
        assertThat(first).isEqualTo(second);
        assertThat(first).hasSize(2);

        RecurrenceStatView crack = first.stream()
                .filter(s -> s.defectType().equals("CRACK")).findFirst().orElseThrow();
        assertThat(crack.count()).isEqualTo(2);
        assertThat(crack.windowEnd()).isEqualTo(crack.windowStart().plusDays(90));
        assertThat(crack.firstDetectedAt()).isBefore(crack.lastDetectedAt());
    }
}
