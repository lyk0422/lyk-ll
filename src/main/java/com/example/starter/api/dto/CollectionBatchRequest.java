package com.example.starter.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 批量采集上传：一次至多 20 包。序号重复、未知点或超窗任一错误整批拒绝（不先写有效前半批）。
 *
 * @param expectedVersion 调用方持有的会话版本，须与当前版本一致
 */
public record CollectionBatchRequest(
        @NotBlank String requestId,
        @NotBlank String sessionNo,
        @NotNull @Positive Integer expectedVersion,
        @NotEmpty @Size(max = 20) @Valid List<CollectionPacketRequest> packets) {
}
