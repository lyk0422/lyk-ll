package com.example.starter.museum;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 点交影像服务：出借/归还点交登记合成影像特征清单（与点交单同一事务落库），
 * 归还点交生效时自动与出借基线比对，差异项超过 0 生成差异工单；
 * 差异工单未闭环前合同不得结案，同一合同同一时刻仅一个进行中工单。
 */
@Service
public class ImageHandoverService {

    /** 差异工单状态：进行中（未闭环）。 */
    public static final String ORDER_OPEN = "OPEN";
    /** 差异工单状态：已闭环（终态）。 */
    public static final String ORDER_CLOSED = "CLOSED";

    /** 差异类型：同编码特征值不一致。 */
    public static final String DIFF_VALUE_MISMATCH = "VALUE_MISMATCH";
    /** 差异类型：归还清单缺失该特征。 */
    public static final String DIFF_MISSING_ON_RETURN = "MISSING_ON_RETURN";
    /** 差异类型：出借清单无此特征（归还新增）。 */
    public static final String DIFF_MISSING_ON_DELIVERY = "MISSING_ON_DELIVERY";

    /** 合成影像特征条目。 */
    public record ImageFeature(String code, String value) {
    }

    /** 比对中的单个差异（含双方特征值，供落库与视图复用）。 */
    private record Diff(String code, String type, String deliveryValue, String returnValue) {
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ImageHandoverService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * 登记点交合成影像特征清单：点交单与特征在同一事务内落库，每个点交单仅允许登记一次。
     * 出借点交特征须在交付点交生效前（合同 APPLIED）登记；归还特征须在归还点交生效前（ON_DISPLAY）登记。
     */
    @Transactional
    public Map<String, Object> registerFeatures(long contractId, String handoverType,
            List<ImageFeature> features) {
        if (!"DELIVERY".equals(handoverType) && !"RETURN".equals(handoverType)) {
            throw ApiException.badRequest("点交类型须为 DELIVERY 或 RETURN");
        }
        if (features == null || features.isEmpty()) {
            throw ApiException.badRequest("影像特征清单不能为空");
        }
        String contractStatus = contractStatus(contractId);
        String required = "DELIVERY".equals(handoverType)
                ? LoanService.STATUS_APPLIED : LoanService.STATUS_ON_DISPLAY;
        if (!required.equals(contractStatus)) {
            throw ApiException.conflict("合同状态为 " + contractStatus
                    + ("DELIVERY".equals(handoverType)
                            ? "，出借点交影像特征须在交付点交生效前登记"
                            : "，归还点交影像特征须在归还点交生效前（在展中）登记"));
        }
        long handoverId = ensureHandover(contractId, handoverType);
        Integer existing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM image_feature WHERE handover_id = ?", Integer.class, handoverId);
        if (existing != null && existing > 0) {
            throw ApiException.conflict("该点交单的合成影像特征清单已登记，不可重复登记");
        }
        Set<String> seenCodes = new HashSet<>();
        List<ImageFeature> normalized = new ArrayList<>();
        for (ImageFeature feature : features) {
            if (feature == null || feature.code() == null || feature.code().isBlank()
                    || feature.value() == null || feature.value().isBlank()) {
                throw ApiException.badRequest("影像特征编码与特征值均不能为空");
            }
            String code = feature.code().trim();
            if (!seenCodes.add(code)) {
                throw ApiException.badRequest("影像特征编码重复：" + code);
            }
            normalized.add(new ImageFeature(code, feature.value().trim()));
        }
        Instant now = Instant.now(clock);
        for (ImageFeature feature : normalized) {
            try {
                jdbc.update(
                        "INSERT INTO image_feature (handover_id, contract_id, handover_type,"
                                + " feature_code, feature_value, created_at) VALUES (?,?,?,?,?,?)",
                        handoverId, contractId, handoverType, feature.code(), feature.value(),
                        Timestamp.from(now));
            } catch (DuplicateKeyException e) {
                // 并发登记同一特征编码：清单唯一性冲突
                throw ApiException.conflict("该点交单的合成影像特征清单已登记或特征编码冲突：" + feature.code());
            }
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("contractId", contractId);
        view.put("handoverId", handoverId);
        view.put("handoverType", handoverType);
        view.put("featureCount", normalized.size());
        view.put("features", normalized.stream().map(f -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", f.code());
            item.put("value", f.value());
            return item;
        }).toList());
        return view;
    }

    /**
     * 归还点交生效后自动比对出借与归还特征清单；差异项超过 0 时生成差异工单。
     * 在归还点交确认事务内调用，比对、工单与合同状态变更原子提交。
     * 未做影像留档（归还清单为空）时不生成工单，沿用原有点交流程。
     *
     * @param requestId 触发本次归还点交的幂等请求号，服务内直接调用时为 null
     * @return 比对结果视图（无差异或未留档时 discrepancyOrderId 为 null）
     */
    @Transactional
    public Map<String, Object> compareOnReturn(long contractId, String requestId) {
        Map<String, String> delivery = loadFeatures(contractId, "DELIVERY");
        Map<String, String> returned = loadFeatures(contractId, "RETURN");
        List<Diff> diffs = new ArrayList<>();
        for (String code : unionCodes(delivery, returned)) {
            String deliveryValue = delivery.get(code);
            String returnValue = returned.get(code);
            if (deliveryValue == null) {
                diffs.add(new Diff(code, DIFF_MISSING_ON_DELIVERY, null, returnValue));
            } else if (returnValue == null) {
                diffs.add(new Diff(code, DIFF_MISSING_ON_RETURN, deliveryValue, null));
            } else if (!deliveryValue.equals(returnValue)) {
                diffs.add(new Diff(code, DIFF_VALUE_MISMATCH, deliveryValue, returnValue));
            }
        }
        Long orderId = null;
        boolean reusedOpenOrder = false;
        if (!returned.isEmpty() && !diffs.isEmpty()) {
            Instant now = Instant.now(clock);
            KeyHolder keyHolder = new GeneratedKeyHolder();
            try {
                jdbc.update(connection -> {
                    var ps = connection.prepareStatement(
                            "INSERT INTO discrepancy_order (contract_id, request_id, status, diff_count,"
                                    + " active_contract_id, created_at) VALUES (?,?,?,?,?,?)",
                            Statement.RETURN_GENERATED_KEYS);
                    ps.setLong(1, contractId);
                    ps.setString(2, requestId);
                    ps.setString(3, ORDER_OPEN);
                    ps.setInt(4, diffs.size());
                    ps.setLong(5, contractId);
                    ps.setTimestamp(6, Timestamp.from(now));
                    return ps;
                }, keyHolder);
                orderId = keyHolder.getKey().longValue();
            } catch (DuplicateKeyException e) {
                // 同一合同已有进行中工单：唯一水位约束兜底，复用既有工单而不重复生成
                reusedOpenOrder = true;
                orderId = findOpenOrderId(contractId);
            }
            if (orderId != null && !reusedOpenOrder) {
                for (Diff diff : diffs) {
                    jdbc.update(
                            "INSERT INTO discrepancy_item (order_id, feature_code, diff_type,"
                                    + " delivery_value, return_value) VALUES (?,?,?,?,?)",
                            orderId, diff.code(), diff.type(), diff.deliveryValue(), diff.returnValue());
                }
            }
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("contractId", contractId);
        view.put("imageCompared", !returned.isEmpty());
        view.put("deliveryFeatureCount", delivery.size());
        view.put("returnFeatureCount", returned.size());
        view.put("diffCount", diffs.size());
        view.put("discrepancyOrderId", orderId);
        view.put("reusedOpenOrder", reusedOpenOrder);
        view.put("differences", diffs.stream().map(this::diffView).toList());
        return view;
    }

    /**
     * 闭环差异工单：仅进行中工单可闭环（条件更新保证并发下唯一闭环），闭环后合同方可结案。
     * 重复闭环已闭环工单返回幂等成功。
     */
    @Transactional
    public Map<String, Object> closeOrder(long orderId, String handler) {
        if (handler == null || handler.isBlank()) {
            throw ApiException.badRequest("闭环处置人 handler 不能为空");
        }
        Map<String, Object> order = findOrderById(orderId);
        if (ORDER_CLOSED.equals(order.get("status"))) {
            Map<String, Object> view = orderView(order);
            view.put("idempotentReplay", true);
            return view;
        }
        Instant now = Instant.now(clock);
        int updated = jdbc.update(
                "UPDATE discrepancy_order SET status = ?, active_contract_id = NULL,"
                        + " closed_at = ?, closed_by = ? WHERE id = ? AND status = ?",
                ORDER_CLOSED, Timestamp.from(now), handler.trim(), orderId, ORDER_OPEN);
        if (updated == 0) {
            Map<String, Object> current = findOrderById(orderId);
            if (ORDER_CLOSED.equals(current.get("status"))) {
                Map<String, Object> view = orderView(current);
                view.put("idempotentReplay", true);
                return view;
            }
            throw ApiException.conflict("差异工单状态已变更，闭环未生效");
        }
        return orderView(findOrderById(orderId));
    }

    /** 查询差异工单详情（含逐项差异明细）。 */
    public Map<String, Object> getOrder(long orderId) {
        return orderView(findOrderById(orderId));
    }

    /** 查询合同下全部差异工单（按生成顺序，不含差异明细）。 */
    public List<Map<String, Object>> ordersForContract(long contractId) {
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT * FROM discrepancy_order WHERE contract_id = ? ORDER BY id",
                (rs, rowNum) -> orderRowView(rs), contractId);
        return rows;
    }

    /** 统计合同当前进行中（未闭环）差异工单数，供结案在同一事务内原子判定。 */
    int countOpenOrders(long contractId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM discrepancy_order WHERE contract_id = ? AND status = ?",
                Integer.class, contractId, ORDER_OPEN);
        return count == null ? 0 : count;
    }

    private long ensureHandover(long contractId, String handoverType) {
        // 合同须存在
        contractStatus(contractId);
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM handover WHERE contract_id = ? AND handover_type = ?",
                Long.class, contractId, handoverType);
        if (!ids.isEmpty()) {
            return ids.get(0);
        }
        try {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                var ps = connection.prepareStatement(
                        "INSERT INTO handover (contract_id, handover_type, created_at) VALUES (?,?,?)",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1, contractId);
                ps.setString(2, handoverType);
                ps.setTimestamp(3, Timestamp.from(Instant.now(clock)));
                return ps;
            }, keyHolder);
            return keyHolder.getKey().longValue();
        } catch (DuplicateKeyException e) {
            return jdbc.queryForObject(
                    "SELECT id FROM handover WHERE contract_id = ? AND handover_type = ?",
                    Long.class, contractId, handoverType);
        }
    }

    private String contractStatus(long contractId) {
        try {
            return jdbc.queryForObject(
                    "SELECT status FROM loan_contract WHERE id = ?", String.class, contractId);
        } catch (EmptyResultDataAccessException e) {
            throw ApiException.notFound("合同不存在：" + contractId);
        }
    }

    private Map<String, String> loadFeatures(long contractId, String handoverType) {
        Map<String, String> features = new LinkedHashMap<>();
        jdbc.query("SELECT feature_code, feature_value FROM image_feature"
                + " WHERE contract_id = ? AND handover_type = ? ORDER BY id",
                rs -> {
                    features.put(rs.getString("feature_code"), rs.getString("feature_value"));
                }, contractId, handoverType);
        return features;
    }

    private List<String> unionCodes(Map<String, String> delivery, Map<String, String> returned) {
        List<String> codes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String code : delivery.keySet()) {
            if (seen.add(code)) {
                codes.add(code);
            }
        }
        for (String code : returned.keySet()) {
            if (seen.add(code)) {
                codes.add(code);
            }
        }
        return codes;
    }

    private Long findOpenOrderId(long contractId) {
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM discrepancy_order WHERE contract_id = ? AND status = ? ORDER BY id",
                Long.class, contractId, ORDER_OPEN);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private Map<String, Object> findOrderById(long orderId) {
        try {
            return jdbc.queryForMap("SELECT * FROM discrepancy_order WHERE id = ?", orderId);
        } catch (EmptyResultDataAccessException e) {
            throw ApiException.notFound("差异工单不存在：" + orderId);
        }
    }

    private Map<String, Object> diffView(Diff diff) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("code", diff.code());
        item.put("type", diff.type());
        item.put("deliveryValue", diff.deliveryValue());
        item.put("returnValue", diff.returnValue());
        return item;
    }

    private Map<String, Object> orderRowView(ResultSet rs) throws SQLException {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("orderId", rs.getLong("id"));
        view.put("contractId", rs.getLong("contract_id"));
        view.put("status", rs.getString("status"));
        view.put("diffCount", rs.getInt("diff_count"));
        Timestamp closedAt = rs.getTimestamp("closed_at");
        view.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
        view.put("closedAt", closedAt == null ? null : closedAt.toInstant().toString());
        view.put("closedBy", rs.getString("closed_by"));
        return view;
    }

    private Map<String, Object> orderView(Map<String, Object> row) {
        long orderId = ((Number) row.get("id")).longValue();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("orderId", orderId);
        view.put("contractId", ((Number) row.get("contract_id")).longValue());
        view.put("status", row.get("status"));
        view.put("diffCount", ((Number) row.get("diff_count")).intValue());
        Timestamp createdAt = (Timestamp) row.get("created_at");
        Timestamp closedAt = (Timestamp) row.get("closed_at");
        view.put("createdAt", createdAt.toInstant().toString());
        view.put("closedAt", closedAt == null ? null : closedAt.toInstant().toString());
        view.put("closedBy", row.get("closed_by"));
        List<Map<String, Object>> items = jdbc.query(
                "SELECT feature_code, diff_type, delivery_value, return_value"
                        + " FROM discrepancy_item WHERE order_id = ? ORDER BY id",
                (rs, rowNum) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("code", rs.getString("feature_code"));
                    item.put("type", rs.getString("diff_type"));
                    item.put("deliveryValue", rs.getString("delivery_value"));
                    item.put("returnValue", rs.getString("return_value"));
                    return item;
                }, orderId);
        view.put("items", items);
        return view;
    }
}
