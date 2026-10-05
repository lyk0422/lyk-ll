package com.example.starter.museum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * 点交影像留档与差异工单业务测试：
 * 特征同事务落库、归还自动比对（不一致/缺失/新增）、差异工单阻断结案、闭环放行、重复登记拦截。
 */
class ImageHandoverServiceTest extends MuseumTestSupport {

    /** 标准出借基线合成特征（3 项）。 */
    private List<ImageHandoverService.ImageFeature> baselineFeatures() {
        return features("CORNER", "hash-a1", "GLAZE", "hash-b2", "INSCRIPTION", "hash-c3");
    }

    /**
     * 完整出借流程：申请（APPLIED）→ 登记出借影像基线 → 双方交付点交确认 → 在展（ON_DISPLAY）。
     * 出借影像必须在交付点交确认前登记，基线在合同进入在展后不可补改。
     */
    private long onDisplayWithDeliveryBaseline(List<ImageHandoverService.ImageFeature> deliveryFeatures) {
        long contractId = ((Number) loanService.apply(newRequestId(), ARTIFACT_LEVEL_ONE, SHOWCASE_PAPER,
                "某省博物馆", LocalDate.of(2026, 2, 1), LocalDate.of(2027, 2, 1)).get("contractId"))
                .longValue();
        imageHandoverService.registerFeatures(newRequestId(), contractId,
                ImageHandoverService.HANDOVER_DELIVERY, deliveryFeatures);
        loanService.confirmHandover(contractId, "DELIVERY", LoanService.PARTY_MUSEUM);
        loanService.confirmHandover(contractId, "DELIVERY", LoanService.PARTY_BORROWER);
        assertEquals(LoanService.STATUS_ON_DISPLAY, contractStatus(contractId));
        return contractId;
    }

    @Test
    void deliveryFeaturesPersistedInSameHandover() {
        long contractId = ((Number) loanService.apply(newRequestId(), ARTIFACT_LEVEL_ONE, SHOWCASE_PAPER,
                "某省博物馆", LocalDate.of(2026, 2, 1), LocalDate.of(2027, 2, 1)).get("contractId"))
                .longValue();
        Map<String, Object> view = imageHandoverService.registerFeatures(
                newRequestId(), contractId, ImageHandoverService.HANDOVER_DELIVERY, baselineFeatures());
        assertEquals(3, view.get("featureCount"));
        assertEquals(false, view.get("compared"));
        long handoverId = ((Number) view.get("handoverId")).longValue();
        // 特征与点交单同库落库
        assertEquals(3, jdbc.queryForObject(
                "SELECT COUNT(*) FROM handover_image_feature WHERE handover_id = ?",
                Long.class, handoverId));
    }

    @Test
    void identicalReturnFeaturesReleaseWithoutOrder() {
        long contractId = onDisplayWithDeliveryBaseline(baselineFeatures());
        Map<String, Object> view = imageHandoverService.registerFeatures(newRequestId(), contractId,
                ImageHandoverService.HANDOVER_RETURN, baselineFeatures());
        assertEquals(true, view.get("compared"));
        assertEquals(0, view.get("differenceCount"));
        assertEquals(true, view.get("released"));
        assertNull(view.get("orderId"));
        assertEquals(0, count("difference_order"));
        assertEquals(0, count("contract_open_diff_order"));
        assertFalse(imageHandoverService.hasOpenOrder(contractId));
    }

    @Test
    void valueMismatchCreatesOpenOrderWithItem() {
        long contractId = onDisplayWithDeliveryBaseline(baselineFeatures());
        Map<String, Object> view = imageHandoverService.registerFeatures(newRequestId(), contractId,
                ImageHandoverService.HANDOVER_RETURN,
                features("CORNER", "hash-a1", "GLAZE", "CHANGED", "INSCRIPTION", "hash-c3"));
        assertEquals(1, view.get("differenceCount"));
        assertEquals(false, view.get("released"));
        long orderId = ((Number) view.get("orderId")).longValue();
        assertEquals(ImageHandoverService.ORDER_OPEN, view.get("orderStatus"));
        assertTrue(imageHandoverService.hasOpenOrder(contractId));
        // 差异明细落库
        Map<String, Object> item = jdbc.queryForMap(
                "SELECT feature_key, delivery_value, return_value, diff_type FROM difference_item WHERE order_id = ?",
                orderId);
        assertEquals("GLAZE", item.get("FEATURE_KEY"));
        assertEquals("hash-b2", item.get("DELIVERY_VALUE"));
        assertEquals("CHANGED", item.get("RETURN_VALUE"));
        assertEquals(ImageHandoverService.DIFF_VALUE_MISMATCH, item.get("DIFF_TYPE"));
    }

    @Test
    void missingAndExtraFeaturesBothCountAsDifferences() {
        long contractId = onDisplayWithDeliveryBaseline(baselineFeatures());
        // 归还时缺失 INSCRIPTION，新增 SEAL
        Map<String, Object> view = imageHandoverService.registerFeatures(newRequestId(), contractId,
                ImageHandoverService.HANDOVER_RETURN,
                features("CORNER", "hash-a1", "GLAZE", "hash-b2", "SEAL", "hash-d4"));
        assertEquals(2, view.get("differenceCount"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> diffs = (List<Map<String, Object>>) view.get("differences");
        assertTrue(diffs.stream().anyMatch(d -> "INSCRIPTION".equals(d.get("featureKey"))
                && ImageHandoverService.DIFF_MISSING_ON_RETURN.equals(d.get("diffType"))));
        assertTrue(diffs.stream().anyMatch(d -> "SEAL".equals(d.get("featureKey"))
                && ImageHandoverService.DIFF_EXTRA_ON_RETURN.equals(d.get("diffType"))));
    }

    @Test
    void openDifferenceOrderBlocksCloseAndResolveReleases() {
        long contractId = onDisplayWithDeliveryBaseline(baselineFeatures());
        Map<String, Object> reg = imageHandoverService.registerFeatures(newRequestId(), contractId,
                ImageHandoverService.HANDOVER_RETURN,
                features("CORNER", "hash-a1", "GLAZE", "CHANGED", "INSCRIPTION", "hash-c3"));
        long orderId = ((Number) reg.get("orderId")).longValue();

        // 先完成双方归还点交确认（合同进入 RETURNED）
        loanService.confirmHandover(contractId, "RETURN", LoanService.PARTY_MUSEUM);
        loanService.confirmHandover(contractId, "RETURN", LoanService.PARTY_BORROWER);
        assertEquals(LoanService.STATUS_RETURNED, contractStatus(contractId));

        // 未闭环工单阻断结案
        ApiException blocked = assertThrows(ApiException.class, () -> loanService.close(contractId));
        assertEquals(HttpStatus.CONFLICT, blocked.status());
        assertEquals(LoanService.STATUS_RETURNED, contractStatus(contractId));

        // 闭环后放行结案
        Map<String, Object> resolved = imageHandoverService.resolveOrder(orderId, "点交员张三");
        assertEquals(ImageHandoverService.ORDER_RESOLVED, resolved.get("status"));
        assertFalse(imageHandoverService.hasOpenOrder(contractId));
        Map<String, Object> closed = loanService.close(contractId);
        assertEquals(LoanService.STATUS_CLOSED, closed.get("status"));
    }

    @Test
    void repeatResolveIsIdempotent() {
        long contractId = onDisplayWithDeliveryBaseline(baselineFeatures());
        Map<String, Object> reg = imageHandoverService.registerFeatures(newRequestId(), contractId,
                ImageHandoverService.HANDOVER_RETURN,
                features("CORNER", "DIFF", "GLAZE", "hash-b2", "INSCRIPTION", "hash-c3"));
        long orderId = ((Number) reg.get("orderId")).longValue();
        imageHandoverService.resolveOrder(orderId, "点交员张三");
        Map<String, Object> again = imageHandoverService.resolveOrder(orderId, "点交员张三");
        assertEquals(ImageHandoverService.ORDER_RESOLVED, again.get("status"));
        assertEquals(true, again.get("idempotentReplay"));
        assertEquals(1, count("difference_order"));
    }

    @Test
    void duplicateFeatureRegistrationRejected() {
        long contractId = ((Number) loanService.apply(newRequestId(), ARTIFACT_LEVEL_ONE, SHOWCASE_PAPER,
                "某省博物馆", LocalDate.of(2026, 2, 1), LocalDate.of(2027, 2, 1)).get("contractId"))
                .longValue();
        imageHandoverService.registerFeatures(newRequestId(), contractId,
                ImageHandoverService.HANDOVER_DELIVERY, baselineFeatures());
        ApiException ex = assertThrows(ApiException.class, () -> imageHandoverService
                .registerFeatures(newRequestId(), contractId,
                        ImageHandoverService.HANDOVER_DELIVERY, baselineFeatures()));
        assertEquals(HttpStatus.CONFLICT, ex.status());
        // 事务回滚：不产生重复特征行
        assertEquals(3, jdbc.queryForObject(
                "SELECT COUNT(*) FROM handover_image_feature", Long.class));
    }

    @Test
    void returnImagesRejectedWithoutDeliveryBaseline() {
        long contractId = applyAndDisplay(ARTIFACT_LEVEL_ONE, SHOWCASE_PAPER,
                LocalDate.of(2026, 2, 1), LocalDate.of(2027, 2, 1));
        ApiException ex = assertThrows(ApiException.class, () -> imageHandoverService
                .registerFeatures(newRequestId(), contractId,
                        ImageHandoverService.HANDOVER_RETURN, baselineFeatures()));
        assertEquals(HttpStatus.CONFLICT, ex.status());
        // 归还特征随事务回滚，不留库
        assertEquals(0, count("handover_image_feature"));
        assertEquals(0, count("difference_order"));
    }

    @Test
    void emptyAndDuplicateFeatureKeysRejected() {
        long contractId = applyAndDisplay(ARTIFACT_LEVEL_ONE, SHOWCASE_PAPER,
                LocalDate.of(2026, 2, 1), LocalDate.of(2027, 2, 1));
        ApiException empty = assertThrows(ApiException.class, () -> imageHandoverService
                .registerFeatures(newRequestId(), contractId,
                        ImageHandoverService.HANDOVER_DELIVERY, List.of()));
        assertEquals(HttpStatus.BAD_REQUEST, empty.status());
        ApiException dup = assertThrows(ApiException.class, () -> imageHandoverService
                .registerFeatures(newRequestId(), contractId,
                        ImageHandoverService.HANDOVER_DELIVERY,
                        features("CORNER", "a", "CORNER", "b")));
        assertEquals(HttpStatus.BAD_REQUEST, dup.status());
    }

    @Test
    void ordersViewContainsItems() {
        long contractId = onDisplayWithDeliveryBaseline(baselineFeatures());
        imageHandoverService.registerFeatures(newRequestId(), contractId,
                ImageHandoverService.HANDOVER_RETURN,
                features("CORNER", "X", "GLAZE", "Y", "INSCRIPTION", "Z"));
        List<Map<String, Object>> orders = imageHandoverService.orders(contractId);
        assertEquals(1, orders.size());
        assertEquals(3, orders.get(0).get("diffCount"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) orders.get(0).get("items");
        assertEquals(3, items.size());
    }
}
