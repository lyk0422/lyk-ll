package com.example.starter.food;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.starter.food.service.BusinessException;
import com.example.starter.food.service.IdempotencyService;
import com.example.starter.food.web.Dtos;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 标准更新 -> 复审观察（90 个自然日 UTC 日界）-> 第 90 日受理 / 逾期自动暂停 / 复审合格恢复或不合格暂停。
 */
class StandardReviewTest extends AbstractFoodTest {

    /** 准备一个依据 V1 批准并备案的饮料配方（P001 用量 0.4，V1 限量 0.5；V2 限量收紧为 0.3 后不合规）。 */
    private Dtos.FilingResponse prepareFiledRecipe(String tag, String p001Amount) {
        Dtos.RecipeResponse r = submit("sr-submit-" + tag, "PRD-SR-" + tag, "v1", "BEV-01",
                List.of(add("P001", p001Amount)), null);
        review("sr-review-" + tag, r.id(), "V1");
        approve("sr-approve-" + tag, r.id());
        return file("sr-file-" + tag, r.id());
    }

    private Instant dayUtc(LocalDate date) {
        return date.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /** V3 在 2026-03-01 发布：旧备案进入复审观察，截止日为 2026-05-30（恰 90 个自然日后）。 */
    @Test
    void standardPublishPutsActiveFilingsIntoReviewPending() {
        Dtos.FilingResponse f = prepareFiledRecipe("1", "0.200000");
        Instant publishedAt = dayUtc(LocalDate.of(2026, 3, 1));
        clock.setInstant(publishedAt);
        IdempotencyService.Result result = service.publishStandard(
                new Dtos.PublishStandardRequest("sr-pub-1", "V3"), publishedAt);
        Dtos.PublishStandardResponse resp = (Dtos.PublishStandardResponse) result.body();
        assertEquals("V3", resp.version());
        assertEquals(1, resp.affectedFilings());
        assertEquals(dayUtc(LocalDate.of(2026, 5, 30)), resp.reviewDueAt());

        Dtos.FilingResponse after = service.getFiling(f.id());
        assertEquals("REVIEW_PENDING", after.status());
        assertEquals(dayUtc(LocalDate.of(2026, 5, 30)), after.reviewDueAt());
    }

    /** 恰第 90 日（UTC 日界）提交复审受理；按新标准仍合规则恢复 ACTIVE。 */
    @Test
    void reviewAcceptedExactlyOn90thDayWhenStillCompliant() {
        Dtos.FilingResponse f = prepareFiledRecipe("2", "0.200000");
        // V3 限量：P001 维持 0.5（未在 V3 配置时按“沿用”处理——此处显式插入 V3 限量）
        jdbc.update("INSERT INTO additive_limit (additive_code, category_code, standard_version, max_amount) "
                + "VALUES ('P001','BEV-01','V3', 0.500000)");
        LocalDate publishDate = LocalDate.of(2026, 3, 1);
        clock.setInstant(dayUtc(publishDate));
        service.publishStandard(new Dtos.PublishStandardRequest("sr-pub-2", "V3"), dayUtc(publishDate));

        // 恰第 90 日 = 2026-05-30，当日任意 UTC 时刻均受理
        clock.setInstant(LocalDate.of(2026, 5, 30).atTime(23, 59, 0).atOffset(ZoneOffset.UTC).toInstant());
        IdempotencyService.Result r = service.reviewFiling(new Dtos.ReviewFilingRequest("sr-frev-2"), f.id());
        Dtos.FilingResponse reviewed = (Dtos.FilingResponse) r.body();
        assertEquals("ACTIVE", reviewed.status());
        assertEquals("V3", reviewed.reviewedStandardVersion());
        assertNotNull(reviewed.reviewedAt());
    }

    /** 第 91 日提交复审逾期：自动暂停备案并返回 409（暂停与响应同事务持久化，可幂等重放）。 */
    @Test
    void reviewOn91stDayOverdueSuspends() {
        Dtos.FilingResponse f = prepareFiledRecipe("3", "0.200000");
        jdbc.update("INSERT INTO additive_limit (additive_code, category_code, standard_version, max_amount) "
                + "VALUES ('P001','BEV-01','V3', 0.500000)");
        LocalDate publishDate = LocalDate.of(2026, 3, 1);
        clock.setInstant(dayUtc(publishDate));
        service.publishStandard(new Dtos.PublishStandardRequest("sr-pub-3", "V3"), dayUtc(publishDate));

        clock.setInstant(dayUtc(LocalDate.of(2026, 5, 31)));
        IdempotencyService.Result r = service.reviewFiling(
                new Dtos.ReviewFilingRequest("sr-frev-3"), f.id());
        assertEquals(409, r.statusCode());
        Dtos.FilingResponse after = service.getFiling(f.id());
        assertEquals("SUSPENDED", after.status());
        assertNotNull(after.suspendedAt());

        // 同键重放：返回首次 409，不产生第二次暂停（suspendedAt 不变）
        IdempotencyService.Result replay = service.reviewFiling(
                new Dtos.ReviewFilingRequest("sr-frev-3"), f.id());
        assertEquals(409, replay.statusCode());
        assertEquals(after.suspendedAt(), service.getFiling(f.id()).suspendedAt());
    }

    /** 到期前未复审：扫描器自动暂停；暂停后量产上报拒绝。 */
    @Test
    void sweepSuspendsOverdueFilings() {
        Dtos.FilingResponse f = prepareFiledRecipe("4", "0.200000");
        LocalDate publishDate = LocalDate.of(2026, 3, 1);
        clock.setInstant(dayUtc(publishDate));
        service.publishStandard(new Dtos.PublishStandardRequest("sr-pub-4", "V3"), dayUtc(publishDate));

        // 截止日当天扫描不暂停
        clock.setInstant(dayUtc(LocalDate.of(2026, 5, 30)));
        assertEquals(0, service.sweepOverdue().suspendedCount());
        assertEquals("REVIEW_PENDING", service.getFiling(f.id()).status());

        // 次日扫描自动暂停
        clock.setInstant(dayUtc(LocalDate.of(2026, 5, 31)));
        assertEquals(1, service.sweepOverdue().suspendedCount());
        assertEquals("SUSPENDED", service.getFiling(f.id()).status());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reportProduction(new Dtos.ProductionReportRequest(
                        "sr-rep-4", "B-4", new BigDecimal("8")), f.id()));
        assertEquals(409, ex.getStatus());
    }

    /** 复审窗口内按新标准复算不合规（P001 0.4 > V2 限量 0.3）：复审返回 409 且备案暂停。 */
    @Test
    void reviewFailUnderNewStandardSuspends() {
        Dtos.FilingResponse f = prepareFiledRecipe("5", "0.400000");
        // 种子数据已含 V2（2026-01-01 发布，P001 饮料限量 0.3）；备案快照标准为 V2，
        // 直接构造复审观察态以模拟“标准更新后须按新标准 V2 复审”。
        jdbc.update("UPDATE filing SET status='REVIEW_PENDING', review_due_date=? WHERE id=?",
                java.sql.Date.valueOf(LocalDate.of(2026, 4, 1)), f.id());
        clock.setInstant(dayUtc(LocalDate.of(2026, 1, 2)));

        IdempotencyService.Result r = service.reviewFiling(
                new Dtos.ReviewFilingRequest("sr-frev-5"), f.id());
        assertEquals(409, r.statusCode());
        assertEquals("SUSPENDED", service.getFiling(f.id()).status());
    }

    /** 观察期内量产仍可上报（观察非暂停）；复审完成后继续有效。 */
    @Test
    void productionAllowedDuringReviewPending() {
        Dtos.FilingResponse f = prepareFiledRecipe("6", "0.200000");
        jdbc.update("INSERT INTO additive_limit (additive_code, category_code, standard_version, max_amount) "
                + "VALUES ('P001','BEV-01','V3', 0.500000)");
        LocalDate publishDate = LocalDate.of(2026, 3, 1);
        clock.setInstant(dayUtc(publishDate));
        service.publishStandard(new Dtos.PublishStandardRequest("sr-pub-6", "V3"), dayUtc(publishDate));

        clock.setInstant(dayUtc(LocalDate.of(2026, 4, 1)));
        IdempotencyService.Result rep = service.reportProduction(new Dtos.ProductionReportRequest(
                "sr-rep-6", "B-6", new BigDecimal("12.5")), f.id());
        Dtos.ProductionReportResponse pr = (Dtos.ProductionReportResponse) rep.body();
        assertEquals("B-6", pr.batchNo());
    }
}
