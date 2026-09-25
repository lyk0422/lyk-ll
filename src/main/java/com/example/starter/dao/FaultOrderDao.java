package com.example.starter.dao;

import com.example.starter.domain.Grade;
import com.example.starter.domain.OrderStatus;
import com.example.starter.po.FaultOrder;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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
 * 故障工单访问。
 */
@Repository
public class FaultOrderDao {

    private static final String COLUMNS = "id, order_no, feeder_id, fault_point, description, affected_users,"
            + " report_time, outage_time, status, user_grade, important_user_id, crew_id, wait_crew_id,"
            + " assigned_time, repair_start_time, restored_time, sealed_time,"
            + " merged_into_id, merged_into_no, create_time, update_time";

    private final JdbcTemplate jdbc;

    public FaultOrderDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(FaultOrder o) {
        Instant now = Instant.now();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO fault_order (order_no, feeder_id, fault_point, description, affected_users,"
                            + " report_time, outage_time, status, user_grade, important_user_id, crew_id, wait_crew_id,"
                            + " assigned_time, repair_start_time, restored_time, sealed_time,"
                            + " merged_into_id, merged_into_no, create_time, update_time)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            int i = 1;
            ps.setString(i++, o.getOrderNo());
            ps.setObject(i++, o.getFeederId());
            ps.setString(i++, o.getFaultPoint());
            ps.setString(i++, o.getDescription());
            ps.setInt(i++, o.getAffectedUsers());
            ps.setTimestamp(i++, ts(o.getReportTime()));
            ps.setTimestamp(i++, ts(o.getOutageTime()));
            ps.setString(i++, o.getStatus().name());
            ps.setString(i++, o.getUserGrade() == null ? null : o.getUserGrade().name());
            ps.setObject(i++, o.getImportantUserId());
            ps.setObject(i++, o.getCrewId());
            ps.setObject(i++, o.getWaitCrewId());
            ps.setTimestamp(i++, ts(o.getAssignedTime()));
            ps.setTimestamp(i++, ts(o.getRepairStartTime()));
            ps.setTimestamp(i++, ts(o.getRestoredTime()));
            ps.setTimestamp(i++, ts(o.getSealedTime()));
            ps.setObject(i++, o.getMergedIntoId());
            ps.setString(i++, o.getMergedIntoNo());
            ps.setTimestamp(i++, ts(now));
            ps.setTimestamp(i, ts(now));
            return ps;
        }, keyHolder);
        Long id = keyHolder.getKeyAs(Long.class);
        if (id == null) {
            throw new IllegalStateException("插入工单未取得自增主键");
        }
        o.setId(id);
        o.setCreateTime(now);
        o.setUpdateTime(now);
        return id;
    }

    public Optional<FaultOrder> findByOrderNo(String orderNo) {
        List<FaultOrder> list = jdbc.query(
                "SELECT " + COLUMNS + " FROM fault_order WHERE order_no = ?",
                new OrderRowMapper(), orderNo);
        return list.stream().findFirst();
    }

    public FaultOrder getByOrderNo(String orderNo) {
        return findByOrderNo(orderNo)
                .orElseThrow(() -> new IllegalArgumentException("工单不存在: " + orderNo));
    }

    /**
     * 按主键查工单号。
     */
    public String findOrderNoById(long id) {
        List<String> nos = jdbc.queryForList(
                "SELECT order_no FROM fault_order WHERE id = ?", String.class, id);
        return nos.stream().findFirst().orElse(null);
    }

    /**
     * 按主键查工单。
     */
    public Optional<FaultOrder> findById(long id) {
        List<FaultOrder> list = jdbc.query(
                "SELECT " + COLUMNS + " FROM fault_order WHERE id = ?", new OrderRowMapper(), id);
        return list.stream().findFirst();
    }

    /**
     * 同故障点在 [windowStart, windowEnd] 时段内、仍在处置中的工单（接报去重归并用）；
     * 已复电/封存/合并的故障点视为新故障，不参与归并。
     */
    public List<FaultOrder> findSamePointInWindow(String faultPoint, Instant windowStart, Instant windowEnd) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM fault_order"
                        + " WHERE fault_point = ? AND report_time >= ? AND report_time <= ?"
                        + " AND status NOT IN ('SEALED', 'MERGED', 'RESTORED') ORDER BY report_time, id",
                new OrderRowMapper(), faultPoint, ts(windowStart), ts(windowEnd));
    }

    /**
     * 某班组挂单等待队列，按派单优先级复算排序：
     * 保电等级 → 同馈线影响户数降序 → 同数接报先后。
     */
    public List<FaultOrder> findWaitingByCrew(long crewId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM fault_order WHERE status = 'WAITING' AND wait_crew_id = ?"
                        + " ORDER BY CASE COALESCE(user_grade, 'NORMAL')"
                        + " WHEN 'SPECIAL' THEN 0 WHEN 'FIRST' THEN 1 WHEN 'SECOND' THEN 2 ELSE 3 END,"
                        + " feeder_id, affected_users DESC, report_time, id",
                new OrderRowMapper(), crewId);
    }

    /**
     * 已研判命中特级、仍在途（未复电/封存/合并）且尚无发电车流程的工单候选。
     */
    public List<FaultOrder> findSpecialActiveOrders() {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM fault_order"
                        + " WHERE user_grade = 'SPECIAL' AND status IN ('ASSESSED', 'WAITING', 'DISPATCHED', 'REPAIRING')"
                        + " ORDER BY outage_time, id",
                new OrderRowMapper());
    }

    /**
     * 馈线上仍在处置中的工单数（用于复电后判断馈线是否恢复正常）。
     */
    public int countActiveByFeeder(long feederId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fault_order WHERE feeder_id = ?"
                        + " AND status IN ('REPORTED', 'ASSESSED', 'WAITING', 'DISPATCHED', 'REPAIRING')",
                Integer.class, feederId);
        return n == null ? 0 : n;
    }

    /**
     * 取工单业务序号下一个值。
     */
    public long nextOrderSequence() {
        return jdbc.queryForObject("SELECT NEXTVAL('seq_order_no')", Long.class);
    }

    public void markAssessed(long orderId, Grade grade, Long importantUserId) {
        jdbc.update(
                "UPDATE fault_order SET status = 'ASSESSED', user_grade = ?, important_user_id = ?, update_time = ?"
                        + " WHERE id = ?",
                grade.name(), importantUserId, ts(Instant.now()), orderId);
    }

    public void markWaiting(long orderId, long crewId) {
        jdbc.update(
                "UPDATE fault_order SET status = 'WAITING', wait_crew_id = ?, update_time = ? WHERE id = ?",
                crewId, ts(Instant.now()), orderId);
    }

    public void markDispatched(long orderId, long crewId, Instant assignedTime) {
        jdbc.update(
                "UPDATE fault_order SET status = 'DISPATCHED', crew_id = ?, assigned_time = ?,"
                        + " wait_crew_id = NULL, update_time = ? WHERE id = ?",
                crewId, ts(assignedTime), ts(Instant.now()), orderId);
    }

    public void markRepairing(long orderId, Instant startTime) {
        jdbc.update(
                "UPDATE fault_order SET status = 'REPAIRING', repair_start_time = ?, update_time = ? WHERE id = ?",
                ts(startTime), ts(Instant.now()), orderId);
    }

    public void markRestored(long orderId, Instant restoredTime) {
        jdbc.update(
                "UPDATE fault_order SET status = 'RESTORED', restored_time = ?, update_time = ? WHERE id = ?",
                ts(restoredTime), ts(Instant.now()), orderId);
    }

    public void markSealed(long orderId, Instant sealedTime) {
        jdbc.update(
                "UPDATE fault_order SET status = 'SEALED', sealed_time = ?, update_time = ? WHERE id = ?",
                ts(sealedTime), ts(Instant.now()), orderId);
    }

    public void markMerged(long orderId, long targetId, String targetNo) {
        jdbc.update(
                "UPDATE fault_order SET status = 'MERGED', merged_into_id = ?, merged_into_no = ?, update_time = ?"
                        + " WHERE id = ?",
                targetId, targetNo, ts(Instant.now()), orderId);
    }

    /**
     * 派单队列复算：已研判但尚未派单（ASSESSED/WAITING），
     * 按馈线内影响用户数降序、同数按接报先后，重要用户等级高者整体优先。
     */
    public List<QueueRow> findDispatchQueue() {
        return jdbc.query(
                "SELECT id, order_no, feeder_id, affected_users, report_time, status, wait_crew_id,"
                        + " COALESCE(user_grade, 'NORMAL') AS grade"
                        + " FROM fault_order WHERE status IN ('ASSESSED', 'WAITING')"
                        + " ORDER BY CASE COALESCE(user_grade, 'NORMAL')"
                        + " WHEN 'SPECIAL' THEN 0 WHEN 'FIRST' THEN 1 WHEN 'SECOND' THEN 2 ELSE 3 END,"
                        + " feeder_id, affected_users DESC, report_time, id",
                (rs, n) -> new QueueRow(
                        rs.getLong("id"),
                        rs.getString("order_no"),
                        (Long) rs.getObject("feeder_id"),
                        rs.getInt("affected_users"),
                        rs.getTimestamp("report_time").toInstant(),
                        OrderStatus.valueOf(rs.getString("status")),
                        (Long) rs.getObject("wait_crew_id"),
                        Grade.valueOf(rs.getString("grade"))));
    }

    /**
     * 派单队列行。
     */
    public record QueueRow(long id, String orderNo, Long feederId, int affectedUsers,
                          Instant reportTime, OrderStatus status, Long waitCrewId, Grade grade) {
    }

    private static Timestamp ts(Instant t) {
        return t == null ? null : Timestamp.from(t);
    }

    private static class OrderRowMapper implements RowMapper<FaultOrder> {
        @Override
        public FaultOrder mapRow(ResultSet rs, int rowNum) throws SQLException {
            FaultOrder o = new FaultOrder();
            o.setId(rs.getLong("id"));
            o.setOrderNo(rs.getString("order_no"));
            o.setFeederId((Long) rs.getObject("feeder_id"));
            o.setFaultPoint(rs.getString("fault_point"));
            o.setDescription(rs.getString("description"));
            o.setAffectedUsers(rs.getInt("affected_users"));
            o.setReportTime(rs.getTimestamp("report_time").toInstant());
            Timestamp outage = rs.getTimestamp("outage_time");
            o.setOutageTime(outage == null ? null : outage.toInstant());
            o.setStatus(OrderStatus.valueOf(rs.getString("status")));
            String grade = rs.getString("user_grade");
            o.setUserGrade(grade == null ? null : Grade.valueOf(grade));
            o.setImportantUserId((Long) rs.getObject("important_user_id"));
            o.setCrewId((Long) rs.getObject("crew_id"));
            o.setWaitCrewId((Long) rs.getObject("wait_crew_id"));
            Timestamp assigned = rs.getTimestamp("assigned_time");
            o.setAssignedTime(assigned == null ? null : assigned.toInstant());
            Timestamp repair = rs.getTimestamp("repair_start_time");
            o.setRepairStartTime(repair == null ? null : repair.toInstant());
            Timestamp restored = rs.getTimestamp("restored_time");
            o.setRestoredTime(restored == null ? null : restored.toInstant());
            Timestamp sealed = rs.getTimestamp("sealed_time");
            o.setSealedTime(sealed == null ? null : sealed.toInstant());
            o.setMergedIntoId((Long) rs.getObject("merged_into_id"));
            o.setMergedIntoNo(rs.getString("merged_into_no"));
            Timestamp create = rs.getTimestamp("create_time");
            o.setCreateTime(create == null ? null : create.toInstant());
            Timestamp update = rs.getTimestamp("update_time");
            o.setUpdateTime(update == null ? null : update.toInstant());
            return o;
        }
    }
}
