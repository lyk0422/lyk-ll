package com.example.starter.irrigation.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/**
 * 配水计划相关请求/响应契约。
 */
public final class PlanContracts {

    private PlanContracts() {
    }

    /**
     * 发布配水计划请求。
     *
     * @param requestId    幂等请求键
     * @param groupId      轮灌组 id
     * @param roundNo      轮次编号（每轮 7 天，UTC 日界）
     * @param totalWaterM3 组内可供水量（立方米）
     */
    public record PublishPlanRequest(
            @NotBlank @Size(max = 64) String requestId,
            @NotNull Long groupId,
            @NotNull @Min(0) Integer roundNo,
            @NotNull @Positive Long totalWaterM3) {
    }

    /**
     * 计划斗口明细视图。
     *
     * @param outletId      斗口 id
     * @param outletCode    斗口编号
     * @param baseM3        按面积权重分配的基础水量（立方米）
     * @param carryoverInM3 上一轮欠供结转补供水量（立方米）
     * @param plannedM3     本轮计划水量=基础+结转（立方米）
     */
    public record PlanDetailView(long outletId, String outletCode,
                                 long baseM3, long carryoverInM3, long plannedM3) {
    }

    /**
     * 计划版本视图。
     *
     * @param planVersionId 计划版本 id
     * @param groupId       轮灌组 id
     * @param roundNo       轮次编号
     * @param versionNo     版本号
     * @param status        版本状态：EFFECTIVE / SUPERSEDED
     * @param totalWaterM3  组内可供水量（立方米）
     * @param roundStart    轮次开始日（UTC，含）
     * @param roundEnd      轮次结束日（UTC，不含）
     * @param replayed      是否为幂等重放（同键同参返回首次结果）
     * @param details       斗口计划明细（按斗口编号排序）
     */
    public record PlanView(long planVersionId, long groupId, int roundNo, int versionNo,
                           String status, long totalWaterM3,
                           LocalDate roundStart, LocalDate roundEnd,
                           boolean replayed, List<PlanDetailView> details) {
    }
}
