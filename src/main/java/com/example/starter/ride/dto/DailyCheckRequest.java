package com.example.starter.ride.dto;

import java.util.List;

/**
 * 日检请求：按合成清单逐项上报。
 *
 * @param requestId 幂等请求编号
 * @param rideId    设备编号
 * @param items     清单项结果；为空或与合成清单不完整按不合格处理
 */
public record DailyCheckRequest(String requestId, String rideId, List<ItemResult> items) {

    /**
     * 单个检查项结果。
     */
    public record ItemResult(String itemCode, Boolean qualified, String remark) {
    }
}
