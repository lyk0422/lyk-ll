package com.example.starter.invoice.repo;

import java.math.BigDecimal;

/**
 * 红字信息表行项目记录。
 *
 * @param id              主键
 * @param infoTableId     所属信息表主键
 * @param originalLineId  原蓝票行项目主键
 * @param amount          本行红冲金额，单位：元
 */
public record RedInfoLineRow(
        long id,
        long infoTableId,
        long originalLineId,
        BigDecimal amount) {
}
