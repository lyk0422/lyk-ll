package com.example.starter.reeval;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * 年度风险复评接口出入参。
 */
public final class ReevalDtos {

    private ReevalDtos() {
    }

    /** 单棵树的复评指标。 */
    public record MetricItem(
            @NotBlank(message = "树木编号不能为空") String treeCode,
            @NotNull(message = "健康度不能为空") @Min(value = 0, message = "健康度范围 0-100")
            @Max(value = 100, message = "健康度范围 0-100") Integer healthScore,
            @NotNull(message = "倾斜度不能为空") @Min(value = 0, message = "倾斜度不能为负") Double leanDegree,
            @NotNull(message = "树龄不能为空") @Min(value = 0, message = "树龄不能为负") Integer age,
            @NotNull(message = "空洞标记不能为空") Boolean cavity) {
    }

    /** 复评请求；requestId 为幂等键，指纹覆盖全部复评指标。 */
    public record ReevaluateRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotEmpty(message = "复评指标不能为空") List<@Valid MetricItem> items) {
    }

    /**
     * 单棵树复评结果。action：NONE 等级未变 / CREATED 新建任务 / REPLANNED 重排待办任务 /
     * CANCELLED 取消待办任务 / KEPT_IN_FLIGHT 在途许可既定时限不变。
     */
    public record ItemResult(String treeCode, int oldLevel, int newLevel, String action,
            Long taskId, LocalDate taskDeadline) {
    }

    /** 复评响应。 */
    public record ReevaluateResponse(List<ItemResult> results) {
    }
}
