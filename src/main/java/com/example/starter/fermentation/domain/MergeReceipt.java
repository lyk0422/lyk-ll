package com.example.starter.fermentation.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 合批接收单：一次接收 2 至 5 个受控源批进入同一接收罐，原子核销。
 *
 * @param id               接收单 ID
 * @param receivingTankId  接收罐 ID
 * @param curveCode        全部来源统一的工艺曲线编码
 * @param curveVersion     全部来源统一的工艺曲线版本号
 * @param totalLiters      本次接收合计升数，等于各来源登记量之和
 * @param receivedAt       接收受理业务时间（由调用方传入）
 * @param createdAt        接收单创建时间（UTC）
 */
public record MergeReceipt(
        long id,
        long receivingTankId,
        String curveCode,
        int curveVersion,
        BigDecimal totalLiters,
        LocalDateTime receivedAt,
        LocalDateTime createdAt) {
}
