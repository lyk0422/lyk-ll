package com.example.starter.repository;

import com.example.starter.domain.BloodUnit;
import com.example.starter.domain.ComponentType;
import com.example.starter.domain.TestResultCode;
import com.example.starter.domain.UnitStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 成分血库存仓储。状态流转在事务内以行锁串行化，并以版本号乐观锁兜底。
 */
@Repository
public class BloodUnitRepository {

    private static final String COLUMNS = "id, barcode, component_type, status, initial_result, repeat_result, "
            + "initial_received_at, repeat_received_at, expires_at, expired_at, scrap_reason, "
            + "created_at, updated_at, version";

    private final NamedParameterJdbcTemplate jdbc;

    public BloodUnitRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<BloodUnit> MAPPER = (rs, n) -> {
        BloodUnit u = new BloodUnit();
        u.setId(rs.getLong("id"));
        u.setBarcode(rs.getString("barcode"));
        u.setComponentType(ComponentType.valueOf(rs.getString("component_type")));
        u.setStatus(UnitStatus.valueOf(rs.getString("status")));
        String ir = rs.getString("initial_result");
        u.setInitialResult(ir == null ? null : TestResultCode.valueOf(ir));
        String rr = rs.getString("repeat_result");
        u.setRepeatResult(rr == null ? null : TestResultCode.valueOf(rr));
        Timestamp iat = rs.getTimestamp("initial_received_at");
        u.setInitialReceivedAt(iat == null ? null : iat.toInstant());
        Timestamp rat = rs.getTimestamp("repeat_received_at");
        u.setRepeatReceivedAt(rat == null ? null : rat.toInstant());
        u.setExpiresAt(rs.getTimestamp("expires_at").toInstant());
        Timestamp eat = rs.getTimestamp("expired_at");
        u.setExpiredAt(eat == null ? null : eat.toInstant());
        u.setScrapReason(rs.getString("scrap_reason"));
        u.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        u.setUpdatedAt(rs.getTimestamp("updated_at").toInstant());
        u.setVersion(rs.getInt("version"));
        return u;
    };

    public void insert(BloodUnit unit) {
        String sql = "INSERT INTO blood_unit (barcode, component_type, status, initial_result, repeat_result, "
                + "initial_received_at, repeat_received_at, expires_at, expired_at, scrap_reason, "
                + "created_at, updated_at, version) VALUES (:barcode, :componentType, :status, "
                + ":initialResult, :repeatResult, :initialReceivedAt, :repeatReceivedAt, :expiresAt, "
                + ":expiredAt, :scrapReason, :createdAt, :updatedAt, 0)";
        MapSqlParameterSource p = params(unit)
                .addValue("createdAt", ts(unit.getCreatedAt()));
        jdbc.update(sql, p);
    }

    public Optional<BloodUnit> findByBarcode(String barcode) {
        String sql = "SELECT " + COLUMNS + " FROM blood_unit WHERE barcode = :barcode";
        List<BloodUnit> list = jdbc.query(sql,
                new MapSqlParameterSource("barcode", barcode), MAPPER);
        return list.stream().findFirst();
    }

    /**
     * 在事务内对条码对应行加写锁，串行化同一单位的检测回传、过期判定与放行。
     */
    public Optional<BloodUnit> findByBarcodeForUpdate(String barcode) {
        String sql = "SELECT " + COLUMNS + " FROM blood_unit WHERE barcode = :barcode FOR UPDATE";
        List<BloodUnit> list = jdbc.query(sql,
                new MapSqlParameterSource("barcode", barcode), MAPPER);
        return list.stream().findFirst();
    }

    /**
     * 条件更新：仅当版本号仍为读取时的版本时生效，返回受影响行数。
     */
    public int compareAndUpdate(BloodUnit unit) {
        String sql = "UPDATE blood_unit SET component_type = :componentType, status = :status, "
                + "initial_result = :initialResult, repeat_result = :repeatResult, "
                + "initial_received_at = :initialReceivedAt, repeat_received_at = :repeatReceivedAt, "
                + "expires_at = :expiresAt, expired_at = :expiredAt, scrap_reason = :scrapReason, "
                + "updated_at = :updatedAt, version = version + 1 "
                + "WHERE id = :id AND version = :version";
        return jdbc.update(sql, params(unit));
    }

    static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static MapSqlParameterSource params(BloodUnit u) {
        return new MapSqlParameterSource()
                .addValue("id", u.getId())
                .addValue("barcode", u.getBarcode())
                .addValue("componentType", u.getComponentType().name())
                .addValue("status", u.getStatus().name())
                .addValue("initialResult", u.getInitialResult() == null ? null : u.getInitialResult().name())
                .addValue("repeatResult", u.getRepeatResult() == null ? null : u.getRepeatResult().name())
                .addValue("initialReceivedAt", ts(u.getInitialReceivedAt()))
                .addValue("repeatReceivedAt", ts(u.getRepeatReceivedAt()))
                .addValue("expiresAt", ts(u.getExpiresAt()))
                .addValue("expiredAt", ts(u.getExpiredAt()))
                .addValue("scrapReason", u.getScrapReason())
                .addValue("updatedAt", ts(u.getUpdatedAt()))
                .addValue("version", u.getVersion());
    }
}
