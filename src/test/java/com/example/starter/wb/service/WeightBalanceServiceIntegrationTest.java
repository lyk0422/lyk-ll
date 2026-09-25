package com.example.starter.wb.service;

import com.example.starter.wb.AbstractIntegrationTest;
import com.example.starter.wb.dto.CheckinRequest;
import com.example.starter.wb.dto.CloseRequest;
import com.example.starter.wb.dto.CreateManifestRequest;
import com.example.starter.wb.dto.LateCheckinRequest;
import com.example.starter.wb.dto.LoadItem;
import com.example.starter.wb.dto.LmcRequest;
import com.example.starter.wb.dto.ManifestResponse;
import com.example.starter.wb.dto.ReviewRequest;
import com.example.starter.wb.dto.StageRequest;
import com.example.starter.wb.exception.ConflictException;
import com.example.starter.wb.exception.UnprocessableException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * H2 事务集成测试：载重恰等上限、重心恰边界、LMC 恰达/超限重打、
 * 双岗复核、油量重心联动、关闭终态与迟到值机告警。
 */
class WeightBalanceServiceIntegrationTest extends AbstractIntegrationTest {

    private static final long FLIGHT_ID = 1L;

    @Autowired
    private WeightBalanceService service;

    private static String rid() {
        return UUID.randomUUID().toString();
    }

    /** 90 人 @80kg@PAX_F(600) = 7200kg；6 件 @1000kg@CARGO_F(500) = 6000kg；业载 13200kg。 */
    private static List<LoadItem> baseItems() {
        return List.of(
                new LoadItem("PASSENGER", "PAX_F", 90, new BigDecimal("80")),
                new LoadItem("CARGO", "CARGO_F", 6, new BigDecimal("1000")));
    }

    private CreateManifestRequest create(BigDecimal fuel, List<LoadItem> items) {
        return new CreateManifestRequest(rid(), FLIGHT_ID, fuel, "lc001", items);
    }

    private ManifestResponse preliminary(BigDecimal fuel, List<LoadItem> items) {
        return service.createManifest(create(fuel, items));
    }

    private ManifestResponse toLoaded(List<LoadItem> items, BigDecimal fuel) {
        service.createManifest(create(fuel, items));
        service.checkin(FLIGHT_ID, new CheckinRequest(rid(), items, fuel, "agent01"));
        return service.confirmLoad(FLIGHT_ID, new StageRequest(rid(), "loader01"));
    }

    private LmcRequest lmc(int paxDelta, BigDecimal weightDelta, BigDecimal fuelDelta) {
        return new LmcRequest(rid(), paxDelta, "PAX_F", new BigDecimal("80"),
                weightDelta, "CARGO", "CARGO_F", fuelDelta, "lc001");
    }

    // ----------------------------------------------------- 业载恰等上限 + 重心恰边界

    @Test
    void payloadExactlyAtLimitAndCgExactlyOnBoundaryAreAccepted() {
        // 100 人@80@600=8000kg；7 件@1000@500=7000kg；业载恰 15000=MZFW-OEW，
        // ZFW=55000 重心恰 14%MAC（顶点），加油 5000kg 后 TOW=60000 重心恰 15%MAC（斜边）。
        List<LoadItem> exact = List.of(
                new LoadItem("PASSENGER", "PAX_F", 100, new BigDecimal("80")),
                new LoadItem("CARGO", "CARGO_F", 7, new BigDecimal("1000")));
        ManifestResponse m = preliminary(new BigDecimal("5000"), exact);
        assertThat(m.status()).isEqualTo("PRELIMINARY");
        assertThat(m.payloadKg()).isEqualByComparingTo("15000.00");
        assertThat(m.zfwKg()).isEqualByComparingTo("55000");
        assertThat(m.cgZfwPct()).isEqualByComparingTo("14.0000");
        assertThat(m.towKg()).isEqualByComparingTo("60000");
        assertThat(m.cgTowPct()).isEqualByComparingTo("15.0000");
        assertThat(m.withinLimits()).isTrue();
        assertThat(m.violations()).isEmpty();
    }

    @Test
    void payloadOneKiloOverLimitIsRejectedWith422() {
        List<LoadItem> over = List.of(
                new LoadItem("PASSENGER", "PAX_F", 100, new BigDecimal("80")),
                new LoadItem("CARGO", "CARGO_F", 7, new BigDecimal("1001")));
        assertThatThrownBy(() -> preliminary(new BigDecimal("5000"), over))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("MZFW-OEW");
        // 失败不落任何版本
        Integer versions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM manifest_version WHERE flight_id = 1", Integer.class);
        assertThat(versions).isZero();
    }

    // ----------------------------------------------------- 全流程 + 双岗

    @Test
    void fullFlowPreliminaryCheckinLoadedTwoPersonReviewClose() {
        ManifestResponse loaded = toLoaded(baseItems(), new BigDecimal("5000"));
        assertThat(loaded.status()).isEqualTo("LOADED");
        assertThat(loaded.frozen()).isFalse();

        // 第一岗（配载）签字不生效：仍 LOADED
        ManifestResponse first = service.review(FLIGHT_ID,
                new ReviewRequest(rid(), "LOAD_CONTROLLER", "lc001"));
        assertThat(first.status()).isEqualTo("LOADED");
        assertThat(first.confirmedRoles()).containsExactly("LOAD_CONTROLLER");
        assertThat(first.frozen()).isFalse();

        // 第二岗（签派，不同人员）签字后复核通过、舱单冻结
        ManifestResponse reviewed = service.review(FLIGHT_ID,
                new ReviewRequest(rid(), "FLIGHT_DISPATCHER", "fd002"));
        assertThat(reviewed.status()).isEqualTo("REVIEWED");
        assertThat(reviewed.frozen()).isTrue();
        assertThat(reviewed.confirmedRoles())
                .containsExactlyInAnyOrder("LOAD_CONTROLLER", "FLIGHT_DISPATCHER");

        ManifestResponse closed = service.close(FLIGHT_ID, new CloseRequest(rid(), "captain"));
        assertThat(closed.status()).isEqualTo("CLOSED");
        assertThat(closed.closedAt()).isNotNull();
    }

    @Test
    void sameRoleCannotSignTwiceAndSamePersonCannotCoverBothRoles() {
        toLoaded(baseItems(), new BigDecimal("5000"));
        service.review(FLIGHT_ID, new ReviewRequest(rid(), "LOAD_CONTROLLER", "lc001"));

        assertThatThrownBy(() -> service.review(FLIGHT_ID,
                new ReviewRequest(rid(), "LOAD_CONTROLLER", "lc999")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("已由");

        assertThatThrownBy(() -> service.review(FLIGHT_ID,
                new ReviewRequest(rid(), "FLIGHT_DISPATCHER", "lc001")))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("两名不同人员");
    }

    @Test
    void stageTransitionsAreEnforced() {
        service.createManifest(create(new BigDecimal("5000"), baseItems()));
        // 未值机不能装载确认
        assertThatThrownBy(() -> service.confirmLoad(FLIGHT_ID, new StageRequest(rid(), "loader01")))
                .isInstanceOf(ConflictException.class);
        // 未双岗复核不能关闭
        service.checkin(FLIGHT_ID, new CheckinRequest(rid(), baseItems(), new BigDecimal("5000"), "a"));
        service.confirmLoad(FLIGHT_ID, new StageRequest(rid(), "loader01"));
        assertThatThrownBy(() -> service.close(FLIGHT_ID, new CloseRequest(rid(), "captain")))
                .isInstanceOf(ConflictException.class);
    }

    // ----------------------------------------------------- LMC 恰达 / 超限重打

    @Test
    void lmcExactlyAtLimitStaysOnSameVersion() {
        ManifestResponse loaded = toLoaded(baseItems(), new BigDecimal("5000"));
        long v1 = loaded.manifestId();

        ManifestResponse plusFivePax = service.applyLmc(FLIGHT_ID,
                lmc(5, BigDecimal.ZERO, BigDecimal.ZERO));
        assertThat(plusFivePax.manifestId()).isEqualTo(v1);
        assertThat(plusFivePax.lmcPaxDelta()).isEqualTo(5);
        assertThat(plusFivePax.lmcWithinLimit()).isTrue();
        assertThat(plusFivePax.passengerCount()).isEqualTo(95);

        ManifestResponse plusFiveHundred = service.applyLmc(FLIGHT_ID,
                lmc(0, new BigDecimal("500"), BigDecimal.ZERO));
        assertThat(plusFiveHundred.manifestId()).isEqualTo(v1);
        assertThat(plusFiveHundred.lmcWeightDelta()).isEqualByComparingTo("500.00");
        assertThat(plusFiveHundred.lmcWithinLimit()).isTrue();
        assertThat(plusFiveHundred.payloadKg()).isEqualByComparingTo("14100.00");
    }

    @Test
    void lmcOverLimitTriggersReprintWithVersionChainAndResetsReview() {
        ManifestResponse loaded = toLoaded(baseItems(), new BigDecimal("5000"));
        long v1 = loaded.manifestId();
        service.applyLmc(FLIGHT_ID, lmc(5, new BigDecimal("500"), BigDecimal.ZERO));

        // 再 +1 人即累计 6 人 > 5：必须重打
        ManifestResponse v2 = service.applyLmc(FLIGHT_ID,
                lmc(1, BigDecimal.ZERO, BigDecimal.ZERO));
        assertThat(v2.manifestId()).isNotEqualTo(v1);
        assertThat(v2.versionNo()).isEqualTo(2);
        assertThat(v2.parentVersionId()).isEqualTo(v1);
        assertThat(v2.status()).isEqualTo("LOADED");
        assertThat(v2.lmcPaxDelta()).isZero();
        assertThat(v2.lmcWeightDelta()).isEqualByComparingTo("0.00");

        // 旧版已作废，同时刻只有一个有效版本
        Integer active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM manifest_version WHERE flight_id=1 AND active_flag=1", Integer.class);
        assertThat(active).isEqualTo(1);
        String oldStatus = jdbc.queryForObject(
                "SELECT status FROM manifest_version WHERE id=?", String.class, v1);
        assertThat(oldStatus).isEqualTo("SUPERSEDED");

        // LMC 链记录了新版本号
        Long linked = jdbc.queryForObject(
                "SELECT new_version_id FROM lmc_log WHERE within_lmc_limit=FALSE", Long.class);
        assertThat(linked).isEqualTo(v2.manifestId());

        // 新版本沿用旧版全部明细并含触发重打的增量，可复算
        assertThat(v2.passengerCount()).isEqualTo(96);

        // 版本链可查询：v1 SUPERSEDED（指向 v2），v2 LOADED
        List<ManifestResponse> versions = service.listVersions(FLIGHT_ID);
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).status()).isEqualTo("SUPERSEDED");
        assertThat(versions.get(0).supersededByVersionId()).isEqualTo(v2.manifestId());
        assertThat(versions.get(1).versionNo()).isEqualTo(2);
        assertThat(versions.get(1).status()).isEqualTo("LOADED");

        // 重打后需重新双岗复核：直接关闭被拒绝
        assertThatThrownBy(() -> service.close(FLIGHT_ID, new CloseRequest(rid(), "captain")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void lmcNotAllowedBeforeCheckin() {
        preliminary(new BigDecimal("5000"), baseItems());
        assertThatThrownBy(() -> service.applyLmc(FLIGHT_ID,
                lmc(1, BigDecimal.ZERO, BigDecimal.ZERO)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("值机全量更新");
    }

    @Test
    void lmcNegativeDeltaBeyondLoadedAmountAndNegativeFuelAreRejected() {
        toLoaded(baseItems(), new BigDecimal("5000"));
        // 前舱仅 90 人，退载 91 人 → 422
        assertThatThrownBy(() -> service.applyLmc(FLIGHT_ID,
                lmc(-91, BigDecimal.ZERO, BigDecimal.ZERO)))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("旅客人数为负");
        // 燃油 -6000 使油量为负 → 422（不允许改油到负值）
        assertThatThrownBy(() -> service.applyLmc(FLIGHT_ID,
                lmc(0, BigDecimal.ZERO, new BigDecimal("-6000"))))
                .isInstanceOf(UnprocessableException.class);
    }

    // ----------------------------------------------------- 油量与重心联动

    @Test
    void fuelChangeRecalculatesCgInSameTransaction() {
        ManifestResponse loaded = toLoaded(baseItems(), new BigDecimal("5000"));
        BigDecimal cgTowBefore = loaded.cgTowPct();
        BigDecimal zfwCgBefore = loaded.cgZfwPct();
        assertThat(loaded.fuelKg()).isEqualByComparingTo("5000.00");

        ManifestResponse refueled = service.applyLmc(FLIGHT_ID,
                lmc(0, BigDecimal.ZERO, new BigDecimal("1000")));
        assertThat(refueled.fuelKg()).isEqualByComparingTo("6000.00");
        assertThat(refueled.cgTowPct()).isNotEqualByComparingTo(cgTowBefore);
        // 无油重心不随油量变化；业载保持不变
        assertThat(refueled.cgZfwPct()).isEqualByComparingTo(zfwCgBefore);
        assertThat(refueled.payloadKg()).isEqualByComparingTo(loaded.payloadKg());

        // 数据库中油量与重心一起更新，不存在“改油不改重心”的中间态
        java.util.Map<String, Object> row = jdbc.queryForMap(
                "SELECT fuel_kg, cg_tow_pct FROM manifest_version WHERE id=?", loaded.manifestId());
        assertThat(row.get("fuel_kg")).isNotNull();
        assertThat(row.get("cg_tow_pct")).isNotNull();
    }

    // ----------------------------------------------------- 关闭终态与迟到值机

    @Test
    void repeatedCloseIsIdempotentAndLateCheckinIsOnlyRecordedWithAlert() {
        ManifestResponse loaded = toLoaded(baseItems(), new BigDecimal("5000"));
        service.review(FLIGHT_ID, new ReviewRequest(rid(), "LOAD_CONTROLLER", "lc001"));
        service.review(FLIGHT_ID, new ReviewRequest(rid(), "FLIGHT_DISPATCHER", "fd002"));
        ManifestResponse closed1 = service.close(FLIGHT_ID, new CloseRequest(rid(), "captain"));

        // 不同 requestId 重复关闭：终态幂等成功，closedAt 不变
        ManifestResponse closed2 = service.close(FLIGHT_ID, new CloseRequest(rid(), "captain"));
        assertThat(closed2.status()).isEqualTo("CLOSED");
        assertThat(closed2.manifestId()).isEqualTo(closed1.manifestId());
        assertThat(closed2.closedAt()).isEqualTo(closed1.closedAt());

        BigDecimal payloadBefore = closed2.payloadKg();
        ManifestResponse withLate = service.recordLateCheckin(FLIGHT_ID,
                new LateCheckinRequest(rid(), 2, new BigDecimal("160"), "两名晚到旅客"));
        assertThat(withLate.status()).isEqualTo("CLOSED");
        assertThat(withLate.payloadKg()).isEqualByComparingTo(payloadBefore);
        assertThat(withLate.lateAlerts()).hasSize(1);
        assertThat(withLate.lateAlerts().get(0).passengerCount()).isEqualTo(2);
        assertThat(withLate.lateAlerts().get(0).alerted()).isTrue();

        Integer lateCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM late_event WHERE flight_id=1 AND alerted=TRUE", Integer.class);
        assertThat(lateCount).isEqualTo(1);
    }

    @Test
    void lateCheckinBeforeCloseIsRejected() {
        toLoaded(baseItems(), new BigDecimal("5000"));
        assertThatThrownBy(() -> service.recordLateCheckin(FLIGHT_ID,
                new LateCheckinRequest(rid(), 1, new BigDecimal("80"), "晚到")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("放行关闭");
    }
}
