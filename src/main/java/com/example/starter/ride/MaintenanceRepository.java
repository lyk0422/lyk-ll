package com.example.starter.ride;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 检修单、挂牌人员清单与挂牌锁仓储（H2，MySQL 兼容模式）。
 * 挂牌清单启动后冻结，挂牌锁每人唯一、双人签认一次性撤除，历史记录只追加不覆盖。
 */
@Repository
public class MaintenanceRepository {

    private static final RowMapper<MaintenanceOrder> ORDER_MAPPER =
            (rs, rowNum) -> mapOrder(rs);

    private static final RowMapper<MaintenanceLock> LOCK_MAPPER =
            (rs, rowNum) -> mapLock(rs);

    private final JdbcTemplate jdbc;

    public MaintenanceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static MaintenanceOrder mapOrder(ResultSet rs) throws SQLException {
        String priorStatusText = rs.getString("prior_status");
        Timestamp startedAt = rs.getTimestamp("started_at");
        Timestamp finishedAt = rs.getTimestamp("finished_at");
        return new MaintenanceOrder(
                rs.getLong("id"),
                rs.getLong("ride_id"),
                rs.getString("request_id"),
                MaintenanceStatus.valueOf(rs.getString("status")),
                priorStatusText == null ? null : RideStatus.valueOf(priorStatusText),
                rs.getLong("version"),
                startedAt == null ? null : startedAt.toInstant(),
                rs.getString("start_request_id"),
                finishedAt == null ? null : finishedAt.toInstant(),
                rs.getString("finish_request_id"));
    }

    private static MaintenanceLock mapLock(ResultSet rs) throws SQLException {
        Timestamp removedAt = rs.getTimestamp("removed_at");
        return new MaintenanceLock(
                rs.getLong("id"),
                rs.getLong("order_id"),
                rs.getLong("ride_id"),
                rs.getString("person_name"),
                rs.getBoolean("removed"),
                removedAt == null ? null : removedAt.toInstant(),
                rs.getString("lock_holder"),
                rs.getString("guardian"),
                rs.getString("remove_request_id"));
    }

    /** 建立检修单（PREPARED，版本从1开始），返回检修单ID。 */
    public long insertOrder(long rideId, String requestId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO maintenance_order (ride_id, request_id, status)"
                            + " VALUES (?, ?, ?)",
                    new String[]{"id"});
            ps.setLong(1, rideId);
            ps.setString(2, requestId);
            ps.setString(3, MaintenanceStatus.PREPARED.name());
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 追加一名挂牌人员（同单唯一约束由数据库兜底）。 */
    public void insertPerson(long orderId, String personName, int seq) {
        jdbc.update("INSERT INTO maintenance_person (order_id, person_name, seq)"
                + " VALUES (?, ?, ?)", orderId, personName, seq);
    }

    public Optional<MaintenanceOrder> findOrderById(long orderId) {
        return jdbc.query("SELECT * FROM maintenance_order WHERE id = ?",
                ORDER_MAPPER, orderId).stream().findFirst();
    }

    /** 设备是否存在进行中检修单。 */
    public boolean existsActiveByRide(long rideId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_order WHERE ride_id = ? AND status = ?",
                Integer.class, rideId, MaintenanceStatus.IN_PROGRESS.name());
        return count != null && count > 0;
    }

    /** 设备当前进行中检修单（不存在返回空）。 */
    public Optional<MaintenanceOrder> findActiveByRide(long rideId) {
        return jdbc.query("SELECT * FROM maintenance_order WHERE ride_id = ? AND status = ?",
                ORDER_MAPPER, rideId, MaintenanceStatus.IN_PROGRESS.name())
                .stream().findFirst();
    }

    /** 设备未撤除挂牌锁数量（进行中检修单口径）。 */
    public int countOpenLocksByRide(long rideId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_lock l"
                        + " JOIN maintenance_order o ON o.id = l.order_id"
                        + " WHERE l.ride_id = ? AND o.status = ? AND l.removed = FALSE",
                Integer.class, rideId, MaintenanceStatus.IN_PROGRESS.name());
        return count == null ? 0 : count;
    }

    /**
     * 启动检修：状态 IN_PROGRESS、记录原状态与启动时刻，仅当 expectedVersion 匹配
     * 且单据仍为 PREPARED 时写入，返回是否成功（版本不符或已启动返回 false）。
     */
    public boolean markStarted(long orderId, long expectedVersion, RideStatus priorStatus,
            Instant startedAt, String startRequestId) {
        int rows = jdbc.update(
                "UPDATE maintenance_order SET status = ?, prior_status = ?, version = version + 1,"
                        + " started_at = ?, start_request_id = ?"
                        + " WHERE id = ? AND version = ? AND status = ?",
                MaintenanceStatus.IN_PROGRESS.name(), priorStatus.name(),
                Timestamp.from(startedAt), startRequestId,
                orderId, expectedVersion, MaintenanceStatus.PREPARED.name());
        return rows == 1;
    }

    /** 为挂牌人员建立唯一未撤锁。 */
    public void insertLock(long orderId, long rideId, String personName) {
        jdbc.update("INSERT INTO maintenance_lock (order_id, ride_id, person_name)"
                + " VALUES (?, ?, ?)", orderId, rideId, personName);
    }

    /** 检修单全部挂牌锁（按清单序号）。 */
    public List<MaintenanceLock> listLocksByOrder(long orderId) {
        return jdbc.query(
                "SELECT l.* FROM maintenance_lock l"
                        + " JOIN maintenance_person p ON p.order_id = l.order_id"
                        + " AND p.person_name = l.person_name"
                        + " WHERE l.order_id = ? ORDER BY p.seq",
                LOCK_MAPPER, orderId);
    }

    /** 检修单内指定挂牌人员的锁。 */
    public Optional<MaintenanceLock> findLock(long orderId, String personName) {
        return jdbc.query("SELECT * FROM maintenance_lock WHERE order_id = ? AND person_name = ?",
                LOCK_MAPPER, orderId, personName).stream().findFirst();
    }

    /**
     * 双人签认撤锁：仅未撤锁可更新，一次性写入本人与监护员签认；
     * 返回是否成功（已撤或并发重复撤返回 false），历史签认不可覆盖。
     */
    public boolean removeLock(long lockId, Instant removedAt, String lockHolder,
            String guardian, String removeRequestId) {
        int rows = jdbc.update(
                "UPDATE maintenance_lock SET removed = TRUE, removed_at = ?,"
                        + " lock_holder = ?, guardian = ?, remove_request_id = ?"
                        + " WHERE id = ? AND removed = FALSE",
                Timestamp.from(removedAt), lockHolder, guardian, removeRequestId, lockId);
        return rows == 1;
    }

    /**
     * 结束检修：状态 FINISHED、记录结束时刻，仅当 expectedVersion 匹配且仍为
     * IN_PROGRESS 时写入，返回是否成功（版本不符或并发已结束返回 false）。
     */
    public boolean markFinished(long orderId, long expectedVersion, Instant finishedAt,
            String finishRequestId) {
        int rows = jdbc.update(
                "UPDATE maintenance_order SET status = ?, version = version + 1,"
                        + " finished_at = ?, finish_request_id = ?"
                        + " WHERE id = ? AND version = ? AND status = ?",
                MaintenanceStatus.FINISHED.name(), Timestamp.from(finishedAt), finishRequestId,
                orderId, expectedVersion, MaintenanceStatus.IN_PROGRESS.name());
        return rows == 1;
    }

    /** 检修单冻结的挂牌人员清单（按清单序号）。 */
    public List<String> listPersonsByOrder(long orderId) {
        return jdbc.queryForList(
                "SELECT person_name FROM maintenance_person WHERE order_id = ? ORDER BY seq",
                String.class, orderId);
    }

    /** 设备全部检修单（按建单顺序），可复算。 */
    public List<MaintenanceOrder> listOrdersByRide(long rideId) {
        return jdbc.query("SELECT * FROM maintenance_order WHERE ride_id = ? ORDER BY id",
                ORDER_MAPPER, rideId);
    }
}
