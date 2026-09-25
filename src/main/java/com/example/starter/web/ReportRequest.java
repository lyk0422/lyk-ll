package com.example.starter.web;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * 故障接报请求。
 *
 * @param requestId     幂等键
 * @param feederCode    馈线编号（可空表示馈线不明）
 * @param faultPoint    故障点（去重键）
 * @param description   故障描述
 * @param affectedUsers 影响用户数，单位：户
 * @param outageTime    停电起始时间（ISO-8601），空则取接报时刻；发电车 5 分钟计时基准
 */
public record ReportRequest(
        @NotBlank String requestId,
        String feederCode,
        @NotBlank @Size(max = 256) String faultPoint,
        @Size(max = 512) String description,
        @NotNull @Min(0) Integer affectedUsers,
        String outageTime) {
}
