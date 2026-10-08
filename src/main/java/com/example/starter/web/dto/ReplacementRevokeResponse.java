package com.example.starter.web.dto;

import java.util.List;

/**
 * 热备接替撤回响应：释放全部新车预占而保留旧车供电。
 *
 * @param replacementId   接替单ID
 * @param releasedTruckIds 被释放预占的热备新车ID
 * @param oldTruckId      继续保留供电的旧车ID
 * @param status          撤回后状态 REVOKED
 * @param version         撤回后接替单版本号
 */
public record ReplacementRevokeResponse(long replacementId, List<Long> releasedTruckIds, long oldTruckId,
        String status, int version) {
}
