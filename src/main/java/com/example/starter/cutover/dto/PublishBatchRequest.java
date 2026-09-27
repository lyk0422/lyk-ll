package com.example.starter.cutover.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 批次发布请求。requestId 为发布幂等键；指纹覆盖成员版本集合、依赖边集合与精确发布时刻。
 * 成员必须全部处于 APPROVED（已批准未开始）且 expectedVersion 与当前版本一致，
 * 任一校验失败整批拒绝，不产生任何记录（失败不占键）。
 *
 * @param publishedAt 发布方给定的冻结时刻（Asia/Shanghai），参与指纹
 * @param members     批次成员（申请编号 + 期望版本），必须唯一
 * @param edges       有向前置依赖边（from 为前置，to 为后继），两端都必须在批内
 */
public record PublishBatchRequest(
        @NotBlank String requestId,
        @NotNull LocalDateTime publishedAt,
        @NotEmpty @Valid List<BatchMemberRef> members,
        @NotNull @Valid List<BatchEdgeRef> edges
) {
}
