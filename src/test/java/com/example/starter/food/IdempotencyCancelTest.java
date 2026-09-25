package com.example.starter.food;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.starter.food.service.BusinessException;
import com.example.starter.food.service.IdempotencyService;
import com.example.starter.food.web.Dtos;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * requestId 幂等：同键同参返回首次结果、同键异参 409；注销终态：重复注销幂等成功、注销后上报一律拒绝。
 */
class IdempotencyCancelTest extends AbstractFoodTest {

    /** 同键同参重放提交：返回首次配方，不产生第二条记录。 */
    @Test
    void sameRequestSameParamsReturnsFirstResult() {
        Dtos.SubmitRecipeRequest req1 = new Dtos.SubmitRecipeRequest("idem-1", "PRD-I1", "v1", "BEV-01",
                List.of(add("P001", "0.100000")), List.of());
        IdempotencyService.Result r1 = service.submitRecipe(req1);
        assertFalse(r1.replay());
        Dtos.RecipeResponse first = (Dtos.RecipeResponse) r1.body();

        IdempotencyService.Result r2 = service.submitRecipe(req1);
        Dtos.RecipeResponse replayBody = read(r2, Dtos.RecipeResponse.class);
        assertEquals(first.id(), replayBody.id());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(1) FROM recipe WHERE request_id = 'idem-1'",
                Integer.class).intValue());

        // 审查同键重放：首次 COMPLIANT 结果回放，不重复写审查
        IdempotencyService.Result rv1 = service.reviewRecipe(
                new Dtos.ReviewRecipeRequest("idem-rev-1", "V1"), first.id());
        assertFalse(rv1.replay());
        IdempotencyService.Result rv2 = service.reviewRecipe(
                new Dtos.ReviewRecipeRequest("idem-rev-1", "V1"), first.id());
        Dtos.RecipeResponse rvBody = read(rv2, Dtos.RecipeResponse.class);
        assertEquals("COMPLIANT", rvBody.status());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(1) FROM recipe WHERE id = ? AND status = 'COMPLIANT'",
                Integer.class, first.id()).intValue());
    }

    /** 同键异参返回 409，且不写入任何配方数据。 */
    @Test
    void sameRequestDifferentParamsConflicts() {
        service.submitRecipe(new Dtos.SubmitRecipeRequest("idem-2", "PRD-I2", "v1", "BEV-01",
                List.of(add("P001", "0.100000")), List.of()));
        BusinessException ex = assertThrows(BusinessException.class, () -> service.submitRecipe(
                new Dtos.SubmitRecipeRequest("idem-2", "PRD-I2", "v2", "BEV-01",
                        List.of(add("P001", "0.200000")), List.of())));
        assertEquals(409, ex.getStatus());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(1) FROM recipe WHERE request_id = 'idem-2'",
                Integer.class).intValue());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(1) FROM recipe WHERE product_code = 'PRD-I2'",
                Integer.class).intValue());
    }

    /** 备案幂等：同键同参回放首次备案；不产生第二条备案。 */
    @Test
    void filingIdempotentReplay() {
        Dtos.RecipeResponse r = submit("idem-3", "PRD-I3", "v1", "BEV-01",
                List.of(add("P001", "0.100000")), null);
        review("idem-rev-3", r.id(), "V1");
        approve("idem-app-3", r.id());

        IdempotencyService.Result f1 = service.fileFiling(new Dtos.FileFilingRequest("idem-file-3", r.id()));
        IdempotencyService.Result f2 = service.fileFiling(new Dtos.FileFilingRequest("idem-file-3", r.id()));
        Dtos.FilingResponse b1 = (Dtos.FilingResponse) f1.body();
        Dtos.FilingResponse b2 = read(f2, Dtos.FilingResponse.class);
        assertEquals(b1.id(), b2.id());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(1) FROM filing WHERE product_code = 'PRD-I3'",
                Integer.class).intValue());
    }

    /** 量产上报同键同参回放，批号唯一约束不重复插入。 */
    @Test
    void productionReportIdempotentReplay() {
        Dtos.FilingResponse f = filed("idem-4", "PRD-I4");
        Dtos.ProductionReportRequest req = new Dtos.ProductionReportRequest(
                "idem-rep-4", "BATCH-1", new BigDecimal("5"));
        IdempotencyService.Result p1 = service.reportProduction(req, f.id());
        IdempotencyService.Result p2 = service.reportProduction(req, f.id());
        Dtos.ProductionReportResponse b1 = (Dtos.ProductionReportResponse) p1.body();
        Dtos.ProductionReportResponse b2 = read(p2, Dtos.ProductionReportResponse.class);
        assertEquals(b1.id(), b2.id());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(1) FROM production_report WHERE filing_id = ? AND batch_no = 'BATCH-1'",
                Integer.class, f.id()).intValue());
    }

    /** 注销为终态：首次注销成功；重复注销幂等成功；注销后量产上报一律拒绝。 */
    @Test
    void cancelIsTerminalAndRejectsProduction() {
        Dtos.FilingResponse f = filed("idem-5", "PRD-I5");

        IdempotencyService.Result c1 = service.cancelFiling("idem-cancel-5", f.id());
        Dtos.FilingResponse cancelled = (Dtos.FilingResponse) c1.body();
        assertEquals("CANCELLED", cancelled.status());
        assertNotNull(cancelled.cancelledAt());

        // 重复注销：幂等成功（200），cancelled_at 不被覆盖
        IdempotencyService.Result c2 = service.cancelFiling("idem-cancel-5", f.id());
        assertEquals(200, c2.statusCode());
        Dtos.FilingResponse still = read(c2, Dtos.FilingResponse.class);
        assertEquals("CANCELLED", still.status());
        assertEquals(cancelled.cancelledAt(), still.cancelledAt());

        // 新 requestId 重复注销同样幂等成功
        IdempotencyService.Result c3 = service.cancelFiling("idem-cancel-5-again", f.id());
        assertEquals(200, c3.statusCode());

        // 注销后量产上报一律拒绝
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reportProduction(new Dtos.ProductionReportRequest(
                        "idem-rep-5", "BATCH-5", new BigDecimal("1")), f.id()));
        assertEquals(409, ex.getStatus());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(1) FROM production_report WHERE filing_id = ?", Integer.class, f.id()).intValue());

        // 注销后不可复审
        BusinessException rv = assertThrows(BusinessException.class,
                () -> service.reviewFiling(new Dtos.ReviewFilingRequest("idem-frev-5"), f.id()));
        assertEquals(409, rv.getStatus());
    }

    private Dtos.FilingResponse filed(String tag, String product) {
        Dtos.RecipeResponse r = submit(tag, product, "v1", "BEV-01",
                List.of(add("P001", "0.100000")), null);
        review(tag + "-rev", r.id(), "V1");
        approve(tag + "-app", r.id());
        return file(tag + "-file", r.id());
    }

    /** 幂等重放时 body 为首次响应的原始 JSON 字符串，首调时为对象；统一反序列化。 */
    private <T> T read(IdempotencyService.Result result, Class<T> type) {
        Object body = result.body();
        if (type.isInstance(body)) {
            return type.cast(body);
        }
        try {
            return mapper.readValue((String) body, type);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }
}
