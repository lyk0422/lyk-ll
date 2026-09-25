package com.example.starter.deicing.repository;

import com.example.starter.deicing.model.DeiceType;
import com.example.starter.deicing.model.QueueStatus;
import com.example.starter.deicing.po.QueueRequestPO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

/**
 * 排队申请与“进行中航班唯一登记”仓储。
 */
@Repository
public class QueueRepository {

    private static final RowMapper<QueueRequestPO> MAPPER = (rs, rowNum) -> {
        QueueRequestPO po = new QueueRequestPO();
        po.setRequestId(rs.getString("request_id"));
        po.setSeq(rs.getLong("seq"));
        po.setFlightNo(rs.getString("flight_no"));
        po.setStand(rs.getString("stand"));
        po.setDeiceType(DeiceType.valueOf(rs.getString("deice_type")));
        po.setRequiredBays(rs.getInt("required_bays"));
        po.setTargetDepartureTs(rs.getLong("target_departure_ts"));
        po.setExpectedDepartureTs(rs.getLong("expected_departure_ts"));
        po.setDeadlineTs(rs.getLong("deadline_ts"));
        po.setStatus(QueueStatus.valueOf(rs.getString("status")));
        po.setActualStartTs(getNullableLong(rs, "actual_start_ts"));
        po.setActualFinishTs(getNullableLong(rs, "actual_finish_ts"));
        po.setHotExpiresTs(getNullableLong(rs, "hot_expires_ts"));
        po.setEarliestReschedTs(getNullableLong(rs, "earliest_resched_ts"));
        po.setCancelReason(rs.getString("cancel_reason"));
        po.setRoundNo(rs.getInt("round_no"));
        po.setCreatedTs(rs.getLong("created_ts"));
        po.setUpdatedTs(rs.getLong("updated_ts"));
        return po;
    };

    private static Long getNullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate JDBC 模板
     */
    public QueueRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 新增排队申请，自增申请序号写回 PO。
     *
     * @param po 申请记录（seq 字段由数据库生成）
     */
    public void insert(QueueRequestPO po) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO queue_request (request_id, flight_no, stand, deice_type, required_bays, "
                            + "target_departure_ts, expected_departure_ts, deadline_ts, status, "
                            + "actual_start_ts, actual_finish_ts, hot_expires_ts, earliest_resched_ts, "
                            + "cancel_reason, round_no, created_ts, updated_ts) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    new String[] {"seq"});
            ps.setString(1, po.getRequestId());
            ps.setString(2, po.getFlightNo());
            ps.setString(3, po.getStand());
            ps.setString(4, po.getDeiceType().name());
            ps.setInt(5, po.getRequiredBays());
            ps.setLong(6, po.getTargetDepartureTs());
            ps.setLong(7, po.getExpectedDepartureTs());
            ps.setLong(8, po.getDeadlineTs());
            ps.setString(9, po.getStatus().name());
            setNullableLong(ps, 10, po.getActualStartTs());
            setNullableLong(ps, 11, po.getActualFinishTs());
            setNullableLong(ps, 12, po.getHotExpiresTs());
            setNullableLong(ps, 13, po.getEarliestReschedTs());
            ps.setString(14, po.getCancelReason());
            ps.setInt(15, po.getRoundNo());
            ps.setLong(16, po.getCreatedTs());
            ps.setLong(17, po.getUpdatedTs());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key != null) {
            po.setSeq(key.longValue());
        }
    }

    private static void setNullableLong(PreparedStatement ps, int index, Long value) throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }

    /**
     * @param requestId 申请号
     * @return 申请记录；不存在返回 null
     */
    public QueueRequestPO findByRequestId(String requestId) {
        List<QueueRequestPO> rows = jdbcTemplate.query(
                "SELECT * FROM queue_request WHERE request_id = ?", MAPPER, requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * @return 全部记录（按申请序号升序）
     */
    public List<QueueRequestPO> findAll() {
        return jdbcTemplate.query("SELECT * FROM queue_request ORDER BY seq", MAPPER);
    }

    /**
     * 按当前内存对象全量更新一行。
     *
     * @param po 申请记录
     */
    public void save(QueueRequestPO po) {
        int affected = jdbcTemplate.update(
                "UPDATE queue_request SET flight_no = ?, stand = ?, deice_type = ?, required_bays = ?, "
                        + "target_departure_ts = ?, expected_departure_ts = ?, deadline_ts = ?, status = ?, "
                        + "actual_start_ts = ?, actual_finish_ts = ?, hot_expires_ts = ?, earliest_resched_ts = ?, "
                        + "cancel_reason = ?, round_no = ?, updated_ts = ? WHERE request_id = ?",
                po.getFlightNo(), po.getStand(), po.getDeiceType().name(), po.getRequiredBays(),
                po.getTargetDepartureTs(), po.getExpectedDepartureTs(), po.getDeadlineTs(),
                po.getStatus().name(), po.getActualStartTs(), po.getActualFinishTs(),
                po.getHotExpiresTs(), po.getEarliestReschedTs(), po.getCancelReason(),
                po.getRoundNo(), po.getUpdatedTs(), po.getRequestId());
        if (affected == 0) {
            throw new IllegalStateException("排队记录已不存在: " + po.getRequestId());
        }
    }

    /**
     * 登记进行中航班（flight_no 唯一约束）；并发下仅一个线程成功。
     *
     * @param flightNo  航班号
     * @param requestId 申请号
     * @param nowTs     登记时刻
     * @return 是否登记成功；该航班已有进行中记录返回 false
     */
    public boolean registerFlightActive(String flightNo, String requestId, long nowTs) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO flight_active (flight_no, request_id, created_ts) VALUES (?, ?, ?)",
                    flightNo, requestId, nowTs);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /**
     * 解除进行中航班登记。
     *
     * @param flightNo 航班号
     */
    public void unregisterFlightActive(String flightNo) {
        jdbcTemplate.update("DELETE FROM flight_active WHERE flight_no = ?", flightNo);
    }

    /**
     * @param flightNo 航班号
     * @return 进行中登记对应的申请号；未登记返回空
     */
    public Optional<String> findActiveRequestId(String flightNo) {
        List<String> rows = jdbcTemplate.queryForList(
                "SELECT request_id FROM flight_active WHERE flight_no = ?", String.class, flightNo);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
}
