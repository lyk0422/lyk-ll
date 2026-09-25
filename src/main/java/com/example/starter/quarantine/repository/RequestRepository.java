package com.example.starter.quarantine.repository;

import com.example.starter.quarantine.domain.AnimalType;
import com.example.starter.quarantine.domain.QuarantineRequestPo;
import com.example.starter.quarantine.domain.RequestStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 检疫申报单据仓储。
 */
@Repository
public class RequestRepository {

    private static final RowMapper<QuarantineRequestPo> ROW_MAPPER = RequestRepository::mapRow;

    private final JdbcTemplate jdbcTemplate;

    public RequestRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(QuarantineRequestPo po) {
        jdbcTemplate.update(
                "INSERT INTO quarantine_request (request_id, origin_farm, destination, animal_type,"
                        + " ear_tag_range, quantity, status, fingerprint, reject_reason, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                po.getRequestId(), po.getOriginFarm(), po.getDestination(), po.getAnimalType().name(),
                po.getEarTagRange(), po.getQuantity(), po.getStatus().name(), po.getFingerprint(),
                po.getRejectReason(), Timestamp.from(po.getCreatedAt()), Timestamp.from(po.getUpdatedAt()));
    }

    public QuarantineRequestPo findById(String requestId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT request_id, origin_farm, destination, animal_type, ear_tag_range, quantity, status,"
                            + " fingerprint, reject_reason, created_at, updated_at"
                            + " FROM quarantine_request WHERE request_id = ?",
                    ROW_MAPPER, requestId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /** 条件更新状态；当前状态匹配才生效，返回是否更新成功（用于状态机与并发封锁）。 */
    public boolean updateStatusIfCurrent(String requestId, RequestStatus expected, RequestStatus target,
                                         String rejectReason, Instant now) {
        int rows = jdbcTemplate.update(
                "UPDATE quarantine_request SET status = ?, reject_reason = COALESCE(?, reject_reason), updated_at = ?"
                        + " WHERE request_id = ? AND status = ?",
                target.name(), rejectReason, Timestamp.from(now), requestId, expected.name());
        return rows == 1;
    }

    /** 无条件更新状态与驳回原因。 */
    public void updateStatus(String requestId, RequestStatus status, String rejectReason, Instant now) {
        jdbcTemplate.update(
                "UPDATE quarantine_request SET status = ?, reject_reason = COALESCE(?, reject_reason), updated_at = ?"
                        + " WHERE request_id = ?",
                status.name(), rejectReason, Timestamp.from(now), requestId);
    }

    /** 查某养殖场处于指定状态的全部单据（封锁三级联动）。 */
    public List<QuarantineRequestPo> findByFarmAndStatus(String farmId, RequestStatus status) {
        return jdbcTemplate.query(
                "SELECT request_id, origin_farm, destination, animal_type, ear_tag_range, quantity, status,"
                        + " fingerprint, reject_reason, created_at, updated_at"
                        + " FROM quarantine_request WHERE origin_farm = ? AND status = ? ORDER BY created_at",
                ROW_MAPPER, farmId, status.name());
    }

    private static QuarantineRequestPo mapRow(ResultSet rs, int n) throws SQLException {
        QuarantineRequestPo po = new QuarantineRequestPo();
        po.setRequestId(rs.getString("request_id"));
        po.setOriginFarm(rs.getString("origin_farm"));
        po.setDestination(rs.getString("destination"));
        po.setAnimalType(AnimalType.valueOf(rs.getString("animal_type")));
        po.setEarTagRange(rs.getString("ear_tag_range"));
        po.setQuantity(rs.getInt("quantity"));
        po.setStatus(RequestStatus.valueOf(rs.getString("status")));
        po.setFingerprint(rs.getString("fingerprint"));
        po.setRejectReason(rs.getString("reject_reason"));
        Timestamp createdAt = rs.getTimestamp("created_at");
        if (createdAt != null) {
            po.setCreatedAt(createdAt.toInstant());
        }
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        if (updatedAt != null) {
            po.setUpdatedAt(updatedAt.toInstant());
        }
        return po;
    }
}
