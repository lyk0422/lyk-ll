package com.example.starter.carbon.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建企业请求（合成数据）。
 */
public record CreateEnterpriseRequest(
        @NotBlank @Size(max = 128) String name) {
}
