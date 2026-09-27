package com.example.starter.cosmetic.child.port;

import java.time.Instant;

/**
 * 儿童产品有效备案槽指向。
 *
 * @param productCode 产品编码
 * @param filingNo    当前有效儿童备案编号
 * @param versionNo   当前有效版本号
 * @param sinceUtc    占据槽位时刻(UTC)
 */
public record ChildActiveSlot(String productCode, String filingNo, int versionNo, Instant sinceUtc) {
}
