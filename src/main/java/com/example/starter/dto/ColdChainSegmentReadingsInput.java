package com.example.starter.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 单个运输片段的读数提交块。
 *
 * @param segmentSeq 所属片段序号（必填，缺序号不得通过）
 * @param readings   该片段的有限温度读数（非空，须含片段 start 与 end 两端样本）
 */
public record ColdChainSegmentReadingsInput(
        @NotNull Integer segmentSeq,
        @NotNull @NotEmpty List<@Valid ColdChainReadingInput> readings
) {
}
