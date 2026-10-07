package com.example.starter.hazmat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 设置安全接力点请求（创建接力段）。
 *
 * @param requestId         幂等键
 * @param waypointSeq       安全接力点途经点序号（计划通过时刻须早于夜间禁行起点）
 * @param personnelControl  是否启用人员管控：true 接班 IN 须先完成资格预约；缺省 false 保持旧行为
 */
public record RelayPointRequest(
        @NotBlank String requestId,
        @PositiveOrZero int waypointSeq,
        Boolean personnelControl) {

    /** 兼容旧两参构造：不启用人员管控。 */
    public RelayPointRequest(String requestId, int waypointSeq) {
        this(requestId, waypointSeq, null);
    }

    /** 归一化：缺省视为不启用。 */
    public boolean personnelControlEnabled() {
        return Boolean.TRUE.equals(personnelControl);
    }
}
