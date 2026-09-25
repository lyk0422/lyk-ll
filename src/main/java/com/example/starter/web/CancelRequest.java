package com.example.starter.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 注销请求（注销为终态，重复注销幂等成功）。
 *
 * @param lotNo 批次号
 */
public record CancelRequest(
        @NotBlank @Size(max = 40) String lotNo) {
}
