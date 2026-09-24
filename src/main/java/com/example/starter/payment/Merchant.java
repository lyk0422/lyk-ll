package com.example.starter.payment;

/**
 * 商户（合成数据）。
 *
 * @param merchantNo   商户号
 * @param merchantName 商户名称
 * @param feeRateBp    手续费率，单位基点（1bp = 0.01%）
 */
public record Merchant(String merchantNo, String merchantName, int feeRateBp) {
}
