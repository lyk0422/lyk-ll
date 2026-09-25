package com.example.starter.service;

import java.time.Clock;
import java.time.Instant;

import com.example.starter.domain.BloodUnit;
import com.example.starter.domain.TestResultValue;
import com.example.starter.domain.TestType;
import com.example.starter.domain.UnitStatus;
import com.example.starter.error.ConflictException;
import com.example.starter.error.NotFoundException;
import com.example.starter.repository.BloodUnitRepository;
import com.example.starter.repository.ResultEventRepository;
import com.example.starter.web.dto.InboundRequest;
import com.example.starter.web.dto.ResultCallbackRequest;
import com.example.starter.web.dto.UnitResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 成分血入库与检测结果回传服务。
 *
 * <p>状态机：待检 →（任一结果回传）→ 隔离/报废；隔离 → 可放行/报废。
 * 两项检测独立、可乱序回传；终态后回传仅记录迟到事件，不得复活。
 */
@Service
public class BloodUnitService {

    private final BloodUnitRepository units;
    private final ResultEventRepository events;
    private final Clock clock;

    public BloodUnitService(BloodUnitRepository units, ResultEventRepository events, Clock clock) {
        this.units = units;
        this.events = events;
        this.clock = clock;
    }

    /**
     * 成分血入库：唯一条码，初始状态为待检。
     *
     * @throws ConflictException 条码已存在（409）
     */
    @Transactional
    public UnitResponse inbound(InboundRequest request) {
        Instant now = Instant.now(clock);
        try {
            units.insert(request.barcode(), request.expiryDate(), now);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("DUPLICATE_BARCODE", "条码已存在: " + request.barcode(), null);
        }
        return toResponse(units.findByBarcode(request.barcode()).orElseThrow());
    }

    /**
     * 检测结果回传。行锁串行化并发回传与过期判定；终态后回传只记录迟到事件。
     *
     * @throws NotFoundException 条码不存在（404）
     */
    @Transactional
    public UnitResponse recordResult(String barcode, ResultCallbackRequest request) {
        BloodUnit unit = units.findByBarcodeForUpdate(barcode)
                .orElseThrow(() -> new NotFoundException("条码不存在: " + barcode));
        Instant now = Instant.now(clock);

        if (unit.status().isTerminal()) {
            // 终态不复活：只记录迟到事件，状态保持不变
            events.insert(barcode, request.testType(), request.result(), true, now);
            return toResponse(unit);
        }

        TestResultValue initial = request.testType() == TestType.INITIAL
                ? request.result() : unit.initialResult();
        TestResultValue recheck = request.testType() == TestType.RECHECK
                ? request.result() : unit.recheckResult();
        UnitStatus newStatus = decideStatus(initial, recheck);

        BloodUnit updated = new BloodUnit(unit.id(), unit.barcode(), newStatus,
                initial, recheck, unit.expiryDate(), unit.createdAt(), now, unit.version());
        units.updateResultsAndStatus(updated, now);
        events.insert(barcode, request.testType(), request.result(), false, now);
        return toResponse(updated);
    }

    /**
     * 查询单位当前状态。
     */
    @Transactional(readOnly = true)
    public UnitResponse getByBarcode(String barcode) {
        return toResponse(units.findByBarcode(barcode)
                .orElseThrow(() -> new NotFoundException("条码不存在: " + barcode)));
    }

    /**
     * 由两项检测结果推导状态：任一不合格即报废；双合格可放行；
     * 一项合格一项未回为隔离；均未回为待检。
     */
    static UnitStatus decideStatus(TestResultValue initial, TestResultValue recheck) {
        if (initial == TestResultValue.FAIL || recheck == TestResultValue.FAIL) {
            return UnitStatus.DISCARDED;
        }
        if (initial == TestResultValue.PASS && recheck == TestResultValue.PASS) {
            return UnitStatus.RELEASABLE;
        }
        if (initial == null && recheck == null) {
            return UnitStatus.PENDING_TEST;
        }
        return UnitStatus.QUARANTINED;
    }

    static UnitResponse toResponse(BloodUnit unit) {
        return new UnitResponse(unit.barcode(), unit.status(),
                unit.initialResult(), unit.recheckResult(), unit.expiryDate());
    }
}
