package com.example.starter.rolling.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 返修放行检验确认：同一申请须两名不同检验员确认合格才原子建立下一代。
 *
 * @param requestId   幂等键，指纹覆盖该键、申请 id 与全部业务字段
 * @param inspectorId 检验员编号；同一申请内两名检验员必须不同
 * @param approved    是否确认合格；任一不合格即终止申请且不建新辊
 * @param certNo      检验凭据编号
 */
public record RepairInspectionRequest(
        @NotBlank String requestId,
        @NotBlank String inspectorId,
        @NotNull Boolean approved,
        @NotBlank String certNo) {
}
