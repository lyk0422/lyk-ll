package com.example.starter.costmap.model;

/**
 * 一条已应用的动态障碍物记录（不可变）。
 * <p>
 * 合并规则（公开且确定性）：同一格被多个障碍物覆盖时，依次按
 * 优先级大者优先、客户端时间戳大者优先、来源 ID 字典序小者优先 选出唯一胜者，
 * 该格障碍物代价取胜者对应格的值。
 *
 * @param obstacleId  服务端生成的记录标识
 * @param requestId   客户端请求标识（幂等键）
 * @param sourceId    来源标识
 * @param priority    优先级，数值大者优先
 * @param obstacleKey 同一来源内的业务键；同键新更新替换旧记录，可为 null
 * @param region      覆盖区域
 * @param cells       区域行主序代价数组（0-255）
 * @param clientTs    客户端时间戳（纪元毫秒）
 * @param expiresAt   过期时刻（纪元毫秒，UTC），null 表示不过期
 */
public record ObstacleRecord(
        String obstacleId,
        String requestId,
        String sourceId,
        int priority,
        String obstacleKey,
        Region region,
        byte[] cells,
        long clientTs,
        Long expiresAt) {

    /**
     * 返回该障碍物覆盖 (cx, cy) 格时的代价值；调用前须确认区域包含该格。
     */
    public int costAt(int cx, int cy) {
        int lx = cx - region.x();
        int ly = cy - region.y();
        return cells[ly * region.width() + lx] & 0xFF;
    }

    /**
     * 合并决胜比较：返回正数表示 this 优先于 other。
     */
    public int comparePrecedence(ObstacleRecord other) {
        int c = Integer.compare(this.priority, other.priority);
        if (c != 0) {
            return c;
        }
        c = Long.compare(this.clientTs, other.clientTs);
        if (c != 0) {
            return c;
        }
        // 来源 ID 字典序小者优先，因此取反。
        return other.sourceId.compareTo(this.sourceId);
    }
}
