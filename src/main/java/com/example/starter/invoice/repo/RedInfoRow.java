package com.example.starter.invoice.repo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.example.starter.invoice.domain.InfoStatus;
import com.example.starter.invoice.domain.RedType;

/**
 * 红字信息表行记录。
 *
 * @param id                 主键
 * @param requestId          幂等请求号
 * @param fingerprint        幂等指纹
 * @param infoNo             信息表编号
 * @param originalInvoiceId  原蓝票主键
 * @param redType            红冲类型
 * @param status             信息表状态
 * @param sellerTaxNo        销方纳税人识别号
 * @param buyerTaxNo         购方纳税人识别号
 * @param totalAmount        本次红冲金额，单位：元
 * @param sellerConfirmed    销方是否已确认
 * @param buyerConfirmed     购方是否已确认
 * @param redInvoiceId       确认后开具的红票主键，未开具为 null
 * @param createdAt          创建时间
 * @param updatedAt          更新时间
 */
public record RedInfoRow(
        long id,
        String requestId,
        String fingerprint,
        String infoNo,
        long originalInvoiceId,
        RedType redType,
        InfoStatus status,
        String sellerTaxNo,
        String buyerTaxNo,
        BigDecimal totalAmount,
        boolean sellerConfirmed,
        boolean buyerConfirmed,
        Long redInvoiceId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
