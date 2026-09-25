package com.example.starter.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import com.example.starter.domain.BloodUnit;
import com.example.starter.domain.TestResultValue;
import com.example.starter.domain.UnitStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 成分血单位数据访问。并发放行与回传通过 SELECT ... FOR UPDATE 行锁串行化。
 */
@Repository
public class BloodUnitRepository {

    private static final String COLUMNS =
            "id, barcode, status, initial_result, recheck_result, expiry_date, created_at, updated_at, version";

    private final JdbcClient jdbc;

    public BloodUnitRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入新单位，初始状态为待检。
     */
    public void insert(String barcode, LocalDate expiryDate, Instant now) {
        jdbc.sql("INSERT INTO blood_unit (barcode, status, expiry_date, created_at, updated_at, version)"
                        + " VALUES (:barcode, 'PENDING_TEST', :expiryDate, :now, :now, 0)")
                .param("barcode", barcode)
                .param("expiryDate", expiryDate)
                .param("now", Timestamp.from(now))
                .update();
    }

    /**
     * 按条码查询（不加锁）。
     */
    public Optional<BloodUnit> findByBarcode(String barcode) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM blood_unit WHERE barcode = :barcode")
                .param("barcode", barcode)
                .query(this::mapRow)
                .optional();
    }

    /**
     * 按条码查询并加行级写锁，须在事务内调用，用于状态迁移串行化。
     */
    public Optional<BloodUnit> findByBarcodeForUpdate(String barcode) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM blood_unit WHERE barcode = :barcode FOR UPDATE")
                .param("barcode", barcode)
                .query(this::mapRow)
                .optional();
    }

    /**
     * 更新检测结果与状态，携带乐观锁版本；返回受影响行数（0 表示版本冲突）。
     */
    public int updateResultsAndStatus(BloodUnit unit, Instant now) {
        return jdbc.sql("UPDATE blood_unit SET initial_result = :initial, recheck_result = :recheck,"
                        + " status = :status, updated_at = :now, version = version + 1"
                        + " WHERE id = :id AND version = :version")
                .param("initial", unit.initialResult() == null ? null : unit.initialResult().name())
                .param("recheck", unit.recheckResult() == null ? null : unit.recheckResult().name())
                .param("status", unit.status().name())
                .param("now", Timestamp.from(now))
                .param("id", unit.id())
                .param("version", unit.version())
                .update();
    }

    /**
     * 仅更新状态（放行扣减、过期标记），携带乐观锁版本；返回受影响行数。
     */
    public int updateStatus(BloodUnit unit, UnitStatus newStatus, Instant now) {
        return jdbc.sql("UPDATE blood_unit SET status = :status, updated_at = :now, version = version + 1"
                        + " WHERE id = :id AND version = :version")
                .param("status", newStatus.name())
                .param("now", Timestamp.from(now))
                .param("id", unit.id())
                .param("version", unit.version())
                .update();
    }

    private BloodUnit mapRow(ResultSet rs, int rowNum) throws SQLException {
        String initial = rs.getString("initial_result");
        String recheck = rs.getString("recheck_result");
        return new BloodUnit(
                rs.getLong("id"),
                rs.getString("barcode"),
                UnitStatus.valueOf(rs.getString("status")),
                initial == null ? null : TestResultValue.valueOf(initial),
                recheck == null ? null : TestResultValue.valueOf(recheck),
                rs.getObject("expiry_date", LocalDate.class),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getLong("version"));
    }
}
