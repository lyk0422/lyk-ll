package com.example.starter.costmap.domain;

/**
 * 障碍物更新应用结果。
 *
 * @param requestId   幂等请求 ID
 * @param replay      是否为同参重放（true 时不重复应用）
 * @param revision    该来源在该区域内每格写入的更新序号
 * @param cellCount   实际覆盖格数
 * @param mapVersion  应用后的当前地图版本
 * @param appliedAt   服务端应用时间，Unix 毫秒
 */
public record UpdateResult(
        String requestId,
        boolean replay,
        long revision,
        int cellCount,
        long mapVersion,
        long appliedAt) {
}
