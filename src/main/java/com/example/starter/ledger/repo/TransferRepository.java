package com.example.starter.ledger.repo;

import com.example.starter.ledger.po.TransferOrderPO;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 站间调拨单数据访问。
 */
@Repository
public class TransferRepository {

    private static final String COLUMNS = "id, order_no, battery_code, from_station, to_station, status, "
            + "dispatched_at, received_at, exception_at, written_off_at, writeoff_reason, version";

    private final JdbcTemplate jdbcTemplate;

    public TransferRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<TransferOrderPO> MAPPER = new TransferRowMapper();

    /** 新建调拨单。 */
    public void insert(TransferOrderPO order) {
        jdbcTemplate.update(
                "INSERT INTO transfer_order (order_no, battery_code, from_station, to_station, status, "
                        + "dispatched_at, received_at, exception_at, written_off_at, writeoff_reason, version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                order.getOrderNo(), order.getBatteryCode(), order.getFromStation(), order.getToStation(),
                order.getStatus(), Timestamp.from(order.getDispatchedAt()),
                order.getReceivedAt() == null ? null : Timestamp.from(order.getReceivedAt()),
                order.getExceptionAt() == null ? null : Timestamp.from(order.getExceptionAt()),
                order.getWrittenOffAt() == null ? null : Timestamp.from(order.getWrittenOffAt()),
                order.getWriteoffReason(), order.getVersion());
    }

    /** 按单号查询，不存在返回 null。 */
    public TransferOrderPO findByOrderNo(String orderNo) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT " + COLUMNS + " FROM transfer_order WHERE order_no = ?", MAPPER, orderNo);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 行锁查询，须在事务内调用。 */
    public TransferOrderPO lockByOrderNo(String orderNo) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT " + COLUMNS + " FROM transfer_order WHERE order_no = ? FOR UPDATE", MAPPER, orderNo);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /**
     * 乐观锁 + 期望状态更新。
     *
     * @return 影响行数；0 表示状态/版本已变化（重复签收、重复核销等）
     */
    public int compareAndUpdate(TransferOrderPO order, String expectedStatus) {
        return jdbcTemplate.update(
                "UPDATE transfer_order SET status = ?, received_at = ?, exception_at = ?, written_off_at = ?, "
                        + "writeoff_reason = ?, version = version + 1 WHERE order_no = ? AND status = ? "
                        + "AND version = ?",
                order.getStatus(),
                order.getReceivedAt() == null ? null : Timestamp.from(order.getReceivedAt()),
                order.getExceptionAt() == null ? null : Timestamp.from(order.getExceptionAt()),
                order.getWrittenOffAt() == null ? null : Timestamp.from(order.getWrittenOffAt()),
                order.getWriteoffReason(), order.getOrderNo(), expectedStatus, order.getVersion());
    }

    /** 查询电池当前未终态（在途/异常）的调拨单；无则返回 null。 */
    public TransferOrderPO findOpenByBattery(String batteryCode) {
        List<TransferOrderPO> list = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM transfer_order WHERE battery_code = ? "
                        + "AND status IN ('DISPATCHED', 'EXCEPTION') ORDER BY id DESC LIMIT 1",
                MAPPER, batteryCode);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 行锁查询电池未终态调拨单，须在事务内调用。 */
    public TransferOrderPO lockOpenByBattery(String batteryCode) {
        List<TransferOrderPO> list = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM transfer_order WHERE battery_code = ? "
                        + "AND status IN ('DISPATCHED', 'EXCEPTION') ORDER BY id DESC LIMIT 1 FOR UPDATE",
                MAPPER, batteryCode);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 查询全部在途（DISPATCHED）调拨单（无锁快照，调用方随后按统一顺序加锁）。 */
    public List<TransferOrderPO> findDispatched() {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM transfer_order WHERE status = 'DISPATCHED' ORDER BY id ASC",
                MAPPER);
    }

    /** 查询全部在途（DISPATCHED）调拨单，行锁。 */
    public List<TransferOrderPO> lockDispatched() {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM transfer_order WHERE status = 'DISPATCHED' ORDER BY id ASC FOR UPDATE",
                MAPPER);
    }

    private static final class TransferRowMapper implements RowMapper<TransferOrderPO> {
        @Override
        public TransferOrderPO mapRow(ResultSet rs, int rowNum) throws SQLException {
            TransferOrderPO po = new TransferOrderPO();
            po.setId(rs.getLong("id"));
            po.setOrderNo(rs.getString("order_no"));
            po.setBatteryCode(rs.getString("battery_code"));
            po.setFromStation(rs.getString("from_station"));
            po.setToStation(rs.getString("to_station"));
            po.setStatus(rs.getString("status"));
            Timestamp dispatched = rs.getTimestamp("dispatched_at");
            Timestamp received = rs.getTimestamp("received_at");
            Timestamp exception = rs.getTimestamp("exception_at");
            Timestamp writtenOff = rs.getTimestamp("written_off_at");
            po.setDispatchedAt(dispatched == null ? null : dispatched.toInstant());
            po.setReceivedAt(received == null ? null : received.toInstant());
            po.setExceptionAt(exception == null ? null : exception.toInstant());
            po.setWrittenOffAt(writtenOff == null ? null : writtenOff.toInstant());
            po.setWriteoffReason(rs.getString("writeoff_reason"));
            po.setVersion(rs.getLong("version"));
            return po;
        }
    }
}
