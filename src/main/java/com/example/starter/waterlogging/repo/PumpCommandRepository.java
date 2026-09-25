package com.example.starter.waterlogging.repo;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.waterlogging.po.PumpCommand;

/**
 * 泵站启停序列台账数据访问。
 */
@Repository
public class PumpCommandRepository {

    private static final String COLUMNS = """
            id, plan_id, station_code, action, sequence_no, command_time,
            gate_opening, gate_adjusted, request_id, created_at
            """;

    private static final RowMapper<PumpCommand> MAPPER = (rs, n) -> new PumpCommand(
            rs.getLong("id"),
            (Long) rs.getObject("plan_id"),
            rs.getString("station_code"),
            rs.getString("action"),
            rs.getInt("sequence_no"),
            readTime(rs, "command_time"),
            rs.getBigDecimal("gate_opening"),
            rs.getBoolean("gate_adjusted"),
            rs.getString("request_id"),
            readTime(rs, "created_at"));

    public PumpCommandRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    protected final JdbcTemplate jdbcTemplate;

    private static LocalDateTime readTime(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    /** 追加启停指令，返回主键；站点+时刻冲突由唯一约束拒绝。 */
    public long insert(PumpCommand command) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO pump_command
                        (plan_id, station_code, action, sequence_no, command_time,
                         gate_opening, gate_adjusted, request_id, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            if (command.planId() == null) {
                ps.setNull(1, java.sql.Types.BIGINT);
            } else {
                ps.setLong(1, command.planId());
            }
            ps.setString(2, command.stationCode());
            ps.setString(3, command.action());
            ps.setInt(4, command.sequenceNo());
            ps.setTimestamp(5, Timestamp.valueOf(command.commandTime()));
            ps.setBigDecimal(6, command.gateOpening());
            ps.setBoolean(7, command.gateAdjusted());
            ps.setString(8, command.requestId());
            ps.setTimestamp(9, Timestamp.valueOf(command.createdAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 同站同时刻既有序列（去重/唯一受理判断）。 */
    public Optional<PumpCommand> findByStationAndTime(String stationCode, LocalDateTime commandTime) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    "SELECT " + COLUMNS + " FROM pump_command WHERE station_code = ? AND command_time = ?",
                    MAPPER, stationCode, Timestamp.valueOf(commandTime)));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** 查询该站最近一次启机时刻（不晚于给定时刻），用于 10 分钟间隔校验。 */
    public Optional<LocalDateTime> findLastStartTimeBefore(String stationCode, LocalDateTime commandTime) {
        List<LocalDateTime> times = jdbcTemplate.query("""
                        SELECT command_time FROM pump_command
                        WHERE station_code = ? AND action = 'START' AND command_time <= ?
                        ORDER BY command_time DESC, id DESC LIMIT 1
                        """,
                (rs, n) -> readTime(rs, "command_time"),
                stationCode, Timestamp.valueOf(commandTime));
        return times.stream().findFirst();
    }

    /** 查询预降单内全部启停记录，按时刻与序号复算序列。 */
    public List<PumpCommand> findByPlan(long planId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM pump_command WHERE plan_id = ? "
                        + "ORDER BY command_time, id",
                MAPPER, planId);
    }

    /** 预降单内下一序号。 */
    public int nextSequenceNo(long planId) {
        Integer max = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(sequence_no), 0) FROM pump_command WHERE plan_id = ?",
                Integer.class, planId);
        return (max == null ? 0 : max) + 1;
    }
}
