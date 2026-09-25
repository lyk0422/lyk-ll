package com.example.starter.margin.po;

import java.time.Instant;

/**
 * 信用账户持久化对象。
 *
 * @param accountNo    账户业务主键
 * @param clientName   客户名称
 * @param baseCurrency 记账币种（ISO 4217）
 * @param createdAt    创建时间（UTC）
 */
public record AccountPo(String accountNo, String clientName, String baseCurrency, Instant createdAt) {
}
