package com.example.starter.cutover.repository;

import com.example.starter.cutover.domain.ApplicationStatus;
import com.example.starter.cutover.po.ApplicationPO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 割接申请数据访问：窗口冲突按半开区间 [start,end) 判定，
 * 即两区间重叠当且仅当 startA &lt; endB 且 startB &lt; endA，端点相接不冲突。
 */
@Repository
public class ApplicationRepository {

    private static final RowMapper<ApplicationPO> MAPPER = (rs, rowNum) -> {
        ApplicationPO po = new ApplicationPO();
        po.setId(rs.getLong("id"));
        po.setRequestId(rs.getString("request_id"));
        po.setDeviceCode(rs.getString("device_code"));
        po.setTitle(rs.getString("title"));
        po.setPriority(rs.getString("priority"));
        po.setWindowStart(rs.getTimestamp("window_start").toLocalDateTime());
        po.setWindowEnd(rs.getTimestamp("window_end").toLocalDateTime());
        po.setStatus(rs.getString("status"));
        po.setRejectReason(rs.getString("reject_reason"));
        po.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        po.setUpdatedAt(rs.getTimestamp("updated_at").toLocalDateTime());
        return po;
    };

    private final JdbcTemplate jdbcTemplate;

    public ApplicationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 新增申请并回填主键。
     */
    public long insert(ApplicationPO po) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO cutover_application "
                            + "(request_id, device_code, title, priority, window_start, window_end, "
                            + "status, reject_reason, created_at, updated_at) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, po.getRequestId());
            ps.setString(2, po.getDeviceCode());
            ps.setString(3, po.getTitle());
            ps.setString(4, po.getPriority());
            ps.setTimestamp(5, Timestamp.valueOf(po.getWindowStart()));
            ps.setTimestamp(6, Timestamp.valueOf(po.getWindowEnd()));
            ps.setString(7, po.getStatus());
            ps.setString(8, po.getRejectReason());
            ps.setTimestamp(9, Timestamp.valueOf(po.getCreatedAt()));
            ps.setTimestamp(10, Timestamp.valueOf(po.getUpdatedAt()));
            return ps;
        }, keyHolder);
        long id = keyHolder.getKey().longValue();
        po.setId(id);
        return id;
    }

    public Optional<ApplicationPO> findById(long id) {
        List<ApplicationPO> list = jdbcTemplate.query(
                "SELECT * FROM cutover_application WHERE id = ?", MAPPER, id);
        return list.stream().findFirst();
    }

    /**
     * 锁定申请行（SELECT ... FOR UPDATE），串行化同一申请的所有状态变更。
     */
    public ApplicationPO lockById(long id) {
        List<ApplicationPO> list = jdbcTemplate.query(
                "SELECT * FROM cutover_application WHERE id = ? FOR UPDATE", MAPPER, id);
        return list.stream().findFirst().orElse(null);
    }

    public Optional<ApplicationPO> findByRequestId(String requestId) {
        List<ApplicationPO> list = jdbcTemplate.query(
                "SELECT * FROM cutover_application WHERE request_id = ?", MAPPER, requestId);
        return list.stream().findFirst();
    }

    /**
     * 条件更新状态（CAS）：仅当当前状态属于 expectedStatuses 时更新，返回是否命中。
     * 用于终态竞争（回退/封板）等场景保证唯一终态。
     */
    public boolean compareAndUpdateStatus(long id, List<ApplicationStatus> expectedStatuses,
                                          ApplicationStatus newStatus, String rejectReason,
                                          LocalDateTime updatedAt) {
        String placeholders = String.join(",", expectedStatuses.stream().map(s -> "?").toList());
        Object[] params = new Object[expectedStatuses.size() + 4];
        int i = 0;
        params[i++] = newStatus.name();
        params[i++] = rejectReason;
        params[i++] = Timestamp.valueOf(updatedAt);
        params[i++] = id;
        for (ApplicationStatus s : expectedStatuses) {
            params[i++] = s.name();
        }
        int rows = jdbcTemplate.update(
                "UPDATE cutover_application SET status = ?, reject_reason = ?, updated_at = ? "
                        + "WHERE id = ? AND status IN (" + placeholders + ")",
                params);
        return rows == 1;
    }

    /**
     * 无条件更新状态（状态机已在业务层校验时使用）。
     */
    public void updateStatus(long id, ApplicationStatus newStatus, LocalDateTime updatedAt) {
        jdbcTemplate.update(
                "UPDATE cutover_application SET status = ?, updated_at = ? WHERE id = ?",
                newStatus.name(), Timestamp.valueOf(updatedAt), id);
    }

    /**
     * 查询同设备、时间窗半开重叠且处于窗口占用状态的其他申请。
     * 端点相接（一端 start 等于另一端 end）不命中。
     */
    public List<ApplicationPO> findWindowHoldingOverlaps(String deviceCode,
                                                         LocalDateTime start, LocalDateTime end,
                                                         Long excludeId) {
        String sql = "SELECT * FROM cutover_application WHERE device_code = ? "
                + "AND window_start < ? AND ? < window_end "
                + "AND status IN ('APPROVED','EXECUTING','VERIFIED','SEALED')";
        List<Object> params = new java.util.ArrayList<>(List.of(deviceCode,
                Timestamp.valueOf(end), Timestamp.valueOf(start)));
        if (excludeId != null) {
            sql += " AND id <> ?";
            params.add(excludeId);
        }
        return jdbcTemplate.query(sql, MAPPER, params.toArray());
    }
}
