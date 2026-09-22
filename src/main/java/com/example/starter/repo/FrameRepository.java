package com.example.starter.repo;

/**
 * 帧审计、信号最新状态与状态变更事件的数据访问。
 */
public interface FrameRepository {

    /** 审计行。 */
    record AuditRow(long id, String version, long canId, long timestampNs, long seq, byte[] payload,
                    boolean accepted, String reason) {
    }

    /**
     * 插入帧审计行；同一 (version, canId, seq) 已存在时返回 null。
     */
    AuditRow insertAuditIfAbsent(String version, long canId, long timestampNs, long seq,
                                 byte[] payload, boolean accepted, String reason);

    AuditRow findAudit(String version, long canId, long seq);

    /**
     * 仅当帧比该信号已存状态更新（时戳更大，或时戳相同且序号更大）时写入状态；
     * 不存在则插入。返回写入结果（是否写入、是否首次插入、写入前旧值）。
     */
    UpsertOutcome upsertStateIfNewer(String version, long canId, String signalName,
                                     long rawValue, double physicalValue, String enumLabel,
                                     long timestampNs, long seq);

    /** 状态条件更新结果。updated=false 时 oldRaw 为 null；inserted=true 时 oldRaw 为 null。 */
    record UpsertOutcome(boolean updated, boolean inserted, Long oldRaw) {
    }

    /** 插入一条状态变更事件。 */
    void insertEvent(String version, long canId, String signalName, long seq, long timestampNs,
                     Long oldRaw, long newRaw);

    /** 统计状态变更事件数（测试用于断言重放不产生重复事件）。 */
    long countEvents(String version, long canId);

    /** 读取某信号当前状态行（测试与查询接口用）。 */
    SignalStateRow findState(String version, long canId, String signalName);

    /** 信号状态行。 */
    record SignalStateRow(String signalName, long rawValue, double physicalValue, String enumLabel,
                          long timestampNs, long seq) {
    }
}
