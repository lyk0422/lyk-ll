package com.example.starter.invoice.repo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 授信额度账户行记录。
 *
 * @param id                    主键
 * @param sellerTaxNo           销方纳税人识别号
 * @param totalLimit            授信总额度，单位：元
 * @param blueUnarchivedAmount  未归档蓝票占用金额，单位：元
 * @param redConfirmedAmount    已确认红票累计金额，单位：元
 * @param version               行版本号
 * @param createdAt             创建时间
 * @param updatedAt             更新时间
 */
public record CreditAccountRow(
        long id,
        String sellerTaxNo,
        BigDecimal totalLimit,
        BigDecimal blueUnarchivedAmount,
        BigDecimal redConfirmedAmount,
        long version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    /**
     * 可用额度＝总额度−未归档蓝票金额＋已确认红票金额。
     */
    public BigDecimal available() {
        return totalLimit.subtract(blueUnarchivedAmount).add(redConfirmedAmount);
    }
}
