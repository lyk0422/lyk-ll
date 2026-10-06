package com.example.starter.tree;

import java.time.LocalDate;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 树木接口出入参。
 */
public final class TreeDtos {

    private TreeDtos() {
    }

    /** 建档请求；requestId 为幂等键，指纹覆盖全部业务字段。 */
    public record RegisterTreeRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotNull(message = "路段 id 不能为空") Long segmentId,
            @NotBlank(message = "树木编号不能为空") String treeCode,
            @NotBlank(message = "树种不能为空") String species,
            @NotNull(message = "树龄不能为空") @Min(value = 0, message = "树龄不能为负") Integer age,
            @NotNull(message = "健康度不能为空") @Min(value = 0, message = "健康度范围 0-100")
            @Max(value = 100, message = "健康度范围 0-100") Integer healthScore,
            @NotNull(message = "倾斜度不能为空") @Min(value = 0, message = "倾斜度不能为负") Double leanDegree,
            @NotNull(message = "空洞标记不能为空") Boolean cavity) {
    }

    /** 建档响应：含合成模型评估结果与（如触发）修剪任务。 */
    public record TreeResponse(long id, long segmentId, String treeCode, int riskScore, int riskLevel,
            Long taskId, LocalDate taskDeadline) {
    }
}
