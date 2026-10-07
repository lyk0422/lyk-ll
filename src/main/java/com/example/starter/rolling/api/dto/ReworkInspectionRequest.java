package com.example.starter.rolling.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 返修放行检验确认请求：须由两名不同检验员各确认一次，两次均合格才原子建立下一代。
 *
 * @param requestId       幂等键，指纹覆盖该键与全部业务字段
 * @param inspector       检验员工号；第二名必须与第一名不同
 * @param qualified       本次检验是否合格；任一不合格即终止申请且不建新辊
 * @param expectedVersion 申请聚合期望版本（从 1 起）；与当前版本不一致返回 409，成功后版本加 1
 */
public record ReworkInspectionRequest(
        @NotBlank String requestId,
        @NotBlank String inspector,
        @NotNull Boolean qualified,
        @NotNull Integer expectedVersion) {
}
