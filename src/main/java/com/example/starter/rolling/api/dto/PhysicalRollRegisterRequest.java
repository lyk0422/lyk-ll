package com.example.starter.rolling.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 合成物理辊登记请求：为物理辊登记唯一编号，首代绑定一个已有辊号。
 *
 * @param requestId  幂等键，指纹覆盖该键与全部业务字段
 * @param physicalNo 物理辊唯一编号（不得与已有物理辊编号重复）
 * @param rollNo     首代绑定的已有辊号（须已在寿命台账中登记，且未绑定其他物理辊）
 */
public record PhysicalRollRegisterRequest(
        @NotBlank String requestId,
        @NotBlank String physicalNo,
        @NotBlank String rollNo) {
}
