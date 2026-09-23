package com.example.starter.lot.repository;

import com.example.starter.lot.api.TransformRequest;
import com.example.starter.lot.model.Lot;
import com.example.starter.lot.model.LotStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 批次谱系数据访问边界（JdbcTemplate + H2/MySQL 兼容 SQL）。
 * 所有写操作均在服务层事务内调用。
 */
public interface LotRepository {

    /** 锁定全局串行互斥行，序列化转换与隔离传播。 */
    void lockMutex();

    /** 新建批次。 */
    void insertLot(Lot lot);

    Optional<Lot> findLot(String lotId);

    List<Lot> findLots(Collection<String> lotIds);

    boolean lotExists(String lotId);

    /**
     * 乐观条件消费：仅当版本等于 expectedVersion 且消费后不超用时更新。
     *
     * @return 受影响行数；0 表示版本不符或并发超用
     */
    int tryConsume(String lotId, BigDecimal quantity, long expectedVersion);

    /** 插入转换产出的新子批次。 */
    void insertChildLot(Lot lot);

    /** 追加不可变数量台账。 */
    void insertLedger(String requestId, String lotId, String changeType,
                      BigDecimal quantity, BigDecimal balanceAfter, LocalDateTime at);

    /** 记录转换请求（指纹与完整报文）。 */
    void insertTransformRecord(TransformRequest request, String fingerprint,
                               String requestJson, BigDecimal totalConsumed,
                               BigDecimal totalProduced, LocalDateTime at);

    /** 回填首次成功响应报文。 */
    void updateTransformResponse(String requestId, String responseJson);

    /** 追加单笔投入/产出口径明细。 */
    void insertTransformIo(String requestId, String side, String lotId,
                           BigDecimal quantity, int seqNo);

    Optional<TransformRecordRow> findTransformRecord(String requestId);

    /** 追加谱系边。 */
    void insertLink(String requestId, String parentLotId, String childLotId);

    /** 直接子批次编号。 */
    List<String> findDirectChildren(String lotId);

    /** 直接父批次编号。 */
    List<String> findDirectParents(String lotId);

    /** 全部传递性后代（沿 lot_link 迭代）。 */
    List<String> findAllDescendants(String lotId);

    /** 全部传递性祖先。 */
    List<String> findAllAncestors(String lotId);

    /** 追加隔离事件历史。 */
    void insertQuarantineEvent(String lotId, String action, String reason, LocalDateTime at);

    /** 加入生效隔离来源（幂等）。 */
    void addQuarantineSource(String lotId, String sourceLotId, LocalDateTime at);

    /** 移除一个生效隔离来源。 */
    int removeQuarantineSource(String lotId, String sourceLotId);

    /** 批次当前生效隔离来源集合。 */
    List<String> findActiveSources(String lotId);

    /** 携带指定生效来源的全部批次（含自身判断）。 */
    List<String> findLotsWithSource(String sourceLotId);

    /**
     * 仅当目标状态与当前不同时下钻更新并递增版本；幂等操作不产生新版本。
     *
     * @return 状态实际发生变化返回 1，否则 0
     */
    int applyStatus(String lotId, LotStatus newStatus);

    /** 转换记录行。 */
    record TransformRecordRow(
            String requestId,
            String fingerprint,
            String unit,
            String requestJson,
            String responseJson,
            BigDecimal totalConsumed,
            BigDecimal totalProduced,
            BigDecimal allowedLoss
    ) {
    }
}
