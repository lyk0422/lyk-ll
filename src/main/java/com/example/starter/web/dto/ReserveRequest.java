package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 检测申请（占用）请求。
 *
 * @param expectedVersion aliquot 期望版本，必填；纳入幂等指纹，变化后同键重放返回 409
 * @param volumeUl 占用体积（微升，最多三位小数字符串）
 * @param custodian 申请（占用记录）首任保管人；为空时继承 aliquot 当前保管人
 */
public record ReserveRequest(
        @NotBlank(message = "不能为空") String requestId,
        @NotBlank(message = "不能为空") String aliquotId,
        @NotBlank(message = "不能为空") String volumeUl,
        @NotBlank(message = "不能为空") String projectCode,
        @NotNull(message = "不能为空") Integer expectedVersion,
        String custodian) {
}
