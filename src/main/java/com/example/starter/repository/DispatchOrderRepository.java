package com.example.starter.repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.starter.model.DispatchOrder;
import com.example.starter.model.OrderStatus;

/**
 * 调度单数据访问；状态迁移均使用带前置状态的条件更新，保证并发下唯一终态。
 */
@Repository
public class DispatchOrderRepository {

    private final JdbcTemplate jdbcTemplate;

    public DispatchOrderRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 新建调度单（在途），返回自增主键。
     * 唯一约束：request_id、unfinished_key（同站仅一张未完成单）、busy_generator_key（同油机仅服务一站）。
     */
    public long insert(String requestId, String stationCode, String generatorCode,
                       BigDecimal supportHours, Instant deadlineAt, Instant now) {
        jdbcTemplate.update(
                "INSERT INTO dispatch_order (request_id, station_code, generator_code, status,"
                        + " support_hours, deadline_at, assigned_at, confirmed_at, closed_at,"
                        + " unfinished_key, busy_generator_key, created_at, updated_at)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                requestId, stationCode, generatorCode, OrderStatus.DISPATCHED.name(),
                supportHours, StationRepository.toOffsetDateTime(deadlineAt),
                StationRepository.toOffsetDateTime(now), null, null,
                stationCode, generatorCode,
                StationRepository.toOffsetDateTime(now), StationRepository.toOffsetDateTime(now));
        Long id = jdbcTemplate.queryForObject(
                "SELECT MAX(id) FROM dispatch_order WHERE station_code = ?", Long.class, stationCode);
        return id == null ? -1L : id;
    }

    /**
     * 按主键查询。
     */
    public Optional<DispatchOrder> findById(long id) {
        return jdbcTemplate.query("SELECT * FROM dispatch_order WHERE id = ?", this::map, id)
                .stream().findFirst();
    }

    /**
     * 按主键查询并加行锁。
     */
    public Optional<DispatchOrder> findByIdForUpdate(long id) {
        return jdbcTemplate.query("SELECT * FROM dispatch_order WHERE id = ? FOR UPDATE", this::map, id)
                .stream().findFirst();
    }

    /**
     * 查询基站所有未完成调度单（在途/发电中/发电异常），加行锁。
     */
    public List<DispatchOrder> findUnfinishedByStationForUpdate(String stationCode) {
        return jdbcTemplate.query(
                "SELECT * FROM dispatch_order WHERE station_code = ? AND status IN (?,?,?) FOR UPDATE",
                this::map, stationCode, OrderStatus.DISPATCHED.name(),
                OrderStatus.GENERATING.name(), OrderStatus.GENERATION_ABNORMAL.name());
    }

    /**
     * 发电确认：仅在途单可转为发电中，返回受影响行数（0 表示已被并发迁移）。
     */
    public int confirmGeneration(long id, Instant now) {
        return jdbcTemplate.update(
                "UPDATE dispatch_order SET status = ?, confirmed_at = ?, updated_at = ?"
                        + " WHERE id = ? AND status = ?",
                OrderStatus.GENERATING.name(), StationRepository.toOffsetDateTime(now),
                StationRepository.toOffsetDateTime(now), id, OrderStatus.DISPATCHED.name());
    }

    /**
     * 确认超时升级：在途单转发电异常并释放在役油机键，返回受影响行数。
     */
    public int escalateToAbnormal(long id, Instant now) {
        return jdbcTemplate.update(
                "UPDATE dispatch_order SET status = ?, generator_code = NULL, busy_generator_key = NULL,"
                        + " updated_at = ? WHERE id = ? AND status = ?",
                OrderStatus.GENERATION_ABNORMAL.name(), StationRepository.toOffsetDateTime(now),
                id, OrderStatus.DISPATCHED.name());
    }

    /**
     * 改派：发电异常单绑定新油机并回到在途，返回受影响行数。
     */
    public int reassign(long id, String newGeneratorCode, Instant now) {
        return jdbcTemplate.update(
                "UPDATE dispatch_order SET status = ?, generator_code = ?, busy_generator_key = ?,"
                        + " assigned_at = ?, updated_at = ? WHERE id = ? AND status = ?",
                OrderStatus.DISPATCHED.name(), newGeneratorCode, newGeneratorCode,
                StationRepository.toOffsetDateTime(now), StationRepository.toOffsetDateTime(now),
                id, OrderStatus.GENERATION_ABNORMAL.name());
    }

    /**
     * 市电恢复解除：未完成单转已解除并释放油机键，返回受影响行数。
     */
    public int release(long id, Instant now) {
        return jdbcTemplate.update(
                "UPDATE dispatch_order SET status = ?, generator_code = NULL, busy_generator_key = NULL,"
                        + " unfinished_key = NULL, updated_at = ?"
                        + " WHERE id = ? AND status IN (?,?,?)",
                OrderStatus.RELEASED.name(), StationRepository.toOffsetDateTime(now), id,
                OrderStatus.DISPATCHED.name(), OrderStatus.GENERATING.name(),
                OrderStatus.GENERATION_ABNORMAL.name());
    }

    /**
     * 结案：非终态单转结案并释放全部占用键，返回受影响行数（0 表示已是终态）。
     */
    public int close(long id, Instant now) {
        return jdbcTemplate.update(
                "UPDATE dispatch_order SET status = ?, generator_code = NULL, busy_generator_key = NULL,"
                        + " unfinished_key = NULL, closed_at = ?, updated_at = ?"
                        + " WHERE id = ? AND status IN (?,?,?,?)",
                OrderStatus.CLOSED.name(), StationRepository.toOffsetDateTime(now),
                StationRepository.toOffsetDateTime(now), id,
                OrderStatus.DISPATCHED.name(), OrderStatus.GENERATING.name(),
                OrderStatus.GENERATION_ABNORMAL.name(), OrderStatus.RELEASED.name());
    }

    private DispatchOrder map(ResultSet rs, int rowNum) throws SQLException {
        return new DispatchOrder(rs.getLong("id"), rs.getString("request_id"),
                rs.getString("station_code"), rs.getString("generator_code"),
                OrderStatus.valueOf(rs.getString("status")), rs.getBigDecimal("support_hours"),
                StationRepository.toInstant(rs, "deadline_at"),
                StationRepository.toInstant(rs, "assigned_at"),
                StationRepository.toInstant(rs, "confirmed_at"),
                StationRepository.toInstant(rs, "closed_at"),
                StationRepository.toInstant(rs, "created_at"),
                StationRepository.toInstant(rs, "updated_at"));
    }
}
