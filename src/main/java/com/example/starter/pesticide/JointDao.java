package com.example.starter.pesticide;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 定量联检数据访问：联检单、项目快照、分样、逐项结果与综合判定；所有时间字段为 UTC 墙钟时间。
 */
@Repository
public class JointDao {

    /**
     * 联检单行记录；version 从 1 开始、成功变更加 1。
     */
    public record JointRow(
            long id,
            String requestId,
            String fingerprint,
            long batchId,
            long testId,
            String agencyCode,
            String status,
            int version,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    /**
     * 联检项目快照行：创建时冻结限值与机构能力版本，分样后回填样本号。
     */
    public record JointItemRow(
            long id,
            long jointId,
            String item,
            BigDecimal limitValue,
            int capabilityVersion,
            String sampleNo) {
    }

    /**
     * 分样记录行。
     */
    public record AllocationRow(
            long id,
            long jointId,
            String requestId,
            String fingerprint,
            LocalDateTime createdAt) {
    }

    /**
     * 联检逐项结果行：同项最大 version 为当前结果，旧版本保留。
     */
    public record JointResultRow(
            long id,
            long jointId,
            String item,
            String sampleNo,
            BigDecimal concentration,
            int version,
            String requestId,
            String fingerprint,
            LocalDateTime createdAt) {
    }

    /**
     * 综合判定行：携带全部项版本冻结证据。
     */
    public record JointDecisionRow(
            long id,
            long jointId,
            String requestId,
            String fingerprint,
            String verdict,
            String evidence,
            LocalDateTime createdAt) {
    }

    private static final RowMapper<JointRow> JOINT_MAPPER = (rs, n) -> new JointRow(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getString("fingerprint"),
            rs.getLong("batch_id"),
            rs.getLong("test_id"),
            rs.getString("agency_code"),
            rs.getString("status"),
            rs.getInt("version"),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("updated_at").toLocalDateTime());

    private static final RowMapper<JointItemRow> ITEM_MAPPER = (rs, n) -> new JointItemRow(
            rs.getLong("id"),
            rs.getLong("joint_id"),
            rs.getString("item"),
            rs.getBigDecimal("limit_value"),
            rs.getInt("capability_version"),
            rs.getString("sample_no"));

    private static final RowMapper<AllocationRow> ALLOC_MAPPER = (rs, n) -> new AllocationRow(
            rs.getLong("id"),
            rs.getLong("joint_id"),
            rs.getString("request_id"),
            rs.getString("fingerprint"),
            rs.getTimestamp("created_at").toLocalDateTime());

    private static final RowMapper<JointResultRow> RESULT_MAPPER = (rs, n) -> new JointResultRow(
            rs.getLong("id"),
            rs.getLong("joint_id"),
            rs.getString("item"),
            rs.getString("sample_no"),
            rs.getBigDecimal("concentration"),
            rs.getInt("version"),
            rs.getString("request_id"),
            rs.getString("fingerprint"),
            rs.getTimestamp("created_at").toLocalDateTime());

    private static final RowMapper<JointDecisionRow> DECISION_MAPPER = (rs, n) -> new JointDecisionRow(
            rs.getLong("id"),
            rs.getLong("joint_id"),
            rs.getString("request_id"),
            rs.getString("fingerprint"),
            rs.getString("verdict"),
            rs.getString("evidence"),
            rs.getTimestamp("created_at").toLocalDateTime());

    private final JdbcTemplate jdbc;

    public JointDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 查询合成项目目录限值（单位：mg/kg）。
     */
    public Optional<BigDecimal> findCatalogLimit(String item) {
        List<BigDecimal> rows = jdbc.query("SELECT limit_value FROM item_catalog WHERE item = ?",
                (rs, n) -> rs.getBigDecimal("limit_value"), item);
        return rows.stream().findFirst();
    }

    /**
     * 查询机构对指定项目的能力版本。
     */
    public Optional<Integer> findCapabilityVersion(String agencyCode, String item) {
        List<Integer> rows = jdbc.query(
                "SELECT capability_version FROM agency_capability WHERE agency_code = ? AND item = ?",
                (rs, n) -> rs.getInt("capability_version"), agencyCode, item);
        return rows.stream().findFirst();
    }

    /**
     * 插入联检单（初始版本 1，状态 OPEN）；requestId 唯一冲突时抛出 DuplicateKeyException。
     */
    public long insertJoint(String requestId, String fingerprint, long batchId, long testId,
                            String agencyCode, LocalDateTime now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO joint_order (request_id, fingerprint, batch_id, test_id, agency_code,"
                            + " status, version, created_at, updated_at)"
                            + " VALUES (?, ?, ?, ?, ?, 'OPEN', 1, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requestId);
            ps.setString(2, fingerprint);
            ps.setLong(3, batchId);
            ps.setLong(4, testId);
            ps.setString(5, agencyCode);
            ps.setTimestamp(6, Timestamp.valueOf(now));
            ps.setTimestamp(7, Timestamp.valueOf(now));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 插入联检项目快照（冻结限值与能力版本）。
     */
    public void insertJointItem(long jointId, String item, BigDecimal limitValue, int capabilityVersion) {
        jdbc.update("INSERT INTO joint_item (joint_id, item, limit_value, capability_version)"
                        + " VALUES (?, ?, ?, ?)",
                jointId, item, limitValue, capabilityVersion);
    }

    /**
     * 按幂等请求号查询联检单。
     */
    public Optional<JointRow> findJointByRequestId(String requestId) {
        List<JointRow> rows = jdbc.query("SELECT * FROM joint_order WHERE request_id = ?",
                JOINT_MAPPER, requestId);
        return rows.stream().findFirst();
    }

    /**
     * 按主键查询联检单。
     */
    public Optional<JointRow> findJointById(long id) {
        List<JointRow> rows = jdbc.query("SELECT * FROM joint_order WHERE id = ?", JOINT_MAPPER, id);
        return rows.stream().findFirst();
    }

    /**
     * 按主键行级锁定联检单（SELECT FOR UPDATE），须在事务内调用。
     */
    public Optional<JointRow> lockJointById(long id) {
        List<JointRow> rows = jdbc.query("SELECT * FROM joint_order WHERE id = ? FOR UPDATE",
                JOINT_MAPPER, id);
        return rows.stream().findFirst();
    }

    /**
     * 更新联检单状态与版本（版本由调用方递增）。
     */
    public void updateJointState(long id, String status, int version, LocalDateTime now) {
        jdbc.update("UPDATE joint_order SET status = ?, version = ?, updated_at = ? WHERE id = ?",
                status, version, Timestamp.valueOf(now), id);
    }

    /**
     * 查询联检单全部项目快照（按项目名排序，保证证据与指纹可复算）。
     */
    public List<JointItemRow> findItems(long jointId) {
        return jdbc.query("SELECT * FROM joint_item WHERE joint_id = ? ORDER BY item",
                ITEM_MAPPER, jointId);
    }

    /**
     * 回填分样样本号；样本号全局唯一冲突时抛出 DuplicateKeyException。
     */
    public void updateSampleNo(long jointId, String item, String sampleNo) {
        jdbc.update("UPDATE joint_item SET sample_no = ? WHERE joint_id = ? AND item = ?",
                sampleNo, jointId, item);
    }

    /**
     * 插入分样记录；requestId 或联检单唯一冲突时抛出 DuplicateKeyException。
     */
    public long insertAllocation(long jointId, String requestId, String fingerprint, LocalDateTime now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO joint_allocation (joint_id, request_id, fingerprint, created_at)"
                            + " VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, jointId);
            ps.setString(2, requestId);
            ps.setString(3, fingerprint);
            ps.setTimestamp(4, Timestamp.valueOf(now));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按幂等请求号查询分样记录。
     */
    public Optional<AllocationRow> findAllocationByRequestId(String requestId) {
        List<AllocationRow> rows = jdbc.query("SELECT * FROM joint_allocation WHERE request_id = ?",
                ALLOC_MAPPER, requestId);
        return rows.stream().findFirst();
    }

    /**
     * 插入联检逐项结果；requestId 或项内版本唯一冲突时抛出 DuplicateKeyException。
     */
    public long insertResult(long jointId, String item, String sampleNo, BigDecimal concentration,
                             int version, String requestId, String fingerprint, LocalDateTime now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO joint_result (joint_id, item, sample_no, concentration, version,"
                            + " request_id, fingerprint, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, jointId);
            ps.setString(2, item);
            ps.setString(3, sampleNo);
            ps.setBigDecimal(4, concentration);
            ps.setInt(5, version);
            ps.setString(6, requestId);
            ps.setString(7, fingerprint);
            ps.setTimestamp(8, Timestamp.valueOf(now));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按幂等请求号查询联检结果。
     */
    public Optional<JointResultRow> findResultByRequestId(String requestId) {
        List<JointResultRow> rows = jdbc.query("SELECT * FROM joint_result WHERE request_id = ?",
                RESULT_MAPPER, requestId);
        return rows.stream().findFirst();
    }

    /**
     * 查询联检单全部结果（含被复测替换的旧版本，按项目与版本排序）。
     */
    public List<JointResultRow> findResults(long jointId) {
        return jdbc.query("SELECT * FROM joint_result WHERE joint_id = ? ORDER BY item, version",
                RESULT_MAPPER, jointId);
    }

    /**
     * 查询联检单各项当前结果（每项最大版本）。
     */
    public List<JointResultRow> findCurrentResults(long jointId) {
        return jdbc.query(
                "SELECT r.* FROM joint_result r"
                        + " WHERE r.joint_id = ? AND r.version = ("
                        + "   SELECT MAX(r2.version) FROM joint_result r2"
                        + "   WHERE r2.joint_id = r.joint_id AND r2.item = r.item)"
                        + " ORDER BY r.item",
                RESULT_MAPPER, jointId);
    }

    /**
     * 插入综合判定；requestId 或联检单唯一冲突时抛出 DuplicateKeyException。
     */
    public long insertDecision(long jointId, String requestId, String fingerprint,
                               String verdict, String evidence, LocalDateTime now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO joint_decision (joint_id, request_id, fingerprint, verdict, evidence, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, jointId);
            ps.setString(2, requestId);
            ps.setString(3, fingerprint);
            ps.setString(4, verdict);
            ps.setString(5, evidence);
            ps.setTimestamp(6, Timestamp.valueOf(now));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按幂等请求号查询综合判定。
     */
    public Optional<JointDecisionRow> findDecisionByRequestId(String requestId) {
        List<JointDecisionRow> rows = jdbc.query("SELECT * FROM joint_decision WHERE request_id = ?",
                DECISION_MAPPER, requestId);
        return rows.stream().findFirst();
    }

    /**
     * 查询联检单的综合判定。
     */
    public Optional<JointDecisionRow> findDecisionByJoint(long jointId) {
        List<JointDecisionRow> rows = jdbc.query("SELECT * FROM joint_decision WHERE joint_id = ?",
                DECISION_MAPPER, jointId);
        return rows.stream().findFirst();
    }

    /**
     * 判断批次是否存在未综合判定的联检单（联检门控旧复检用）。
     */
    public boolean existsUndecidedJointByBatch(long batchId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM joint_order WHERE batch_id = ? AND status <> 'DECIDED'",
                Integer.class, batchId);
        return count != null && count > 0;
    }
}
