package com.example.starter.ventilation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 模式归档请求。归档为终态，重复归档返回幂等成功。
 *
 * @param requestId      归档指令自身的幂等键
 * @param modeRequestId  待归档火灾模式的激活 requestId
 */
public record ArchiveRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String modeRequestId) {
}
