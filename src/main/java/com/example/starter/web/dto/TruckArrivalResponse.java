package com.example.starter.web.dto;

/**
 * 发电车到场确认响应。
 *
 * @param dispatchId 调度单ID
 * @param truckId    发电车ID
 * @param status     确认后的调度单状态
 * @param allArrived 是否全部到场
 * @param supplying  是否已确认供电（全部到场才供电）
 * @param late       是否迟到到场（只记事件，不再占车）
 * @param version    确认后的调度单版本
 * @param replayed   是否幂等重放
 */
public record TruckArrivalResponse(
        long dispatchId,
        long truckId,
        String status,
        boolean allArrived,
        boolean supplying,
        boolean late,
        int version,
        boolean replayed) implements ReplayableResponse<TruckArrivalResponse> {

    @Override
    public TruckArrivalResponse asReplayed() {
        return new TruckArrivalResponse(dispatchId, truckId, status, allArrived, supplying, late,
                version, true);
    }
}
