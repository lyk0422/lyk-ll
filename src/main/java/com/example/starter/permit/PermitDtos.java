package com.example.starter.permit;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 作业许可与回传接口出入参。
 */
public final class PermitDtos {

    private PermitDtos() {
    }

    /** 签发许可请求；requestId 为幂等键。 */
    public record IssuePermitRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotNull(message = "任务 id 不能为空") Long taskId,
            @NotBlank(message = "作业类型不能为空")
            @Pattern(regexp = "AERIAL|GROUND", message = "作业类型仅支持 AERIAL/GROUND") String workType) {
    }

    /** 许可响应；deadlineDate 为既定时限，计划重排不改变在途许可。 */
    public record PermitResponse(long id, String permitNo, long taskId, long segmentId, String workType,
            String status, LocalDate deadlineDate) {
    }

    /** 作业回传请求：前后照片标识与弃枝清运记录均必填。 */
    public record SubmitReportRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "作业前照片标识不能为空") String beforePhotoId,
            @NotBlank(message = "作业后照片标识不能为空") String afterPhotoId,
            @NotBlank(message = "弃枝清运记录不能为空") String disposalRecord) {
    }

    /** 回传响应：onTime 表示是否在截止日（含恰第 7 日）前闭环。 */
    public record ReportResponse(long reportId, long permitId, long taskId, LocalDateTime closedAt,
            LocalDate deadlineDate, boolean onTime, boolean escalated) {
    }
}
