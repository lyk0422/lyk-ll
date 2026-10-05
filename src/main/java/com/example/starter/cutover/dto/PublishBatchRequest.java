package com.example.starter.cutover.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 批次发布请求。requestId 为批次编号兼发布幂等键；指纹覆盖 requestId、批次名称、
 * 精确发布时刻、成员（申请+期望版本）集合与依赖边集合，同键同参重放首次冻结结果，
 * 同键异参 409，校验失败不占键。
 *
 * @param publishTime 精确发布时刻（Asia/Shanghai），纳入幂等指纹
 */
public record PublishBatchRequest(
        @NotBlank String requestId,
        @NotBlank String batchName,
        @NotNull LocalDateTime publishTime,
        @NotEmpty @Valid List<BatchMemberRef> members,
        @Valid List<BatchEdgeRef> edges
) {
}
