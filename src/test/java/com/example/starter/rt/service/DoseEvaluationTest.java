package com.example.starter.rt.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.starter.rt.dto.ConstraintCheckItem;
import com.example.starter.rt.repository.DirectoryRepository.ConstraintItemRow;
import com.example.starter.rt.repository.PlanRepository.OarDoseRow;

/**
 * {@link DoseEvaluation} 纯逻辑单元测试：恰等限值、超限拒绝、总剂量勾稽、偏差恰 3%。
 */
class DoseEvaluationTest {

    private final DoseEvaluation evaluation = new DoseEvaluation();

    private ConstraintItemRow dmaxLimit(String organ, long limit) {
        return new ConstraintItemRow(1L, "TPL", organ, organ, "DMAX_CGY", null,
                BigDecimal.valueOf(limit));
    }

    private OarDoseRow dmaxPlanned(String organ, long value) {
        return new OarDoseRow(1L, 1001L, organ, "DMAX_CGY", null, BigDecimal.valueOf(value));
    }

    @Test
    @DisplayName("恰等限值合法：申报值等于限值时 withinLimit=true")
    void equalToLimitIsWithin() {
        DoseEvaluation.CheckResult result = evaluation.evaluate(
                List.of(dmaxLimit("SPINAL_CORD", 4500)),
                List.of(dmaxPlanned("SPINAL_CORD", 4500)));

        assertThat(result.allWithinLimit()).isTrue();
        assertThat(result.exactCoverageAndWithin()).isTrue();
        ConstraintCheckItem item = result.items().get(0);
        assertThat(item.withinLimit()).isTrue();
        assertThat(item.plannedValue()).isEqualByComparingTo("4500");
        assertThat(item.limitValue()).isEqualByComparingTo("4500");
    }

    @Test
    @DisplayName("超过限值 1 cGy 即超限：withinLimit=false 且整体不通过")
    void exceedsLimitIsRejected() {
        DoseEvaluation.CheckResult result = evaluation.evaluate(
                List.of(dmaxLimit("SPINAL_CORD", 4500)),
                List.of(dmaxPlanned("SPINAL_CORD", 4501)));

        assertThat(result.allWithinLimit()).isFalse();
        assertThat(result.exactCoverageAndWithin()).isFalse();
        assertThat(result.items().get(0).withinLimit()).isFalse();
    }

    @Test
    @DisplayName("V_DOSE_PCT 按参照剂量匹配指标项，恰等百分比合法")
    void vDosePctEqualLimit() {
        ConstraintItemRow limit = new ConstraintItemRow(2L, "TPL", "BLADDER", "膀胱",
                "V_DOSE_PCT", 4000L, new BigDecimal("40.00"));
        OarDoseRow planned = new OarDoseRow(2L, 1001L, "BLADDER", "V_DOSE_PCT", 4000L,
                new BigDecimal("40.00"));

        DoseEvaluation.CheckResult result = evaluation.evaluate(List.of(limit), List.of(planned));
        assertThat(result.exactCoverageAndWithin()).isTrue();
    }

    @Test
    @DisplayName("任一项超限即不通过，且未超限项仍逐项返回 within=true")
    void anyViolationFailsAll() {
        DoseEvaluation.CheckResult result = evaluation.evaluate(
                List.of(dmaxLimit("SPINAL_CORD", 4500), dmaxLimit("BRAIN_STEM", 5400)),
                List.of(dmaxPlanned("SPINAL_CORD", 4500), dmaxPlanned("BRAIN_STEM", 6000)));

        assertThat(result.allWithinLimit()).isFalse();
        assertThat(result.items()).hasSize(2);
        assertThat(result.items().get(0).withinLimit()).isTrue();
        assertThat(result.items().get(1).withinLimit()).isFalse();
    }

    @Test
    @DisplayName("申报未覆盖模板全部项：missingMetrics 非空且不通过")
    void missingMetricFails() {
        DoseEvaluation.CheckResult result = evaluation.evaluate(
                List.of(dmaxLimit("SPINAL_CORD", 4500), dmaxLimit("BLADDER", 5000)),
                List.of(dmaxPlanned("SPINAL_CORD", 4000)));

        assertThat(result.missingMetrics()).containsExactly("BLADDER|DMAX_CGY|");
        assertThat(result.exactCoverageAndWithin()).isFalse();
    }

    @Test
    @DisplayName("总剂量勾稽：分次剂量×次数=处方总剂量合法，不等拒绝")
    void totalDoseArithmetic() {
        assertThat(evaluation.totalDoseMatches(5000, 200, 25)).isTrue();
        assertThat(evaluation.totalDoseMatches(5000, 201, 25)).isFalse();
        assertThat(evaluation.totalDoseMatches(5000, 250, 20)).isTrue();
        assertThat(evaluation.totalDoseMatches(4999, 200, 25)).isFalse();
    }

    @Test
    @DisplayName("偏差恰 3% 合法，超过 3% 拒绝")
    void deviationBoundaryThreePercent() {
        // 计划 200cGy，恰 3% → 实测 206cGy
        BigDecimal exactlyThree = evaluation.deviationPct(200, 206);
        assertThat(exactlyThree).isEqualByComparingTo("3.0000");
        assertThat(evaluation.deviationWithinLimit(exactlyThree)).isTrue();

        // 超过 3%：实测 207 → 3.5%
        BigDecimal overThree = evaluation.deviationPct(200, 207);
        assertThat(overThree).isEqualByComparingTo("3.5000");
        assertThat(evaluation.deviationWithinLimit(overThree)).isFalse();
    }

    @Test
    @DisplayName("偏差为对称绝对值，实测偏小超限同样拒绝")
    void deviationUndershootAlsoRejected() {
        BigDecimal under = evaluation.deviationPct(1000, 960);
        assertThat(under).isEqualByComparingTo("4.0000");
        assertThat(evaluation.deviationWithinLimit(under)).isFalse();
    }

    @Test
    @DisplayName("指纹工具：同参一致、任一业务字段变化指纹不同")
    void fingerprintSensitivity() {
        Map<String, Object> params1 = Map.of("patientId", "P1", "versionNo", 1);
        Map<String, Object> params2 = Map.of("versionNo", 1, "patientId", "P1");
        String f1 = com.example.starter.rt.support.Fingerprint.of(
                com.example.starter.rt.domain.OperationType.AUDIT, "REQ-1", params1);
        String f2 = com.example.starter.rt.support.Fingerprint.of(
                com.example.starter.rt.domain.OperationType.AUDIT, "REQ-1", params2);
        assertThat(f1).isEqualTo(f2);

        Map<String, Object> params3 = Map.of("patientId", "P1", "versionNo", 2);
        String f3 = com.example.starter.rt.support.Fingerprint.of(
                com.example.starter.rt.domain.OperationType.AUDIT, "REQ-1", params3);
        assertThat(f3).isNotEqualTo(f1);
    }
}
