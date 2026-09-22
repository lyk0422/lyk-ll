package com.example.starter.repository;

import com.example.starter.domain.SignalStateRow;

import java.util.List;
import java.util.Optional;

/**
 * 帧审计、信号状态与变更事件存储。
 */
public interface FrameRepository {

    /** 写入一条帧审计记录（接受、拒绝、乱序、重放均留存）。 */
    void insertAudit(long versionId, long canId, long frameSeq, long timestampNs,
                     byte[] payload, boolean accepted, String rejectReason, long createdAtNano);

    /** 查询完全相同的帧（版本+CAN ID+序号+时戳+负载）是否已审计过。 */
    boolean existsSameFrame(long versionId, long canId, long frameSeq, long timestampNs, byte[] payload);

    /** 查询当前已接受的最新帧时戳（无则空）。 */
    Optional<Long> findLatestAcceptedTimestamp(long versionId, long canId);

    /** 查询当前已接受的最大帧序号（无则空）。 */
    Optional<Long> findLatestAcceptedSeq(long versionId, long canId);

    /** 查询某信号当前状态行。 */
    Optional<SignalStateRow> findState(long versionId, long canId, String signalName);

    /**
     * 仅当帧序号新于当前状态序号时插入/更新信号状态。
     *
     * @return 实际更新行数（0 表示已被更新的帧抢先，状态未变）
     */
    int upsertStateIfNewer(long versionId, long canId, String signalName,
                           long rawValue, double physicalValue, String enumText,
                           long frameSeq, long timestampNs);

    /**
     * 写入状态变更事件，相同（版本+CAN ID+序号+信号）已存在时忽略。
     *
     * @return 实际插入行数
     */
    int insertEventIfAbsent(long versionId, long canId, long frameSeq, long timestampNs,
                            String signalName, Long oldRawValue, long newRawValue, long createdAtNano);

    /** 列出版本 + CAN ID 下全部信号最新状态。 */
    List<SignalStateRow> listStates(long versionId, long canId);
}
