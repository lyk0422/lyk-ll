package com.example.starter.water;

import com.example.starter.water.error.ApiException;
import com.example.starter.water.web.ApiDtos;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 业务主流程与失败分支测试：额满边界、结转扣减、限取减半、
 * 幂等重放与异参冲突、读数去重与乱序归月、封存终态。
 */
@Import(TestClockConfig.class)
class WaterServiceTest extends AbstractWaterTest {

    // ---------- 额满边界 ----------

    @Test
    void declare_exactlyRemainingQuota_accepted() {
        String user = newUser("R1", "1000");
        ApiDtos.DeclarationResponse first = declare("req-1", user, "2026-01", "600");
        assertThat(first.status()).isEqualTo("CONFIRMED");
        assertThat(first.version()).isEqualTo(1);

        // 剩余额度 400，申报恰好达到剩余额度仍受理
        ApiDtos.DeclarationResponse second = declare("req-2", user, "2026-02", "400");
        assertThat(second.status()).isEqualTo("CONFIRMED");
        assertAmount("400", second.acceptedAmount());

        ApiDtos.QuotaResponse quota = service.quota(user, 2026);
        assertAmount("0", quota.remaining());
        assertAmount("1000", quota.confirmedTotal());
    }

    @Test
    void declare_exceedsRemainingQuota_rejectedWithoutNewVersion() {
        String user = newUser("R1", "1000");
        declare("req-1", user, "2026-01", "600");

        // 剩余 400，申报 401 超出即拒绝整月申报
        ApiDtos.DeclarationResponse rejected = declare("req-2", user, "2026-02", "401");
        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(rejected.reason()).isEqualTo("QUOTA_EXCEEDED");
        assertThat(rejected.version()).isNull();

        // 拒绝不产生新版本：该月仍无已确认版本
        ApiDtos.QuotaResponse quota = service.quota(user, 2026);
        assertAmount("600", quota.confirmedTotal());
        assertAmount("400", quota.remaining());

        // 改为不超额申报可正常受理，版本从 1 开始（拒绝未占用版本号）
        ApiDtos.DeclarationResponse ok = declare("req-3", user, "2026-02", "400");
        assertThat(ok.status()).isEqualTo("CONFIRMED");
        assertThat(ok.version()).isEqualTo(1);
    }

    @Test
    void declare_modificationTakesLatestConfirmedVersion() {
        String user = newUser("R1", "1000");
        declare("req-1", user, "2026-01", "600");
        // 同月修改申报，取最后已确认版本
        ApiDtos.DeclarationResponse modified = declare("req-2", user, "2026-01", "300");
        assertThat(modified.status()).isEqualTo("CONFIRMED");
        assertThat(modified.version()).isEqualTo(2);

        ApiDtos.QuotaResponse quota = service.quota(user, 2026);
        assertAmount("300", quota.confirmedTotal());
        assertAmount("700", quota.remaining());
    }

    // ---------- 超限结转 ----------

    @Test
    void carryover_overQuotaDeductsNextYearOneToOne() {
        String user = newUser("R1", "1000");
        String point = newMeterPoint(user);

        // 2026 年实际取水 1200（基线 0 -> 1200），超许可 200
        report(point, "2026-01-31T00:00:00Z", "0");
        report(point, "2026-12-31T23:59:59Z", "1200");

        ApiDtos.QuotaResponse y2026 = service.quota(user, 2026);
        assertAmount("1200", y2026.actualUsage());

        // 次年额度按 1:1 结转扣减 200
        ApiDtos.QuotaResponse y2027 = service.quota(user, 2027);
        assertAmount("1000", y2027.baseQuota());
        assertAmount("200", y2027.carryoverDeduction());
        assertAmount("800", y2027.effectiveQuota());

        // 次年申报按扣减后额度判定：801 拒绝，800 受理
        ApiDtos.DeclarationResponse rejected = declare("req-c1", user, "2027-01", "801");
        assertThat(rejected.status()).isEqualTo("REJECTED");
        ApiDtos.DeclarationResponse accepted = declare("req-c2", user, "2027-01", "800");
        assertThat(accepted.status()).isEqualTo("CONFIRMED");
    }

    @Test
    void carryover_withinQuota_noDeduction() {
        String user = newUser("R1", "1000");
        String point = newMeterPoint(user);
        report(point, "2026-01-31T00:00:00Z", "0");
        report(point, "2026-12-31T23:59:59Z", "999");

        ApiDtos.QuotaResponse y2027 = service.quota(user, 2027);
        assertAmount("0", y2027.carryoverDeduction());
        assertAmount("1000", y2027.effectiveQuota());
    }

    // ---------- 限取减半 ----------

    @Test
    void restriction_newDeclarationHalved_confirmedPlansUnchanged() {
        String user = newUser("R1", "1000");
        // 限取前已确认计划
        ApiDtos.DeclarationResponse before = declare("req-1", user, "2026-01", "600");
        assertThat(before.restricted()).isFalse();
        assertAmount("600", before.acceptedAmount());

        service.triggerRestriction(new ApiDtos.RestrictionRequest("R1"));

        // 限取期间新申报减半受理
        ApiDtos.DeclarationResponse during = declare("req-2", user, "2026-02", "300");
        assertThat(during.status()).isEqualTo("CONFIRMED");
        assertThat(during.restricted()).isTrue();
        assertAmount("150", during.acceptedAmount());

        // 已确认计划不变：年度确认合计 = 600 + 150
        ApiDtos.QuotaResponse quota = service.quota(user, 2026);
        assertAmount("750", quota.confirmedTotal());
    }

    @Test
    void restriction_triggerIsIdempotentAndAffectsWholeReach() {
        String userA = newUser("R2", "1000");
        String userB = newUser("R2", "1000");

        ApiDtos.RestrictionResponse first =
                service.triggerRestriction(new ApiDtos.RestrictionRequest("R2"));
        assertThat(first.active()).isTrue();
        // 固定时钟下的生效时刻
        assertThat(first.effectiveAt()).isEqualTo(TestClockConfig.FIXED_NOW);

        // 重复触发幂等：生效时刻保持首次值
        ApiDtos.RestrictionResponse again =
                service.triggerRestriction(new ApiDtos.RestrictionRequest("R2"));
        assertThat(again.effectiveAt()).isEqualTo(TestClockConfig.FIXED_NOW);

        // 同河段全部取水户进入限取
        assertThat(declare("req-a", userA, "2026-03", "100").acceptedAmount())
                .isEqualByComparingTo(new BigDecimal("50"));
        assertThat(declare("req-b", userB, "2026-03", "100").acceptedAmount())
                .isEqualByComparingTo(new BigDecimal("50"));
    }

    // ---------- 幂等 ----------

    @Test
    void idempotency_sameKeySameParams_returnsFirstResult() {
        String user = newUser("R1", "1000");
        ApiDtos.DeclarationResponse first = declare("req-x", user, "2026-01", "500");
        ApiDtos.DeclarationResponse replay = declare("req-x", user, "2026-01", "500");

        assertThat(replay.status()).isEqualTo("CONFIRMED");
        assertThat(replay.version()).isEqualTo(first.version());
        assertAmount("500", replay.acceptedAmount());

        // 重放不产生新版本
        ApiDtos.DeclarationResponse next = declare("req-y", user, "2026-01", "200");
        assertThat(next.version()).isEqualTo(2);
    }

    @Test
    void idempotency_sameKeyDifferentParams_conflict409() {
        String user = newUser("R1", "1000");
        declare("req-x", user, "2026-01", "500");

        // 同键异参（水量不同）：409 且不产生新版本
        assertThatThrownBy(() -> declare("req-x", user, "2026-01", "600"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.code()).isEqualTo("IDEMPOTENCY_CONFLICT");
                    assertThat(apiEx.status().value()).isEqualTo(409);
                });

        // 同键异参（月份不同）同样 409
        assertThatThrownBy(() -> declare("req-x", user, "2026-02", "500"))
                .isInstanceOf(ApiException.class);

        // 未产生新版本：下一个正常申报版本仍为 2
        ApiDtos.DeclarationResponse next = declare("req-z", user, "2026-01", "100");
        assertThat(next.version()).isEqualTo(2);
    }

    @Test
    void idempotency_rejectedResultAlsoReplays() {
        String user = newUser("R1", "1000");
        declare("req-1", user, "2026-01", "900");
        ApiDtos.DeclarationResponse rejected = declare("req-2", user, "2026-02", "200");
        assertThat(rejected.status()).isEqualTo("REJECTED");

        // 同键同参重放首次拒绝结果
        ApiDtos.DeclarationResponse replay = declare("req-2", user, "2026-02", "200");
        assertThat(replay.status()).isEqualTo("REJECTED");
        assertThat(replay.reason()).isEqualTo("QUOTA_EXCEEDED");
    }

    // ---------- 读数去重与乱序归月 ----------

    @Test
    void reading_duplicateNotCountedTwice() {
        String user = newUser("R1", "1000");
        String point = newMeterPoint(user);
        report(point, "2026-01-01T00:00:00Z", "0");
        report(point, "2026-01-31T00:00:00Z", "100");

        // 重复上报同一计量点+表底时刻：幂等返回且不重累计
        ApiDtos.ReadingResponse dup = report(point, "2026-01-31T00:00:00Z", "100");
        assertThat(dup.duplicate()).isTrue();

        ApiDtos.UsageResponse usage = service.usage(user, "2026-01");
        assertAmount("100", usage.actualUsage());
    }

    @Test
    void reading_outOfOrder_bucketedByReadingTime() {
        String user = newUser("R1", "1000");
        String point = newMeterPoint(user);

        // 乱序到达：先报 3 月表底，再报 1 月、2 月表底，最后补 1 月初基线
        report(point, "2026-03-31T00:00:00Z", "300");
        report(point, "2026-01-31T00:00:00Z", "100");
        report(point, "2026-02-28T00:00:00Z", "200");
        report(point, "2026-01-01T00:00:00Z", "0");

        // 按表底时刻归月：1 月 100、2 月 100、3 月 100
        assertAmount("100", service.usage(user, "2026-01").actualUsage());
        assertAmount("100", service.usage(user, "2026-02").actualUsage());
        assertAmount("100", service.usage(user, "2026-03").actualUsage());
    }

    @Test
    void reading_unknownMeterPoint_404() {
        assertThatThrownBy(() -> report("NO-SUCH-POINT", "2026-01-01T00:00:00Z", "10"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status().value()).isEqualTo(404));
    }

    // ---------- 封存终态 ----------

    @Test
    void seal_repeatReturnsIdempotentSuccess() {
        String user = newUser("R1", "1000");
        ApiDtos.SealResponse first = service.seal(new ApiDtos.SealRequest(user, 2026));
        assertThat(first.alreadySealed()).isFalse();
        assertThat(first.sealedAt()).isEqualTo(TestClockConfig.FIXED_NOW);

        ApiDtos.SealResponse again = service.seal(new ApiDtos.SealRequest(user, 2026));
        assertThat(again.alreadySealed()).isTrue();
        assertThat(again.sealedAt()).isEqualTo(TestClockConfig.FIXED_NOW);
    }

    @Test
    void seal_lateDeclarationAndModificationRejected() {
        String user = newUser("R1", "1000");
        declare("req-1", user, "2026-01", "500");
        service.seal(new ApiDtos.SealRequest(user, 2026));

        // 迟到申报（新月份）拒绝
        ApiDtos.DeclarationResponse late = declare("req-2", user, "2026-02", "100");
        assertThat(late.status()).isEqualTo("REJECTED");
        assertThat(late.reason()).isEqualTo("PLAN_SEALED");

        // 封存后修改（已确认月份再申报）拒绝
        ApiDtos.DeclarationResponse modify = declare("req-3", user, "2026-01", "600");
        assertThat(modify.status()).isEqualTo("REJECTED");
        assertThat(modify.reason()).isEqualTo("PLAN_SEALED");

        // 已确认计划不受影响
        ApiDtos.QuotaResponse quota = service.quota(user, 2026);
        assertAmount("500", quota.confirmedTotal());

        // 其他年度不受封存影响
        ApiDtos.DeclarationResponse nextYear = declare("req-4", user, "2027-01", "100");
        assertThat(nextYear.status()).isEqualTo("CONFIRMED");
    }
}
