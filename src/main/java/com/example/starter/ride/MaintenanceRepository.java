package com.example.starter.ride;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 检修挂牌仓储：检修单、冻结人员清单、挂牌锁与双人签认。
 * 历史清单与签认记录只追加不覆盖；所有写操作由调用方持有设备行锁串行执行。
 */
@Repository
public class MaintenanceRepository {

    private static final RowMapper<MaintenanceOrder> ORDER_MAPPER = (rs, rowNum) -> mapOrder(rs);
    private static final RowMapper<MaintenanceLock> LOCK_MAPPER = (rs, rowNum) -> mapLock(rs);

    private final JdbcTemplate jdbc;

    public MaintenanceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static MaintenanceOrder mapOrder(ResultSet rs) throws SQLException {
        Timestamp startedAt = rs.getTimestamp("started_at");
        Timestamp finishedAt = rs.getTimestamp("finished_at");
        return new MaintenanceOrder(
                rs.getLong("id"),
                rs.getLong("ride_id"),
                MaintenanceStatus.valueOf(rs.getString("status")),
                rs.getLong("version"),
                startedAt == null ? null : startedAt.toInstant(),
                finishedAt == null ? null : finishedAt.toInstant());
    }

    private static MaintenanceLock mapLock(ResultSet rs) throws SQLException {
        Timestamp removedAt = rs.getTimestamp("removed_at");
        return new MaintenanceLock(
                rs.getLong("id"),
                rs.getLong("maintenance_id"),
                rs.getString("person_name"),
                rs.getBoolean("removed"),
                removedAt == null ? null : removedAt.toInstant());
    }

    /** 建档检修单（PREPARED，版本从1开始）并写入挂牌人员清单，返回检修单ID。 */
    public long insertOrder(long rideId, String requestId, List<String> personnel) {
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
        long maintenanceId = keyHolder.getKey().longValue();
        for (int i = 0; i < personnel.size(); i++) {
            jdbc.update("INSERT INTO maintenance_person (maintenance_id, person_name, seq)"
                    + " VALUES (?, ?, ?)", maintenanceId, personnel.get(i), i + 1);
        }
        return maintenanceId;
    }

    public Optional<MaintenanceOrder> findOrder(long rideId, long maintenanceId) {
        return jdbc.query("SELECT * FROM maintenance_order WHERE id = ? AND ride_id = ?",
                        ORDER_MAPPER, maintenanceId, rideId)
                .stream().findFirst();
    }

    /** 设备全部检修单，按建档顺序排列，历史清单不可覆盖。 */
    public List<MaintenanceOrder> listOrders(long rideId) {
        return jdbc.query("SELECT * FROM maintenance_order WHERE ride_id = ? ORDER BY id",
                ORDER_MAPPER, rideId);
    }

    /** 冻结后的挂牌人员清单，按清单序号排列。 */
    public List<String> listPersonnel(long maintenanceId) {
        return jdbc.queryForList(
                "SELECT person_name FROM maintenance_person WHERE maintenance_id = ? ORDER BY seq",
                String.class, maintenanceId);
    }

    /** 启动检修：为每名挂牌人员建立唯一未撤锁。 */
    public void insertLocks(long maintenanceId, List<String> personnel) {
        for (String person : personnel) {
            jdbc.update("INSERT INTO maintenance_lock (maintenance_id, person_name)"
                    + " VALUES (?, ?)", maintenanceId, person);
        }
    }

    public Optional<MaintenanceLock> findLock(long maintenanceId, String personName) {
        return jdbc.query("SELECT * FROM maintenance_lock"
                        + " WHERE maintenance_id = ? AND person_name = ?",
                        LOCK_MAPPER, maintenanceId, personName)
                .stream().findFirst();
    }

    /** 检修单全部挂牌锁，按建锁顺序排列。 */
    public List<MaintenanceLock> listLocks(long maintenanceId) {
        return jdbc.query("SELECT * FROM maintenance_lock WHERE maintenance_id = ? ORDER BY id",
                LOCK_MAPPER, maintenanceId);
    }

    /** 检修单未撤锁数量：为0方可结束检修。 */
    public int countUnremovedLocks(long maintenanceId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_lock"
                        + " WHERE maintenance_id = ? AND removed = FALSE",
                Integer.class, maintenanceId);
        return count == null ? 0 : count;
    }

    /** 撤锁：仅当锁仍未撤除时写入撤锁时刻（UTC），返回是否成功。 */
    public boolean markLockRemoved(long lockId, Instant removedAt) {
        int rows = jdbc.update(
                "UPDATE maintenance_lock SET removed = TRUE, removed_at = ?"
                        + " WHERE id = ? AND removed = FALSE",
                Timestamp.from(removedAt), lockId);
        return rows == 1;
    }

    /** 记录双人签认：本人与一名不同监护员，签认只追加不覆盖（每锁唯一）。 */
    public void insertRemoval(long lockId, String personName, String supervisorName,
            Instant removedAt) {
        jdbc.update("INSERT INTO maintenance_lock_removal"
                        + " (lock_id, person_name, supervisor_name, removed_at)"
                        + " VALUES (?, ?, ?, ?)",
                lockId, personName, supervisorName, Timestamp.from(removedAt));
    }

    /** 检修单全部双人签认记录，按签认顺序排列，可复算。 */
    public List<Map<String, Object>> listRemovals(long maintenanceId) {
        return jdbc.queryForList(
                "SELECT r.id, r.lock_id, r.person_name, r.supervisor_name, r.removed_at"
                        + " FROM maintenance_lock_removal r"
                        + " JOIN maintenance_lock l ON l.id = r.lock_id"
                        + " WHERE l.maintenance_id = ? ORDER BY r.id", maintenanceId);
    }

    /**
     * 检修单状态流转：仅当 expectedVersion 匹配时写入，版本加1；
     * 启动时记录启动时刻并占用设备（active_ride_id=rideId），结束时释放占用。
     */
    public boolean transition(long maintenanceId, long expectedVersion,
            MaintenanceStatus newStatus, Instant at) {
        String sql;
        if (newStatus == MaintenanceStatus.IN_PROGRESS) {
            sql = "UPDATE maintenance_order SET status = ?, version = version + 1,"
                    + " started_at = ?, active_ride_id = ride_id"
                    + " WHERE id = ? AND version = ?";
        } else if (newStatus == MaintenanceStatus.FINISHED) {
            sql = "UPDATE maintenance_order SET status = ?, version = version + 1,"
                    + " finished_at = ?, active_ride_id = NULL"
                    + " WHERE id = ? AND version = ?";
        } else {
            throw new IllegalArgumentException("不支持的目标状态：" + newStatus);
        }
        int rows = jdbc.update(sql, newStatus.name(), Timestamp.from(at),
                maintenanceId, expectedVersion);
        return rows == 1;
    }

    /** 单据版本递增（撤锁等不改变状态的修改）：仅当 expectedVersion 匹配时写入。 */
    public boolean bumpVersion(long maintenanceId, long expectedVersion) {
        int rows = jdbc.update(
                "UPDATE maintenance_order SET version = version + 1"
                        + " WHERE id = ? AND version = ?",
                maintenanceId, expectedVersion);
        return rows == 1;
    }

    /** 设备是否存在未结束（PREPARED或进行中）检修单：开放/复开入口必须拒绝。 */    public boolean existsUnfinished(long rideId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_order"
                        + " WHERE ride_id = ? AND status <> 'FINISHED'",
                Integer.class, rideId);
        return count != null && count > 0;
    }

    /** 设备是否存在进行中检修单：同设备只允许一张，占用时拒绝启动新检修。 */
    public boolean existsInProgress(long rideId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_order"
                        + " WHERE ride_id = ? AND status = 'IN_PROGRESS'",
                Integer.class, rideId);
        return count != null && count > 0;
    }

    /** 设备是否存在未撤锁：开放/复开入口必须逐人核对。 */
    public boolean existsUnremovedLock(long rideId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_lock l"
                        + " JOIN maintenance_order m ON m.id = l.maintenance_id"
                        + " WHERE m.ride_id = ? AND l.removed = FALSE",
                Integer.class, rideId);
        return count != null && count > 0;
    }
}
