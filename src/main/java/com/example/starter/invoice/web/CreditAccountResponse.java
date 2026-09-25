package com.example.starter.invoice.web;

import java.math.BigDecimal;

/**
 * 授信账户响应。
 *
 * @param sellerTaxNo           销方纳税人识别号
 * @param totalLimit            授信总额度，单位：元
 * @param blueUnarchivedAmount  未归档蓝票占用金额，单位：元
 * @param redConfirmedAmount    已确认红票累计金额，单位：元
 * @param availableAmount       可用额度＝总额度−未归档蓝票金额＋已确认红票金额，单位：元
 */
public record CreditAccountResponse(
        String sellerTaxNo,
        BigDecimal totalLimit,
        BigDecimal blueUnarchivedAmount,
        BigDecimal redConfirmedAmount,
        BigDecimal availableAmount) {
}
