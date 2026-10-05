package com.example.starter.museum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;

/**
 * 点交影像留档与差异比对业务测试：
 * 特征与点交同事务落库、归还自动比对、差异工单阻断结案、工单闭环放行、同合同进行中工单唯一。
 */
class ImageHandoverServiceTest extends MuseumTestSupport {

    private static final LocalDate START = LocalDate.of(2026, 2, 1);
    private static final LocalDate END = LocalDate.of(2027, 2, 1);

    @Test
    void featuresPersistWithinHandoverTransaction() {
        long contractId = ((Number) loanService.apply(newRequestId(), ARTIFACT_LEVEL_ONE, SHOWCASE_PAPER,
                "某省博物馆", START, END).get("contractId")).longValue();
        Map<String, Object> view = imageHandoverService.registerFeatures(contractId, "DELIVERY",
                baselineFeatures());
        assertEquals(3, view.get("featureCount"));
        assertEquals(3, count("image_feature"));
        // 点交单已在同事务建立
        assertEquals(1, count("handover"));
    }

    @Test
    void duplicateFeatureRegistrationRejected() {
        long contractId = ((Number) loanService.apply(newRequestId(), ARTIFACT_LEVEL_ONE, SHOWCASE_PAPER,
                "某省博物馆", START, END).get("contractId")).longValue();
        imageHandoverService.registerFeatures(contractId, "DELIVERY", baselineFeatures());
        ApiException ex = assertThrows(ApiException.class,
                () -> imageHandoverService.registerFeatures(contractId, "DELIVERY",
                        List.of(feature("FEATURE_SEAL", "seal-hash-A1"))));
        assertEquals(HttpStatus.CONFLICT, ex.status());
        assertEquals(3, count("image_feature"));
    }

    @Test
    void rejectsEmptyOrDuplicateCodes() {
        long contractId = ((Number) loanService.apply(newRequestId(), ARTIFACT_LEVEL_ONE, SHOWCASE_PAPER,
                "某省博物馆", START, END).get("contractId")).longValue();
        ApiException empty = assertThrows(ApiException.class,
                () -> imageHandoverService.registerFeatures(contractId, "DELIVERY", List.of()));
        assertEquals(HttpStatus.BAD_REQUEST, empty.status());
        ApiException dupCode = assertThrows(ApiException.class,
                () -> imageHandoverService.registerFeatures(contractId, "DELIVERY",
                        List.of(feature("A", "v1"), feature("A", "v2"))));
        assertEquals(HttpStatus.BAD_REQUEST, dupCode.status());
        assertEquals(0, count("image_feature"));
    }

    @Test
    void deliveryImagesRejectedAfterHandoverCompleted() {
        long contractId = applyAndDisplay(ARTIFACT_LEVEL_ONE, SHOWCASE_PAPER, START, END);
        ApiException ex = assertThrows(ApiException.class,
                () -> imageHandoverService.registerFeatures(contractId, "DELIVERY", baselineFeatures()));
        assertEquals(HttpStatus.CONFLICT, ex.status());
    }

    @Test
    @SuppressWarnings("unchecked")
    void identicalFeaturesPassWithoutDiscrepancyOrder() {
        long contractId = applyWithDeliveryImages(ARTIFACT_LEVEL_ONE, SHOWCASE_PAPER, START, END,
                baselineFeatures());
        Map<String, Object> result = registerReturnAndConfirm(contractId, baselineFeatures());
        assertEquals(LoanService.STATUS_RETURNED, contractStatus(contractId));
        Map<String, Object> comparison = (Map<String, Object>) result.get("imageComparison");
        assertNotNull(comparison);
        assertEquals(0, comparison.get("diffCount"));
        assertEquals(true, comparison.get("imageCompared"));
        assertNull(comparison.get("discrepancyOrderId"));
        assertEquals(0, count("discrepancy_order"));
        // 恰一致放行：可直接结案
        assertEquals(LoanService.STATUS_CLOSED, loanService.close(contractId).get("status"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void valueMismatchCreatesOpenOrderAndBlocksClose() {
        long contractId = applyWithDeliveryImages(ARTIFACT_LEVEL_ONE, SHOWCASE_PAPER, START, END,
                baselineFeatures());
        List<ImageHandoverService.ImageFeature> returned = List.of(
                feature("FEATURE_SEAL", "seal-hash-A1"),
                feature("FEATURE_EDGE", "edge-DAMAGED-X9"),
                feature("FEATURE_PATCH", "patch-37-C3"));
        Map<String, Object> result = registerReturnAndConfirm(contractId, returned);
        Map<String, Object> comparison = (Map<String, Object>) result.get("imageComparison");
        assertEquals(1, comparison.get("diffCount"));
        long orderId = ((Number) comparison.get("discrepancyOrderId")).longValue();
        assertEquals(1, count("discrepancy_order"));
        assertEquals(1, count("discrepancy_item"));
        // 差异工单未闭环前合同不得结案
        ApiException ex = assertThrows(ApiException.class, () -> loanService.close(contractId));
        assertEquals(HttpStatus.CONFLICT, ex.status());
        assertEquals(LoanService.STATUS_RETURNED, contractStatus(contractId));
        // 工单内容核对
        Map<String, Object> order = imageHandoverService.getOrder(orderId);
        assertEquals(ImageHandoverService.ORDER_OPEN, order.get("status"));
        Map<String, Object> item = ((List<Map<String, Object>>) order.get("items")).get(0);
        assertEquals("FEATURE_EDGE", item.get("code"));
        assertEquals(ImageHandoverService.DIFF_VALUE_MISMATCH, item.get("type"));
        assertEquals("edge-intact-B2", item.get("deliveryValue"));
        assertEquals("edge-DAMAGED-X9", item.get("returnValue"));
    }

    @Test
    void closingOrderAllowsContractClose() {
        long contractId = applyWithDeliveryImages(ARTIFACT_LEVEL_ONE, SHOWCASE_PAPER, START, END,
                baselineFeatures());
        List<ImageHandoverService.ImageFeature> returned = List.of(
                feature("FEATURE_SEAL", "seal-hash-A1"),
                feature("FEATURE_EDGE", "edge-DAMAGED-X9"),
                feature("FEATURE_PATCH", "patch-37-C3"));
        long orderId = ((Number) ((Map<?, ?>) registerReturnAndConfirm(contractId, returned)
                .get("imageComparison")).get("discrepancyOrderId")).longValue();
        assertThrows(ApiException.class, () -> loanService.close(contractId));
        Map<String, Object> closed = imageHandoverService.closeOrder(orderId, "点交员王五");
        assertEquals(ImageHandoverService.ORDER_CLOSED, closed.get("status"));
        assertEquals("点交员王五", closed.get("closedBy"));
        // 重复闭环：幂等成功
        assertEquals(true, imageHandoverService.closeOrder(orderId, "点交员王五").get("idempotentReplay"));
        // 闭环后结案放行
        assertEquals(LoanService.STATUS_CLOSED, loanService.close(contractId).get("status"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void missingFeatureOnEachSideReportedAsSeparateDiffTypes() {
        long contractId = applyWithDeliveryImages(ARTIFACT_LEVEL_ONE, SHOWCASE_PAPER, START, END,
                baselineFeatures());
        // 归还清单少了 FEATURE_PATCH、多了 FEATURE_SCRATCH
        List<ImageHandoverService.ImageFeature> returned = List.of(
                feature("FEATURE_SEAL", "seal-hash-A1"),
                feature("FEATURE_EDGE", "edge-intact-B2"),
                feature("FEATURE_SCRATCH", "scratch-new-D4"));
        Map<String, Object> comparison = (Map<String, Object>) registerReturnAndConfirm(contractId, returned)
                .get("imageComparison");
        assertEquals(2, comparison.get("diffCount"));
        long orderId = ((Number) comparison.get("discrepancyOrderId")).longValue();
        Map<String, Object> order = imageHandoverService.getOrder(orderId);
        List<Map<String, Object>> items = (List<Map<String, Object>>) order.get("items");
        Map<String, String> types = items.stream()
                .collect(java.util.stream.Collectors.toMap(
                        i -> (String) i.get("code"), i -> (String) i.get("type")));
        assertEquals(ImageHandoverService.DIFF_MISSING_ON_RETURN, types.get("FEATURE_PATCH"));
        assertEquals(ImageHandoverService.DIFF_MISSING_ON_DELIVERY, types.get("FEATURE_SCRATCH"));
    }

    @Test
    void secondOpenOrderForSameContractViolatesUniqueConstraint() {
        long contractId = applyWithDeliveryImages(ARTIFACT_LEVEL_ONE, SHOWCASE_PAPER, START, END,
                baselineFeatures());
        List<ImageHandoverService.ImageFeature> returned = List.of(
                feature("FEATURE_SEAL", "seal-hash-CHANGED"),
                feature("FEATURE_EDGE", "edge-intact-B2"),
                feature("FEATURE_PATCH", "patch-37-C3"));
        long orderId = ((Number) ((Map<?, ?>) registerReturnAndConfirm(contractId, returned)
                .get("imageComparison")).get("discrepancyOrderId")).longValue();
        // 数据库层兜底：同一合同同时刻不允许第二个进行中工单（active_contract_id 唯一水位）
        assertThrows(DuplicateKeyException.class, () -> jdbc.update(
                "INSERT INTO discrepancy_order (contract_id, status, diff_count, active_contract_id, created_at)"
                        + " VALUES (?, 'OPEN', 1, ?, CURRENT_TIMESTAMP)",
                contractId, contractId));
        // 原工单闭环后水位释放，但历史工单保留
        imageHandoverService.closeOrder(orderId, "点交员王五");
        Integer active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM discrepancy_order WHERE active_contract_id = ?",
                Integer.class, contractId);
        assertEquals(0, active);
        assertEquals(1, count("discrepancy_order"));
    }

    @Test
    void returnWithoutImageArchivingKeepsLegacyFlow() {
        // 保留旧用例：未做影像留档时不比对、不生成工单，归还后可结案
        long contractId = applyAndDisplay(ARTIFACT_LEVEL_TWO, SHOWCASE_BRONZE, START, END);
        Map<String, Object> result = loanService.confirmHandover(contractId, "RETURN",
                LoanService.PARTY_BORROWER);
        assertFalse(result.containsKey("imageComparison"));
        loanService.confirmHandover(contractId, "RETURN", LoanService.PARTY_MUSEUM);
        assertEquals(0, count("discrepancy_order"));
        assertEquals(LoanService.STATUS_CLOSED, loanService.close(contractId).get("status"));
    }

    @Test
    void closeOrderRejectsBlankHandlerAndUnknownOrder() {
        long contractId = applyWithDeliveryImages(ARTIFACT_LEVEL_ONE, SHOWCASE_PAPER, START, END,
                baselineFeatures());
        List<ImageHandoverService.ImageFeature> returned = List.of(
                feature("FEATURE_SEAL", "seal-hash-CHANGED"),
                feature("FEATURE_EDGE", "edge-intact-B2"),
                feature("FEATURE_PATCH", "patch-37-C3"));
        long orderId = ((Number) ((Map<?, ?>) registerReturnAndConfirm(contractId, returned)
                .get("imageComparison")).get("discrepancyOrderId")).longValue();
        ApiException blank = assertThrows(ApiException.class,
                () -> imageHandoverService.closeOrder(orderId, "  "));
        assertEquals(HttpStatus.BAD_REQUEST, blank.status());
        ApiException missing = assertThrows(ApiException.class,
                () -> imageHandoverService.closeOrder(999999L, "点交员王五"));
        assertEquals(HttpStatus.NOT_FOUND, missing.status());
        assertTrue(imageHandoverService.ordersForContract(contractId).stream()
                .anyMatch(o -> ImageHandoverService.ORDER_OPEN.equals(o.get("status"))));
    }
}
