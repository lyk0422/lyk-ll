package com.example.starter.wb.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 集装器装配图提交请求。
 *
 * <p>受控舱单每次提交写一张新图：{@code expectedVersion} 为客户端所见的当前装配图版本
 * （从未提交过为 0），成功提交后版本 +1（自 1 起）。
 *
 * @param requestId       幂等键：同键同参回放首次冻结结果，异参 409，失败不占键
 * @param expectedVersion 期望的当前装配图版本；与库内不一致 409
 * @param occurredAt      请求声明的精确业务时刻（参与指纹，保证跨时刻可区分）
 * @param createdBy       提交人岗位账号
 * @param items           全量装配图逐项集合（非空）
 */
public record CargoPlanSubmitRequest(
        @NotBlank String requestId,
        @NotNull @PositiveOrZero Integer expectedVersion,
        @NotNull LocalDateTime occurredAt,
        @NotBlank String createdBy,
        @NotEmpty List<@Valid UldItemInput> items
) {
}
