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
 * 联检数据访问：联检单、冻结项目、分样、结果、综合判定与幂等台账。
 * 所有时间字段为 UTC 墙钟时间；目录与机构能力均为合成数据。
 */
@Repository
public class JointDao {

    /**
     * 联检单行。
     */
    public record JointOrderRow(
            long id,
            long batchId,
            long sourceTestId,
            String status,
            int version,
            String capabilityVersion,
            String overallResult,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    /**
     * 联检项目行（建单冻结）。
     */
    public record JointItemRow(
            long id,
            long orderId,
            String item,
            BigDecimal limitValue,
            String catalogVersion) {
    }

    /**
     * 分样分配行。
     */
    public record AssignmentRow(
            long id,
            long orderId,
            String item,
            String sampleNo,
            String agencyCode,
            String agencyCapabilityVersion,
            LocalDateTime createdAt) {
    }

    /**
     * 联检结果行。
     */
    public record JointResultRow(
            long id,
            long orderId,
            String item,
            String sampleNo,
            int orderVersion,
            int resultVersion,
            BigDecimal concentration,
            boolean qualified,
            LocalDateTime occurredAt,
            LocalDateTime createdAt) {
    }

    /**
     * 综合判定证据快照行。
     */
    public record DeterminationItemRow(
            long id,
            long determinationId,
            String item,
            int resultVersion,
            BigDecimal concentration,
            boolean qualified) {
    }

    /**
     * 综合判定行。
     */
    public record DeterminationRow(
            long id,
            long orderId,
            String overallResult,
            LocalDateTime createdAt) {
    }

    /**
     * 幂等台账行。
     */
    public record RequestLogRow(
            String requestId,
            String fingerprint,
            String operation,
            String response,
            LocalDateTime createdAt) {
    }

    /**
     * 合成项目目录行。
     */
    public record CatalogItemRow(
            String item,
            BigDecimal limitValue,
            String catalogVersion) {
    }

    /**
     * 合成机构行。
     */
    public record AgencyRow(
            String code,
            String name,
            String capabilityVersion) {
    }

    private static final RowMapper<JointOrderRow> ORDER_MAPPER = (rs, n) -> new JointOrderRow(
            rs.getLong("id"),
            rs.getLong("batch_id"),
            rs.getLong("source_test_id"),
            rs.getString("status"),
            rs.getInt("version"),
            rs.getString("capability_version"),
            rs.getString("overall_result"),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("updated_at").toLocalDateTime());

    private static final RowMapper<JointItemRow> ITEM_MAPPER = (rs, n) -> new JointItemRow(
            rs.getLong("id"),
            rs.getLong("order_id"),
            rs.getString("item"),
            rs.getBigDecimal("limit_value"),
            rs.getString("catalog_version"));

    private static final RowMapper<AssignmentRow> ASSIGNMENT_MAPPER = (rs, n) -> new AssignmentRow(
            rs.getLong("id"),
            rs.getLong("order_id"),
            rs.getString("item"),
            rs.getString("sample_no"),
            rs.getString("agency_code"),
            rs.getString("agency_capability_version"),
            rs.getTimestamp("created_at").toLocalDateTime());

    private static final RowMapper<JointResultRow> RESULT_MAPPER = (rs, n) -> new JointResultRow(
            rs.getLong("id"),
            rs.getLong("order_id"),
            rs.getString("item"),
            rs.getString("sample_no"),
            rs.getInt("order_version"),
            rs.getInt("result_version"),
            rs.getBigDecimal("concentration"),
            rs.getBoolean("qualified"),
            rs.getTimestamp("occurred_at").toLocalDateTime(),
            rs.getTimestamp("created_at").toLocalDateTime());

    private static final RowMapper<DeterminationItemRow> DET_ITEM_MAPPER = (rs, n) -> new DeterminationItemRow(
            rs.getLong("id"),
            rs.getLong("determination_id"),
            rs.getString("item"),
            rs.getInt("result_version"),
            rs.getBigDecimal("concentration"),
            rs.getBoolean("qualified"));

    private static final RowMapper<DeterminationRow> DET_MAPPER = (rs, n) -> new DeterminationRow(
            rs.getLong("id"),
            rs.getLong("order_id"),
            rs.getString("overall_result"),
            rs.getTimestamp("created_at").toLocalDateTime());

    private static final RowMapper<RequestLogRow> LOG_MAPPER = (rs, n) -> new RequestLogRow(
            rs.getString("request_id"),
            rs.getString("fingerprint"),
            rs.getString("operation"),
            rs.getString("response"),
            rs.getTimestamp("created_at").toLocalDateTime());

    private static final RowMapper<CatalogItemRow> CATALOG_MAPPER = (rs, n) -> new CatalogItemRow(
            rs.getString("item"),
            rs.getBigDecimal("limit_value"),
            rs.getString("catalog_version"));

    private static final RowMapper<AgencyRow> AGENCY_MAPPER = (rs, n) -> new AgencyRow(
            rs.getString("code"),
            rs.getString("name"),
            rs.getString("capability_version"));

    private final JdbcTemplate jdbc;

    public JointDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- 合成目录与机构能力 ----------

    /**
     * 查询全部合成项目目录。
     */
    public List<CatalogItemRow> findCatalogItems() {
        return jdbc.query("SELECT * FROM catalog_item ORDER BY item", CATALOG_MAPPER);
    }

    /**
     * 按项目名查询合成目录条目。
     */
    public Optional<CatalogItemRow> findCatalogItem(String item) {
        List<CatalogItemRow> rows = jdbc.query("SELECT * FROM catalog_item WHERE item = ?",
                CATALOG_MAPPER, item);
        return rows.stream().findFirst();
    }

    /**
     * 查询全部合成机构。
     */
    public List<AgencyRow> findAgencies() {
        return jdbc.query("SELECT * FROM agency ORDER BY code", AGENCY_MAPPER);
    }

    /**
     * 按编码查询合成机构。
     */
    public Optional<AgencyRow> findAgency(String code) {
        List<AgencyRow> rows = jdbc.query("SELECT * FROM agency WHERE code = ?", AGENCY_MAPPER, code);
        return rows.stream().findFirst();
    }

    /**
     * 查询机构可承检项目列表。
     */
    public List<String> findAgencyItems(String agencyCode) {
        return jdbc.queryForList(
                "SELECT item FROM agency_capability WHERE agency_code = ? ORDER BY item",
                String.class, agencyCode);
    }

    /**
     * 判断机构是否具备指定项目能力。
     */
    public boolean agencyCapable(String agencyCode, String item) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agency_capability WHERE agency_code = ? AND item = ?",
                Integer.class, agencyCode, item);
        return count != null && count > 0;
    }

    /**
     * 查询机构能力目录版本（单行）。
     */
    public String capabilityDirectoryVersion() {
        return jdbc.queryForObject("SELECT version FROM capability_directory WHERE id = 1",
                String.class);
    }

    // ---------- 联检单 ----------

    /**
     * 插入联检单（版本从 1 开始）；批次唯一冲突时抛出 DuplicateKeyException。
     */
    public long insertOrder(long batchId, long sourceTestId, String status,
                            String capabilityVersion, LocalDateTime now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO joint_order (batch_id, source_test_id, status, version,"
                            + " capability_version, overall_result, created_at, updated_at)"
                            + " VALUES (?, ?, ?, 1, ?, NULL, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, batchId);
            ps.setLong(2, sourceTestId);
            ps.setString(3, status);
            ps.setString(4, capabilityVersion);
            ps.setTimestamp(5, Timestamp.valueOf(now));
            ps.setTimestamp(6, Timestamp.valueOf(now));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按主键查询联检单。
     */
    public Optional<JointOrderRow> findOrderById(long id) {
        List<JointOrderRow> rows = jdbc.query("SELECT * FROM joint_order WHERE id = ?",
                ORDER_MAPPER, id);
        return rows.stream().findFirst();
    }

    /**
     * 按主键行级锁定联检单（SELECT FOR UPDATE），须在事务内调用。
     */
    public Optional<JointOrderRow> lockOrderById(long id) {
        List<JointOrderRow> rows = jdbc.query("SELECT * FROM joint_order WHERE id = ? FOR UPDATE",
                ORDER_MAPPER, id);
        return rows.stream().findFirst();
    }

    /**
     * 按批次查询联检单（每批次至多一张）。
     */
    public Optional<JointOrderRow> findOrderByBatch(long batchId) {
        List<JointOrderRow> rows = jdbc.query("SELECT * FROM joint_order WHERE batch_id = ?",
                ORDER_MAPPER, batchId);
        return rows.stream().findFirst();
    }

    /**
     * 乐观推进联检单：仅当当前版本等于 expectedVersion 时状态变更并版本加 1；
     * 返回更新行数，0 表示版本失配（零写入）。
     */
    public int advanceOrder(long id, int expectedVersion, String newStatus,
                            String overallResult, LocalDateTime now) {
        return jdbc.update(
                "UPDATE joint_order SET status = ?, version = version + 1, overall_result = ?,"
                        + " updated_at = ? WHERE id = ? AND version = ?",
                newStatus, overallResult, Timestamp.valueOf(now), id, expectedVersion);
    }

    // ---------- 联检项目 ----------

    /**
     * 插入冻结的联检项目。
     */
    public long insertItem(long orderId, String item, BigDecimal limitValue, String catalogVersion) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO joint_order_item (order_id, item, limit_value, catalog_version)"
                            + " VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, orderId);
            ps.setString(2, item);
            ps.setBigDecimal(3, limitValue);
            ps.setString(4, catalogVersion);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 查询联检单全部冻结项目。
     */
    public List<JointItemRow> findItems(long orderId) {
        return jdbc.query("SELECT * FROM joint_order_item WHERE order_id = ? ORDER BY id",
                ITEM_MAPPER, orderId);
    }

    // ---------- 分样 ----------

    /**
     * 插入分样分配；样本号全局唯一或单内项目重复时抛出 DuplicateKeyException。
     */
    public long insertAssignment(long orderId, String item, String sampleNo, String agencyCode,
                                 String agencyCapabilityVersion, LocalDateTime now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO joint_assignment (order_id, item, sample_no, agency_code,"
                            + " agency_capability_version, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, orderId);
            ps.setString(2, item);
            ps.setString(3, sampleNo);
            ps.setString(4, agencyCode);
            ps.setString(5, agencyCapabilityVersion);
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
     * 按联检单+项目查询分样。
     */
    public Optional<AssignmentRow> findAssignment(long orderId, String item) {
        List<AssignmentRow> rows = jdbc.query(
                "SELECT * FROM joint_assignment WHERE order_id = ? AND item = ?",
                ASSIGNMENT_MAPPER, orderId, item);
        return rows.stream().findFirst();
    }

    // ---------- 联检结果 ----------

    /**
     * 插入联检结果（首次或复测，结果版本递增）；(单,项目,版本) 唯一冲突时抛出 DuplicateKeyException。
     */
    public long insertResult(long orderId, String item, String sampleNo, int orderVersion,
                             int resultVersion, BigDecimal concentration, boolean qualified,
                             LocalDateTime occurredAt, LocalDateTime now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO joint_result (order_id, item, sample_no, order_version,"
                            + " result_version, concentration, qualified, occurred_at, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, orderId);
            ps.setString(2, item);
            ps.setString(3, sampleNo);
            ps.setInt(4, orderVersion);
            ps.setInt(5, resultVersion);
            ps.setBigDecimal(6, concentration);
            ps.setBoolean(7, qualified);
            ps.setTimestamp(8, Timestamp.valueOf(occurredAt));
            ps.setTimestamp(9, Timestamp.valueOf(now));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 查询联检单全部结果（含历史复测版本，按项目与版本排序）。
     */
    public List<JointResultRow> findResults(long orderId) {
        return jdbc.query("SELECT * FROM joint_result WHERE order_id = ?"
                        + " ORDER BY item, result_version",
                RESULT_MAPPER, orderId);
    }

    /**
     * 查询联检单各项目当前（最高版本）结果。
     */
    public List<JointResultRow> findCurrentResults(long orderId) {
        return jdbc.query(
                "SELECT r.* FROM joint_result r"
                        + " JOIN (SELECT item, MAX(result_version) AS max_version"
                        + "       FROM joint_result WHERE order_id = ? GROUP BY item) latest"
                        + "   ON r.order_id = ? AND r.item = latest.item"
                        + "  AND r.result_version = latest.max_version"
                        + " ORDER BY r.item",
                RESULT_MAPPER, orderId, orderId);
    }

    /**
     * 查询指定项目的最高结果版本；无结果返回 0。
     */
    public int maxResultVersion(long orderId, String item) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(result_version), 0) FROM joint_result"
                        + " WHERE order_id = ? AND item = ?",
                Integer.class, orderId, item);
        return max == null ? 0 : max;
    }

    // ---------- 综合判定 ----------

    /**
     * 登记综合判定；每单至多一次，重复时抛出 DuplicateKeyException。
     */
    public long insertDetermination(long orderId, String overallResult, LocalDateTime now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO joint_determination (order_id, overall_result, created_at)"
                            + " VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, orderId);
            ps.setString(2, overallResult);
            ps.setTimestamp(3, Timestamp.valueOf(now));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 登记综合判定单项证据快照。
     */
    public long insertDeterminationItem(long determinationId, String item, int resultVersion,
                                        BigDecimal concentration, boolean qualified) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO joint_determination_item (determination_id, item, result_version,"
                            + " concentration, qualified) VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, determinationId);
            ps.setString(2, item);
            ps.setInt(3, resultVersion);
            ps.setBigDecimal(4, concentration);
            ps.setBoolean(5, qualified);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按联检单查询综合判定。
     */
    public Optional<DeterminationRow> findDetermination(long orderId) {
        List<DeterminationRow> rows = jdbc.query(
                "SELECT * FROM joint_determination WHERE order_id = ?", DET_MAPPER, orderId);
        return rows.stream().findFirst();
    }

    /**
     * 查询综合判定全部证据快照。
     */
    public List<DeterminationItemRow> findDeterminationItems(long determinationId) {
        return jdbc.query("SELECT * FROM joint_determination_item WHERE determination_id = ?"
                        + " ORDER BY item",
                DET_ITEM_MAPPER, determinationId);
    }

    // ---------- 幂等台账 ----------

    /**
     * 按幂等请求号查询台账。
     */
    public Optional<RequestLogRow> findRequestLog(String requestId) {
        List<RequestLogRow> rows = jdbc.query(
                "SELECT * FROM joint_request_log WHERE request_id = ?", LOG_MAPPER, requestId);
        return rows.stream().findFirst();
    }

    /**
     * 登记幂等台账（与业务同事务）；requestId 冲突时抛出 DuplicateKeyException。
     */
    public void insertRequestLog(String requestId, String fingerprint, String operation,
                                 String response, LocalDateTime now) {
        jdbc.update(
                "INSERT INTO joint_request_log (request_id, fingerprint, operation, response,"
                        + " created_at) VALUES (?, ?, ?, ?, ?)",
                requestId, fingerprint, operation, response, Timestamp.valueOf(now));
    }
}
