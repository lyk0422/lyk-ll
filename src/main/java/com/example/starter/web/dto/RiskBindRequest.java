package com.example.starter.web.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 风险抽检绑定请求：宰前检疫合格后为受控批次绑定合成风险等级并冻结首份抽检清单。
 *
 * @param requestId       幂等请求标识，全局唯一
 * @param level           合成风险等级：NORMAL 普通（10%）/ TIGHTENED 加严（30%）
 * @param ruleVersion     合成风险规则版本（随清单冻结，不用于生产）
 * @param declaredHeadNos 申报头只集合（调用方须与头只台账完全一致，作为冻结快照并参与指纹）
 * @param expectedVersion 期望当前风险版本，首次绑定固定为 0，成功后版本为 1
 * @param occurredAt      业务精确时刻（UTC ISO-8601），参与指纹，用于冻结证据
 */
public record RiskBindRequest(
        @NotBlank String requestId,
        @NotBlank String level,
        @NotBlank String ruleVersion,
        @NotEmpty List<@NotBlank String> declaredHeadNos,
        @NotNull @PositiveOrZero Integer expectedVersion,
        @NotBlank String occurredAt) {
}
