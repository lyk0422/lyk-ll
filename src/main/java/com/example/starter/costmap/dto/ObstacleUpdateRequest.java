package com.example.starter.costmap.dto;

import com.example.starter.costmap.model.Region;

/**
 * 障碍物更新请求。
 *
 * @param requestId  请求标识（幂等键）；同 requestId 同参数重放不重复应用，异参数返回 409
 * @param sourceId   来源标识，合并决胜项之一
 * @param priority   优先级，数值大者优先；为空时按 0 处理
 * @param obstacleKey 同一来源内的业务键；同键新更新替换旧记录，可为空
 * @param region     更新区域（格坐标）
 * @param cells      区域行主序代价数组（0-255），长度须为 region.width*region.height
 * @param timestamp  客户端时间戳（纪元毫秒），合并决胜项之一
 * @param ttlMillis  生存时间（毫秒）；为空表示不过期，过期时刻按接收时间加 TTL 计算
 */
public record ObstacleUpdateRequest(
        String requestId,
        String sourceId,
        Integer priority,
        String obstacleKey,
        Region region,
        int[] cells,
        Long timestamp,
        Long ttlMillis) {
}
