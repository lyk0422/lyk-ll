package com.example.starter.rt.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.example.starter.rt.dto.ConstraintCheckItem;
import com.example.starter.rt.repository.DirectoryRepository.ConstraintItemRow;
import com.example.starter.rt.repository.PlanRepository.OarDoseRow;

/**
 * 剂量核对纯逻辑：约束逐项比对、总剂量勾稽与模体偏差计算。
 * 不访问数据库，输入模板限值与计划申报值即可复算，保证“约束明细可复算”。
 */
@Component
public class DoseEvaluation {

    /** 偏差判定阈值（%）：恰 3% 合法，仅大于 3% 拒绝。 */
    public static final BigDecimal DEVIATION_LIMIT_PCT = new BigDecimal("3.00");

    /** 单项核对结论。 */
    public record CheckResult(List<ConstraintCheckItem> items, List<String> missingMetrics,
                              List<String> extraMetrics, boolean allWithinLimit) {

        /** 模板每一项都有申报值，且无多余申报项，且全部未超限。 */
        public boolean exactCoverageAndWithin() {
            return missingMetrics.isEmpty() && extraMetrics.isEmpty() && allWithinLimit;
        }
    }

    /**
     * 按模板逐项核对计划申报剂量。
     * 恰等限值合法（{@code planned <= limit} 即通过）。
     */
    public CheckResult evaluate(List<ConstraintItemRow> templateItems, List<OarDoseRow> plannedDoses) {
        Map<String, OarDoseRow> plannedByKey = new LinkedHashMap<>();
        for (OarDoseRow dose : plannedDoses) {
            plannedByKey.put(metricKey(dose.organCode(), dose.metricType(), dose.metricRef()), dose);
        }

        List<ConstraintCheckItem> items = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        boolean allWithin = true;
        for (ConstraintItemRow constraint : templateItems) {
            String key = metricKey(constraint.organCode(), constraint.metricType(), constraint.metricRef());
            OarDoseRow planned = plannedByKey.remove(key);
            if (planned == null) {
                missing.add(key);
                allWithin = false;
                continue;
            }
            boolean within = planned.metricValue().compareTo(constraint.limitValue()) <= 0;
            allWithin = allWithin && within;
            items.add(new ConstraintCheckItem(
                    constraint.organCode(),
                    constraint.organName(),
                    constraint.metricType(),
                    constraint.metricRef(),
                    planned.metricValue(),
                    constraint.limitValue(),
                    within));
        }
        return new CheckResult(items, missing, new ArrayList<>(plannedByKey.keySet()), allWithin);
    }

    /** 总剂量勾稽：分次剂量 × 次数 == 处方总剂量（恰等合法，不等拒绝）。 */
    public boolean totalDoseMatches(long prescribedTotalDoseCgy, long fractionDoseCgy, int fractionCount) {
        return Math.multiplyExact(fractionDoseCgy, fractionCount) == prescribedTotalDoseCgy;
    }

    /**
     * 模体验证偏差百分比：|实测-计划|/计划×100，保留 4 位小数（HALF_UP）。
     */
    public BigDecimal deviationPct(long plannedDoseCgy, long measuredDoseCgy) {
        BigDecimal diff = BigDecimal.valueOf(Math.abs(measuredDoseCgy - plannedDoseCgy));
        return diff.multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(plannedDoseCgy), 4, RoundingMode.HALF_UP);
    }

    /** 偏差是否合法：≤3%，恰 3% 合法。 */
    public boolean deviationWithinLimit(BigDecimal deviationPct) {
        return deviationPct.compareTo(DEVIATION_LIMIT_PCT) <= 0;
    }

    private String metricKey(String organCode, String metricType, Long metricRef) {
        return organCode + "|" + metricType + "|" + (metricRef == null ? "" : metricRef);
    }
}
