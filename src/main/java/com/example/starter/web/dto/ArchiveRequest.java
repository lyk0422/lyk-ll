package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

/**
 * 处置链归档（幂等，归档为终态）。重复归档返回幂等成功；
 * 归档后迟到的位移数据只记迟到事件，不再影响处置链。
 */
public record ArchiveRequest(
        @NotBlank String requestId,
        @NotBlank String chainNo,
        @NotNull LocalDateTime archiveTime) {
}
