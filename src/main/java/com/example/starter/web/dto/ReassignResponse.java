package com.example.starter.web.dto;

import java.util.List;

/**
 * 整体改派响应。
 *
 * @param oldDispatchId   旧调度单ID（已置REASSIGNED）
 * @param newDispatchId   新调度单ID
 * @param keptTruckIds    保留的已到场车
 * @param releasedTruckIds 释放的未到场车
 * @param newTruckIds     新增车辆
 * @param status          新调度单状态
 * @param version         新调度单版本号（从1开始）
 */
public record ReassignResponse(long oldDispatchId, long newDispatchId, List<Long> keptTruckIds,
        List<Long> releasedTruckIds, List<Long> newTruckIds, String status, int version) {
}
