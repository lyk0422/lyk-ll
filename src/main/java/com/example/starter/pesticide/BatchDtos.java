package com.example.starter.pesticide;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 药剂批号与召回接口报文。
 */
public final class BatchDtos {

    private BatchDtos() {
    }

    /**
     * 批号召回请求。召回一旦生效不得撤回。
     *
     * @param requestId 幂等请求标识
     * @param reason 召回原因
     * @param operator 召回操作人
     * @param expectedVersion 批号乐观锁版本，须与当前版本一致，过期返回 409
     */
    public record RecallRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "召回原因不能为空") String reason,
            @NotBlank(message = "召回操作人不能为空") String operator,
            @NotNull(message = "expectedVersion 不能为空")
            @PositiveOrZero(message = "expectedVersion 不合法") Integer expectedVersion) {
    }

    /**
     * 批号信息响应。
     *
     * @param status 状态：ACTIVE 可用 / RECALLED 已召回
     * @param version 当前乐观锁版本
     */
    public record BatchResponse(long id, long pesticideId, String batchNo, BigDecimal quantity,
            LocalDate expiryDate, String status, int version, String recallReason,
            LocalDateTime recalledAt) {
    }

    /**
     * 召回关联处置链影响。
     *
     * @param impactType PENDING_REPLACEMENT 在途链转待补投 / ARCHIVED_RISK 已解除归档链仅追加风险
     */
    public record RecallImpactResponse(long placementId, long caseId, String impactType) {
    }

    /**
     * 召回响应：含全部关联处置链影响，可据此查询原投放、召回、补投对应关系。
     *
     * @param version 召回成功后的批号版本
     */
    public record RecallResponse(long recallId, long batchId, String batchNo, String status,
            int version, List<RecallImpactResponse> impacts) {
    }
}
