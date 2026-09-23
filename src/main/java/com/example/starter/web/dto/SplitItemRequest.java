package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 分装条目：一个目标 aliquot 的业务主键、分配体积与首任保管人。
 *
 * @param volumeUl 分配体积（微升，最多三位小数字符串）
 * @param custodian 首任保管人；为空时继承请求级或样本当前保管人
 */
public record SplitItemRequest(
        @NotBlank(message = "不能为空") String aliquotId,
        @NotBlank(message = "不能为空") String volumeUl,
        String custodian) {
}
