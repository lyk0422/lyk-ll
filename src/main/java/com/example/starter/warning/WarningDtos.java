package com.example.starter.warning;

import java.time.LocalDateTime;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 大风预警接口出入参。
 */
public final class WarningDtos {

    private WarningDtos() {
    }

    /** 发布预警请求；requestId 为幂等键。 */
    public record PublishWarningRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "预警编号不能为空") String warningNo,
            @NotBlank(message = "预警级别不能为空")
            @Pattern(regexp = "BLUE|YELLOW|ORANGE|RED", message = "预警级别仅支持 BLUE/YELLOW/ORANGE/RED")
            String level) {
    }

    /** 预警响应：含本次冻结的高风险路段。 */
    public record WarningResponse(long id, String warningNo, String level, String status,
            LocalDateTime publishedAt, List<Long> frozenSegmentIds) {
    }

    /** 解除预警请求。 */
    public record LiftWarningRequest(@NotBlank(message = "requestId 不能为空") String requestId) {
    }

    /** 解除响应：含本次解除冻结的路段数。 */
    public record LiftResponse(long id, String warningNo, String status, LocalDateTime liftedAt, int releasedSegments) {
    }
}
