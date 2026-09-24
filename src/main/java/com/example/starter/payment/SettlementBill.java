package com.example.starter.payment;

/**
 * 清算单（每批次每商户一单）。
 *
 * @param id          清算单ID
 * @param batchId     批次ID
 * @param merchantNo  商户号
 * @param txnCount    交易笔数
 * @param totalAmount 交易总额，单位分
 * @param totalFee    手续费总额，单位分
 * @param netAmount   清算净额（交易额-手续费），单位分
 */
public record SettlementBill(long id, long batchId, String merchantNo, int txnCount,
                             long totalAmount, long totalFee, long netAmount) {
}
