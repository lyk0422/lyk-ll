package com.example.starter.epidemic.api;

/**
 * 特殊订正发起/确认结果。
 *
 * @param effective 是否已生效（两名不同审核员确认完成）
 * @param appliedVersionNo 生效时生成的新版本号
 */
public record SpecialCorrectionView(
        String requestId,
        String cardNumber,
        String status,
        String confirmer1,
        String confirmer2,
        boolean effective,
        Integer appliedVersionNo,
        String message
) {
}
