package com.example.starter.wb.dto;

import java.math.BigDecimal;

/**
 * 业载明细录入项。
 *
 * @param category      业载类别：PASSENGER/BAGGAGE/CARGO/MAIL
 * @param stationCode   装载站位代码
 * @param pieces        人数或件数（旅客为人，其余为件；LMC 可为负）
 * @param unitWeightKg  单位重量，kg/人或 kg/件
 */
public record LoadItem(
        String category,
        String stationCode,
        int pieces,
        BigDecimal unitWeightKg
) {
}
