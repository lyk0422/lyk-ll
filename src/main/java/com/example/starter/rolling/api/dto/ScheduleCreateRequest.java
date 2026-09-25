package com.example.starter.rolling.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 排产单编制（含修改新版本）请求。
 *
 * @param requestId     幂等键，指纹覆盖该键与全部业务字段
 * @param lineCode      产线编码
 * @param unitCode      轧制单元编码
 * @param baseVersionId 被修改的已下达版本 ID；为空表示首版
 * @param items         轧制作业列表（块序即列表顺序，从 1 开始）
 * @param windows       换辊窗口列表
 */
public record ScheduleCreateRequest(
        @NotBlank String requestId,
        @NotBlank String lineCode,
        @NotBlank String unitCode,
        Long baseVersionId,
        @NotEmpty @Valid List<ScheduleItemRequest> items,
        @Valid List<RollWindowRequest> windows) {
}
