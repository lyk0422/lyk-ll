package com.example.starter.blood.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.blood.domain.BloodUnit;
import com.example.starter.blood.domain.TestOutcome;
import com.example.starter.blood.domain.TestType;
import com.example.starter.blood.domain.UnitStatus;
import com.example.starter.blood.repo.BloodUnitRepository;
import com.example.starter.blood.repo.LateEventRepository;

/**
 * 成分血单位服务：入库、检测结果回传与状态机流转。
 */
@Service
public class BloodUnitService {

    private final BloodUnitRepository unitRepository;
    private final LateEventRepository lateEventRepository;
    private final Clock clock;

    public BloodUnitService(BloodUnitRepository unitRepository, LateEventRepository lateEventRepository,
                            Clock clock) {
        this.unitRepository = unitRepository;
        this.lateEventRepository = lateEventRepository;
        this.clock = clock;
    }

    /**
     * 成分血入库，初始状态为待检；条码唯一，重复入库返回 409。
     *
     * @param barcode       唯一条码
     * @param componentType 成分类型（合成数据）
     * @param expireDate    效期日期（UTC 日历日）；可放行截止为该日 23:59:59 UTC（含边界）
     * @return 入库后的单位
     */
    @Transactional
    public BloodUnit checkIn(String barcode, String componentType, LocalDate expireDate) {
        Instant expireAt = expireDate.atTime(LocalTime.of(23, 59, 59)).toInstant(ZoneOffset.UTC);
        Instant now = clock.instant();
        try {
            unitRepository.insert(barcode, componentType, expireAt, now);
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "条码已入库: " + barcode);
        }
        return unitRepository.findByBarcode(barcode).orElseThrow();
    }

    /**
     * 检测结果回传（初检/复检可乱序）。终态单位不复活，仅记录迟到事件。
     *
     * @param barcode 条码
     * @param testType 检测项
     * @param outcome  检测结果
     * @return 回传应答（含处理后状态与是否迟到）
     */
    @Transactional
    public TestResultAck applyTestResult(String barcode, TestType testType, TestOutcome outcome) {
        BloodUnit unit = unitRepository.findByBarcodeForUpdate(barcode)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "条码不存在: " + barcode));
        if (unit.status().isTerminal()) {
            lateEventRepository.insert(barcode, testType, outcome, unit.status(), clock.instant());
            return new TestResultAck(barcode, unit.status(), true);
        }
        TestOutcome initial = testType == TestType.INITIAL ? outcome : unit.initialResult();
        TestOutcome recheck = testType == TestType.RECHECK ? outcome : unit.recheckResult();
        UnitStatus next = decideStatus(initial, recheck);
        unitRepository.updateResultsAndStatus(barcode, initial, recheck, next, clock.instant());
        return new TestResultAck(barcode, next, false);
    }

    /**
     * 按条码查询单位当前状态。
     *
     * @param barcode 条码
     * @return 单位记录
     */
    @Transactional(readOnly = true)
    public BloodUnit getUnit(String barcode) {
        return unitRepository.findByBarcode(barcode)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "条码不存在: " + barcode));
    }

    /**
     * 状态机：任一不合格进入报废终态；两项均合格进入可放行；恰一项合格保持隔离；否则待检。
     *
     * @param initial 初检结果（可为 null）
     * @param recheck 复检结果（可为 null）
     * @return 重算后的状态
     */
    static UnitStatus decideStatus(TestOutcome initial, TestOutcome recheck) {
        if (initial == TestOutcome.UNQUALIFIED || recheck == TestOutcome.UNQUALIFIED) {
            return UnitStatus.DISCARDED;
        }
        if (initial == TestOutcome.QUALIFIED && recheck == TestOutcome.QUALIFIED) {
            return UnitStatus.RELEASABLE;
        }
        if (initial == TestOutcome.QUALIFIED || recheck == TestOutcome.QUALIFIED) {
            return UnitStatus.ISOLATED;
        }
        return UnitStatus.PENDING;
    }
}
