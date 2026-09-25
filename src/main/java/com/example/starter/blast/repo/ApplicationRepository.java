package com.example.starter.blast.repo;

import com.example.starter.blast.domain.ApplicationStatus;
import com.example.starter.blast.domain.BlastApplication;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 爆破作业申请仓储。
 * 已批准“爆区＋计划起爆时刻”唯一性由库表生成列上的 uq_approved_slot 约束兜底。
 */
@Repository
public class ApplicationRepository {

    private static final RowMapper<BlastApplication> ROW_MAPPER = (rs, rowNum) -> new BlastApplication(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getString("mine_code"),
            rs.getString("zone_code"),
            rs.getString("applicant"),
            rs.getTimestamp("planned_time").toInstant(),
            rs.getBigDecimal("charge_per_delay_kg"),
            ApplicationStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("detonate_time") == null ? null : rs.getTimestamp("detonate_time").toInstant(),
            rs.getString("reject_reason"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public ApplicationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long insert(BlastApplication application) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO blast_application "
                            + "(request_id, mine_code, zone_code, applicant, planned_time, charge_per_delay_kg, "
                            + "status, detonate_time, reject_reason, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setString(1, application.requestId());
            ps.setString(2, application.mineCode());
            ps.setString(3, application.zoneCode());
            ps.setString(4, application.applicant());
            ps.setTimestamp(5, Timestamp.from(application.plannedTime()));
            ps.setBigDecimal(6, application.chargePerDelayKg());
            ps.setString(7, application.status().name());
            ps.setTimestamp(8, application.detonateTime() == null ? null
                    : Timestamp.from(application.detonateTime()));
            ps.setString(9, application.rejectReason());
            ps.setTimestamp(10, Timestamp.from(application.createdAt()));
            ps.setTimestamp(11, Timestamp.from(application.updatedAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<BlastApplication> findById(long id) {
        return jdbcTemplate.query(selectColumns() + " WHERE id = ?", ROW_MAPPER, id).stream().findFirst();
    }

    public Optional<BlastApplication> findByRequestId(String requestId) {
        return jdbcTemplate.query(selectColumns() + " WHERE request_id = ?", ROW_MAPPER, requestId).stream()
                .findFirst();
    }

    /**
     * 仅当状态等于期望值时更新为目标状态；返回 false 包含状态不符或唯一批准槽位冲突两种情况。
     */
    public boolean compareAndSetStatus(long id, ApplicationStatus expected, ApplicationStatus target,
                                       String reason, Instant detonateTime, Instant now) {
        try {
            int rows = jdbcTemplate.update(
                    "UPDATE blast_application SET status = ?, reject_reason = ?, detonate_time = ?, updated_at = ? "
                            + "WHERE id = ? AND status = ?",
                    target.name(), reason, detonateTime == null ? null : Timestamp.from(detonateTime),
                    Timestamp.from(now), id, expected.name());
            return rows == 1;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }

    /**
     * 冻结生效时批量处置：在途申请驳回、已批准未起爆作业作废。返回受影响行数。
     */
    public int bulkStatusChange(String mineCode, ApplicationStatus expected, ApplicationStatus target,
                                String reason, Instant now) {
        return jdbcTemplate.update(
                "UPDATE blast_application SET status = ?, reject_reason = ?, updated_at = ? "
                        + "WHERE mine_code = ? AND status = ?",
                target.name(), reason, Timestamp.from(now), mineCode, expected.name());
    }

    public List<BlastApplication> findByMineAndStatuses(String mineCode, List<ApplicationStatus> statuses) {
        if (statuses.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", statuses.stream().map(s -> "?").toList());
        Object[] args = new Object[statuses.size() + 1];
        args[0] = mineCode;
        for (int i = 0; i < statuses.size(); i++) {
            args[i + 1] = statuses.get(i).name();
        }
        return jdbcTemplate.query(
                selectColumns() + " WHERE mine_code = ? AND status IN (" + placeholders + ") ORDER BY id",
                ROW_MAPPER, args);
    }

    private String selectColumns() {
        return "SELECT id, request_id, mine_code, zone_code, applicant, planned_time, charge_per_delay_kg, "
                + "status, detonate_time, reject_reason, created_at, updated_at FROM blast_application";
    }
}
