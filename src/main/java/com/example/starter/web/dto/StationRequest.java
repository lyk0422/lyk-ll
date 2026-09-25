package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建交接站请求（合成基础数据）。
 *
 * @param stationCode 交接站编码
 * @param stationName 交接站名称
 * @param partyA 交出方名称
 * @param partyB 接收方名称
 */
public record StationRequest(
        @NotBlank @Size(max = 64) String stationCode,
        @NotBlank @Size(max = 128) String stationName,
        @NotBlank @Size(max = 128) String partyA,
        @NotBlank @Size(max = 128) String partyB) {
}
