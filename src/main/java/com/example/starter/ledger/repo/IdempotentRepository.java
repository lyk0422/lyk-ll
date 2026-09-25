package com.example.starter.ledger.repo;

import com.example.starter.ledger.po.IdempotentRecordPO;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 幂等请求记录数据访问。
 */
@Repository
public class IdempotentRepository {

    private final JdbcTemplate jdbcTemplate;

    public IdempotentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<IdempotentRecordPO> MAPPER = new IdempotentRowMapper();

    /** 首次落库；requestId 冲突抛 {@link DuplicateKeyException}，由服务层区分重放/异参。 */
    public void insert(IdempotentRecordPO record) {
        jdbcTemplate.update(
                "INSERT INTO idempotent_record (request_id, operation, fingerprint, result_status, result_body, "
                        + "created_at) VALUES (?, ?, ?, ?, ?, ?)",
                record.getRequestId(), record.getOperation(), record.getFingerprint(),
                record.getResultStatus(), record.getResultBody(), Timestamp.from(record.getCreatedAt()));
    }

    /** 按 requestId 查询，不存在返回 null。 */
    public IdempotentRecordPO findByRequestId(String requestId) {
        return jdbcTemplate.query(
                "SELECT request_id, operation, fingerprint, result_status, result_body, created_at "
                        + "FROM idempotent_record WHERE request_id = ?",
                rs -> rs.next() ? MAPPER.mapRow(rs, 1) : null, requestId);
    }

    private static final class IdempotentRowMapper implements RowMapper<IdempotentRecordPO> {
        @Override
        public IdempotentRecordPO mapRow(ResultSet rs, int rowNum) throws SQLException {
            IdempotentRecordPO po = new IdempotentRecordPO();
            po.setRequestId(rs.getString("request_id"));
            po.setOperation(rs.getString("operation"));
            po.setFingerprint(rs.getString("fingerprint"));
            po.setResultStatus(rs.getInt("result_status"));
            po.setResultBody(rs.getString("result_body"));
            Timestamp created = rs.getTimestamp("created_at");
            po.setCreatedAt(created == null ? null : created.toInstant());
            return po;
        }
    }
}
