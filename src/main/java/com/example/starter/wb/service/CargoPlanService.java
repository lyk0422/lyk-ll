package com.example.starter.wb.service;

import com.example.starter.wb.calc.WeightBalanceResult;
import com.example.starter.wb.domain.Aircraft;
import com.example.starter.wb.domain.ManifestRow;
import com.example.starter.wb.dto.CargoPlanResponse;
import com.example.starter.wb.dto.CargoPlanSubmitRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 集装器装配图业务边界：提交装配图、查询当前图与历史图。
 * 未启用装配控制的既有舱单不强制使用本边界。
 */
public interface CargoPlanService {

    /** 提交（全量改图）一张新装配图；版本自 1 起，成功 +1。 */
    CargoPlanResponse submitPlan(long flightId, CargoPlanSubmitRequest request);

    /** 当前有效舱单的最新装配图；从未提交返回 404。 */
    CargoPlanResponse getActivePlan(long flightId);

    /** 当前有效舱单的全部历史图（只读），按图版本升序。 */
    List<CargoPlanResponse> listPlanHistory(long flightId);

    /**
     * 关闭放行前校验并原子释放实物占用：启用装配控制的航班，最新图必须挂当前舱单版本、
     * 总货重与该版本货运业载一致、按图重算重心合法；未启用装配控制的舱单直接放行。
     */
    void verifyAndReleaseOnClose(Aircraft aircraft, ManifestRow manifest, LocalDateTime now);

    /**
     * 双岗复核前校验：启用装配控制时，最新装配图必须仍对应最新舱单版本且总货重/重心一致，
     * LMC 或加油后未重新提交装配图的舱单不得复核；未启用装配控制的舱单直接放行。
     */
    void assertReviewable(Aircraft aircraft, ManifestRow manifest);

    /**
     * 当该舱单存在仍匹配（同舱单版本、加油/LMC 快照一致）的最新装配图时，
     * 返回按装配图物理力矩重算的载重平衡结果；否则为空（调用方回退到明细站位复算）。
     */
    Optional<WeightBalanceResult> physicalResultIfPlanMatches(Aircraft aircraft, ManifestRow manifest);
}
