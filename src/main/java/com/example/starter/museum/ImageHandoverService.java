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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 点交影像留档服务：出借点交与归还点交均登记合成影像特征清单，
 * 特征与点交单在同一事务内落库；归还点交自动比对出借基线清单，
 * 差异项超过 0 即生成差异工单，恰一致放行。
 * 差异工单未闭环前合同不得结案；同一合同同一时刻仅允许一个进行中差异工单。
 */
@Service
public class ImageHandoverService {

    /** 点交类型：出借（交付）点交。 */
    public static final String HANDOVER_DELIVERY = "DELIVERY";
    /** 点交类型：归还点交。 */
    public static final String HANDOVER_RETURN = "RETURN";

    /** 差异工单状态：进行中（未闭环）。 */
    public static final String ORDER_OPEN = "OPEN";
    /** 差异工单状态：已闭环。 */
    public static final String ORDER_RESOLVED = "RESOLVED";

    /** 差异类型：特征值不一致。 */
    public static final String DIFF_VALUE_MISMATCH = "VALUE_MISMATCH";
    /** 差异类型：归还点交缺失该特征。 */
    public static final String DIFF_MISSING_ON_RETURN = "MISSING_ON_RETURN";
    /** 差异类型：归还点交出现新增特征。 */
    public static final String DIFF_EXTRA_ON_RETURN = "EXTRA_ON_RETURN";

    /** 合成影像特征项。 */
    public record ImageFeature(String key, String value) {
    }

    /** 差异工单行记录。 */
    public record DifferenceOrder(long id, long contractId, String status, int diffCount,
            String requestId, Instant createdAt, Instant resolvedAt, String resolvedBy) {
    }

    private static final RowMapper<DifferenceOrder> ORDER_MAPPER = new RowMapper<>() {
        @Override
        public DifferenceOrder mapRow(ResultSet rs, int rowNum) throws SQLException {
            Timestamp resolvedAt = rs.getTimestamp("resolved_at");
            return new DifferenceOrder(
                    rs.getLong("id"),
                    rs.getLong("contract_id"),
                    rs.getString("status"),
                    rs.getInt("diff_count"),
                    rs.getString("request_id"),
                    rs.getTimestamp("created_at").toInstant(),
                    resolvedAt == null ? null : resolvedAt.toInstant(),
                    rs.getString("resolved_by"));
        }
    };

    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final Clock clock;
    private final LoanService loanService;

    public ImageHandoverService(org.springframework.jdbc.core.JdbcTemplate jdbc,
            Clock clock, LoanService loanService) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.loanService = loanService;
    }

    /**
     * 登记点交影像特征清单：特征与点交单在同一事务内落库。
     * 归还点交登记后自动比对出借基线：差异项超过 0 生成差异工单并拦截结案，恰一致放行。
     *
     * @param requestId    幂等请求号（由入口层幂等包裹，此处仅随工单留档）
     * @param contractId   借展合同主键
     * @param handoverType DELIVERY 出借点交 / RETURN 归还点交
     * @param features     合成影像特征清单（键值对，键不可重复）
     */
    @Transactional
    public Map<String, Object> registerFeatures(String requestId, long contractId,
            String handoverType, List<ImageFeature> features) {
        if (!HANDOVER_DELIVERY.equals(handoverType) && !HANDOVER_RETURN.equals(handoverType)) {
            throw ApiException.badRequest("未知点交类型：" + handoverType);
        }
        List<ImageFeature> normalized = validateFeatures(features);
        // 锁定合同行：与结案、同一合同的并发登记串行，保证工单状态判定原子
        LoanService.Contract contract = getContractForUpdate(contractId);
        if (HANDOVER_DELIVERY.equals(handoverType)
                && !LoanService.STATUS_APPLIED.equals(contract.status())) {
            throw ApiException.conflict(
                    "合同状态为 " + contract.status() + "，出借点交影像登记仅在申请待点交阶段受理");
        }
        if (HANDOVER_RETURN.equals(handoverType)
                && !LoanService.STATUS_ON_DISPLAY.equals(contract.status())) {
            throw ApiException.conflict(
                    "合同状态为 " + contract.status() + "，归还点交影像登记仅在在展阶段受理");
        }
        long handoverId = loanService.ensureHandover(contractId, handoverType);
        Instant now = Instant.now(clock);
        try {
            for (ImageFeature feature : normalized) {
                jdbc.update(
                        "INSERT INTO handover_image_feature (handover_id, feature_key, feature_value, captured_at)"
                                + " VALUES (?,?,?,?)",
                        handoverId, feature.key(), feature.value(), Timestamp.from(now));
            }
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("该点交单已登记影像特征清单，不可重复登记");
        }

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("contractId", contractId);
        view.put("handoverId", handoverId);
        view.put("handoverType", handoverType);
        view.put("featureCount", normalized.size());
        view.put("features", normalized.stream().map(f -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", f.key());
            item.put("value", f.value());
            return item;
        }).toList());

        if (HANDOVER_RETURN.equals(handoverType)) {
            List<FeatureDifferenceView> differences = compareWithDelivery(contractId, handoverId);
            view.put("compared", true);
            view.put("differenceCount", differences.size());
            view.put("differences", differences.stream().map(FeatureDifferenceView::toMap).toList());
            if (!differences.isEmpty()) {
                long orderId = createDifferenceOrder(contractId, requestId, differences, now);
                view.put("orderId", orderId);
                view.put("orderStatus", ORDER_OPEN);
                view.put("released", false);
            } else {
                view.put("orderId", null);
                view.put("orderStatus", null);
                view.put("released", true);
            }
        } else {
            view.put("compared", false);
            view.put("differenceCount", null);
            view.put("differences", List.of());
            view.put("orderId", null);
            view.put("orderStatus", null);
            view.put("released", null);
        }
        return view;
    }

    /**
     * 闭环差异工单：闭环后合同方可结案。重复闭环返回幂等成功。
     */
    @Transactional
    public Map<String, Object> resolveOrder(long orderId, String resolvedBy) {
        if (resolvedBy == null || resolvedBy.isBlank()) {
            throw ApiException.badRequest("闭环处置人 resolvedBy 不能为空");
        }
        DifferenceOrder order = getOrder(orderId);
        if (ORDER_RESOLVED.equals(order.status())) {
            Map<String, Object> view = orderView(order);
            view.put("idempotentReplay", true);
            return view;
        }
        // 锁定合同行：与结案事务串行，保证“结案校验 ↔ 工单闭环”原子判定
        jdbc.queryForObject("SELECT id FROM loan_contract WHERE id = ? FOR UPDATE",
                Long.class, order.contractId());
        Instant now = Instant.now(clock);
        int updated = jdbc.update(
                "UPDATE difference_order SET status = ?, resolved_at = ?, resolved_by = ?"
                        + " WHERE id = ? AND status = ?",
                ORDER_RESOLVED, Timestamp.from(now), resolvedBy, orderId, ORDER_OPEN);
        if (updated == 0) {
            DifferenceOrder current = getOrder(orderId);
            if (ORDER_RESOLVED.equals(current.status())) {
                Map<String, Object> view = orderView(current);
                view.put("idempotentReplay", true);
                return view;
            }
            throw ApiException.conflict("差异工单状态已变更，闭环未生效");
        }
        jdbc.update("DELETE FROM contract_open_diff_order WHERE contract_id = ? AND order_id = ?",
                order.contractId(), orderId);
        return orderView(getOrder(orderId));
    }

    /**
     * 合同是否存在未闭环（进行中）差异工单。
     */
    public boolean hasOpenOrder(long contractId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM contract_open_diff_order WHERE contract_id = ?",
                Integer.class, contractId);
        return count != null && count > 0;
    }

    /**
     * 查询合同的差异工单（按创建顺序，含差异明细）。
     */
    public List<Map<String, Object>> orders(long contractId) {
        List<DifferenceOrder> orders = jdbc.query(
                "SELECT * FROM difference_order WHERE contract_id = ? ORDER BY id",
                ORDER_MAPPER, contractId);
        return orders.stream().map(this::orderView).toList();
    }

    private List<ImageFeature> validateFeatures(List<ImageFeature> features) {
        if (features == null || features.isEmpty()) {
            throw ApiException.badRequest("影像特征清单不能为空");
        }
        List<ImageFeature> normalized = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (ImageFeature feature : features) {
            if (feature == null || feature.key() == null || feature.key().isBlank()) {
                throw ApiException.badRequest("影像特征项名称不能为空");
            }
            if (feature.value() == null || feature.value().isBlank()) {
                throw ApiException.badRequest("影像特征值不能为空：" + feature.key());
            }
            if (!keys.add(feature.key())) {
                throw ApiException.badRequest("影像特征项名称重复：" + feature.key());
            }
            normalized.add(new ImageFeature(feature.key(), feature.value()));
        }
        return normalized;
    }

    private LoanService.Contract getContractForUpdate(long contractId) {
        // 先确认合同存在（不存在给出 404），再加行锁
        LoanService.Contract contract = loanService.getContract(contractId);
        jdbc.queryForObject("SELECT id FROM loan_contract WHERE id = ? FOR UPDATE", Long.class, contractId);
        return contract;
    }

    private List<FeatureDifferenceView> compareWithDelivery(long contractId, long returnHandoverId) {
        Long deliveryHandoverId = jdbc.queryForObject(
                "SELECT id FROM handover WHERE contract_id = ? AND handover_type = ?",
                Long.class, contractId, HANDOVER_DELIVERY);
        if (deliveryHandoverId == null) {
            throw ApiException.conflict("出借点交影像特征清单缺失，无法进行归还比对");
        }
        Map<String, String> delivery = new LinkedHashMap<>();
        jdbc.query("SELECT feature_key, feature_value FROM handover_image_feature WHERE handover_id = ?",
                rs -> {
                    delivery.put(rs.getString("feature_key"), rs.getString("feature_value"));
                }, deliveryHandoverId);
        if (delivery.isEmpty()) {
            throw ApiException.conflict("出借点交影像特征清单缺失，无法进行归还比对");
        }
        Map<String, String> returned = new LinkedHashMap<>();
        jdbc.query("SELECT feature_key, feature_value FROM handover_image_feature WHERE handover_id = ?",
                rs -> {
                    returned.put(rs.getString("feature_key"), rs.getString("feature_value"));
                }, returnHandoverId);

        List<FeatureDifferenceView> differences = new ArrayList<>();
        Set<String> processed = new LinkedHashSet<>();
        for (Map.Entry<String, String> entry : delivery.entrySet()) {
            String key = entry.getKey();
            processed.add(key);
            String returnValue = returned.get(key);
            if (returnValue == null) {
                differences.add(new FeatureDifferenceView(
                        key, entry.getValue(), null, DIFF_MISSING_ON_RETURN));
            } else if (!entry.getValue().equals(returnValue)) {
                differences.add(new FeatureDifferenceView(
                        key, entry.getValue(), returnValue, DIFF_VALUE_MISMATCH));
            }
        }
        for (Map.Entry<String, String> entry : returned.entrySet()) {
            if (!processed.contains(entry.getKey())) {
                differences.add(new FeatureDifferenceView(
                        entry.getKey(), null, entry.getValue(), DIFF_EXTRA_ON_RETURN));
            }
        }
        return differences;
    }

    private long createDifferenceOrder(long contractId, String requestId,
            List<FeatureDifferenceView> differences, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement(
                    "INSERT INTO difference_order (contract_id, status, diff_count, request_id, created_at)"
                            + " VALUES (?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, contractId);
            ps.setString(2, ORDER_OPEN);
            ps.setInt(3, differences.size());
            ps.setString(4, requestId);
            ps.setTimestamp(5, Timestamp.from(now));
            return ps;
        }, keyHolder);
        long orderId = keyHolder.getKey().longValue();
        for (FeatureDifferenceView diff : differences) {
            jdbc.update(
                    "INSERT INTO difference_item (order_id, feature_key, delivery_value, return_value, diff_type)"
                            + " VALUES (?,?,?,?,?)",
                    orderId, diff.featureKey(), diff.deliveryValue(), diff.returnValue(), diff.diffType());
        }
        try {
            jdbc.update("INSERT INTO contract_open_diff_order (contract_id, order_id) VALUES (?,?)",
                    contractId, orderId);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("同一合同已存在进行中差异工单，须先闭环后方可再次登记差异");
        }
        return orderId;
    }

    private DifferenceOrder getOrder(long orderId) {
        List<DifferenceOrder> orders = jdbc.query(
                "SELECT * FROM difference_order WHERE id = ?", ORDER_MAPPER, orderId);
        if (orders.isEmpty()) {
            throw ApiException.notFound("差异工单不存在：" + orderId);
        }
        return orders.get(0);
    }

    private Map<String, Object> orderView(DifferenceOrder order) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("orderId", order.id());
        view.put("contractId", order.contractId());
        view.put("status", order.status());
        view.put("diffCount", order.diffCount());
        view.put("createdAt", order.createdAt().toString());
        view.put("resolvedAt", order.resolvedAt() == null ? null : order.resolvedAt().toString());
        view.put("resolvedBy", order.resolvedBy());
        List<Map<String, Object>> items = jdbc.query(
                "SELECT feature_key, delivery_value, return_value, diff_type"
                        + " FROM difference_item WHERE order_id = ? ORDER BY id",
                (rs, rowNum) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("featureKey", rs.getString("feature_key"));
                    item.put("deliveryValue", rs.getString("delivery_value"));
                    item.put("returnValue", rs.getString("return_value"));
                    item.put("diffType", rs.getString("diff_type"));
                    return item;
                }, order.id());
        view.put("items", items);
        return view;
    }

    /** 差异项视图（服务内部传递与序列化）。 */
    private record FeatureDifferenceView(String featureKey, String deliveryValue,
            String returnValue, String diffType) {
        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("featureKey", featureKey);
            map.put("deliveryValue", deliveryValue);
            map.put("returnValue", returnValue);
            map.put("diffType", diffType);
            return map;
        }
    }
}
