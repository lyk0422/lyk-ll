package com.example.starter.cutover.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 批次发布请求。batchId 为幂等键；指纹覆盖 batchId、发布精确时刻、
 * 成员集合（含各自 expectedVersion）与依赖边集合，同键同参重放首次冻结结果，
 * 同键异参 409，校验/业务失败不占键。
 *
 * @param publishedAt 发布精确时刻，Asia/Shanghai，参与幂等指纹
 * @param members     已批准但未开始的割接申请及其版本，必须唯一
 * @param edges       有向前置依赖边，两端须在批内，不允许自环/环路；null 视为空集
 */
public record PublishBatchRequest(
        @NotBlank String batchId,
        @NotNull LocalDateTime publishedAt,
        @NotEmpty @Valid List<BatchMemberRef> members,
        @Valid List<BatchEdgeRef> edges
) {
}
