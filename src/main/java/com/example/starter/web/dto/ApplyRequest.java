package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 申请引航计划请求。
 *
 * @param requestId 幂等键，客户端提供
 * @param planNo    计划编号，业务唯一
 * @param shipName  船舶名称
 * @param voyageNo  航次号
 */
public record ApplyRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 64) String planNo,
        @NotBlank @Size(max = 128) String shipName,
        @NotBlank @Size(max = 64) String voyageNo) {
}
