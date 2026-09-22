package com.example.starter.ros.repository;

import com.example.starter.ros.domain.RequestRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 幂等请求记录表 request_record 数据访问。
 */
@Repository
public class RequestRecordJdbcRepository {

    private static final RequestRowMapper ROW_MAPPER = new RequestRowMapper();

    private final JdbcTemplate jdbcTemplate;

    public RequestRecordJdbcRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<RequestRecord> findById(String requestId) {
        List<RequestRecord> records = jdbcTemplate.query("""
                        SELECT request_id, node_id, op, request_hash, response, created_at
                          FROM request_record WHERE request_id = ?
                        """, ROW_MAPPER, requestId);
        return records.stream().findFirst();
    }

    /**
     * 保存首次成功处理的请求占位快照(response 暂为空),抢占 requestId 主键;
     * 并发重复提交时由主键约束抛出 DuplicateKeyException。业务成功后由
     * {@link #updateResponse} 回填正式响应;业务失败随事务整体回滚。
     */
    public void insert(RequestRecord record) {
        jdbcTemplate.update("""
                        INSERT INTO request_record
                            (request_id, node_id, op, request_hash, response, created_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                record.requestId(), record.nodeId(), record.op(), record.requestHash(),
                record.responseJson(), Timestamp.valueOf(record.createdAt()));
    }

    /**
     * 业务处理成功后回填响应 JSON 快照,供同参重放原样返回。
     */
    public void updateResponse(String requestId, String responseJson) {
        jdbcTemplate.update("UPDATE request_record SET response = ? WHERE request_id = ?",
                responseJson, requestId);
    }

    /**
     * 查询某节点已处理的全部幂等请求ID,用于检查点保存与恢复。
     */
    public List<String> findIdsByNodeId(String nodeId) {
        return jdbcTemplate.query(
                "SELECT request_id FROM request_record WHERE node_id = ? ORDER BY request_id",
                (rs, rowNum) -> rs.getString(1), nodeId);
    }

    private static final class RequestRowMapper implements RowMapper<RequestRecord> {
        @Override
        public RequestRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new RequestRecord(
                    rs.getString("request_id"),
                    rs.getString("node_id"),
                    rs.getString("op"),
                    rs.getString("request_hash"),
                    rs.getString("response"),
                    rs.getTimestamp("created_at").toLocalDateTime());
        }
    }
}
