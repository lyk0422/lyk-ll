package com.example.starter.damage.dto;

import com.example.starter.damage.domain.ChangeDecision;
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
 * 所有写操作均携带 requestId 用于幂等；单据版本从 1 起，修改携带 expectedVersion，成功变更后 +1。
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
     * 申请修理变更候选。
     * expectedVersion 为本案已存在变更单数量：首张传 0，之后按实际数量递增。
     * lines 为 1 至 10 个不同残损项的新工法与分报价，其余项沿用原中标。
     */
    public record CreateChangeOrderRequest(
            @NotBlank String requestId,
            @NotNull @PositiveOrZero Integer expectedVersion,
            @NotEmpty @Size(max = 10) List<@Valid ChangeLineInput> lines) {
    }

    /** 复核变更候选；同一候选同一复核人不得重复，两名不同复核人均同意才切换 */
    public record ChangeReviewRequest(
            @NotBlank String requestId,
            @NotNull @Positive Integer expectedVersion,
            @NotBlank String reviewer,
            @NotNull ChangeDecision decision) {
    }

    /** 撤回待生效候选；生效后不可撤回 */
    public record WithdrawChangeOrderRequest(
            @NotBlank String requestId,
            @NotNull @Positive Integer expectedVersion) {
    }
}
