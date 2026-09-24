package com.example.starter.portability.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 携号转网申请请求。
 *
 * @param requestId 幂等请求标识，指纹组成部分
 * @param msisdn    携转手机号码（合成数据）
 */
public record ApplyRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Pattern(regexp = "\\d{6,20}", message = "msisdn 须为 6-20 位数字") String msisdn) {
}
