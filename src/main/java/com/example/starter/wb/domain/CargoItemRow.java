package com.example.starter.wb.domain;

/**
 * 装配图逐项行（cargo_loading_item）。
 *
 * @param uldNo           集装器唯一编号（前 3 位为类型代码）
 * @param compartmentCode 装载舱位代码（同图唯一）
 * @param armMm           舱位整数毫米力臂快照
 * @param tareKg          皮重，非负整数公斤
 * @param netKg           净重，正整数公斤
 * @param grossKg         含皮重总重 = 皮重 + 净重
 */
public record CargoItemRow(
        Long id,
        long planId,
        int seqNo,
        String uldNo,
        String compartmentCode,
        int armMm,
        int tareKg,
        int netKg,
        int grossKg
) {
}
