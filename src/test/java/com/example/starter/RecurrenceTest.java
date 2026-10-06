package com.example.starter;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;

import com.example.starter.service.DefectService;
import com.example.starter.service.EvaluationService;
import com.example.starter.service.QueryService;
import com.example.starter.web.dto.ApiDtos.CompleteEvaluationRequest;
import com.example.starter.web.dto.ApiDtos.DefectResponse;
import com.example.starter.web.dto.ApiDtos.EvaluationResponse;
import com.example.starter.web.dto.ApiDtos.RecurrenceDefectPoint;
import com.example.starter.web.dto.ApiDtos.RecurrenceStatsResponse;
import com.example.starter.web.dto.ApiDtos.RecurrenceTypeStats;
import com.example.starter.web.dto.ApiDtos.RegisterDefectRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 复发触发根因评估：同类缺陷 90 日内恰第 2 次触发，评估期间叶片维持限功率。
 */
class RecurrenceTest extends BaseIntegrationTest {

    @Autowired
    private DefectService defectService;

    @Autowired
    private EvaluationService evaluationService;

    @Autowired
    private QueryService queryService;

    private final AtomicLong requestSeq = new AtomicLong();

    private DefectResponse registerSurface(long bladeId, String type) {
        return defectService.registerDefect(new RegisterDefectRequest(
                "req-recur-" + requestSeq.incrementAndGet(), null, bladeId, "叶根0-5m",
                type, new BigDecimal("80.00"), new BigDecimal("0.02")));
    }

    @Test
    void 同类缺陷90日内恰第2次触发根因评估_第3次不再触发() {
        long blade = bladeId("BLADE-A01");
        DefectResponse first = registerSurface(blade, "EROSION");
        assertThat(first.evaluationId()).isNull();
        assertThat(queryService.bladeStatus(blade).curtailed()).isFalse();

        clock.plusDays(30);
        DefectResponse second = registerSurface(blade, "EROSION");
        assertThat(second.evaluationId()).isNotNull();
        // 评估期间该叶片维持限功率
        assertThat(queryService.bladeStatus(blade).curtailed()).isTrue();
        assertThat(queryService.bladeStatus(blade).activeCurtailments())
                .singleElement()
                .satisfies(c -> {
                    assertThat(c.reason()).isEqualTo("ROOT_CAUSE_EVAL");
                    assertThat(c.sourceEvaluationId()).isEqualTo(second.evaluationId());
                });

        clock.plusDays(30);
        DefectResponse third = registerSurface(blade, "EROSION");
        assertThat(third.evaluationId()).isNull();
        assertThat(count("root_cause_evaluation")).isEqualTo(1);

        // 评估绑定恰第 2 次缺陷
        Long triggerDefectId = jdbcTemplate.queryForObject(
                "SELECT trigger_defect_id FROM root_cause_evaluation WHERE id = ?",
                Long.class, second.evaluationId());
        assertThat(triggerDefectId).isEqualTo(second.id());
    }

    @Test
    void 超过90日窗口不触发评估() {
        long blade = bladeId("BLADE-A01");
        registerSurface(blade, "EROSION");

        clock.plusDays(91);
        DefectResponse second = registerSurface(blade, "EROSION");

        assertThat(second.evaluationId()).isNull();
        assertThat(count("root_cause_evaluation")).isZero();
        assertThat(queryService.bladeStatus(blade).curtailed()).isFalse();
    }

    @Test
    void 完成评估后撤销评估限功率() {
        long blade = bladeId("BLADE-A01");
        registerSurface(blade, "DELAMINATION");
        clock.plusDays(10);
        DefectResponse second = registerSurface(blade, "DELAMINATION");
        assertThat(queryService.bladeStatus(blade).curtailed()).isTrue();

        EvaluationResponse completed = evaluationService.complete(second.evaluationId(),
                new CompleteEvaluationRequest("req-eval-1", "前缘防护层批次缺陷，已更换供应商"));

        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertThat(completed.completedAt()).isNotNull();
        assertThat(queryService.bladeStatus(blade).curtailed()).isFalse();
    }

    @Test
    void 复发统计可复算且恰第2次为触发点() {
        long blade = bladeId("BLADE-A01");
        DefectResponse first = registerSurface(blade, "EROSION");
        clock.plusDays(30);
        DefectResponse second = registerSurface(blade, "EROSION");
        clock.plusDays(30);
        DefectResponse third = registerSurface(blade, "EROSION");

        RecurrenceStatsResponse stats = queryService.recurrenceStats(blade);
        assertThat(stats.stats()).singleElement().satisfies((RecurrenceTypeStats typeStats) -> {
            assertThat(typeStats.defectType()).isEqualTo("EROSION");
            assertThat(typeStats.totalCount()).isEqualTo(3);
            assertThat(typeStats.evaluationTriggered()).isTrue();
            assertThat(typeStats.evaluationId()).isEqualTo(second.evaluationId());
            assertThat(typeStats.defects())
                    .extracting(RecurrenceDefectPoint::defectId,
                            RecurrenceDefectPoint::windowCount,
                            RecurrenceDefectPoint::triggerPoint)
                    .containsExactly(
                            tuple(first.id(), 1, false),
                            tuple(second.id(), 2, true),
                            tuple(third.id(), 3, false));
        });
        // 重复查询结果稳定
        assertThat(queryService.recurrenceStats(blade)).isEqualTo(stats);
    }
}
