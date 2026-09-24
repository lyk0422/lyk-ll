package com.example.starter.blood.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.example.starter.blood.domain.BloodUnit;
import com.example.starter.blood.domain.TestOutcome;
import com.example.starter.blood.domain.UnitStatus;

/**
 * 成分血单位数据访问：参数化 SQL，并发放行依赖行级悲观锁与条件更新。
 */
@Repository
public class BloodUnitRepository {

    private final JdbcClient jdbc;

    public BloodUnitRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 入库新单位，初始状态为待检。
     *
     * @param barcode       唯一条码
     * @param componentType 成分类型
     * @param expireAt      可放行截止时刻（UTC）
     * @param now           当前时刻（UTC）
     */
    public void insert(String barcode, String componentType, Instant expireAt, Instant now) {
        jdbc.sql("INSERT INTO blood_unit (barcode, component_type, status, expire_at, created_at, updated_at)"
                        + " VALUES (:barcode, :componentType, :status, :expireAt, :createdAt, :updatedAt)")
                .param("barcode", barcode)
                .param("componentType", componentType)
                .param("status", UnitStatus.PENDING.name())
                .param("expireAt", Timestamp.from(expireAt))
                .param("createdAt", Timestamp.from(now))
                .param("updatedAt", Timestamp.from(now))
                .update();
    }

    /**
     * 按条码查询单位（不加锁）。
     *
     * @param barcode 条码
     * @return 单位记录
     */
    public Optional<BloodUnit> findByBarcode(String barcode) {
        return jdbc.sql("SELECT * FROM blood_unit WHERE barcode = :barcode")
                .param("barcode", barcode)
                .query(this::mapRow)
                .optional();
    }

    /**
     * 按条码查询并加行级悲观写锁（须在事务内调用），用于串行化同一单位的状态流转。
     *
     * @param barcode 条码
     * @return 单位记录
     */
    public Optional<BloodUnit> findByBarcodeForUpdate(String barcode) {
        return jdbc.sql("SELECT * FROM blood_unit WHERE barcode = :barcode FOR UPDATE")
                .param("barcode", barcode)
                .query(this::mapRow)
                .optional();
    }

    /**
     * 回写检测结果与重算后的状态。
     *
     * @param barcode       条码
     * @param initialResult 初检结果（可为 null）
     * @param recheckResult 复检结果（可为 null）
     * @param status        重算后的状态
     * @param now           当前时刻（UTC）
     */
    public void updateResultsAndStatus(String barcode, TestOutcome initialResult, TestOutcome recheckResult,
                                       UnitStatus status, Instant now) {
        jdbc.sql("UPDATE blood_unit SET initial_result = :initial, recheck_result = :recheck,"
                        + " status = :status, updated_at = :updatedAt WHERE barcode = :barcode")
                .param("initial", initialResult == null ? null : initialResult.name())
                .param("recheck", recheckResult == null ? null : recheckResult.name())
                .param("status", status.name())
                .param("updatedAt", Timestamp.from(now))
                .param("barcode", barcode)
                .update();
    }

    /**
     * 条件更新状态（乐观扣减可发库存）：仅当当前状态等于期望状态时迁移。
     *
     * @param barcode  条码
     * @param expected 期望的当前状态
     * @param target   目标状态
     * @param now      当前时刻（UTC）
     * @return 受影响行数，1 表示扣减成功，0 表示库存已被并发扣减
     */
    public int compareAndSetStatus(String barcode, UnitStatus expected, UnitStatus target, Instant now) {
        return jdbc.sql("UPDATE blood_unit SET status = :target, updated_at = :updatedAt"
                        + " WHERE barcode = :barcode AND status = :expected")
                .param("target", target.name())
                .param("updatedAt", Timestamp.from(now))
                .param("barcode", barcode)
                .param("expected", expected.name())
                .update();
    }

    /**
     * 将未终结的单位标记为过期报废；终态单位不受影响。
     *
     * @param barcode 条码
     * @param now     当前时刻（UTC）
     * @return 受影响行数
     */
    public int markExpiredDiscarded(String barcode, Instant now) {
        return jdbc.sql("UPDATE blood_unit SET status = :target, updated_at = :updatedAt"
                        + " WHERE barcode = :barcode AND status IN ('PENDING', 'ISOLATED', 'RELEASABLE')")
                .param("target", UnitStatus.EXPIRED_DISCARDED.name())
                .param("updatedAt", Timestamp.from(now))
                .param("barcode", barcode)
                .update();
    }

    private BloodUnit mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new BloodUnit(
                rs.getLong("id"),
                rs.getString("barcode"),
                rs.getString("component_type"),
                UnitStatus.valueOf(rs.getString("status")),
                rs.getString("initial_result") == null ? null : TestOutcome.valueOf(rs.getString("initial_result")),
                rs.getString("recheck_result") == null ? null : TestOutcome.valueOf(rs.getString("recheck_result")),
                rs.getTimestamp("expire_at").toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
