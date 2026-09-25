package com.example.starter.irrigation.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 配水计划发布与查询报文。
 */
public final class PlanDtos {

    private PlanDtos() {
    }

    /**
     * 发布请求：按组内斗口灌溉面积权重分配 {@code availableCubicMeter}。
     *
     * @param requestId      幂等键；同键同参返回首次结果，同键异参 409
     * @param turnNo         轮次编号；为 null 时取请求时刻的当前 UTC 轮次
     * @param baseVersionNo  修订基线版本号：首次发布为 null；修改已发布计划时必须传当前生效版本号，
     *                       服务据此区分“显式修订（发新版本）”与“并发发布（409）”；
     *                       基线已被他人取代时返回 409
     */
    public record PublishPlanRequest(
            @NotBlank String requestId,
            @NotBlank String groupCode,
            Long turnNo,
            @NotNull @PositiveOrZero Long availableCubicMeter,
            Integer baseVersionNo) {

        /** 便捷构造器：首次发布（无修订基线）。 */
        public PublishPlanRequest(String requestId, String groupCode, Long turnNo,
                                  Long availableCubicMeter) {
            this(requestId, groupCode, turnNo, availableCubicMeter, null);
        }
    }

    /** 计划版本响应；计划发布后冻结。 */
    public record PlanVersionResponse(
            long planId,
            String groupCode,
            long turnNo,
            int versionNo,
            String status,
            long availableCubicMeter,
            String requestId,
            String fingerprint,
            Instant publishedAt,
            List<PlanLineResponse> lines) {
    }

    /**
     * 斗口计划明细，按斗口编号升序；权重四位小数，水量单位立方米。
     * 本轮总供水目标 = planCubicMeter + carryoverCubicMeter。
     */
    public record PlanLineResponse(
            String outletCode,
            long areaSquareMeter,
            BigDecimal weight,
            long planCubicMeter,
            long carryoverCubicMeter) {
    }
}
