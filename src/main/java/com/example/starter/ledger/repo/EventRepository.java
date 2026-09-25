package com.example.starter.ledger.repo;

import com.example.starter.ledger.po.LedgerEventPO;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 流转事件哈希链表数据访问。
 */
@Repository
public class EventRepository {

    private static final String COLUMNS =
            "id, battery_code, seq, event_type, prev_hash, event_hash, payload, request_id, occurred_at";

    private final JdbcTemplate jdbcTemplate;

    public EventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<LedgerEventPO> MAPPER = new EventRowMapper();

    /** 追加事件；(battery_code, seq) 唯一冲突会抛异常（链并发追加由电池行锁串行化）。 */
    public void insert(LedgerEventPO event) {
        jdbcTemplate.update(
                "INSERT INTO ledger_event (battery_code, seq, event_type, prev_hash, event_hash, payload, "
                        + "request_id, occurred_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                event.getBatteryCode(), event.getSeq(), event.getEventType(), event.getPrevHash(),
                event.getEventHash(), event.getPayload(), event.getRequestId(),
                Timestamp.from(event.getOccurredAt()));
    }

    /** 查询电池当前最大链序号；无事件返回 0。 */
    public long maxSeq(String batteryCode) {
        Long max = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(seq), 0) FROM ledger_event WHERE battery_code = ?",
                Long.class, batteryCode);
        return max == null ? 0L : max;
    }

    /** 按链序号稳定升序查询电池全部事件。 */
    public List<LedgerEventPO> findByBattery(String batteryCode) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM ledger_event WHERE battery_code = ? ORDER BY seq ASC",
                MAPPER, batteryCode);
    }

    /** 按事件哈希查询（篡改检测比对用），不存在返回 null。 */
    public LedgerEventPO findByHash(String eventHash) {
        List<LedgerEventPO> list = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM ledger_event WHERE event_hash = ?", MAPPER, eventHash);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 直接改写事件载荷（仅供测试模拟篡改，业务代码严禁调用）。 */
    public void tamperPayloadForTest(String eventHash, String payload) {
        jdbcTemplate.update("UPDATE ledger_event SET payload = ? WHERE event_hash = ?", payload, eventHash);
    }

    private static final class EventRowMapper implements RowMapper<LedgerEventPO> {
        @Override
        public LedgerEventPO mapRow(ResultSet rs, int rowNum) throws SQLException {
            LedgerEventPO po = new LedgerEventPO();
            po.setId(rs.getLong("id"));
            po.setBatteryCode(rs.getString("battery_code"));
            po.setSeq(rs.getLong("seq"));
            po.setEventType(rs.getString("event_type"));
            po.setPrevHash(rs.getString("prev_hash"));
            po.setEventHash(rs.getString("event_hash"));
            po.setPayload(rs.getString("payload"));
            po.setRequestId(rs.getString("request_id"));
            Timestamp occurred = rs.getTimestamp("occurred_at");
            po.setOccurredAt(occurred == null ? null : occurred.toInstant());
            return po;
        }
    }
}
