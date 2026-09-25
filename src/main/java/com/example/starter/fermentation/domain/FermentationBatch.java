package com.example.starter.fermentation.domain;

import java.time.LocalDateTime;

/**
 * 发酵批次。时间字段为业务发生时间（本地时间，无时区），未发生时为 null。
 *
 * @param id                     批次 ID
 * @param batchNo                批次号，全局唯一
 * @param tankId                 入罐发酵罐 ID
 * @param curveCode              工艺曲线编码
 * @param curveVersion           当前生效曲线版本号
 * @param status                 批次状态
 * @param filledAt               满罐时间
 * @param mainFermentStartedAt   主酵开始时间，未开始为 null
 * @param coolingStartedAt       降温指令执行时间，未执行为 null
 * @param maturationStartedAt    后熟开始时间，未开始为 null
 * @param maturationExtended     后熟是否已延长（仅允许一次）
 * @param judgedAt               最近一次判定时间，未判定为 null
 * @param transferredAt          清酒转出时间，未转出为 null
 */
public record FermentationBatch(
        long id,
        String batchNo,
        long tankId,
        String curveCode,
        int curveVersion,
        BatchStatus status,
        LocalDateTime filledAt,
        LocalDateTime mainFermentStartedAt,
        LocalDateTime coolingStartedAt,
        LocalDateTime maturationStartedAt,
        boolean maturationExtended,
        LocalDateTime judgedAt,
        LocalDateTime transferredAt) {
}
