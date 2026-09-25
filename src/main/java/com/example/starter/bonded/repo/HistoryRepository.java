package com.example.starter.bonded.repo;

import com.example.starter.bonded.domain.HistoryPo;
import com.example.starter.bonded.domain.TransferStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 状态变更留痕数据访问，全部状态变更按序追加，只增不改。
 */
@Repository
public class HistoryRepository {

    private static final RowMapper<HistoryPo> MAPPER = (rs, n) -> {
        String from = rs.getString("from_status");
        String to = rs.getString("to_status");
        return new HistoryPo(
                rs.getLong("id"),
                rs.getLong("order_id"),
                from == null ? null : TransferStatus.valueOf(from),
                TransferStatus.valueOf(to),
                rs.getTimestamp("changed_at").toInstant(),
                rs.getString("note"));
    };

    private final JdbcTemplate jdbc;

    public HistoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条留痕。
     */
    public void append(Long orderId, TransferStatus from, TransferStatus to, Instant at, String note) {
        jdbc.update("INSERT INTO status_history (order_id, from_status, to_status, changed_at, note) VALUES (?,?,?,?,?)",
                orderId,
                from == null ? null : from.name(),
                to.name(),
                Timestamp.from(at),
                note);
    }

    /**
     * 按时间与主键升序查询单据全部留痕。
     */
    public List<HistoryPo> findByOrderId(Long orderId) {
        return jdbc.query(
                "SELECT * FROM status_history WHERE order_id = ? ORDER BY changed_at, id", MAPPER, orderId);
    }
}
