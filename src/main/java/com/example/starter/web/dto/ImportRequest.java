package com.example.starter.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * DBC 版本原子导入请求。
 *
 * @param requestId 导入请求幂等键；同 requestId 同参重放返回同一结果，异参返回 409
 * @param version   DBC 版本名（版本内报文定义整体生效或整体失败）
 * @param messages  报文定义列表
 */
public record ImportRequest(
        @NotBlank String requestId,
        @NotBlank String version,
        @NotEmpty @Valid List<MessageDto> messages
) {
    /** 报文定义 DTO。 */
    public record MessageDto(
            @NotNull Long canId,
            @NotBlank String name,
            @NotNull Integer dlc,
            @NotEmpty @Valid List<SignalDto> signals
    ) {
    }
}
