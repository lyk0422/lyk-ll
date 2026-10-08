package com.example.starter.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 分缸请求：把已投料来源缸 sourceLotNo 拆成 2~4 个不同空闲目标缸。
 * expectedVersion 为来源缸分缸版本（新增，从 1 起；首次拆分传 1），
 * 修改用 expectedVersion、成功加 1、旧版冲突返回 409。
 * 指纹覆盖全部目标、重量与版本；目标提交顺序即子缸编号（最小耗用余量按编号升序循环分配）。
 */
public record SplitLotRequest(
        @NotBlank String requestId,
        @NotBlank String sourceLotNo,
        @NotNull Integer expectedVersion,
        @NotEmpty @Size(min = 2, max = 4) @Valid List<SplitLotTargetInput> targets) {
}
