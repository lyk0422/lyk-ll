package com.example.starter.reinsurance.repository;

import com.example.starter.reinsurance.domain.ClaimEvent;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 赔案事件流仓储（结案后的迟到估损调整只记事件）。
 */
@Repository
public class ClaimEventRepository {

    private static final RowMapper<ClaimEvent> MAPPER = (rs, n) -> {
        ClaimEvent event = new ClaimEvent();
        event.setId(rs.getLong("id"));
        event.setClaimId(rs.getLong("claim_id"));
        event.setEventType(rs.getString("event_type"));
        event.setFromAmount(rs.getBigDecimal("from_amount"));
        if (rs.wasNull()) {
            event.setFromAmount(null);
        }
        event.setToAmount(rs.getBigDecimal("to_amount"));
        if (rs.wasNull()) {
            event.setToAmount(null);
        }
        event.setNote(rs.getString("note"));
        Timestamp createdAt = rs.getTimestamp("created_at");
        if (createdAt != null) {
            event.setCreatedAt(createdAt.toInstant());
        }
        return event;
    };

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public ClaimEventRepository(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    public void record(
            Long claimId,
            String eventType,
            java.math.BigDecimal fromAmount,
            java.math.BigDecimal toAmount,
            String note) {
        jdbcTemplate.update(
                "INSERT INTO claim_event (claim_id, event_type, from_amount, to_amount, note, created_at) "
                        + "VALUES (?,?,?,?,?,?)",
                claimId,
                eventType,
                fromAmount,
                toAmount,
                note,
                Timestamp.from(java.time.Instant.now(clock)));
    }

    public List<ClaimEvent> findByClaimId(Long claimId) {
        return jdbcTemplate.query(
                "SELECT * FROM claim_event WHERE claim_id = ? ORDER BY id", MAPPER, claimId);
    }
}
