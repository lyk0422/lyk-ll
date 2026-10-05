package com.example.starter.flood.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 预警级别变更事件仓储：提级与方案版本追加在同一事务内写入。
 */
@Repository
public class WarningLevelEventRepository {

    private final JdbcTemplate jdbcTemplate;

    public WarningLevelEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public static final RowMapper<WarningLevelEventPO> MAPPER = new RowMapper<>() {
        @Override
        public WarningLevelEventPO mapRow(ResultSet rs, int rowNum) throws SQLException {
            WarningLevelEventPO po = new WarningLevelEventPO();
            po.id = rs.getLong("id");
            po.warningId = rs.getLong("warning_id");
            po.fromLevel = rs.getString("from_level");
            po.toLevel = rs.getString("to_level");
            po.reason = rs.getString("reason");
            long rollingId = rs.getLong("rolling_id");
            po.rollingId = rs.wasNull() ? null : rollingId;
            Timestamp created = rs.getTimestamp("created_at");
            po.createdAt = created == null ? null : created.toLocalDateTime();
            return po;
        }
    };

    public void insert(WarningLevelEventPO po) {
        jdbcTemplate.update(
                "INSERT INTO warning_level_event(warning_id, from_level, to_level, reason, "
                        + "rolling_id, created_at) VALUES (?,?,?,?,?,?)",
                po.warningId, po.fromLevel, po.toLevel, po.reason, po.rollingId,
                Timestamp.valueOf(po.createdAt));
    }

    /**
     * 处置链全部级别变更事件，按时间升序（留痕可查）。
     */
    public List<WarningLevelEventPO> findByWarningId(Long warningId) {
        return jdbcTemplate.query(
                "SELECT * FROM warning_level_event WHERE warning_id = ? ORDER BY id",
                MAPPER, warningId);
    }
}
