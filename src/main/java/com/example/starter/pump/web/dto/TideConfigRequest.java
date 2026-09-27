package com.example.starter.pump.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 分区潮汐控制配置请求。配置不存在且 expectedVersion 为空时新建（版本从1开始）；
 * 配置已存在时必须携带匹配的 expectedVersion，成功修改后版本加1。
 *
 * @param requestId          幂等键
 * @param zoneCode           流域分区代码
 * @param backwaterThreshold 潮位顶托门槛（米），非负；恰达进入强排
 * @param expectedVersion    期望工况版本；新建时须为空，修改时必填
 */
public record TideConfigRequest(
        @NotBlank String requestId,
        @NotBlank String zoneCode,
        @NotNull @DecimalMin(value = "0.0", message = "顶托门槛必须非负") BigDecimal backwaterThreshold,
        Integer expectedVersion
) {
}
