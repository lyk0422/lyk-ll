package com.example.starter.water;

import java.math.BigDecimal;
import java.time.Instant;

import com.example.starter.water.dto.CreateMeterRequest;
import com.example.starter.water.dto.CreateUserRequest;
import com.example.starter.water.dto.DeclarationRequest;
import com.example.starter.water.dto.DeclarationResult;
import com.example.starter.water.dto.LedgerView;
import com.example.starter.water.dto.ReadingRequest;
import com.example.starter.water.dto.ReadingResult;
import com.example.starter.water.dto.RestrictionRequest;
import com.example.starter.water.dto.SealResult;
import com.example.starter.water.dto.UserView;
import com.example.starter.water.service.WaterService;
import com.example.starter.water.support.BusinessRejectedException;
import com.example.starter.water.support.IdempotencyConflictException;
import com.example.starter.water.testsupport.MutableTimeProvider;
import com.example.starter.water.testsupport.TestClockConfig;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 基于 H2（MySQL 兼容模式）真实事务的取水监管业务测试。
 */
@SpringBootTest
@Import(TestClockConfig.class)
class WaterServiceIntegrationTest {

    @Autowired
    private WaterService waterService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private MutableTimeProvider clock;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
        for (String table : new String[] {"meter_reading", "declaration_idempotency", "plan_version",
                "annual_quota", "restriction_event", "meter_point", "water_user"}) {
            jdbcTemplate.execute("TRUNCATE TABLE " + table + " RESTART IDENTITY");
        }
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY TRUE");
        clock.set(Instant.parse("2026-06-01T00:00:00Z"));
    }

    private UserView newUser(String section, String quota) {
        return waterService.createUser(
                new CreateUserRequest("合成取水户-" + section, section, new BigDecimal(quota)));
    }

    private DeclarationResult declare(String requestId, long userId, String month, String volume) {
        return waterService.declare(new DeclarationRequest(
                requestId, userId, month, new BigDecimal(volume)));
    }

    private ReadingResult reading(long meterId, String time, String cumulative) {
        return waterService.reportReading(
                new ReadingRequest(meterId, time, new BigDecimal(cumulative)));
    }

    // ---- 额满边界：达到剩余额度可受理，超出整月拒绝且保留旧版本 ----

    @Test
    void declarationAtExactlyRemainingQuotaAcceptedAndOverRejected() {
        UserView user = newUser("R1", "100.000");

        DeclarationResult jan = declare("req-jan", user.id(), "2026-01", "60.000");
        assertThat(jan.status()).isEqualTo("CONFIRMED");
        assertThat(jan.acceptedVolume()).isEqualByComparingTo("60.000");
        assertThat(jan.version()).isEqualTo(1);

        // 恰好等于剩余额度 40：含边界受理。
        DeclarationResult feb = declare("req-feb", user.id(), "2026-02", "40.000");
        assertThat(feb.status()).isEqualTo("CONFIRMED");
        assertThat(feb.acceptedVolume()).isEqualByComparingTo("40.000");

        // 超出 0.001：整月拒绝，不产生新版本（2 月版本仍为 v1=40）。
        DeclarationResult over = declare("req-feb-over", user.id(), "2026-02", "40.001");
        assertThat(over.status()).isEqualTo("REJECTED");
        assertThat(over.acceptedVolume()).isNull();
        assertThat(over.version()).isNull();
        assertThat(over.reason()).contains("超出");

        LedgerView ledger = waterService.ledger(user.id(), 2026);
        assertThat(ledger.declaredTotal()).isEqualByComparingTo("100.000");
        assertThat(ledger.remaining()).isEqualByComparingTo("0.000");
        assertThat(ledger.months()).filteredOn(m -> m.month().equals("2026-02")).singleElement()
                .satisfies(m -> {
                    assertThat(m.version()).isEqualTo(1);
                    assertThat(m.acceptedVolume()).isEqualByComparingTo("40.000");
                });
    }

    // ---- 同键重放与异参冲突 ----

    @Test
    void sameRequestIdReplaysFirstResultAndDifferentParamsConflict409() {
        UserView user = newUser("R2", "100.000");

        DeclarationRequest first = new DeclarationRequest("idem-1", user.id(), "2026-01",
                new BigDecimal("30.000"));
        DeclarationResult r1 = waterService.declare(first);
        DeclarationResult r2 = waterService.declare(first);
        assertThat(r2.status()).isEqualTo("CONFIRMED");
        assertThat(r2.version()).isEqualTo(r1.version());
        assertThat(r2.acceptedVolume()).isEqualByComparingTo("30.000");

        // 异参（水量不同）：409，不产生新版本。
        assertThatThrownBy(() -> waterService.declare(new DeclarationRequest("idem-1", user.id(),
                "2026-01", new BigDecimal("31.000"))))
                .isInstanceOf(IdempotencyConflictException.class);
        // 异参（月份不同）：409。
        assertThatThrownBy(() -> waterService.declare(new DeclarationRequest("idem-1", user.id(),
                "2026-02", new BigDecimal("30.000"))))
                .isInstanceOf(IdempotencyConflictException.class);
        // 异参（取水户不同）：409。
        UserView other = newUser("R2", "100.000");
        assertThatThrownBy(() -> waterService.declare(new DeclarationRequest("idem-1", other.id(),
                "2026-01", new BigDecimal("30.000"))))
                .isInstanceOf(IdempotencyConflictException.class);

        assertThat(waterService.ledger(user.id(), 2026).months()).hasSize(1);
    }

    @Test
    void rejectedDeclarationIsAlsoIdempotentAndReplayStaysRejected() {
        UserView user = newUser("R2B", "10.000");
        DeclarationResult rejected = declare("idem-rej", user.id(), "2026-01", "11.000");
        assertThat(rejected.status()).isEqualTo("REJECTED");
        DeclarationResult replay = declare("idem-rej", user.id(), "2026-01", "11.000");
        assertThat(replay.status()).isEqualTo("REJECTED");
        assertThat(waterService.ledger(user.id(), 2026).months()).isEmpty();
    }

    // ---- 限取减半：新申报减半，已确认计划不变 ----

    @Test
    void restrictionHalvesNewDeclarationsAndKeepsConfirmedPlan() {
        UserView user = newUser("R3", "100.000");
        DeclarationResult jan = declare("req-r3-jan", user.id(), "2026-01", "60.000");
        assertThat(jan.restricted()).isFalse();

        waterService.triggerRestriction(new RestrictionRequest("R3", null, "生态流量预警"));

        DeclarationResult feb = declare("req-r3-feb", user.id(), "2026-02", "40.000");
        assertThat(feb.status()).isEqualTo("CONFIRMED");
        assertThat(feb.restricted()).isTrue();
        assertThat(feb.acceptedVolume()).isEqualByComparingTo("20.000");

        // 减半四舍五入保留 3 位：10.001/2 = 5.0005 → 5.001。
        DeclarationResult mar = declare("req-r3-mar", user.id(), "2026-03", "10.001");
        assertThat(mar.acceptedVolume()).isEqualByComparingTo("5.001");

        LedgerView ledger = waterService.ledger(user.id(), 2026);
        // 已确认的 1 月计划 60 不变；2 月减半受理 20。
        assertThat(ledger.declaredTotal()).isEqualByComparingTo("85.001");
        assertThat(ledger.remaining()).isEqualByComparingTo("14.999");
        assertThat(ledger.months()).filteredOn(m -> m.month().equals("2026-01")).singleElement()
                .satisfies(m -> {
                    assertThat(m.acceptedVolume()).isEqualByComparingTo("60.000");
                    assertThat(m.version()).isEqualTo(1);
                });
    }

    @Test
    void futureRestrictionNotEffectiveUntilItsTime() {
        UserView user = newUser("R3B", "100.000");
        // 生效时刻在未来 1 小时：当前申报仍全额。
        waterService.triggerRestriction(new RestrictionRequest("R3B",
                clock.now().plusSeconds(3600).toString(), "未来预警"));
        DeclarationResult now = declare("req-before-effective", user.id(), "2026-01", "10.000");
        assertThat(now.acceptedVolume()).isEqualByComparingTo("10.000");
        assertThat(now.restricted()).isFalse();

        clock.advanceSeconds(7200);
        DeclarationResult later = declare("req-after-effective", user.id(), "2026-02", "10.000");
        assertThat(later.acceptedVolume()).isEqualByComparingTo("5.000");
        assertThat(later.restricted()).isTrue();
    }

    // ---- 读数去重与乱序归月 ----

    @Test
    void readingsDeduplicatedAndOutOfOrderAttributedToCorrectMonth() {
        UserView user = newUser("R4", "1000.000");
        Long meterId = waterService.createMeter(user.id(), new CreateMeterRequest("主计量点")).id();

        // 1 月首个表底 10（起始用量 10）。
        ReadingResult jan = reading(meterId, "2026-01-31T23:00:00Z", "10.000");
        assertThat(jan.delta()).isEqualByComparingTo("10.000");
        assertThat(jan.month()).isEqualTo("2026-01");

        // 3 月读数 30：增量 20。
        ReadingResult mar = reading(meterId, "2026-03-15T08:00:00Z", "30.000");
        assertThat(mar.delta()).isEqualByComparingTo("20.000");

        // 2 月读数乱序迟到（表底 20）：自身增量 10，3 月读数增量重算为 10。
        ReadingResult febLate = reading(meterId, "2026-02-15T08:00:00Z", "20.000");
        assertThat(febLate.duplicate()).isFalse();
        assertThat(febLate.delta()).isEqualByComparingTo("10.000");
        assertThat(febLate.month()).isEqualTo("2026-02");

        // 同“计量点＋表底时刻”重复上报：不重累计。
        ReadingResult dup = reading(meterId, "2026-02-15T08:00:00Z", "20.000");
        assertThat(dup.duplicate()).isTrue();
        ReadingResult dup2 = reading(meterId, "2026-03-15T08:00:00Z", "30.000");
        assertThat(dup2.duplicate()).isTrue();

        LedgerView ledger = waterService.ledger(user.id(), 2026);
        assertThat(ledger.actualTotal()).isEqualByComparingTo("30.000");
        assertThat(ledger.months()).anySatisfy(m -> {
            assertThat(m.month()).isEqualTo("2026-01");
            assertThat(m.actualVolume()).isEqualByComparingTo("10.000");
        });
        assertThat(ledger.months()).anySatisfy(m -> {
            assertThat(m.month()).isEqualTo("2026-02");
            assertThat(m.actualVolume()).isEqualByComparingTo("10.000");
        });
        assertThat(ledger.months()).anySatisfy(m -> {
            assertThat(m.month()).isEqualTo("2026-03");
            assertThat(m.actualVolume()).isEqualByComparingTo("10.000");
        });
    }

    @Test
    void meterReadingBelowPreviousCumulativeIsRejected() {
        UserView user = newUser("R4B", "1000.000");
        Long meterId = waterService.createMeter(user.id(), new CreateMeterRequest("主计量点")).id();
        reading(meterId, "2026-01-15T00:00:00Z", "100.000");
        assertThatThrownBy(() -> reading(meterId, "2026-01-16T00:00:00Z", "90.000"))
                .isInstanceOf(BusinessRejectedException.class);
        LedgerView ledger = waterService.ledger(user.id(), 2026);
        assertThat(ledger.actualTotal()).isEqualByComparingTo("100.000");
    }

    // ---- 超限结转：按实际读数结算，1:1 扣减次年额度 ----

    @Test
    void excessCarriesOverToOneNextYearAndReducesNextYearQuota() {
        UserView user = newUser("R5", "100.000");
        Long meterId = waterService.createMeter(user.id(), new CreateMeterRequest("主计量点")).id();
        reading(meterId, "2026-06-01T00:00:00Z", "130.000");

        SealResult seal = waterService.seal(user.id(), 2026);
        assertThat(seal.sealed()).isTrue();
        assertThat(seal.actualTotal()).isEqualByComparingTo("130.000");
        assertThat(seal.excess()).isEqualByComparingTo("30.000");
        assertThat(seal.carryoverToNextYear()).isEqualByComparingTo("30.000");

        // 次年有效额度 = 100 - 30 = 70。
        LedgerView nextYear = waterService.ledger(user.id(), 2027);
        assertThat(nextYear.effectiveQuota()).isEqualByComparingTo("70.000");
        assertThat(nextYear.carryoverDeduction()).isEqualByComparingTo("30.000");

        // 申报 80 超次年剩余 → 拒绝；申报 70 恰好额满受理。
        assertThat(declare("req-next-over", user.id(), "2027-01", "80.000").status())
                .isEqualTo("REJECTED");
        DeclarationResult boundary = declare("req-next-ok", user.id(), "2027-01", "70.000");
        assertThat(boundary.status()).isEqualTo("CONFIRMED");
        assertThat(boundary.acceptedVolume()).isEqualByComparingTo("70.000");
    }

    @Test
    void sealWithoutExcessCarriesZero() {
        UserView user = newUser("R5B", "100.000");
        Long meterId = waterService.createMeter(user.id(), new CreateMeterRequest("主计量点")).id();
        reading(meterId, "2026-05-01T00:00:00Z", "50.000");
        SealResult seal = waterService.seal(user.id(), 2026);
        assertThat(seal.excess()).isEqualByComparingTo("0.000");
        assertThat(waterService.ledger(user.id(), 2027).effectiveQuota())
                .isEqualByComparingTo("100.000");
    }

    // ---- 封存终态：修改、迟到申报、迟到读数拒绝；重复封存幂等 ----

    @Test
    void sealedYearRejectsLatePlanAndReadingsAndRepeatedSealIsIdempotent() {
        UserView user = newUser("R6", "100.000");
        Long meterId = waterService.createMeter(user.id(), new CreateMeterRequest("主计量点")).id();
        declare("req-r6-jan", user.id(), "2026-01", "40.000");
        reading(meterId, "2026-01-20T00:00:00Z", "120.000");

        SealResult first = waterService.seal(user.id(), 2026);
        assertThat(first.excess()).isEqualByComparingTo("20.000");

        // 迟到申报（新月）拒绝，拒绝结果幂等。
        DeclarationResult lateNew = declare("req-late-new", user.id(), "2026-08", "1.000");
        assertThat(lateNew.status()).isEqualTo("REJECTED");
        assertThat(lateNew.reason()).contains("封存");
        // 对已确认月的修改拒绝。
        DeclarationResult lateModify = declare("req-late-mod", user.id(), "2026-01", "41.000");
        assertThat(lateModify.status()).isEqualTo("REJECTED");
        // 封存年的迟到读数抛业务异常拒绝，冻结实际总量。
        assertThatThrownBy(() -> reading(meterId, "2026-02-20T00:00:00Z", "200.000"))
                .isInstanceOf(BusinessRejectedException.class);

        // 封存后再到达的读数不影响冻结值。
        LedgerView ledger = waterService.ledger(user.id(), 2026);
        assertThat(ledger.sealed()).isTrue();
        assertThat(ledger.actualTotal()).isEqualByComparingTo("120.000");
        assertThat(ledger.excess()).isEqualByComparingTo("20.000");

        // 重复封存幂等成功，回放冻结值（即使时钟推进）。
        clock.advanceSeconds(3600);
        SealResult repeated = waterService.seal(user.id(), 2026);
        assertThat(repeated.sealed()).isTrue();
        assertThat(repeated.actualTotal()).isEqualByComparingTo("120.000");
        assertThat(repeated.excess()).isEqualByComparingTo("20.000");
        assertThat(repeated.carryoverToNextYear()).isEqualByComparingTo("20.000");
    }

    @Test
    void rejectedLateDeclarationAfterSealIsIdempotentPerRequestId() {
        UserView user = newUser("R6B", "100.000");
        waterService.seal(user.id(), 2026);
        DeclarationResult first = declare("req-late-x", user.id(), "2026-03", "5.000");
        assertThat(first.status()).isEqualTo("REJECTED");
        assertThat(first.reason()).contains("封存");
        DeclarationResult replay = declare("req-late-x", user.id(), "2026-03", "5.000");
        assertThat(replay.status()).isEqualTo("REJECTED");
    }

    // ---- 计量点不存在等失败分支 ----

    @Test
    void unknownResourcesAreReported() {
        assertThatThrownBy(() -> waterService.createMeter(999999L, new CreateMeterRequest("x")))
                .hasMessageContaining("取水户不存在");
        assertThatThrownBy(() -> declare("req-x", 999999L, "2026-01", "1.000"))
                .hasMessageContaining("取水户不存在");
        assertThatThrownBy(() -> waterService.reportReading(
                new ReadingRequest(999999L, "2026-01-01T00:00:00Z", new BigDecimal("1"))))
                .hasMessageContaining("计量点不存在");
    }
}
