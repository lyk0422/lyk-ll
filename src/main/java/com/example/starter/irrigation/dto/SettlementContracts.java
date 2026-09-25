package com.example.starter.irrigation.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 欠供结转结算封存相关请求/响应契约。
 */
public final class SettlementContracts {

    private SettlementContracts() {
    }

    /**
     * 轮次封存请求。
     *
     * @param groupId 轮灌组 id
     * @param roundNo 轮次编号
     */
    public record SealRequest(
            @NotNull Long groupId,
            @NotNull @Min(0) Integer roundNo) {
    }

    /**
     * 结算明细视图。
     *
     * @param outletId    斗口 id
     * @param plannedM3   本轮计划水量（立方米）
     * @param actualM3    封存时已确认实供水量（立方米）
     * @param deficitM3   欠供水量（立方米）
     * @param carryoverM3 结转下一轮补供水量（立方米），上限本轮计划 30%
     * @param voidedM3    超出结转上限作废水量（立方米）
     */
    public record SettlementDetailView(long outletId, long plannedM3, long actualM3,
                                       long deficitM3, long carryoverM3, long voidedM3) {
    }

    /**
     * 结算封存视图。
     *
     * @param settlementId 结算 id
     * @param groupId      轮灌组 id
     * @param roundNo      轮次编号
     * @param replayed     是否为幂等重放（重复封存返回首次结果）
     * @param details      斗口结算明细（按斗口 id 排序）
     */
    public record SettlementView(long settlementId, long groupId, int roundNo,
                                 boolean replayed, List<SettlementDetailView> details) {
    }
}
