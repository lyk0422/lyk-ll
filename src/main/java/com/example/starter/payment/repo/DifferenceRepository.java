package com.example.starter.payment.repo;

import com.example.starter.payment.ReconDifference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 对账差异与处理留痕数据访问。
 */
@Repository
public class DifferenceRepository {

    private static final RowMapper<ReconDifference> MAPPER = (rs, i) -> {
        Long localAmount = rs.getLong("local_amount");
        if (rs.wasNull()) {
            localAmount = null;
        }
        Long channelAmount = rs.getLong("channel_amount");
        if (rs.wasNull()) {
            channelAmount = null;
        }
        Timestamp resolvedAt = rs.getTimestamp("resolved_at");
        return new ReconDifference(rs.getLong("id"), rs.getLong("batch_id"), rs.getString("serial_no"),
                rs.getString("diff_type"), localAmount, channelAmount, rs.getString("status"),
                rs.getString("resolution"), rs.getString("resolved_by"),
                resolvedAt == null ? null : resolvedAt.toInstant());
    };

    private final JdbcTemplate jdbc;

    public DifferenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ReconDifference insert(long batchId, String serialNo, String diffType,
                                  Long localAmount, Long channelAmount) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO recon_difference (batch_id, serial_no, diff_type, local_amount, channel_amount,"
                            + " status) VALUES (?,?,?,?,?,?)",
                    new String[]{"id"});
            ps.setLong(1, batchId);
            ps.setString(2, serialNo);
            ps.setString(3, diffType);
            if (localAmount == null) {
                ps.setNull(4, java.sql.Types.BIGINT);
            } else {
                ps.setLong(4, localAmount);
            }
            if (channelAmount == null) {
                ps.setNull(5, java.sql.Types.BIGINT);
            } else {
                ps.setLong(5, channelAmount);
            }
            ps.setString(6, ReconDifference.OPEN);
            return ps;
        }, keyHolder);
        long id = Objects.requireNonNull(keyHolder.getKey()).longValue();
        return new ReconDifference(id, batchId, serialNo, diffType, localAmount, channelAmount,
                ReconDifference.OPEN, null, null, null);
    }

    public Optional<ReconDifference> findById(long id) {
        return jdbc.query("SELECT * FROM recon_difference WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public List<ReconDifference> findByBatchId(long batchId) {
        return jdbc.query("SELECT * FROM recon_difference WHERE batch_id = ? ORDER BY id", MAPPER, batchId);
    }

    /**
     * 将待处理差异原子地置为已处理（防重复核销的条件更新）。
     *
     * @return 更新行数；0 表示不存在或已被处理
     */
    public int markResolved(long id, String resolution, String operator) {
        return jdbc.update("UPDATE recon_difference SET status = ?, resolution = ?, resolved_by = ?,"
                        + " resolved_at = ? WHERE id = ? AND status = ?",
                ReconDifference.RESOLVED, resolution, operator, Timestamp.from(Instant.now()),
                id, ReconDifference.OPEN);
    }

    /** 处理留痕。 */
    public void insertResolutionLog(long differenceId, String resolution, String operator) {
        jdbc.update("INSERT INTO recon_resolution_log (difference_id, resolution, operator) VALUES (?,?,?)",
                differenceId, resolution, operator);
    }

    public int countResolutionLogs(long differenceId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM recon_resolution_log WHERE difference_id = ?", Integer.class, differenceId);
        return count == null ? 0 : count;
    }
}
