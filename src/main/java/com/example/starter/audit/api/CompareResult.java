package com.example.starter.audit.api;

import java.util.List;
import java.util.Map;

/**
 * 两次任务比较结果。
 *
 * @param comparable         是否整体可比较（阶段集合与指标口径一致）
 * @param bindingDiffs       绑定版本与种子差异
 * @param metricDiffs        同名指标数值差（右 - 左）
 * @param incomparableItems  具体不可比较项（缺失阶段或指标口径不一致）
 */
public record CompareResult(
        boolean comparable,
        Map<String, BindingDiff> bindingDiffs,
        Map<String, Map<String, Double>> metricDiffs,
        List<String> incomparableItems
) {

    /**
     * 单个绑定项差异。
     */
    public record BindingDiff(String left, String right, boolean changed) {
    }
}
