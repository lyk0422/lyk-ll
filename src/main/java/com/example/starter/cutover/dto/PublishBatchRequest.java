package com.example.starter.cutover.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 割接批次发布请求。
 *
 * <p>requestId 即批次幂等键；指纹结构化覆盖 requestId、成员（申请编号+版本）集合、
 * 依赖边集合与精确发布时刻 frozenAt。同键同参重放首次冻结结果，同键异参 409，
 * 业务校验失败不占用该键。</p>
 *
 * @param frozenAt 批次冻结的精确时刻（Asia/Shanghai），由调用方提供
 */
public record PublishBatchRequest(
        @NotBlank String requestId,
        @NotNull LocalDateTime frozenAt,
        @NotEmpty @Valid List<BatchMemberInput> members,
        @NotNull @Valid List<DependencyEdgeInput> edges
) {
}
