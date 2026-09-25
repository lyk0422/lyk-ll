package com.example.starter.quarantine.api.dto;

import com.example.starter.quarantine.domain.AnimalType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 检疫申报请求。
 *
 * @param requestId    申报业务键，全流程幂等键
 * @param originFarm   启运养殖场编号（合成养殖场）
 * @param destination  目的地
 * @param animalType   动物种类：PIG/CATTLE/SHEEP/POULTRY
 * @param earTagRange  耳标段（同养殖场同耳标段只允许一张有效证）
 * @param quantity     申报数量，单位 头/只
 */
public record DeclareRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String originFarm,
        @NotBlank @Size(max = 128) String destination,
        @NotNull AnimalType animalType,
        @NotBlank @Size(max = 128) String earTagRange,
        @Min(1) int quantity) {
}
