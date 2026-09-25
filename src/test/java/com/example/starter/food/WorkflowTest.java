package com.example.starter.food;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.starter.food.service.BusinessException;
import com.example.starter.food.web.Dtos;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 提交 -> 审查 -> 批准（版本冻结）-> 量产备案工作流，以及新版本批准与旧版本备案暂停同事务。
 */
class WorkflowTest extends AbstractFoodTest {

    /** 未审查不能批准；未批准不能备案。 */
    @Test
    void cannotApproveBeforeReviewOrFileBeforeApproval() {
        Dtos.RecipeResponse r = submit("wf-req-1", "PRD-WF", "v1", "BEV-01",
                List.of(add("P001", "0.100000")), null);
        BusinessException e1 = assertThrows(BusinessException.class, () -> approve("wf-app-1", r.id()));
        assertEquals(409, e1.getStatus());
        BusinessException e2 = assertThrows(BusinessException.class, () -> file("wf-file-1", r.id()));
        assertEquals(409, e2.getStatus());
    }

    /** 批准后版本冻结：再次审查 409，快照包含全部业务字段，备案按快照版本执行。 */
    @Test
    void approvedRecipeIsFrozenAndFiledBySnapshot() {
        Dtos.RecipeResponse r = submit("wf-req-2", "PRD-WF2", "v1", "BEV-01",
                List.of(add("P001", "0.200000")), List.of(ing("C001", "0.100000")));
        review("wf-rev-2", r.id(), "V1");
        Dtos.RecipeResponse approved = approve("wf-app-2", r.id());
        assertEquals("APPROVED", approved.status());
        assertNotNull(approved.approvedAt());

        // 冻结后不可再次审查
        BusinessException ex = assertThrows(BusinessException.class,
                () -> review("wf-rev-2b", r.id(), "V2"));
        assertEquals(409, ex.getStatus());

        Dtos.FilingResponse f = file("wf-file-2", r.id());
        assertEquals("ACTIVE", f.status());
        assertEquals("v1", f.recipeVersion());
        assertEquals("V1", f.standardVersion());

        // 快照内容可复算
        String snapshot = jdbc.queryForObject("SELECT snapshot_json FROM filing WHERE id = ?",
                String.class, f.id());
        assertNotNull(snapshot);
        assertTrue(snapshot.contains("P001"));
        assertTrue(snapshot.contains("C001"));
    }

    /** 同一产品不能重复备案；新版本批准时旧备案在同一事务内暂停，绝不双版本同时有效。 */
    @Test
    void newVersionApprovalSuspendsOldFilingInSameTransaction() {
        // 旧版本 v1 走完流程并备案
        Dtos.RecipeResponse v1 = submit("wf-req-3", "PRD-WF3", "v1", "BEV-01",
                List.of(add("P001", "0.100000")), null);
        review("wf-rev-3", v1.id(), "V1");
        approve("wf-app-3", v1.id());
        Dtos.FilingResponse oldFiling = file("wf-file-3", v1.id());
        assertEquals("ACTIVE", oldFiling.status());

        // 未批准新版本前不能再备案
        Dtos.RecipeResponse v2 = submit("wf-req-3b", "PRD-WF3", "v2", "BEV-01",
                List.of(add("P001", "0.050000")), null);
        review("wf-rev-3b", v2.id(), "V1");
        BusinessException dup = assertThrows(BusinessException.class, () -> file("wf-file-3b", v2.id()));
        assertEquals(409, dup.getStatus());

        // 批准 v2：v1 备案应在同事务内暂停；此刻 v2 尚未备案，因此有效备案数为 0（绝无双有效）
        approve("wf-app-3b", v2.id());
        Dtos.FilingResponse oldAfter = service.getFiling(oldFiling.id());
        assertEquals("SUSPENDED", oldAfter.status());
        assertNotNull(oldAfter.suspendedAt());
        assertEquals(0, countActiveToken("PRD-WF3"));

        // v2 备案成功且为唯一有效版本
        Dtos.FilingResponse newFiling = file("wf-file-3c", v2.id());
        assertEquals("ACTIVE", newFiling.status());
        assertEquals("v2", newFiling.recipeVersion());
        assertEquals(1, countActiveToken("PRD-WF3"));
        assertEquals(1, countByStatus("PRD-WF3", "ACTIVE"));
        assertEquals(1, countByStatus("PRD-WF3", "SUSPENDED"));

        // 暂停的旧备案不允许量产上报
        BusinessException reportEx = assertThrows(BusinessException.class,
                () -> service.reportProduction(
                        new Dtos.ProductionReportRequest("wf-rep-3", "B-001", new java.math.BigDecimal("10")),
                        oldFiling.id()));
        assertEquals(409, reportEx.getStatus());
    }

    /** 拒绝的配方不能批准；重新提交新配方不受影响。 */
    @Test
    void rejectedRecipeCannotBeApproved() {
        Dtos.RecipeResponse r = submit("wf-req-4", "PRD-WF4", "v1", "BEV-01",
                List.of(add("P001", "5.000000")), null);
        review("wf-rev-4", r.id(), "V1");
        BusinessException ex = assertThrows(BusinessException.class, () -> approve("wf-app-4", r.id()));
        assertEquals(409, ex.getStatus());
        assertNull(service.getRecipe(r.id()).approvedAt());
    }

    /** 版本轨迹可复算：重复查询配方/备案结果稳定。 */
    @Test
    void repeatedQueriesAreStable() {
        Dtos.RecipeResponse r = submit("wf-req-5", "PRD-WF5", "v1", "BEV-01",
                List.of(add("P001", "0.500000")), null);
        review("wf-rev-5", r.id(), "V1");
        approve("wf-app-5", r.id());
        Dtos.FilingResponse f = file("wf-file-5", r.id());

        Dtos.RecipeResponse rq1 = service.getRecipe(r.id());
        Dtos.RecipeResponse rq2 = service.getRecipe(r.id());
        assertEquals(rq1.status(), rq2.status());
        assertEquals(rq1.reviewItems().get(0).ratio(), rq2.reviewItems().get(0).ratio());
        assertFalse(rq1.reviewItems().get(0).ratio() == null);

        assertEquals(service.getFiling(f.id()).status(), service.getFiling(f.id()).status());
    }

    private int countActiveToken(String product) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(1) FROM filing WHERE product_code = ? AND active_token = 'X'",
                Integer.class, product);
        return c == null ? 0 : c;
    }

    private int countByStatus(String product, String status) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(1) FROM filing WHERE product_code = ? AND status = ?",
                Integer.class, product, status);
        return c == null ? 0 : c;
    }
}
