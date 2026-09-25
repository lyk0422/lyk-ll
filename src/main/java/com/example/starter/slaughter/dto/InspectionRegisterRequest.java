package com.example.starter.slaughter.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 同步检验登记请求：同一批次首次登记时开启唯一检验链，后续登记追加头只。
 *
 * @param requestId 幂等键
 * @param items     本批登记的头只检验结果，按头只编号一一对应
 */
public record InspectionRegisterRequest(
        @NotBlank String requestId,
        @NotEmpty List<@Valid InspectionItem> items) {
}
