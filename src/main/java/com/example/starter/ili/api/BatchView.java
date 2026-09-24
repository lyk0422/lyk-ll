package com.example.starter.ili.api;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 对齐批次视图：批次元信息、匹配对、新发现与已消失读数键。
 *
 * @param id             批次主键
 * @param requestId      客户端幂等键
 * @param segmentId      管段标识
 * @param oldRunId       旧回合标识
 * @param newRunId       新回合标识
 * @param oldInspectedAt 旧回合检测日期
 * @param newInspectedAt 新回合检测日期
 * @param status         批次状态（OPEN/SEALED）
 * @param matches        匹配对列表
 * @param newFindings    新发现读数键（NEW 侧未匹配）
 * @param disappeared    已消失读数键（OLD 侧未匹配）
 * @param createdAt      创建时间
 * @param sealedAt       封板时间，未封板为 null
 */
public record BatchView(long id, String requestId, String segmentId,
                        String oldRunId, String newRunId,
                        LocalDate oldInspectedAt, LocalDate newInspectedAt,
                        String status, List<MatchView> matches,
                        List<String> newFindings, List<String> disappeared,
                        LocalDateTime createdAt, LocalDateTime sealedAt) {
}
