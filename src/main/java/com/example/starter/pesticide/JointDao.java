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
 * 联检数据访问：合成目录/机构能力、联检单、分样、结果、综合判定、补充信息与幂等记录。
 * 所有时间字段为 UTC 墙钟时间。
 */
@Repository
public class JointDao {

    /**
     * 合成目录项目行。
     */
    public record CatalogItemRow(
            String itemCode,
            String itemName,
            String unit,
            BigDecimal limitValue,
            String catalogVersion,
            boolean active) {
    }

    /**
     * 合成机构行。
     */
    public record LabRow(
            long id,
            String labCode,
            String labName,
            String capabilityVersion,
            boolean active) {
    }

    /**
     * 联检单行。
     */
    public record OrderRow(
            long id,
            String requestId,
            String fingerprint,
            long batchId,
            long labId,
            String labCode,
            String labName,
            String labCapabilityVersion,
            String catalogVersion,
            String frozenTests,
            String status,
            int version,
            LocalDateTime judgedAt,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    /**
     * 联检单项目行（冻结限值）。
     */
    public record OrderItemRow(
            long id,
            long orderId,
            String itemCode,
            String itemName,
            String unit,
            BigDecimal limitValue) {
    }

    /**
     * 分样行。
     */
    public record AssignmentRow(
            long id,
            String requestId,
            long orderId,
            String itemCode,
            String sampleNo,
            LocalDateTime createdAt) {
    }

    /**
     * 联检结果行。
     */
    public record ResultRow(
            long id,
            String requestId,
            long orderId,
            long assignmentId,
            String itemCode,
            BigDecimal concentration,
            boolean qualified,
            int itemVersion,
            int orderVersion,
            boolean superseded,
            LocalDateTime createdAt) {
    }

    /**
     * 综合判定行。
     */
    public record JudgmentRow(
            long id,
            String requestId,
            long orderId,
            String verdict,
            String itemVersions,
            String evidence,
            String evidenceFingerprint,
            LocalDateTime judgedAt,
            LocalDateTime createdAt) {
    }

    /**
     * 补充信息行。
     */
    public record SupplementRow(
            long id,
            String requestId,
            long orderId,
            String itemCode,
            String note,
            LocalDateTime createdAt) {
    }

    /**
     * 幂等记录行。
     */
    public record IdempotencyRow(
            String requestId,
            String fingerprint,
            String operation,
            long orderId,
            String responseJson,
            LocalDateTime createdAt) {
    }

    private static final RowMapper<CatalogItemRow> CATALOG_MAPPER = (rs, n) -> new CatalogItemRow(
            rs.getString("item_code"),
            rs.getString("item_name"),
            rs.getString("unit"),
            rs.getBigDecimal("limit_value"),
            rs.getString("catalog_version"),
            rs.getBoolean("active"));

    private static final RowMapper<LabRow> LAB_MAPPER = (rs, n) -> new LabRow(
            rs.getLong("id"),
            rs.getString("lab_code"),
            rs.getString("lab_name"),
            rs.getString("capability_version"),
            rs.getBoolean("active"));

    private static final RowMapper<OrderRow> ORDER_MAPPER = (rs, n) -> new OrderRow(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getString("fingerprint"),
            rs.getLong("batch_id"),
            rs.getLong("lab_id"),
            rs.getString("lab_code"),
            rs.getString("lab_name"),
            rs.getString("lab_capability_version"),
            rs.getString("catalog_version"),
            rs.getString("frozen_tests"),
            rs.getString("status"),
            rs.getInt("version"),
            rs.getTimestamp("judged_at") == null ? null : rs.getTimestamp("judged_at").toLocalDateTime(),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("updated_at").toLocalDateTime());

    private static final RowMapper<OrderItemRow> ORDER_ITEM_MAPPER = (rs, n) -> new OrderItemRow(
            rs.getLong("id"),
            rs.getLong("order_id"),
            rs.getString("item_code"),
            rs.getString("item_name"),
            rs.getString("unit"),
            rs.getBigDecimal("limit_value"));

    private static final RowMapper<AssignmentRow> ASSIGNMENT_MAPPER = (rs, n) -> new AssignmentRow(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getLong("order_id"),
            rs.getString("item_code"),
            rs.getString("sample_no"),
            rs.getTimestamp("created_at").toLocalDateTime());

    private static final RowMapper<ResultRow> RESULT_MAPPER = (rs, n) -> new ResultRow(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getLong("order_id"),
            rs.getLong("assignment_id"),
            rs.getString("item_code"),
            rs.getBigDecimal("concentration"),
            rs.getBoolean("qualified"),
            rs.getInt("item_version"),
            rs.getInt("order_version"),
            rs.getBoolean("superseded"),
            rs.getTimestamp("created_at").toLocalDateTime());

    private static final RowMapper<JudgmentRow> JUDGMENT_MAPPER = (rs, n) -> new JudgmentRow(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getLong("order_id"),
            rs.getString("verdict"),
            rs.getString("item_versions"),
            rs.getString("evidence"),
            rs.getString("evidence_fingerprint"),
            rs.getTimestamp("judged_at").toLocalDateTime(),
            rs.getTimestamp("created_at").toLocalDateTime());

    private static final RowMapper<SupplementRow> SUPPLEMENT_MAPPER = (rs, n) -> new SupplementRow(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getLong("order_id"),
            rs.getString("item_code"),
            rs.getString("note"),
            rs.getTimestamp("created_at").toLocalDateTime());

    private static final RowMapper<IdempotencyRow> IDEMPOTENCY_MAPPER = (rs, n) -> new IdempotencyRow(
            rs.getString("request_id"),
            rs.getString("fingerprint"),
            rs.getString("operation"),
            rs.getLong("order_id"),
            rs.getString("response_json"),
            rs.getTimestamp("created_at").toLocalDateTime());

    private final JdbcTemplate jdbc;

    public JointDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ============ 合成目录与机构能力 ============

    /**
     * 按编码查询合成目录项目。
     */
    public Optional<CatalogItemRow> findCatalogItem(String itemCode) {
        List<CatalogItemRow> rows = jdbc.query("SELECT * FROM joint_catalog_item WHERE item_code = ?",
                CATALOG_MAPPER, itemCode);
        return rows.stream().findFirst();
    }

    /**
     * 按编码查询合成机构。
     */
    public Optional<LabRow> findLabByCode(String labCode) {
        List<LabRow> rows = jdbc.query("SELECT * FROM joint_lab WHERE lab_code = ?",
                LAB_MAPPER, labCode);
        return rows.stream().findFirst();
    }

    /**
     * 判断机构是否具备指定项目能力。
     */
    public boolean labHasCapability(long labId, String itemCode) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM joint_lab_capability WHERE lab_id = ? AND item_code = ?",
                Integer.class, labId, itemCode);
        return count != null && count > 0;
    }

    // ============ 联检单 ============

    /**
     * 插入联检单（状态 CREATED、版本 1），返回生成主键；requestId 或批次唯一冲突时抛出 DuplicateKeyException。
     */
    public long insertOrder(String requestId, String fingerprint, long batchId, LabRow lab,
                            String catalogVersion, String frozenTests, LocalDateTime now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO joint_order (request_id, fingerprint, batch_id, lab_id, lab_code, lab_name,"
                            + " lab_capability_version, catalog_version, frozen_tests, status, version,"
                            + " created_at, updated_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'CREATED', 1, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requestId);
            ps.setString(2, fingerprint);
            ps.setLong(3, batchId);
            ps.setLong(4, lab.id());
            ps.setString(5, lab.labCode());
            ps.setString(6, lab.labName());
            ps.setString(7, lab.capabilityVersion());
            ps.setString(8, catalogVersion);
            ps.setString(9, frozenTests);
            ps.setTimestamp(10, Timestamp.valueOf(now));
            ps.setTimestamp(11, Timestamp.valueOf(now));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 插入联检单项目（冻结限值）。
     */
    public long insertOrderItem(long orderId, CatalogItemRow item) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO joint_order_item (order_id, item_code, item_name, unit, limit_value)"
                            + " VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, orderId);
            ps.setString(2, item.itemCode());
            ps.setString(3, item.itemName());
            ps.setString(4, item.unit());
            ps.setBigDecimal(5, item.limitValue());
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按主键查询联检单。
     */
    public Optional<OrderRow> findOrderById(long id) {
        List<OrderRow> rows = jdbc.query("SELECT * FROM joint_order WHERE id = ?", ORDER_MAPPER, id);
        return rows.stream().findFirst();
    }

    /**
     * 按主键行级锁定联检单（SELECT FOR UPDATE），须在事务内调用。
     */
    public Optional<OrderRow> lockOrderById(long id) {
        List<OrderRow> rows = jdbc.query("SELECT * FROM joint_order WHERE id = ? FOR UPDATE",
                ORDER_MAPPER, id);
        return rows.stream().findFirst();
    }

    /**
     * 按建单幂等请求号查询联检单。
     */
    public Optional<OrderRow> findOrderByRequestId(String requestId) {
        List<OrderRow> rows = jdbc.query("SELECT * FROM joint_order WHERE request_id = ?",
                ORDER_MAPPER, requestId);
        return rows.stream().findFirst();
    }

    /**
     * 按批次查询联检单（每批次至多一单）。
     */
    public Optional<OrderRow> findOrderByBatchId(long batchId) {
        List<OrderRow> rows = jdbc.query("SELECT * FROM joint_order WHERE batch_id = ?",
                ORDER_MAPPER, batchId);
        return rows.stream().findFirst();
    }

    /**
     * 查询联检单全部项目。
     */
    public List<OrderItemRow> findOrderItems(long orderId) {
        return jdbc.query("SELECT * FROM joint_order_item WHERE order_id = ? ORDER BY id",
                ORDER_ITEM_MAPPER, orderId);
    }

    /**
     * 乐观版本推进：仅当当前版本等于期望版本时状态推进并版本加 1，返回受影响行数（0 表示版本失配）。
     */
    public int advanceOrder(long orderId, int expectedVersion, String newStatus, LocalDateTime now) {
        return jdbc.update("UPDATE joint_order SET status = ?, version = version + 1, updated_at = ?"
                        + " WHERE id = ? AND version = ?",
                newStatus, Timestamp.valueOf(now), orderId, expectedVersion);
    }

    /**
     * 综合判定落定：仅当当前版本等于期望版本时置 JUDGED、记录判定时间并版本加 1，返回受影响行数。
     */
    public int judgeOrder(long orderId, int expectedVersion, LocalDateTime judgedAt, LocalDateTime now) {
        return jdbc.update("UPDATE joint_order SET status = 'JUDGED', version = version + 1,"
                        + " judged_at = ?, updated_at = ? WHERE id = ? AND version = ?",
                Timestamp.valueOf(judgedAt), Timestamp.valueOf(now), orderId, expectedVersion);
    }

    // ============ 分样 ============

    /**
     * 插入分样记录；requestId、单内项目或样本号唯一冲突时抛出 DuplicateKeyException。
     */
    public long insertAssignment(String requestId, String fingerprint, long orderId, String itemCode,
                                 String sampleNo, LocalDateTime now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO joint_assignment (request_id, fingerprint, order_id, item_code, sample_no, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requestId);
            ps.setString(2, fingerprint);
            ps.setLong(3, orderId);
            ps.setString(4, itemCode);
            ps.setString(5, sampleNo);
            ps.setTimestamp(6, Timestamp.valueOf(now));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 查询联检单全部分样。
     */
    public List<AssignmentRow> findAssignments(long orderId) {
        return jdbc.query("SELECT * FROM joint_assignment WHERE order_id = ? ORDER BY id",
                ASSIGNMENT_MAPPER, orderId);
    }

    /**
     * 查询联检单指定项目的分样。
     */
    public Optional<AssignmentRow> findAssignment(long orderId, String itemCode) {
        List<AssignmentRow> rows = jdbc.query(
                "SELECT * FROM joint_assignment WHERE order_id = ? AND item_code = ?",
                ASSIGNMENT_MAPPER, orderId, itemCode);
        return rows.stream().findFirst();
    }

    /**
     * 判断样本号是否已全局使用。
     */
    public boolean sampleNoExists(String sampleNo) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM joint_assignment WHERE sample_no = ?",
                Integer.class, sampleNo);
        return count != null && count > 0;
    }

    // ============ 联检结果 ============

    /**
     * 插入联检结果；requestId 唯一冲突时抛出 DuplicateKeyException。
     */
    public long insertResult(String requestId, String fingerprint, long orderId, long assignmentId,
                             String itemCode, BigDecimal concentration, boolean qualified,
                             int itemVersion, int orderVersion, LocalDateTime now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO joint_result (request_id, fingerprint, order_id, assignment_id, item_code,"
                            + " concentration, qualified, item_version, order_version, superseded, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, FALSE, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requestId);
            ps.setString(2, fingerprint);
            ps.setLong(3, orderId);
            ps.setLong(4, assignmentId);
            ps.setString(5, itemCode);
            ps.setBigDecimal(6, concentration);
            ps.setBoolean(7, qualified);
            ps.setInt(8, itemVersion);
            ps.setInt(9, orderVersion);
            ps.setTimestamp(10, Timestamp.valueOf(now));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 将联检单指定项目当前结果标记为已被复测替换。
     */
    public void supersedeCurrentResult(long orderId, String itemCode) {
        jdbc.update("UPDATE joint_result SET superseded = TRUE"
                + " WHERE order_id = ? AND item_code = ? AND superseded = FALSE", orderId, itemCode);
    }

    /**
     * 查询联检单全部结果（含历史版本）。
     */
    public List<ResultRow> findResults(long orderId) {
        return jdbc.query("SELECT * FROM joint_result WHERE order_id = ? ORDER BY id",
                RESULT_MAPPER, orderId);
    }

    /**
     * 查询联检单全部当前结果（未被复测替换）。
     */
    public List<ResultRow> findCurrentResults(long orderId) {
        return jdbc.query("SELECT * FROM joint_result WHERE order_id = ? AND superseded = FALSE ORDER BY id",
                RESULT_MAPPER, orderId);
    }

    /**
     * 查询联检单指定项目的当前结果。
     */
    public Optional<ResultRow> findCurrentResult(long orderId, String itemCode) {
        List<ResultRow> rows = jdbc.query(
                "SELECT * FROM joint_result WHERE order_id = ? AND item_code = ? AND superseded = FALSE",
                RESULT_MAPPER, orderId, itemCode);
        return rows.stream().findFirst();
    }

    /**
     * 查询联检单指定项目已用最大结果版本（无结果时为 0）。
     */
    public int maxItemVersion(long orderId, String itemCode) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(item_version), 0) FROM joint_result WHERE order_id = ? AND item_code = ?",
                Integer.class, orderId, itemCode);
        return max == null ? 0 : max;
    }

    // ============ 综合判定 ============

    /**
     * 插入综合判定；requestId 或联检单唯一冲突时抛出 DuplicateKeyException。
     */
    public long insertJudgment(String requestId, String fingerprint, long orderId, String verdict,
                               String itemVersions, String evidence, String evidenceFingerprint,
                               LocalDateTime judgedAt, LocalDateTime now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO joint_judgment (request_id, fingerprint, order_id, verdict, item_versions,"
                            + " evidence, evidence_fingerprint, judged_at, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requestId);
            ps.setString(2, fingerprint);
            ps.setLong(3, orderId);
            ps.setString(4, verdict);
            ps.setString(5, itemVersions);
            ps.setString(6, evidence);
            ps.setString(7, evidenceFingerprint);
            ps.setTimestamp(8, Timestamp.valueOf(judgedAt));
            ps.setTimestamp(9, Timestamp.valueOf(now));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 查询联检单综合判定。
     */
    public Optional<JudgmentRow> findJudgmentByOrderId(long orderId) {
        List<JudgmentRow> rows = jdbc.query("SELECT * FROM joint_judgment WHERE order_id = ?",
                JUDGMENT_MAPPER, orderId);
        return rows.stream().findFirst();
    }

    // ============ 补充信息 ============

    /**
     * 插入补充信息；requestId 唯一冲突时抛出 DuplicateKeyException。
     */
    public long insertSupplement(String requestId, String fingerprint, long orderId, String itemCode,
                                 String note, LocalDateTime now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO joint_supplement (request_id, fingerprint, order_id, item_code, note, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requestId);
            ps.setString(2, fingerprint);
            ps.setLong(3, orderId);
            ps.setString(4, itemCode);
            ps.setString(5, note);
            ps.setTimestamp(6, Timestamp.valueOf(now));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 查询联检单全部补充信息。
     */
    public List<SupplementRow> findSupplements(long orderId) {
        return jdbc.query("SELECT * FROM joint_supplement WHERE order_id = ? ORDER BY id",
                SUPPLEMENT_MAPPER, orderId);
    }

    // ============ 幂等记录 ============

    /**
     * 登记幂等记录；requestId 冲突时抛出 DuplicateKeyException。
     */
    public void insertIdempotency(String requestId, String fingerprint, String operation, long orderId,
                                  String responseJson, LocalDateTime now) {
        jdbc.update("INSERT INTO joint_idempotency (request_id, fingerprint, operation, order_id,"
                        + " response_json, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                requestId, fingerprint, operation, orderId, responseJson, Timestamp.valueOf(now));
    }

    /**
     * 按请求号查询幂等记录。
     */
    public Optional<IdempotencyRow> findIdempotency(String requestId) {
        List<IdempotencyRow> rows = jdbc.query("SELECT * FROM joint_idempotency WHERE request_id = ?",
                IDEMPOTENCY_MAPPER, requestId);
        return rows.stream().findFirst();
    }
}
