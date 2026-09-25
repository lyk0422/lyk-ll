package com.example.starter.repository;

import com.example.starter.entity.ReconDiff;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** 渠道对账文件与差异记录表数据访问。 */
@Repository
public class ReconRepository {

    private static final RowMapper<ReconDiff> MAPPER = new DiffRowMapper();

    private final JdbcTemplate jdbcTemplate;

    public ReconRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean existsFile(String fileNo) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM recon_file WHERE file_no = ?", Integer.class, fileNo);
        return count != null && count > 0;
    }

    public void insertFile(String fileNo, LocalDate batchDate, String channelCode, Instant now) {
        jdbcTemplate.update(
                "INSERT INTO recon_file (file_no, batch_date, channel_code, created_at) VALUES (?, ?, ?, ?)",
                fileNo, batchDate, channelCode == null ? "SYNTHETIC" : channelCode, Timestamp.from(now));
    }

    /** 差异幂等落库：同批次+流水号+类型已存在则忽略，返回库中记录数由上层重新查询。 */
    public void insertDiffIfAbsent(ReconDiff diff) {
        jdbcTemplate.update(
                "INSERT INTO recon_diff (batch_date, channel_flow_no, merchant_no, diff_type, local_amount, "
                        + "channel_amount, status, created_at) "
                        + "SELECT ?, ?, ?, ?, ?, ?, 'PENDING', ? WHERE NOT EXISTS ("
                        + "SELECT 1 FROM recon_diff WHERE batch_date = ? AND channel_flow_no = ? AND diff_type = ?)",
                diff.getBatchDate(), diff.getChannelFlowNo(), diff.getMerchantNo(), diff.getDiffType(),
                diff.getLocalAmount(), diff.getChannelAmount(), Timestamp.from(diff.getCreatedAt()),
                diff.getBatchDate(), diff.getChannelFlowNo(), diff.getDiffType());
    }

    public ReconDiff findById(Long id) {
        List<ReconDiff> list = jdbcTemplate.query(
                "SELECT id, batch_date, channel_flow_no, merchant_no, diff_type, local_amount, channel_amount, "
                        + "status, resolution, resolved_at, resolved_by, remark, created_at FROM recon_diff WHERE id = ?",
                MAPPER, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<ReconDiff> findByBatch(LocalDate batchDate) {
        return jdbcTemplate.query(
                "SELECT id, batch_date, channel_flow_no, merchant_no, diff_type, local_amount, channel_amount, "
                        + "status, resolution, resolved_at, resolved_by, remark, created_at "
                        + "FROM recon_diff WHERE batch_date = ? ORDER BY id",
                MAPPER, batchDate);
    }

    /** 条件核销：仅 PENDING 可核销（终态禁止重复核销），返回受影响行数。 */
    public int markResolved(Long id, String resolution, String resolvedBy, String remark, Instant resolvedAt) {
        return jdbcTemplate.update(
                "UPDATE recon_diff SET status = 'RESOLVED', resolution = ?, resolved_at = ?, resolved_by = ?, remark = ? "
                        + "WHERE id = ? AND status = 'PENDING'",
                resolution, Timestamp.from(resolvedAt), resolvedBy, remark, id);
    }

    private static class DiffRowMapper implements RowMapper<ReconDiff> {
        @Override
        public ReconDiff mapRow(ResultSet rs, int rowNum) throws SQLException {
            ReconDiff d = new ReconDiff();
            d.setId(rs.getLong("id"));
            d.setBatchDate(rs.getDate("batch_date").toLocalDate());
            d.setChannelFlowNo(rs.getString("channel_flow_no"));
            d.setMerchantNo(rs.getString("merchant_no"));
            d.setDiffType(rs.getString("diff_type"));
            Object local = rs.getObject("local_amount");
            d.setLocalAmount(local == null ? null : rs.getLong("local_amount"));
            Object channel = rs.getObject("channel_amount");
            d.setChannelAmount(channel == null ? null : rs.getLong("channel_amount"));
            d.setStatus(rs.getString("status"));
            d.setResolution(rs.getString("resolution"));
            Timestamp resolvedAt = rs.getTimestamp("resolved_at");
            d.setResolvedAt(resolvedAt == null ? null : resolvedAt.toInstant());
            d.setResolvedBy(rs.getString("resolved_by"));
            d.setRemark(rs.getString("remark"));
            d.setCreatedAt(rs.getTimestamp("created_at").toInstant());
            return d;
        }
    }
}
