package com.example.starter.pest;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 处置链、进行中状态与虫迹数据访问。
 */
@Repository
public class TreatmentChainRepository {

    /**
     * 处置链记录。
     *
     * @param monitoringStart 监测期起点（最近投放或虫迹时间）；NULL 表示尚未投放
     */
    public record Chain(long id, String zoneCode, long reportId, String status, Instant createdAt,
                        Instant monitoringStart, Instant liftRequestedAt,
                        boolean confirmDisinfection, boolean confirmWarehouse, Instant liftedAt) {
    }

    private final JdbcClient jdbc;

    public TreatmentChainRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 新建处置链，返回自增 ID。
     */
    public long insert(String zoneCode, long reportId, String status, Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO treatment_chain (zone_code, report_id, status, created_at)"
                        + " VALUES (:zoneCode, :reportId, :status, :createdAt)")
                .param("zoneCode", zoneCode)
                .param("reportId", reportId)
                .param("status", status)
                .param("createdAt", Timestamp.from(createdAt))
                .update(keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 按 ID 查询处置链。
     */
    public Optional<Chain> findById(long id) {
        return jdbc.sql("SELECT * FROM treatment_chain WHERE id = :id")
                .param("id", id)
                .query((rs, i) -> map(rs))
                .optional();
    }

    /**
     * 投放后进入监测期：状态置为 MONITORING 并以投放时间为监测起点。
     */
    public void startMonitoring(long chainId, Instant monitoringStart) {
        jdbc.sql("UPDATE treatment_chain SET status = 'MONITORING', monitoring_start = :start WHERE id = :id")
                .param("start", Timestamp.from(monitoringStart))
                .param("id", chainId)
                .update();
    }

    /**
     * 新增虫迹后监测期自虫迹时间重新起算。
     */
    public void resetMonitoringStart(long chainId, Instant foundAt) {
        jdbc.sql("UPDATE treatment_chain SET monitoring_start = :start WHERE id = :id")
                .param("start", Timestamp.from(foundAt))
                .param("id", chainId)
                .update();
    }

    /**
     * 登记解除申请。
     */
    public void markLiftRequested(long chainId, Instant liftRequestedAt) {
        jdbc.sql("UPDATE treatment_chain SET status = 'LIFT_REQUESTED', lift_requested_at = :at WHERE id = :id")
                .param("at", Timestamp.from(liftRequestedAt))
                .param("id", chainId)
                .update();
    }

    /**
     * 登记消杀方确认。
     */
    public void confirmDisinfection(long chainId) {
        jdbc.sql("UPDATE treatment_chain SET confirm_disinfection = TRUE WHERE id = :id")
                .param("id", chainId)
                .update();
    }

    /**
     * 登记库房方确认。
     */
    public void confirmWarehouse(long chainId) {
        jdbc.sql("UPDATE treatment_chain SET confirm_warehouse = TRUE WHERE id = :id")
                .param("id", chainId)
                .update();
    }

    /**
     * 双方确认后置为已解除。
     */
    public void markLifted(long chainId, Instant liftedAt) {
        jdbc.sql("UPDATE treatment_chain SET status = 'LIFTED', lifted_at = :at WHERE id = :id")
                .param("at", Timestamp.from(liftedAt))
                .param("id", chainId)
                .update();
    }

    /**
     * 占用库区进行中处置链位（主键冲突即同库区已有进行中处置链）。
     */
    public void insertActive(String zoneCode, long chainId) {
        jdbc.sql("INSERT INTO active_chain (zone_code, chain_id) VALUES (:zoneCode, :chainId)")
                .param("zoneCode", zoneCode)
                .param("chainId", chainId)
                .update();
    }

    /**
     * 释放库区进行中处置链位。
     */
    public void removeActive(String zoneCode) {
        jdbc.sql("DELETE FROM active_chain WHERE zone_code = :zoneCode")
                .param("zoneCode", zoneCode)
                .update();
    }

    /**
     * 判断库区是否处于处置状态。
     */
    public boolean isZoneActive(String zoneCode) {
        return jdbc.sql("SELECT COUNT(*) FROM active_chain WHERE zone_code = :zoneCode")
                .param("zoneCode", zoneCode)
                .query(Long.class)
                .single() > 0;
    }

    /**
     * 登记新增虫迹，返回自增 ID。
     */
    public long insertTrace(long chainId, Instant foundAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO pest_trace (chain_id, found_at) VALUES (:chainId, :foundAt)")
                .param("chainId", chainId)
                .param("foundAt", Timestamp.from(foundAt))
                .update(keyHolder);
        return keyHolder.getKey().longValue();
    }

    private Chain map(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp monitoringStart = rs.getTimestamp("monitoring_start");
        Timestamp liftRequestedAt = rs.getTimestamp("lift_requested_at");
        Timestamp liftedAt = rs.getTimestamp("lifted_at");
        return new Chain(
                rs.getLong("id"),
                rs.getString("zone_code"),
                rs.getLong("report_id"),
                rs.getString("status"),
                rs.getTimestamp("created_at").toInstant(),
                monitoringStart == null ? null : monitoringStart.toInstant(),
                liftRequestedAt == null ? null : liftRequestedAt.toInstant(),
                rs.getBoolean("confirm_disinfection"),
                rs.getBoolean("confirm_warehouse"),
                liftedAt == null ? null : liftedAt.toInstant());
    }
}
