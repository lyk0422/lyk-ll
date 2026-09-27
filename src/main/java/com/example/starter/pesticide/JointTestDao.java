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
 * 定量联检数据访问：联检单、冻结项目、分样、版本化结果与综合判定。
 * 所有时间字段为 UTC 墙钟时间。
 */
@Repository
public class JointTestDao {

    /**
     * 联检单行记录。
     */
    public record OrderRow(
            long id,
            String requestId,
            String fingerprint,
            long batchId,
            String agencyCode,
            int version,
            String status,
            String overallResult,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    /**
     * 联检项目行记录（创建时冻结限值与能力版本）。
     */
    public record ItemRow(
            long id,
            long orderId,
            String item,
            BigDecimal limitValue,
            int capabilityVersion) {
    }

    /**
     * 分样行记录。
     */
    public record SplitRow(
            long id,
            long orderId,
            String requestId,
            String fingerprint,
            String item,
            String sampleNo,
            int orderVersion,
            LocalDateTime createdAt) {
    }

    /**
     * 联检结果行记录（复测保留旧结果，项目版本递增）。
     */
    public record ResultRow(
            long id,
            String requestId,
            String fingerprint,
            long orderId,
            long splitId,
            String item,
            BigDecimal concentration,
            boolean qualified,
            int itemVersion,
            int orderVersion,
            boolean current,
            boolean supplementary,
            LocalDateTime occurredAt,
            LocalDateTime createdAt) {
    }

    /**
     * 综合判定行记录（冻结证据）。
     */
    public record JudgmentRow(
            long id,
            String requestId,
            String fingerprint,
            long orderId,
            String overallResult,
            String itemVersions,
            int orderVersion,
            LocalDateTime createdAt) {
    }

    /**
     * 合成项目目录行。
     */
    public record CatalogRow(String item, BigDecimal limitValue, String unit) {
    }

    private static final RowMapper<OrderRow> ORDER_MAPPER = (rs, n) -> new OrderRow(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getString("fingerprint"),
            rs.getLong("batch_id"),
            rs.getString("agency_code"),
            rs.getInt("version"),
            rs.getString("status"),
            rs.getString("overall_result"),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("updated_at").toLocalDateTime());

    private static final RowMapper<ItemRow> ITEM_MAPPER = (rs, n) -> new ItemRow(
            rs.getLong("id"),
            rs.getLong("order_id"),
            rs.getString("item"),
            rs.getBigDecimal("limit_value"),
            rs.getInt("capability_version"));

    private static final RowMapper<SplitRow> SPLIT_MAPPER = (rs, n) -> new SplitRow(
            rs.getLong("id"),
            rs.getLong("order_id"),
            rs.getString("request_id"),
            rs.getString("fingerprint"),
            rs.getString("item"),
            rs.getString("sample_no"),
            rs.getInt("order_version"),
            rs.getTimestamp("created_at").toLocalDateTime());

    private static final RowMapper<ResultRow> RESULT_MAPPER = (rs, n) -> new ResultRow(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getString("fingerprint"),
            rs.getLong("order_id"),
            rs.getLong("split_id"),
            rs.getString("item"),
            rs.getBigDecimal("concentration"),
            rs.getBoolean("qualified"),
            rs.getInt("item_version"),
            rs.getInt("order_version"),
            rs.getBoolean("current_flag"),
            rs.getBoolean("supplementary"),
            rs.getTimestamp("occurred_at").toLocalDateTime(),
            rs.getTimestamp("created_at").toLocalDateTime());

    private static final RowMapper<JudgmentRow> JUDGMENT_MAPPER = (rs, n) -> new JudgmentRow(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getString("fingerprint"),
            rs.getLong("order_id"),
            rs.getString("overall_result"),
            rs.getString("item_versions"),
            rs.getInt("order_version"),
            rs.getTimestamp("created_at").toLocalDateTime());

    private final JdbcTemplate jdbc;

    public JointTestDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 查询合成项目目录条目。
     */
    public Optional<CatalogRow> findCatalogItem(String item) {
        List<CatalogRow> rows = jdbc.query(
                "SELECT item, limit_value, unit FROM item_catalog WHERE item = ?",
                (rs, n) -> new CatalogRow(rs.getString("item"), rs.getBigDecimal("limit_value"),
                        rs.getString("unit")),
                item);
        return rows.stream().findFirst();
    }

    /**
     * 查询机构对项目的能力版本。
     */
    public Optional<Integer> findCapabilityVersion(String agencyCode, String item) {
        List<Integer> rows = jdbc.query(
                "SELECT capability_version FROM agency_capability WHERE agency_code = ? AND item = ?",
                (rs, n) -> rs.getInt("capability_version"),
                agencyCode, item);
        return rows.stream().findFirst();
    }

    /**
     * 插入联检单（版本 1，状态 CREATED）；requestId 或批次唯一冲突时抛出 DuplicateKeyException。
     */
    public long insertOrder(String requestId, String fingerprint, long batchId, String agencyCode,
                            LocalDateTime now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO joint_test_order (request_id, fingerprint, batch_id, agency_code,"
                            + " version, status, overall_result, created_at, updated_at)"
                            + " VALUES (?, ?, ?, ?, 1, 'CREATED', NULL, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requestId);
            ps.setString(2, fingerprint);
            ps.setLong(3, batchId);
            ps.setString(4, agencyCode);
            ps.setTimestamp(5, Timestamp.valueOf(now));
            ps.setTimestamp(6, Timestamp.valueOf(now));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按幂等请求号查询联检单。
     */
    public Optional<OrderRow> findOrderByRequestId(String requestId) {
        List<OrderRow> rows = jdbc.query("SELECT * FROM joint_test_order WHERE request_id = ?",
                ORDER_MAPPER, requestId);
        return rows.stream().findFirst();
    }

    /**
     * 按主键查询联检单。
     */
    public Optional<OrderRow> findOrderById(long id) {
        List<OrderRow> rows = jdbc.query("SELECT * FROM joint_test_order WHERE id = ?", ORDER_MAPPER, id);
        return rows.stream().findFirst();
    }

    /**
     * 按主键行级锁定联检单（SELECT FOR UPDATE），须在事务内调用。
     */
    public Optional<OrderRow> lockOrderById(long id) {
        List<OrderRow> rows = jdbc.query("SELECT * FROM joint_test_order WHERE id = ? FOR UPDATE",
                ORDER_MAPPER, id);
        return rows.stream().findFirst();
    }

    /**
     * 按批次查询联检单（每批次至多一单）。
     */
    public Optional<OrderRow> findOrderByBatchId(long batchId) {
        List<OrderRow> rows = jdbc.query("SELECT * FROM joint_test_order WHERE batch_id = ?",
                ORDER_MAPPER, batchId);
        return rows.stream().findFirst();
    }

    /**
     * 乐观锁版本推进：仅当当前版本等于期望版本时版本加一并更新状态/结论，返回影响行数。
     */
    public int bumpVersion(long orderId, int expectedVersion, String newStatus, String overallResult,
                           LocalDateTime now) {
        return jdbc.update(
                "UPDATE joint_test_order SET version = version + 1, status = ?, overall_result = ?,"
                        + " updated_at = ? WHERE id = ? AND version = ?",
                newStatus, overallResult, Timestamp.valueOf(now), orderId, expectedVersion);
    }

    /**
     * 插入联检项目（冻结限值与能力版本）。
     */
    public void insertItem(long orderId, String item, BigDecimal limitValue, int capabilityVersion) {
        jdbc.update("INSERT INTO joint_test_item (order_id, item, limit_value, capability_version)"
                        + " VALUES (?, ?, ?, ?)",
                orderId, item, limitValue, capabilityVersion);
    }

    /**
     * 查询联检单全部冻结项目。
     */
    public List<ItemRow> findItems(long orderId) {
        return jdbc.query("SELECT * FROM joint_test_item WHERE order_id = ? ORDER BY id",
                ITEM_MAPPER, orderId);
    }

    /**
     * 插入分样；requestId、单内项目或样本号唯一冲突时抛出 DuplicateKeyException。
     */
    public void insertSplit(long orderId, String requestId, String fingerprint, String item,
                            String sampleNo, int orderVersion, LocalDateTime now) {
        jdbc.update("INSERT INTO joint_test_split (order_id, request_id, fingerprint, item, sample_no,"
                        + " order_version, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                orderId, requestId, fingerprint, item, sampleNo, orderVersion,
                Timestamp.valueOf(now));
    }

    /**
     * 按幂等请求号查询分样（同次分样多行）。
     */
    public List<SplitRow> findSplitsByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM joint_test_split WHERE request_id = ? ORDER BY id",
                SPLIT_MAPPER, requestId);
    }

    /**
     * 查询联检单全部分样。
     */
    public List<SplitRow> findSplits(long orderId) {
        return jdbc.query("SELECT * FROM joint_test_split WHERE order_id = ? ORDER BY id",
                SPLIT_MAPPER, orderId);
    }

    /**
     * 按联检单+项目查询分样。
     */
    public Optional<SplitRow> findSplit(long orderId, String item) {
        List<SplitRow> rows = jdbc.query(
                "SELECT * FROM joint_test_split WHERE order_id = ? AND item = ?",
                SPLIT_MAPPER, orderId, item);
        return rows.stream().findFirst();
    }

    /**
     * 判断样本号是否已占用（全局唯一）。
     */
    public boolean sampleNoExists(String sampleNo) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM joint_test_split WHERE sample_no = ?",
                Integer.class, sampleNo);
        return count != null && count > 0;
    }

    /**
     * 插入联检结果；requestId 唯一冲突时抛出 DuplicateKeyException。
     */
    public long insertResult(String requestId, String fingerprint, long orderId, long splitId,
                             String item, BigDecimal concentration, boolean qualified,
                             int itemVersion, int orderVersion, boolean current, boolean supplementary,
                             LocalDateTime occurredAt, LocalDateTime now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO joint_test_result (request_id, fingerprint, order_id, split_id, item,"
                            + " concentration, qualified, item_version, order_version, current_flag,"
                            + " supplementary, occurred_at, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requestId);
            ps.setString(2, fingerprint);
            ps.setLong(3, orderId);
            ps.setLong(4, splitId);
            ps.setString(5, item);
            ps.setBigDecimal(6, concentration);
            ps.setBoolean(7, qualified);
            ps.setInt(8, itemVersion);
            ps.setInt(9, orderVersion);
            ps.setBoolean(10, current);
            ps.setBoolean(11, supplementary);
            ps.setTimestamp(12, Timestamp.valueOf(occurredAt));
            ps.setTimestamp(13, Timestamp.valueOf(now));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按幂等请求号查询联检结果。
     */
    public Optional<ResultRow> findResultByRequestId(String requestId) {
        List<ResultRow> rows = jdbc.query("SELECT * FROM joint_test_result WHERE request_id = ?",
                RESULT_MAPPER, requestId);
        return rows.stream().findFirst();
    }

    /**
     * 查询项目当前有效结果。
     */
    public Optional<ResultRow> findCurrentResult(long orderId, String item) {
        List<ResultRow> rows = jdbc.query(
                "SELECT * FROM joint_test_result WHERE order_id = ? AND item = ? AND current_flag = TRUE",
                RESULT_MAPPER, orderId, item);
        return rows.stream().findFirst();
    }

    /**
     * 查询联检单全部当前有效结果。
     */
    public List<ResultRow> findCurrentResults(long orderId) {
        return jdbc.query(
                "SELECT * FROM joint_test_result WHERE order_id = ? AND current_flag = TRUE ORDER BY id",
                RESULT_MAPPER, orderId);
    }

    /**
     * 查询联检单全部结果（含历史与补充）。
     */
    public List<ResultRow> findResults(long orderId) {
        return jdbc.query("SELECT * FROM joint_test_result WHERE order_id = ? ORDER BY id",
                RESULT_MAPPER, orderId);
    }

    /**
     * 将结果置为非当前（复测替换时保留旧结果）。
     */
    public void markResultNotCurrent(long resultId) {
        jdbc.update("UPDATE joint_test_result SET current_flag = FALSE WHERE id = ?", resultId);
    }

    /**
     * 插入综合判定；requestId 或联检单唯一冲突时抛出 DuplicateKeyException。
     */
    public long insertJudgment(String requestId, String fingerprint, long orderId, String overallResult,
                               String itemVersions, int orderVersion, LocalDateTime now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO joint_test_judgment (request_id, fingerprint, order_id, overall_result,"
                            + " item_versions, order_version, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requestId);
            ps.setString(2, fingerprint);
            ps.setLong(3, orderId);
            ps.setString(4, overallResult);
            ps.setString(5, itemVersions);
            ps.setInt(6, orderVersion);
            ps.setTimestamp(7, Timestamp.valueOf(now));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按幂等请求号查询综合判定。
     */
    public Optional<JudgmentRow> findJudgmentByRequestId(String requestId) {
        List<JudgmentRow> rows = jdbc.query("SELECT * FROM joint_test_judgment WHERE request_id = ?",
                JUDGMENT_MAPPER, requestId);
        return rows.stream().findFirst();
    }

    /**
     * 按联检单查询综合判定（每单至多一条）。
     */
    public Optional<JudgmentRow> findJudgmentByOrderId(long orderId) {
        List<JudgmentRow> rows = jdbc.query("SELECT * FROM joint_test_judgment WHERE order_id = ?",
                JUDGMENT_MAPPER, orderId);
        return rows.stream().findFirst();
    }
}
