package com.example.starter.ride;

import java.util.List;

import com.example.starter.ride.dto.RideDtos.CheckItem;

/**
 * 大修后的监督检验记录：绑定登记时的设备大修版本。
 * 仅当 overhaulVersion 与设备当前大修版本相等、全清单合格且两名检验人不同时有效；
 * 设备再次登记大修后旧记录自然失效（无需改写历史记录）。
 *
 * @param id               记录主键
 * @param rideId           设备ID
 * @param requestId        幂等请求ID
 * @param overhaulVersion  绑定的设备大修版本
 * @param inspectorA       检验人A
 * @param inspectorB       检验人B
 * @param allPassed        全清单是否逐项合格
 * @param items            清单明细
 */
public record SupervisoryInspection(
        long id,
        long rideId,
        String requestId,
        int overhaulVersion,
        String inspectorA,
        String inspectorB,
        boolean allPassed,
        List<CheckItem> items) {
}
