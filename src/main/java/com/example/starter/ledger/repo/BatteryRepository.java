package com.example.starter.ledger.repo;

import com.example.starter.ledger.po.BatteryPO;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 电池资产表数据访问。
 */
@Repository
public class BatteryRepository {

    private static final String COLUMNS =
            "id, code, status, current_station, bound_vehicle, head_event_hash, version, created_at, updated_at";

    private final JdbcTemplate jdbcTemplate;

    public BatteryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<BatteryPO> MAPPER = new BatteryRowMapper();

    /** 建档插入并回填自增主键；编码唯一冲突时抛 {@link org.springframework.dao.DuplicateKeyException}。 */
    public void insert(BatteryPO battery) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO battery (code, status, current_station, bound_vehicle, head_event_hash, version, "
                            + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, battery.getCode());
            ps.setString(2, battery.getStatus());
            ps.setString(3, battery.getCurrentStation());
            ps.setString(4, battery.getBoundVehicle());
            ps.setString(5, battery.getHeadEventHash());
            ps.setLong(6, battery.getVersion());
            ps.setTimestamp(7, Timestamp.from(battery.getCreatedAt()));
            ps.setTimestamp(8, Timestamp.from(battery.getUpdatedAt()));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key != null) {
            battery.setId(key.longValue());
        }
    }

    /** 按编码查询，不存在返回 null。 */
    public BatteryPO findByCode(String code) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT " + COLUMNS + " FROM battery WHERE code = ?", MAPPER, code);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /**
     * 乐观锁 + 状态条件更新（用于并发换出/调拨互斥）。
     *
     * @param expectedStatus 仅当当前状态等于该值才更新
     * @return 更新行数；0 表示状态/版本已变化
     */
    public int compareAndUpdateStatus(BatteryPO battery, String expectedStatus) {
        Instant now = battery.getUpdatedAt();
        return jdbcTemplate.update(
                "UPDATE battery SET status = ?, current_station = ?, bound_vehicle = ?, head_event_hash = ?, "
                        + "version = version + 1, updated_at = ? WHERE code = ? AND status = ? AND version = ?",
                battery.getStatus(), battery.getCurrentStation(), battery.getBoundVehicle(),
                battery.getHeadEventHash(), Timestamp.from(now), battery.getCode(), expectedStatus,
                battery.getVersion());
    }

    /** 无条件更新头哈希与状态（已在事务中按主键持锁时使用）。 */
    public int updateWithLock(BatteryPO battery) {
        Instant now = battery.getUpdatedAt();
        return jdbcTemplate.update(
                "UPDATE battery SET status = ?, current_station = ?, bound_vehicle = ?, head_event_hash = ?, "
                        + "version = version + 1, updated_at = ? WHERE id = ? AND version = ?",
                battery.getStatus(), battery.getCurrentStation(), battery.getBoundVehicle(),
                battery.getHeadEventHash(), Timestamp.from(now), battery.getId(), battery.getVersion());
    }

    /** 行锁查询，须在事务内调用。 */
    public BatteryPO lockByCode(String code) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT " + COLUMNS + " FROM battery WHERE code = ? FOR UPDATE", MAPPER, code);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 全量查询，按编码稳定排序。 */
    public List<BatteryPO> findAll() {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM battery ORDER BY code ASC", MAPPER);
    }

    private static final class BatteryRowMapper implements RowMapper<BatteryPO> {
        @Override
        public BatteryPO mapRow(ResultSet rs, int rowNum) throws SQLException {
            BatteryPO po = new BatteryPO();
            po.setId(rs.getLong("id"));
            po.setCode(rs.getString("code"));
            po.setStatus(rs.getString("status"));
            po.setCurrentStation(rs.getString("current_station"));
            po.setBoundVehicle(rs.getString("bound_vehicle"));
            po.setHeadEventHash(rs.getString("head_event_hash"));
            po.setVersion(rs.getLong("version"));
            Timestamp created = rs.getTimestamp("created_at");
            Timestamp updated = rs.getTimestamp("updated_at");
            po.setCreatedAt(created == null ? null : created.toInstant());
            po.setUpdatedAt(updated == null ? null : updated.toInstant());
            return po;
        }
    }
}
