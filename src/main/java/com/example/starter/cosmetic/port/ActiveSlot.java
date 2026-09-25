package com.example.starter.cosmetic.port;

import java.time.Instant;

/**
 * 产品有效备案槽指向。
 *
 * @param productCode 产品编码
 * @param filingNo    当前有效备案编号
 * @param versionNo   当前有效版本号
 * @param sinceUtc    占据槽位时刻(UTC)
 */
public record ActiveSlot(String productCode, String filingNo, int versionNo, Instant sinceUtc) {
}
