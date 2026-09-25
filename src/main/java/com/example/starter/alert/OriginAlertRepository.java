package com.example.starter.alert;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 产地预警台账数据访问，（产地＋品种＋UTC 日期）唯一约束防止重复预警。
 */
@Repository
public class OriginAlertRepository {

    private final JdbcClient jdbc;

    public OriginAlertRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 登记预警，唯一约束保证同日同产地同品种只触发一次；返回生成主键。
     */
    public long insert(String origin, String variety, LocalDate alertDate,
                       int triggerCount, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO origin_alert (origin, variety, alert_date, trigger_count, status, created_at)"
                        + " VALUES (?, ?, ?, ?, 'ACTIVE', ?)")
                .params(origin, variety, alertDate, triggerCount, now)
                .update(keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<OriginAlert> find(String origin, String variety, LocalDate alertDate) {
        return jdbc.sql("SELECT * FROM origin_alert WHERE origin = ? AND variety = ? AND alert_date = ?")
                .params(origin, variety, alertDate)
                .query(this::map)
                .optional();
    }

    public List<OriginAlert> findAll() {
        return jdbc.sql("SELECT * FROM origin_alert ORDER BY id")
                .query(this::map)
                .list();
    }

    public long count() {
        return jdbc.sql("SELECT COUNT(*) FROM origin_alert").query(Long.class).single();
    }

    private OriginAlert map(ResultSet rs, int rowNum) throws SQLException {
        return new OriginAlert(
                rs.getLong("id"),
                rs.getString("origin"),
                rs.getString("variety"),
                rs.getDate("alert_date").toLocalDate(),
                rs.getInt("trigger_count"),
                rs.getString("status"),
                rs.getTimestamp("created_at").toLocalDateTime());
    }
}
