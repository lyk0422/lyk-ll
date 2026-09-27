package com.example.starter.wb.service;

import com.example.starter.wb.AbstractIntegrationTest;
import com.example.starter.wb.dto.AssemblyItemRequest;
import com.example.starter.wb.dto.AssemblyPlanResponse;
import com.example.starter.wb.dto.AssemblySubmitRequest;
import com.example.starter.wb.dto.CheckinRequest;
import com.example.starter.wb.dto.CloseRequest;
import com.example.starter.wb.dto.CreateManifestRequest;
import com.example.starter.wb.dto.LoadItem;
import com.example.starter.wb.dto.LmcRequest;
import com.example.starter.wb.dto.ManifestResponse;
import com.example.starter.wb.dto.ReviewRequest;
import com.example.starter.wb.dto.StageRequest;
import com.example.starter.wb.exception.BadRequestException;
import com.example.starter.wb.exception.ConflictException;
import com.example.starter.wb.exception.NotFoundException;
import com.example.starter.wb.exception.UnprocessableException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 集装器装配图 H2 集成测试：提交主流程、舱位/类型/重量一致性校验、
 * 版本联动、复核失效、关闭释放与幂等语义。
 */
class AssemblyServiceIntegrationTest extends AbstractIntegrationTest {

    private static final long FLIGHT_ID = 1L;
    private static final BigDecimal FUEL = new BigDecimal("5000");
    private static final LocalDateTime SUBMITTED_AT = LocalDateTime.of(2026, 9, 26, 8, 30, 0);

    @Autowired
    private WeightBalanceService wbService;

    @Autowired
    private AssemblyService assemblyService;

    private static String rid() {
        return UUID.randomUUID().toString();
    }

    /** 90 人 @80kg@PAX_F = 7200kg；6 件 @1000kg@CARGO_F = 6000kg；货运业载 6000kg。 */
    private static List<LoadItem> items() {
        return List.of(
                new LoadItem("PASSENGER", "PAX_F", 90, new BigDecimal("80")),
                new LoadItem("CARGO", "CARGO_F", 6, new BigDecimal("1000")));
    }

    /** 两台 AKE 恰达舱位限额：3000+3000=6000kg，力矩 84,000,000 kg·mm。 */
    private static List<AssemblyItemRequest> defaultPlanItems() {
        return List.of(
                new AssemblyItemRequest("ULD001", "AKE", "ULD_P1", 80, 2920),
                new AssemblyItemRequest("ULD002", "AKE", "ULD_P2", 80, 2920));
    }

    private AssemblySubmitRequest planReq(String requestId, int expManifest, int expPlan,
                                          List<AssemblyItemRequest> items) {
        return new AssemblySubmitRequest(requestId, expManifest, expPlan, items, "uld01", SUBMITTED_AT);
    }

    private ManifestResponse createControlled(List<LoadItem> items) {
        return wbService.createManifest(new CreateManifestRequest(
                rid(), FLIGHT_ID, FUEL, "lc001", items, true));
    }

    private ManifestResponse controlledLoaded() {
        createControlled(items());
        wbService.checkin(FLIGHT_ID, new CheckinRequest(rid(), items(), FUEL, "agent01"));
        return wbService.confirmLoad(FLIGHT_ID, new StageRequest(rid(), "loader01"));
    }

    private void reviewBoth() {
        wbService.review(FLIGHT_ID, new ReviewRequest(rid(), "LOAD_CONTROLLER", "lc001"));
        wbService.review(FLIGHT_ID, new ReviewRequest(rid(), "FLIGHT_DISPATCHER", "fd002"));
    }

    // ------------------------------------------------------------- 主流程

    @Test
    void submitHappyPathWritesPlanUpdatesCargoMomentAndOccupiesUlds() {
        controlledLoaded();
        AssemblyPlanResponse plan = assemblyService.submit(FLIGHT_ID,
                planReq(rid(), 1, 0, defaultPlanItems()));

        assertThat(plan.planVersion()).isEqualTo(1);
        assertThat(plan.manifestVersionNo()).isEqualTo(1);
        assertThat(plan.status()).isEqualTo("ACTIVE");
        assertThat(plan.totalTareKg()).isEqualTo(160);
        assertThat(plan.totalNetKg()).isEqualTo(5840);
        assertThat(plan.totalWeightKg()).isEqualTo(6000);
        // 力矩精确求和：3000×13000 + 3000×15000 = 84,000,000 kg·mm
        assertThat(plan.cargoMomentKgMm()).isEqualByComparingTo("84000000");
        assertThat(plan.cgZfwPct()).isEqualByComparingTo("17.8728");
        assertThat(plan.cgTowPct()).isEqualByComparingTo("18.5710");
        assertThat(plan.items()).hasSize(2);
        assertThat(plan.items().get(0).totalKg()).isEqualTo(3000);
        assertThat(plan.items().get(0).momentKgMm()).isEqualByComparingTo("39000000");

        // 舱单货运力矩（重心）已按装配图更新
        ManifestResponse active = wbService.getActive(FLIGHT_ID);
        assertThat(active.assemblyControlled()).isTrue();
        assertThat(active.assemblyPlanVersion()).isEqualTo(1);
        assertThat(active.cgZfwPct()).isEqualByComparingTo("17.8728");
        assertThat(active.cgTowPct()).isEqualByComparingTo("18.5710");
        assertThat(active.payloadKg()).isEqualByComparingTo("13200.00");

        // 实物占用可追溯
        assertThat(assemblyService.getOccupation("ULD001")).isPresent()
                .get().extracting(o -> o.flightId()).isEqualTo(FLIGHT_ID);
        Integer occupations = jdbc.queryForObject(
                "SELECT COUNT(*) FROM uld_occupation WHERE flight_id=1", Integer.class);
        assertThat(occupations).isEqualTo(2);

        // 有效装配图可查询
        AssemblyPlanResponse active2 = assemblyService.getActive(FLIGHT_ID);
        assertThat(active2.planId()).isEqualTo(plan.planId());
        assertThat(assemblyService.listVersions(FLIGHT_ID)).hasSize(1);
    }

    @Test
    void exactlyAtCompartmentLimitIsLegal() {
        controlledLoaded();
        // 3000 = ULD_P1 限额 3000，恰达合法
        AssemblyPlanResponse plan = assemblyService.submit(FLIGHT_ID, planReq(rid(), 1, 0,
                List.of(new AssemblyItemRequest("ULD001", "AKE", "ULD_P1", 0, 3000),
                        new AssemblyItemRequest("ULD002", "AKE", "ULD_P2", 0, 3000))));
        assertThat(plan.totalWeightKg()).isEqualTo(6000);
    }

    @Test
    void fullFlowAssemblyReviewCloseReleasesOccupationAtomically() {
        controlledLoaded();
        assemblyService.submit(FLIGHT_ID, planReq(rid(), 1, 0, defaultPlanItems()));
        reviewBoth();
        ManifestResponse closed = wbService.close(FLIGHT_ID, new CloseRequest(rid(), "captain"));
        assertThat(closed.status()).isEqualTo("CLOSED");

        // 实物占用原子释放，历史图只读（RELEASED）
        Integer occupations = jdbc.queryForObject(
                "SELECT COUNT(*) FROM uld_occupation WHERE flight_id=1", Integer.class);
        assertThat(occupations).isZero();
        List<AssemblyPlanResponse> versions = assemblyService.listVersions(FLIGHT_ID);
        assertThat(versions).hasSize(1);
        assertThat(versions.get(0).status()).isEqualTo("RELEASED");
        assertThatThrownBy(() -> assemblyService.getActive(FLIGHT_ID))
                .isInstanceOf(NotFoundException.class);
        // 释放后编号可被其他航班占用
        assertThat(assemblyService.getOccupation("ULD001")).isEmpty();
    }

    // ------------------------------------------------------------- 校验失败

    @Test
    void overCompartmentLimitRejected422AndFailureDoesNotOccupyKey() {
        controlledLoaded();
        String requestId = rid();
        // 3001 > ULD_P1 限额 3000 → 422，整版拒绝
        assertThatThrownBy(() -> assemblyService.submit(FLIGHT_ID, planReq(requestId, 1, 0,
                List.of(new AssemblyItemRequest("ULD001", "AKE", "ULD_P1", 80, 2921),
                        new AssemblyItemRequest("ULD002", "AKE", "ULD_P2", 80, 2920)))))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("限额");
        // 失败不占键：同键修正参数后可重新提交成功
        Integer idem = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id=?", Integer.class, requestId);
        assertThat(idem).isZero();
        AssemblyPlanResponse plan = assemblyService.submit(FLIGHT_ID,
                planReq(requestId, 1, 0, defaultPlanItems()));
        assertThat(plan.planVersion()).isEqualTo(1);
    }

    @Test
    void duplicateUldNumberInSamePlanRejected400() {
        controlledLoaded();
        assertThatThrownBy(() -> assemblyService.submit(FLIGHT_ID, planReq(rid(), 1, 0,
                List.of(new AssemblyItemRequest("ULD001", "AKE", "ULD_P1", 80, 2920),
                        new AssemblyItemRequest("ULD001", "AKE", "ULD_P2", 80, 2920)))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("重复");
    }

    @Test
    void twoUldsInSameCompartmentRejected422() {
        controlledLoaded();
        assertThatThrownBy(() -> assemblyService.submit(FLIGHT_ID, planReq(rid(), 1, 0,
                List.of(new AssemblyItemRequest("ULD001", "AKE", "ULD_P1", 80, 1000),
                        new AssemblyItemRequest("ULD002", "AKE", "ULD_P1", 80, 4840)))))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("最多装载一器");
    }

    @Test
    void unknownCompartmentRejected400AndTypeNotAllowedRejected422() {
        controlledLoaded();
        assertThatThrownBy(() -> assemblyService.submit(FLIGHT_ID, planReq(rid(), 1, 0,
                List.of(new AssemblyItemRequest("ULD001", "AKE", "ULD_XX", 80, 2920),
                        new AssemblyItemRequest("ULD002", "AKE", "ULD_P2", 80, 2920)))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("不存在舱位");
        // ULD_P1 仅允许 AKE/DPE，PLA 不允许 → 422
        assertThatThrownBy(() -> assemblyService.submit(FLIGHT_ID, planReq(rid(), 1, 0,
                List.of(new AssemblyItemRequest("ULD001", "PLA", "ULD_P1", 80, 2920),
                        new AssemblyItemRequest("ULD002", "AKE", "ULD_P2", 80, 2920)))))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("不允许集装器类型");
    }

    @Test
    void negativeTareOrNonPositiveNetRejected400() {
        controlledLoaded();
        assertThatThrownBy(() -> assemblyService.submit(FLIGHT_ID, planReq(rid(), 1, 0,
                List.of(new AssemblyItemRequest("ULD001", "AKE", "ULD_P1", -1, 2921),
                        new AssemblyItemRequest("ULD002", "AKE", "ULD_P2", 80, 2920)))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("皮重");
        assertThatThrownBy(() -> assemblyService.submit(FLIGHT_ID, planReq(rid(), 1, 0,
                List.of(new AssemblyItemRequest("ULD001", "AKE", "ULD_P1", 80, 0),
                        new AssemblyItemRequest("ULD002", "AKE", "ULD_P2", 80, 5920)))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("净重");
    }

    @Test
    void totalWeightMustEqualCargoPayload() {
        controlledLoaded();
        // 5900 ≠ 货运业载 6000 → 422
        assertThatThrownBy(() -> assemblyService.submit(FLIGHT_ID, planReq(rid(), 1, 0,
                List.of(new AssemblyItemRequest("ULD001", "AKE", "ULD_P1", 80, 2920),
                        new AssemblyItemRequest("ULD002", "AKE", "ULD_P2", 80, 2820)))))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("货运业载");
        Integer plans = jdbc.queryForObject("SELECT COUNT(*) FROM assembly_plan", Integer.class);
        assertThat(plans).isZero();
    }

    @Test
    void versionMismatchRejected409() {
        controlledLoaded();
        // 尚无装配图时期望版本 1 → 409
        assertThatThrownBy(() -> assemblyService.submit(FLIGHT_ID,
                planReq(rid(), 1, 1, defaultPlanItems())))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("装配图版本");
        // 舱单版本号不符 → 409
        assertThatThrownBy(() -> assemblyService.submit(FLIGHT_ID,
                planReq(rid(), 2, 0, defaultPlanItems())))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("舱单版本");
        // 首次提交成功后旧期望版本失效
        assemblyService.submit(FLIGHT_ID, planReq(rid(), 1, 0, defaultPlanItems()));
        assertThatThrownBy(() -> assemblyService.submit(FLIGHT_ID,
                planReq(rid(), 1, 0, defaultPlanItems())))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("装配图版本");
    }

    @Test
    void idempotencyReplaysFirstResultAndRejectsDifferentParams() {
        controlledLoaded();
        String requestId = rid();
        AssemblyPlanResponse first = assemblyService.submit(FLIGHT_ID,
                planReq(requestId, 1, 0, defaultPlanItems()));
        // 同键同参：回放首次冻结结果，不产生新图版本
        AssemblyPlanResponse replay = assemblyService.submit(FLIGHT_ID,
                planReq(requestId, 1, 0, defaultPlanItems()));
        assertThat(replay.planId()).isEqualTo(first.planId());
        Integer plans = jdbc.queryForObject("SELECT COUNT(*) FROM assembly_plan", Integer.class);
        assertThat(plans).isEqualTo(1);
        // 同键异参（仅精确时刻不同）→ 409
        AssemblySubmitRequest tampered = new AssemblySubmitRequest(requestId, 1, 0,
                defaultPlanItems(), "uld01", SUBMITTED_AT.plusMinutes(1));
        assertThatThrownBy(() -> assemblyService.submit(FLIGHT_ID, tampered))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("不同业务参数");
        // 指纹结构化无歧义：编号含分隔符的合法文本与拼接文本区分
        assertThatThrownBy(() -> assemblyService.submit(FLIGHT_ID,
                planReq(requestId, 1, 0, List.of(
                        new AssemblyItemRequest("ULD001|AKE", "AKE", "ULD_P1", 80, 2920),
                        new AssemblyItemRequest("ULD002", "AKE", "ULD_P2", 80, 2920)))))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("不同业务参数");
    }

    // ------------------------------------------------------------- 复核与关闭联动

    @Test
    void submitInvalidatesDualReviewsAndRequiresFreshReview() {
        controlledLoaded();
        reviewBoth();
        assertThat(wbService.getActive(FLIGHT_ID).status()).isEqualTo("REVIEWED");

        // 装配提交使旧双岗复核失效：状态退回 LOADED，签字清空
        assemblyService.submit(FLIGHT_ID, planReq(rid(), 1, 0, defaultPlanItems()));
        ManifestResponse after = wbService.getActive(FLIGHT_ID);
        assertThat(after.status()).isEqualTo("LOADED");
        assertThat(after.confirmedRoles()).isEmpty();
        assertThat(after.frozen()).isFalse();
        Integer reviews = jdbc.queryForObject(
                "SELECT COUNT(*) FROM review_confirmation", Integer.class);
        assertThat(reviews).isZero();

        // 不得沿用旧复核：直接关闭被拒绝，重新双岗复核后方可关闭
        assertThatThrownBy(() -> wbService.close(FLIGHT_ID, new CloseRequest(rid(), "captain")))
                .isInstanceOf(ConflictException.class);
        reviewBoth();
        ManifestResponse closed = wbService.close(FLIGHT_ID, new CloseRequest(rid(), "captain"));
        assertThat(closed.status()).isEqualTo("CLOSED");
    }

    @Test
    void closeWithoutActivePlanRejected409() {
        controlledLoaded();
        reviewBoth();
        assertThatThrownBy(() -> wbService.close(FLIGHT_ID, new CloseRequest(rid(), "captain")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("装配图");
    }

    @Test
    void submitOnClosedManifestRejected409() {
        controlledLoaded();
        assemblyService.submit(FLIGHT_ID, planReq(rid(), 1, 0, defaultPlanItems()));
        reviewBoth();
        wbService.close(FLIGHT_ID, new CloseRequest(rid(), "captain"));
        assertThatThrownBy(() -> assemblyService.submit(FLIGHT_ID,
                planReq(rid(), 1, 1, defaultPlanItems())))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("只读");
    }

    @Test
    void lmcCargoChangeBreaksPlanMatchUntilResubmitted() {
        controlledLoaded();
        assemblyService.submit(FLIGHT_ID, planReq(rid(), 1, 0, defaultPlanItems()));
        // 限内 LMC：货运 +100kg（累计 ≤500），舱单版本不变，装配图仍在但总量不再匹配
        wbService.applyLmc(FLIGHT_ID, new LmcRequest(rid(), 0, "PAX_F", new BigDecimal("80"),
                new BigDecimal("100"), "CARGO", "CARGO_F", BigDecimal.ZERO, "lc001"));
        reviewBoth();
        assertThatThrownBy(() -> wbService.close(FLIGHT_ID, new CloseRequest(rid(), "captain")))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("货运业载");

        // 重新装配 6100kg：旧图作废可追溯，新图版本 2，复核重做后关闭
        AssemblyPlanResponse v2 = assemblyService.submit(FLIGHT_ID, planReq(rid(), 1, 1,
                List.of(new AssemblyItemRequest("ULD001", "AKE", "ULD_P1", 80, 2920),
                        new AssemblyItemRequest("ULD002", "AKE", "ULD_P2", 80, 2920),
                        new AssemblyItemRequest("ULD003", "AKE", "ULD_P3", 80, 20))));
        assertThat(v2.planVersion()).isEqualTo(2);
        assertThat(v2.totalWeightKg()).isEqualTo(6100);
        List<AssemblyPlanResponse> versions = assemblyService.listVersions(FLIGHT_ID);
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).status()).isEqualTo("SUPERSEDED");
        assertThat(versions.get(1).status()).isEqualTo("ACTIVE");
        reviewBoth();
        assertThat(wbService.close(FLIGHT_ID, new CloseRequest(rid(), "captain")).status())
                .isEqualTo("CLOSED");
    }

    @Test
    void lmcReprintRequiresNewPlanMatchingNewManifestVersion() {
        controlledLoaded();
        assemblyService.submit(FLIGHT_ID, planReq(rid(), 1, 0, defaultPlanItems()));
        // 超限 LMC：+6 人 → 重打舱单版本 2；旧装配图仍可追溯但不再匹配
        ManifestResponse v2 = wbService.applyLmc(FLIGHT_ID, new LmcRequest(rid(), 6, "PAX_F",
                new BigDecimal("80"), BigDecimal.ZERO, "CARGO", "CARGO_F", BigDecimal.ZERO, "lc001"));
        assertThat(v2.versionNo()).isEqualTo(2);
        assertThat(assemblyService.getActive(FLIGHT_ID).manifestVersionNo()).isEqualTo(1);
        reviewBoth();
        assertThatThrownBy(() -> wbService.close(FLIGHT_ID, new CloseRequest(rid(), "captain")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("不匹配");

        // 按新舱单版本重新提交（货运未变，同批集装器），装配图版本链延续
        AssemblyPlanResponse planV2 = assemblyService.submit(FLIGHT_ID,
                planReq(rid(), 2, 1, defaultPlanItems()));
        assertThat(planV2.planVersion()).isEqualTo(2);
        assertThat(planV2.manifestVersionNo()).isEqualTo(2);
        reviewBoth();
        assertThat(wbService.close(FLIGHT_ID, new CloseRequest(rid(), "captain")).status())
                .isEqualTo("CLOSED");
    }

    // ------------------------------------------------------------- 兼容性

    @Test
    void nonControlledManifestKeepsLegacyFlowAndRejectsAssembly() {
        wbService.createManifest(new CreateManifestRequest(rid(), FLIGHT_ID, FUEL, "lc001", items()));
        wbService.checkin(FLIGHT_ID, new CheckinRequest(rid(), items(), FUEL, "agent01"));
        wbService.confirmLoad(FLIGHT_ID, new StageRequest(rid(), "loader01"));
        // 未启用装配控制：提交装配图 → 422
        assertThatThrownBy(() -> assemblyService.submit(FLIGHT_ID,
                planReq(rid(), 1, 0, defaultPlanItems())))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("未启用");
        // 既有流程不受影响：无需装配图即可复核关闭
        reviewBoth();
        ManifestResponse closed = wbService.close(FLIGHT_ID, new CloseRequest(rid(), "captain"));
        assertThat(closed.status()).isEqualTo("CLOSED");
        assertThat(closed.assemblyControlled()).isFalse();
        assertThat(closed.assemblyPlanVersion()).isNull();
    }

    @Test
    void unknownFlightOrMissingManifestRejected404() {
        assertThatThrownBy(() -> assemblyService.submit(999, planReq(rid(), 1, 0, defaultPlanItems())))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> assemblyService.submit(FLIGHT_ID, planReq(rid(), 1, 0, defaultPlanItems())))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("有效舱单");
        assertThatThrownBy(() -> assemblyService.getActive(FLIGHT_ID))
                .isInstanceOf(NotFoundException.class);
    }
}
