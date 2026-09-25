package com.example.starter.feed.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 配方提交请求。requestId 为幂等键；指纹覆盖 requestId 与下列全部业务字段。
 *
 * @param requestId     幂等键
 * @param formulaCode   配方业务编码；同一编码可提交多个独立版本
 * @param name          配方名称
 * @param targetSpecies 目标动物种类；含药配方必填且须与各药物允许种类匹配
 * @param ingredients   普通原料清单
 * @param drugs         药物添加剂清单；为空表示无药配方
 */
public record FormulaSubmitRequest(
        @NotBlank(message = "requestId 不能为空")
        @Size(max = 64)
        String requestId,

        @NotBlank(message = "配方编码不能为空")
        @Size(max = 64)
        String formulaCode,

        @NotBlank(message = "配方名称不能为空")
        @Size(max = 128)
        String name,

        @Size(max = 64)
        String targetSpecies,

        @Valid
        @Size(max = 100)
        List<RatioItem> ingredients,

        @Valid
        @Size(max = 50)
        List<RatioItem> drugs
) {
}
