package com.example.starter.irrigation.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 临时加购相关请求/响应契约。
 */
public final class ExtraPurchaseContracts {

    private ExtraPurchaseContracts() {
    }

    /**
     * 临时加购请求。
     *
     * @param requestId 幂等请求键
     * @param groupId   轮灌组 id
     * @param roundNo   轮次编号
     * @param amountM3  加购水量（立方米）
     */
    public record ExtraPurchaseRequest(
            @NotBlank @Size(max = 64) String requestId,
            @NotNull Long groupId,
            @NotNull @Min(0) Integer roundNo,
            @NotNull @Positive Long amountM3) {
    }

    /**
     * 加购分配明细视图。
     *
     * @param outletId   斗口 id
     * @param outletCode 斗口编号
     * @param amountM3   分配水量（立方米）
     */
    public record ExtraAllocationView(long outletId, String outletCode, long amountM3) {
    }

    /**
     * 加购结果视图。
     *
     * @param purchaseId  加购单 id
     * @param groupId     轮灌组 id
     * @param roundNo     轮次编号
     * @param amountM3    加购总水量（立方米）
     * @param replayed    是否为幂等重放
     * @param allocations 分配明细（按斗口编号排序）
     */
    public record ExtraPurchaseView(long purchaseId, long groupId, int roundNo, long amountM3,
                                    boolean replayed, List<ExtraAllocationView> allocations) {
    }
}
