package com.example.starter.cosmetic.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 未领标签销毁登记请求。
 *
 * <p>仅未领余额可销毁（已领部分不能销毁退回余额）；禁用印次的未领剩余
 * 可登记销毁，但禁用状态不可复活。</p>
 *
 * @param requestId       幂等键；指纹覆盖 requestId 与全部业务字段（含时刻）
 * @param quantity        本次销毁张数（正整数）
 * @param expectedVersion 印次台账期望版本（乐观并发控制，旧版 409）
 * @param atUtc           业务时刻(UTC)，可空（空取当前时刻）；参与幂等指纹
 */
public record DestroyLabelsRequest(
        @NotBlank String requestId,
        @NotNull @Min(1) Integer quantity,
        @NotNull @Min(1) Integer expectedVersion,
        Instant atUtc) {
}
