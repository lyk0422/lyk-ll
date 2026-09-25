package com.example.starter.slaughter;

import com.example.starter.slaughter.domain.BatchStatus;
import com.example.starter.slaughter.domain.InventoryScope;
import com.example.starter.slaughter.domain.JudgmentCategory;
import com.example.starter.slaughter.dto.JudgmentItem;
import com.example.starter.slaughter.dto.JudgmentRequest;
import com.example.starter.slaughter.dto.JudgmentResponse;
import com.example.starter.slaughter.service.ServiceOutcome;
import com.example.starter.slaughter.web.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 判定分流：合格 / 有条件食用 / 化制 / 销毁 四类；
 * 有条件食用须标注高温处理并限定流通区域；判定与库存标识同一事务原子落库。
 */
class JudgmentFlowTest extends AbstractSlaughterTest {

    private final List<String> heads = List.of("H001", "H002", "H003", "H004");

    private List<JudgmentItem> fourWayItems() {
        return List.of(
                judge("H001", JudgmentCategory.QUALIFIED),
                conditional("H002", "本区定点销售"),
                judge("H003", JudgmentCategory.RENDERED),
                judge("H004", JudgmentCategory.DESTROYED));
    }

    @Test
    void fourWayJudgment_routesAllCategoriesAtomically() {
        declareAndPass("judge", "B-JUDGE-1", heads);
        inspectAllAndComplete("judge", "B-JUDGE-1", heads);

        ServiceOutcome<JudgmentResponse> outcome = service.judge(
                "B-JUDGE-1", judgment("judge-req-1", fourWayItems()));

        assertFalse(outcome.replayed());
        assertEquals(BatchStatus.JUDGED.name(), outcome.data().status());
        assertEquals(Map.of(
                "QUALIFIED", 1,
                "CONDITIONAL", 1,
                "RENDERED", 1,
                "DESTROYED", 1), outcome.data().categoryCounts());

        // 合格 + 有条件食用各 1 条库存标识；化制/销毁不入库。
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_mark WHERE batch_no = 'B-JUDGE-1'", Integer.class));
        assertInventory("H001", InventoryScope.GENERAL, null, Boolean.FALSE);
        assertInventory("H002", InventoryScope.LIMITED_REGION, "本区定点销售", Boolean.TRUE);
        assertHeadLedger("H001", JudgmentCategory.QUALIFIED, InventoryScope.GENERAL);
        assertHeadLedger("H002", JudgmentCategory.CONDITIONAL, InventoryScope.LIMITED_REGION);
        assertHeadLedger("H003", JudgmentCategory.RENDERED, InventoryScope.NONE);
        assertHeadLedger("H004", JudgmentCategory.DESTROYED, InventoryScope.NONE);
    }

    @Test
    void conditionalWithoutRegionRejectedAndNothingPersisted() {
        declareAndPass("cond", "B-JUDGE-2", heads);
        inspectAllAndComplete("cond", "B-JUDGE-2", heads);

        JudgmentRequest invalid = judgment("cond-req", List.of(
                judge("H001", JudgmentCategory.QUALIFIED),
                new JudgmentItem("H002", JudgmentCategory.CONDITIONAL, "  "),
                judge("H003", JudgmentCategory.RENDERED),
                judge("H004", JudgmentCategory.DESTROYED)));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.judge("B-JUDGE-2", invalid));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
        assertEquals("LIMITED_REGION_REQUIRED", ex.getCode());

        // 事务回滚：批次仍为 INSPECTED，无头只判定、无库存标识。
        assertEquals(BatchStatus.INSPECTED.name(), jdbc.queryForObject(
                "SELECT status FROM slaughter_batch WHERE batch_no = 'B-JUDGE-2'", String.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_head WHERE judgment_category IS NOT NULL "
                        + "AND batch_no = 'B-JUDGE-2'", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_mark WHERE batch_no = 'B-JUDGE-2'", Integer.class));
    }

    @Test
    void regionOnNonConditionalRejected() {
        declareAndPass("reg", "B-JUDGE-3", heads);
        inspectAllAndComplete("reg", "B-JUDGE-3", heads);

        JudgmentRequest invalid = judgment("reg-req", List.of(
                new JudgmentItem("H001", JudgmentCategory.QUALIFIED, "越权区域"),
                conditional("H002", "本区"),
                judge("H003", JudgmentCategory.RENDERED),
                judge("H004", JudgmentCategory.DESTROYED)));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.judge("B-JUDGE-3", invalid));
        assertEquals("LIMITED_REGION_NOT_ALLOWED", ex.getCode());
    }

    @Test
    void judgmentCountMismatchRejected() {
        declareAndPass("cnt", "B-JUDGE-4", heads);
        inspectAllAndComplete("cnt", "B-JUDGE-4", heads);

        JudgmentRequest shortRequest = judgment("cnt-req", List.of(
                judge("H001", JudgmentCategory.QUALIFIED),
                judge("H002", JudgmentCategory.QUALIFIED)));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.judge("B-JUDGE-4", shortRequest));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
        assertEquals("JUDGMENT_COUNT_MISMATCH", ex.getCode());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_mark WHERE batch_no = 'B-JUDGE-4'", Integer.class));
    }

    @Test
    void duplicateJudgmentConflictsAndDoesNotDoubleMark() {
        declareAndPass("djudge", "B-JUDGE-5", heads);
        inspectAllAndComplete("djudge", "B-JUDGE-5", heads);
        service.judge("B-JUDGE-5", judgment("djudge-req-1", fourWayItems()));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.judge(
                "B-JUDGE-5", judgment("djudge-req-2",
                        heads.stream().map(h -> judge(h, JudgmentCategory.QUALIFIED)).toList())));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        // 库存标识未重复：仍恰为 2 条。
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_mark WHERE batch_no = 'B-JUDGE-5'", Integer.class));
        // 判定结果未被第二次请求改写。
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_head WHERE batch_no = 'B-JUDGE-5' "
                        + "AND judgment_category = 'RENDERED'", Integer.class));
    }

    private void assertInventory(String headNo, InventoryScope scope, String region, Boolean heat) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT scope, limited_region, heat_treatment FROM inventory_mark "
                        + "WHERE batch_no = 'B-JUDGE-1' AND head_no = ?", headNo);
        assertEquals(scope.name(), row.get("scope"));
        assertEquals(region, row.get("limited_region"));
        assertEquals(heat, row.get("heat_treatment"));
    }

    private void assertHeadLedger(String headNo, JudgmentCategory category, InventoryScope scope) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT judgment_category, inventory_scope, heat_treatment FROM batch_head "
                        + "WHERE batch_no = 'B-JUDGE-1' AND head_no = ?", headNo);
        assertEquals(category.name(), row.get("judgment_category"));
        assertEquals(scope.name(), row.get("inventory_scope"));
        if (category == JudgmentCategory.CONDITIONAL) {
            assertEquals(Boolean.TRUE, row.get("heat_treatment"));
        }
    }
}
