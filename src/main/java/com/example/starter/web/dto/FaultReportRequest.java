package com.example.starter.web.dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 故障接报请求。reportedAt 为空时由服务端取当前时间；importantUser 为空表示不涉及重要用户。
 *
 * @param requestId     幂等请求号
 * @param feederId      馈线编号
 * @param faultPoint    故障点
 * @param affectedUsers 影响用户数
 * @param importantUser 重要用户名称（合成数据），可为空
 * @param reportedAt    接报时间，可为空
 */
public record FaultReportRequest(
        @NotBlank String requestId,
        @NotBlank String feederId,
        @NotBlank String faultPoint,
        @NotNull @Min(0) Integer affectedUsers,
        String importantUser,
        LocalDateTime reportedAt) {
}
