package com.example.starter.web.dto;

/**
 * 发电车视图。
 *
 * @param truckId    发电车ID
 * @param truckCode  发电车编号
 * @param capacityKw 额定容量，单位kW
 * @param feederId   服务馈线编号
 * @param available  是否可用
 * @param version    车辆版本，从1开始
 * @param replayed   是否幂等重放
 */
public record TruckView(
        long truckId,
        String truckCode,
        int capacityKw,
        String feederId,
        boolean available,
        int version,
        boolean replayed) implements ReplayableResponse<TruckView> {

    @Override
    public TruckView asReplayed() {
        return new TruckView(truckId, truckCode, capacityKw, feederId, available, version, true);
    }
}
