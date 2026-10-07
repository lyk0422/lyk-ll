package com.example.starter.damage.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 修理变更单请求报文集合。
 * 所有写操作均携带 requestId 用于幂等；单据版本从 1 起，修改携带 expectedVersion，成功 +1，旧版 409。
 * 金额为正整数分；时刻均为 UTC。
 */
public final class ChangeOrderRequests {

    private ChangeOrderRequests() {
    }

    /** 变更行：锚定本案一个残损项，给出新工法与正整数分报价 */
    public record ChangeLineInput(
            @NotNull @Positive Long damageItemId,
            @NotBlank String methodCode,
            @NotNull @Positive Long amountCents) {
    }

    /**
     * 创建修理变更单候选。
     * expectedVersion 为该案件当前变更单序号数：首次创建传 0。
     * lines 为 1 至 10 个不同残损项的新工法与分报价，其余项沿用原中标。
     */
    public record CreateChangeOrderRequest(
            @NotBlank String requestId,
            @NotNull @PositiveOrZero Integer expectedVersion,
            @NotEmpty @Size(max = 10) List<@Valid ChangeLineInput> lines) {
    }

    /**
     * 复核变更单候选。
     * expectedVersion 为变更单当前版本；两名不同复核人均 AGREE 才生效换版，任一 REJECT 即驳回。
     */
    public record ReviewChangeOrderRequest(
            @NotBlank String requestId,
            @NotNull @Positive Integer expectedVersion,
            @NotBlank String reviewer,
            @NotBlank String decision) {
    }

    /** 撤回待复核候选；生效后不可撤回 */
    public record WithdrawChangeOrderRequest(
            @NotBlank String requestId,
            @NotNull @Positive Integer expectedVersion) {
    }
}
