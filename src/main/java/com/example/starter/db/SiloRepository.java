package com.example.starter.db;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 粮仓表数据访问。
 */
@Repository
public class SiloRepository {

    private static final RowMapper<SiloPo> MAPPER = (rs, n) -> new SiloPo(
            rs.getLong("id"),
            rs.getString("code"),
            rs.getString("name"),
            rs.getString("grain_type"),
            rs.getDouble("safe_temperature"),
            rs.getDouble("max_gradient"),
            rs.getDouble("equilibrium_humidity"),
            rs.getString("status"),
            rs.getInt("ventilation_rounds"),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("updated_at").toLocalDateTime()
    );

    private final JdbcTemplate jdbc;

    public SiloRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<SiloPo> findByCode(String code) {
        List<SiloPo> rows = jdbc.query(
                "SELECT * FROM silo WHERE code = ?", MAPPER, code);
        return rows.stream().findFirst();
    }

    public SiloPo insert(CreateSiloRow row) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update(
                "INSERT INTO silo (code, name, grain_type, safe_temperature, max_gradient, "
                        + "equilibrium_humidity, status, ventilation_rounds, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'MONITORING', 0, ?, ?)",
                row.code(), row.name(), row.grainType(), row.safeTemperature(),
                row.maxGradient(), row.equilibriumHumidity(), Timestamp.valueOf(now), Timestamp.valueOf(now));
        return findByCode(row.code()).orElseThrow();
    }

    public List<SiloPo> findAll() {
        return jdbc.query("SELECT * FROM silo ORDER BY id", MAPPER);
    }

    /**
     * 行级写锁（SELECT ... FOR UPDATE），用于串行化同仓并发作业流转。
     */
    public void lockForUpdate(long id) {
        jdbc.queryForObject("SELECT id FROM silo WHERE id = ? FOR UPDATE", Long.class, id);
    }

    /**
     * 条件更新状态（乐观并发：仅当当前状态在期望集合内且轮次匹配时），可选原子递增轮次。
     *
     * @return 是否更新成功（并发落败返回 false）
     */
    public boolean compareAndUpdateStatus(long id, List<String> expected, String next, int expectedRounds,
                                          boolean incrementRounds) {
        String placeholders = String.join(",", expected.stream().map(s -> "?").toList());
        String sql = "UPDATE silo SET status = ?, ventilation_rounds = ventilation_rounds + ?, updated_at = ? "
                + "WHERE id = ? AND ventilation_rounds = ? AND status IN (" + placeholders + ")";
        Object[] params = new Object[5 + expected.size()];
        params[0] = next;
        params[1] = incrementRounds ? 1 : 0;
        params[2] = Timestamp.valueOf(LocalDateTime.now());
        params[3] = id;
        params[4] = expectedRounds;
        for (int i = 0; i < expected.size(); i++) {
            params[5 + i] = expected.get(i);
        }
        return jdbc.update(sql, params) > 0;
    }

    /**
     * 无条件更新状态（复测、归档等由行级锁保护的流转）。
     */
    public void updateStatus(long id, String next) {
        jdbc.update("UPDATE silo SET status = ?, updated_at = ? WHERE id = ?",
                next, Timestamp.valueOf(LocalDateTime.now()), id);
    }

    /**
     * 仅按当前状态集合做条件更新（不校验轮次），归档等场景使用。
     */
    public boolean compareAndUpdateStatusIn(long id, List<String> expected, String next) {
        String placeholders = String.join(",", expected.stream().map(s -> "?").toList());
        Object[] params = new Object[3 + expected.size()];
        params[0] = next;
        params[1] = Timestamp.valueOf(LocalDateTime.now());
        params[2] = id;
        for (int i = 0; i < expected.size(); i++) {
            params[3 + i] = expected.get(i);
        }
        return jdbc.update(
                "UPDATE silo SET status = ?, updated_at = ? WHERE id = ? AND status IN (" + placeholders + ")",
                params) > 0;
    }

    /**
     * 创建粮仓入参。
     */
    public record CreateSiloRow(String code, String name, String grainType, double safeTemperature,
                                double maxGradient, double equilibriumHumidity) {
    }
}
