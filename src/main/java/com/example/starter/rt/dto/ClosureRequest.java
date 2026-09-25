package com.example.starter.rt.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 疗程结案请求。结案为终态：重复结案幂等成功；结案后迟到分次仅登记迟到事件。
 *
 * @param patientId 患者ID
 * @param planCode  计划业务编码
 * @param requestId 结案幂等键
 */
public record ClosureRequest(
        @NotBlank(message = "不能为空") String patientId,
        @NotBlank(message = "不能为空") String planCode,
        @NotBlank(message = "不能为空") String requestId) {
}
