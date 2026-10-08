package com.example.starter.web.dto;

import java.util.List;

/**
 * 热备切换前后供电组合容量证据。
 *
 * @param beforeTruckIds   切换前供电组合车辆ID（含旧车）
 * @param beforeCapacityKw 切换前组合总容量，单位kW
 * @param afterTruckIds    切换后供电组合车辆ID（全部为热备新车）
 * @param afterCapacityKw  切换后组合总容量，单位kW
 */
public record ReplacementSwitchEvidence(List<Long> beforeTruckIds, int beforeCapacityKw,
        List<Long> afterTruckIds, int afterCapacityKw) {
}
