package com.example.starter.fishery;

import com.example.starter.fishery.api.BusinessRuleException;
import com.example.starter.fishery.api.ConflictException;
import com.example.starter.fishery.api.dto.CarryoverRequest;
import com.example.starter.fishery.api.dto.CarryoverResponse;
import com.example.starter.fishery.api.dto.CarryoverView;
import com.example.starter.fishery.init.SyntheticDataInitializer;
import com.example.starter.fishery.service.CarryoverService;
import com.example.starter.fishery.service.FisheryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 年度结转：20% 上限（恰 20% 合法）、产地预警拦截、次年额度原子初始化、
 * 结转额度次年单列并优先核销、幂等重放/异参 409 与重复结转拒绝（真实 H2 事务）。
 */
@SpringBootTest
class CarryoverServiceTest extends AbstractFisheryTest {

    private static final String SPECIES = SyntheticDataInitializer.SPECIES_HAIRTAIL;
    private static final int SOURCE_YEAR = 2026;
    private static final int TARGET_YEAR = 2027;

    @Autowired
    private CarryoverService carryoverService;

    @Autowired
    private FisheryService fisheryService;

    @AfterEach
    void tearDown() {
        cleanup();
    }

    private CarryoverRequest carryoverRequest(String requestId, String vessel) {
        return new CarryoverRequest(requestId, vessel, SPECIES, SOURCE_YEAR);
    }

    private BigDecimal carryoverUsed(String vessel) {
        List<BigDecimal> rows = jdbc.query(
                "SELECT used_kg FROM quota_carryover WHERE vessel_code = ? AND species_code = ? "
                        + "AND quota_year = ?",
                (rs, n) -> rs.getBigDecimal(1), vessel, SPECIES, TARGET_YEAR);
        return rows.isEmpty() ? null : rows.get(0).stripTrailingZeros();
    }

    private BigDecimal quotaKg(String vessel, int year) {
        List<BigDecimal> rows = jdbc.query(
                "SELECT quota_kg FROM quota WHERE vessel_code = ? AND species_code = ? AND quota_year = ?",
                (rs, n) -> rs.getBigDecimal(1), vessel, SPECIES, year);
        return rows.isEmpty() ? null : rows.get(0).stripTrailingZeros();
    }

    private BigDecimal usedKg(String vessel, int year) {
        List<BigDecimal> rows = jdbc.query(
                "SELECT used_kg FROM quota WHERE vessel_code = ? AND species_code = ? AND quota_year = ?",
                (rs, n) -> rs.getBigDecimal(1), vessel, SPECIES, year);
        return rows.isEmpty() ? null : rows.get(0).stripTrailingZeros();
    }

    private int alertCount(String vessel) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM origin_alert WHERE vessel_code = ? AND species_code = ? "
                        + "AND quota_year = ?",
                Integer.class, vessel, SPECIES, SOURCE_YEAR);
        return count == null ? 0 : count;
    }

    /** 在来源年度完成一笔核销，使 used_kg 达到指定值。 */
    private void settleInSourceYear(String vessel, String tripCode, String kg) {
        runTripToDischarged(fisheryService, vessel, tripCode, SPECIES, kg);
        fisheryService.settle(settlementRequest(tripCode));
    }

    @Test
    void carryoverCapsAtTwentyPercent_andInitializesNextYearAtomically() {
        String vessel = registerVesselWithQuota(SPECIES, "1000.000");
        settleInSourceYear(vessel, "T-CO-CAP", "700.000"); // 用量 70%，未触发预警
        assertEquals(0, alertCount(vessel));

        CarryoverResponse response = carryoverService.carryover(carryoverRequest(newRequestId(), vessel));
        assertFalse(response.replayed());
        assertEquals(SOURCE_YEAR, response.sourceYear());
        assertEquals(TARGET_YEAR, response.targetYear());
        assertEquals(0, response.unusedKg().compareTo(new BigDecimal("300.000")));
        assertEquals(0, response.capKg().compareTo(new BigDecimal("200.000")));
        // 未用 300kg 超上限，按 20% 上限结转 200kg
        assertEquals(0, response.carriedKg().compareTo(new BigDecimal("200.000")));

        // 结转与次年额度初始化原子完成：次年配额行已建（0 基数），结转额度单列
        assertEquals(0, quotaKg(vessel, TARGET_YEAR).compareTo(new BigDecimal("0.000")));
        List<CarryoverView> ledger = carryoverService.list(vessel, TARGET_YEAR);
        assertEquals(1, ledger.size());
        assertEquals(0, ledger.get(0).carriedKg().compareTo(new BigDecimal("200.000")));
        assertEquals(0, ledger.get(0).remainingKg().compareTo(new BigDecimal("200.000")));
        assertEquals(SOURCE_YEAR, ledger.get(0).sourceYear());
    }

    @Test
    void carryoverExactlyTwentyPercentUnused_isLegal() {
        String vessel = registerVesselWithQuota(SPECIES, "1000.000");
        settleInSourceYear(vessel, "T-CO-EXACT", "800.000"); // 用量 80%，未触发预警
        assertEquals(0, alertCount(vessel));

        // 未用恰为配额 20%（200kg）：恰 20% 合法，全额结转
        CarryoverResponse response = carryoverService.carryover(carryoverRequest(newRequestId(), vessel));
        assertEquals(0, response.unusedKg().compareTo(new BigDecimal("200.000")));
        assertEquals(0, response.carriedKg().compareTo(new BigDecimal("200.000")));
    }

    @Test
    void carryoverBelowCap_carriesFullUnused() {
        String vessel = registerVesselWithQuota(SPECIES, "1000.000");
        settleInSourceYear(vessel, "T-CO-BELOW", "850.000"); // 用量 85%，未触发预警

        CarryoverResponse response = carryoverService.carryover(carryoverRequest(newRequestId(), vessel));
        assertEquals(0, response.unusedKg().compareTo(new BigDecimal("150.000")));
        assertEquals(0, response.carriedKg().compareTo(new BigDecimal("150.000")));
    }

    @Test
    void originAlertAtNinetyPercent_carryoverRefusedAndNothingPartial() {
        String vessel = registerVesselWithQuota(SPECIES, "1000.000");
        settleInSourceYear(vessel, "T-CO-ALERT", "900.000"); // 用量恰 90% 触发产地预警
        assertEquals(1, alertCount(vessel));

        // 已触发产地预警的额度不得结转
        BusinessRuleException ex = assertThrows(BusinessRuleException.class,
                () -> carryoverService.carryover(carryoverRequest(newRequestId(), vessel)));
        assertTrue(ex.getMessage().contains("不得结转"));
        // 拒绝即整体回滚：无结转台账、次年额度未初始化
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(1) FROM quota_carryover WHERE vessel_code = ?", Integer.class, vessel));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(1) FROM quota WHERE vessel_code = ? AND quota_year = ?",
                Integer.class, vessel, TARGET_YEAR));
    }

    @Test
    void carryoverWithoutUnused_isRefused() {
        String vessel = registerVesselWithQuota(SPECIES, "1000.000");
        // 直接置为全额核销（不经过核销流程，故无产地预警），隔离“无未用配额”分支
        jdbc.update("UPDATE quota SET used_kg = quota_kg WHERE vessel_code = ? AND species_code = ? "
                + "AND quota_year = ?", vessel, SPECIES, SOURCE_YEAR);

        BusinessRuleException ex = assertThrows(BusinessRuleException.class,
                () -> carryoverService.carryover(carryoverRequest(newRequestId(), vessel)));
        assertTrue(ex.getMessage().contains("无未用配额"));
    }

    @Test
    void carryoverIdempotency_sameKeyReplays_differentParamsConflict() {
        String vessel = registerVesselWithQuota(SPECIES, "1000.000");
        settleInSourceYear(vessel, "T-CO-IDEM", "700.000");

        String requestId = newRequestId();
        CarryoverResponse first = carryoverService.carryover(carryoverRequest(requestId, vessel));
        CarryoverResponse replay = carryoverService.carryover(carryoverRequest(requestId, vessel));
        assertTrue(replay.replayed(), "同键同参必须重放首次结果");
        assertEquals(first.carriedKg(), replay.carriedKg());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(1) FROM quota_carryover WHERE vessel_code = ?", Integer.class, vessel));

        // 同 requestId 异参（不同来源年度）→ 409
        ConflictException ex = assertThrows(ConflictException.class,
                () -> carryoverService.carryover(
                        new CarryoverRequest(requestId, vessel, SPECIES, SOURCE_YEAR - 1)));
        assertTrue(ex.getMessage().contains("不同业务参数"));
    }

    @Test
    void duplicateCarryoverSameTargetYear_conflict() {
        String vessel = registerVesselWithQuota(SPECIES, "1000.000");
        settleInSourceYear(vessel, "T-CO-DUP", "700.000");
        carryoverService.carryover(carryoverRequest(newRequestId(), vessel));

        // 新 requestId 重复结转同一目标年度 → 409
        ConflictException ex = assertThrows(ConflictException.class,
                () -> carryoverService.carryover(carryoverRequest(newRequestId(), vessel)));
        assertTrue(ex.getMessage().contains("不得重复结转"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(1) FROM quota_carryover WHERE vessel_code = ?", Integer.class, vessel));
    }

    @Test
    void carryoverConsumedFirstInNextYearSettlement() {
        String vessel = registerVesselWithQuota(SPECIES, "1000.000");
        settleInSourceYear(vessel, "T-CO-USE-0", "700.000"); // 来源年度用量 70%
        addQuota(vessel, SPECIES, TARGET_YEAR, "100.000");   // 次年已有核定配额，初始化不得覆盖
        carryoverService.carryover(carryoverRequest(newRequestId(), vessel));
        assertEquals(0, quotaKg(vessel, TARGET_YEAR).compareTo(new BigDecimal("100.000")),
                "次年已有配额行时初始化不得覆盖核定配额");

        // 次年航次 B 核销 150kg：全部优先从结转额度扣减
        Instant departB = Instant.parse("2027-01-15T00:00:00Z");
        runTripToDischarged(fisheryService, vessel, "T-CO-USE-1", SPECIES, "150.000",
                "Z-CO-1", departB, departB.plusSeconds(3600), departB.plusSeconds(7200));
        fisheryService.settle(settlementRequest("T-CO-USE-1"));
        assertEquals(0, carryoverUsed(vessel).compareTo(new BigDecimal("150.000")));
        assertEquals(0, usedKg(vessel, TARGET_YEAR).compareTo(new BigDecimal("0.000")),
                "结转额度未用完前不得扣年度配额");
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(1) FROM settlement WHERE trip_code = 'T-CO-USE-1'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(1) FROM carryover_settlement WHERE trip_code = 'T-CO-USE-1'",
                Integer.class));

        // 次年航次 C 核销 100kg：结转余 50kg 先扣，超出部分 50kg 扣年度配额
        Instant departC = Instant.parse("2027-02-15T00:00:00Z");
        runTripToDischarged(fisheryService, vessel, "T-CO-USE-2", SPECIES, "100.000",
                "Z-CO-2", departC, departC.plusSeconds(3600), departC.plusSeconds(7200));
        fisheryService.settle(settlementRequest("T-CO-USE-2"));
        assertEquals(0, carryoverUsed(vessel).compareTo(new BigDecimal("200.000")));
        assertEquals(0, usedKg(vessel, TARGET_YEAR).compareTo(new BigDecimal("50.000")));

        // 台账复算：两本账各自平衡，且航次已结案
        assertTrue(fisheryService.reconcile("T-CO-USE-2").ledgerConsistent());
        BigDecimal carrySettled = jdbc.queryForObject(
                "SELECT COALESCE(SUM(deducted_kg), 0) FROM carryover_settlement "
                        + "WHERE vessel_code = ? AND quota_year = ?",
                BigDecimal.class, vessel, TARGET_YEAR);
        assertEquals(0, carrySettled.stripTrailingZeros().compareTo(new BigDecimal("200.000")));
        assertEquals(0, carryoverUsed(vessel).compareTo(carrySettled.stripTrailingZeros()),
                "结转台账 used_kg 必须等于结转核销明细合计");
    }

    @Test
    void departureGuardAcceptsCarryoverBalance() {
        String vessel = registerVesselWithQuota(SPECIES, "1000.000");
        settleInSourceYear(vessel, "T-CO-GUARD-0", "700.000");
        carryoverService.carryover(carryoverRequest(newRequestId(), vessel));
        // 次年核定配额 0，仅靠结转 200kg：申报 150kg 合法
        Instant depart = Instant.parse("2027-01-15T00:00:00Z");
        fisheryService.declareDeparture(departureRequest("T-CO-GUARD-1", vessel, depart,
                SPECIES, "150.000"));
        assertEquals("DEPARTED", tripStatus("T-CO-GUARD-1"));
        // 超结转余额申报 250kg 整笔拒绝
        BusinessRuleException ex = assertThrows(BusinessRuleException.class,
                () -> fisheryService.declareDeparture(departureRequest("T-CO-GUARD-2", vessel,
                        depart.plusSeconds(60), SPECIES, "250.000")));
        assertTrue(ex.getMessage().contains("超过配额"));
    }
}
