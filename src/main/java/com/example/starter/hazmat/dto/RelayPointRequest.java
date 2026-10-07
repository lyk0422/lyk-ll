package com.example.starter.hazmat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 设置安全接力点请求（创建接力段）。
 *
 * @param requestId     幂等键
 * @param waypointSeq   安全接力点途经点序号（计划通过时刻须早于夜间禁行起点）
 * @param escortControl 是否启用人员管控：true 时该段 IN 确认须来自已预约接班员；
 *                      缺省/false 保持旧行为
 */
public record RelayPointRequest(
        @NotBlank String requestId,
        @PositiveOrZero int waypointSeq,
        Boolean escortControl) {

    /** 兼容旧调用：缺省不启用人员管控。 */
    public RelayPointRequest(String requestId, int waypointSeq) {
        this(requestId, waypointSeq, null);
    }

    /** 是否启用人员管控（缺省 false）。 */
    public boolean controlEnabled() {
        return Boolean.TRUE.equals(escortControl);
    }
}
