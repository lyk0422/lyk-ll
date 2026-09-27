package com.example.starter.tank;

import com.example.starter.idempotent.IdempotencyService;
import com.example.starter.idempotent.ReplayResult;
import com.example.starter.station.Station;
import com.example.starter.station.StationRepository;
import com.example.starter.web.BusinessRejectedException;
import com.example.starter.web.ResourceNotFoundException;
import com.example.starter.web.VersionConflictException;
import com.example.starter.web.dto.RefillConfirmRequest;
import com.example.starter.web.dto.RefillConfirmResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 补液交接：运输批号唯一、双人确认、浓度与容量门禁。
 *
 * <ul>
 *   <li>须两名不同交接人共同确认，单人交接（两签名同号）业务拒绝（422）；</li>
 *   <li>合成验收浓度须在许可闭区间（门站目标浓度±允许偏差，恰等边界允许）；</li>
 *   <li>加入后不超容量才原子入账：余额增加、版本加 1、台账追加 REFILL 明细，
 *       并关闭可满足的待补液事件（入账后可用量回到安全储备之上）；</li>
 *   <li>运输批号全局唯一，重复批号业务拒绝（422）；</li>
 *   <li>不因一次入账自动重发旧加注命令：入账只更新库存与事件，不触碰加注计划。</li>
 * </ul>
 */
@Service
public class RefillService {

    private final StationRepository stationRepository;
    private final TankRepository tankRepository;
    private final RefillOrderRepository refillOrderRepository;
    private final RefillEventRepository refillEventRepository;
    private final TankLedgerRepository ledgerRepository;
    private final IdempotencyService idempotencyService;

    public RefillService(StationRepository stationRepository,
                         TankRepository tankRepository,
                         RefillOrderRepository refillOrderRepository,
                         RefillEventRepository refillEventRepository,
                         TankLedgerRepository ledgerRepository,
                         IdempotencyService idempotencyService) {
        this.stationRepository = stationRepository;
        this.tankRepository = tankRepository;
        this.refillOrderRepository = refillOrderRepository;
        this.refillEventRepository = refillEventRepository;
        this.ledgerRepository = ledgerRepository;
        this.idempotencyService = idempotencyService;
    }

    public RefillConfirmResponse confirm(RefillConfirmRequest request) {
        Station station = stationRepository.findByCode(request.stationCode())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "station not found: " + request.stationCode()));
        ReplayResult<RefillConfirmResponse> replay = idempotencyService.run(
                request.requestId(), station.id(), "REFILL_CONFIRM", request, 200,
                () -> doConfirm(station, request), RefillConfirmResponse.class);
        return replay.result();
    }

    private RefillConfirmResponse doConfirm(Station station, RefillConfirmRequest request) {
        BigDecimal quantity = TankService.requireKgScale(request.quantityKg(), "quantityKg");
        OdorantTank tank = tankRepository.selectForUpdateByStationId(station.id());
        if (tank == null) {
            throw new ResourceNotFoundException(
                    "tank not bound for station: " + station.stationCode());
        }
        if (request.expectedVersion() == null || request.expectedVersion() != tank.version()) {
            throw new VersionConflictException(
                    "expectedVersion " + request.expectedVersion() + " != current " + tank.version());
        }

        // 双人门禁：两名交接人工号必须不同
        if (request.firstOperator().equals(request.secondOperator())) {
            throw new BusinessRejectedException(
                    "refill requires two distinct operators: " + request.firstOperator());
        }

        // 许可闭区间：目标浓度±允许偏差，恰等边界允许
        BigDecimal target = station.targetConcentrationMgM3();
        BigDecimal toleranceAmount = target
                .multiply(station.concentrationTolerancePct())
                .divide(BigDecimal.valueOf(100), 3, RoundingMode.HALF_UP);
        BigDecimal lowerBound = target.subtract(toleranceAmount);
        BigDecimal upperBound = target.add(toleranceAmount);
        BigDecimal concentration = request.concentrationMgM3();
        if (concentration.compareTo(lowerBound) < 0 || concentration.compareTo(upperBound) > 0) {
            throw new BusinessRejectedException(
                    "concentration out of permitted range [" + lowerBound + ", " + upperBound
                            + "]: " + concentration);
        }

        // 容量门禁：加入后不超容量
        BigDecimal balanceAfter = tank.balanceKg().add(quantity);
        if (balanceAfter.compareTo(tank.capacityKg()) > 0) {
            throw new BusinessRejectedException(
                    "refill would exceed capacity: " + balanceAfter + " > " + tank.capacityKg());
        }

        Long orderId;
        try {
            orderId = refillOrderRepository.insert(station.id(), tank.id(), request.requestId(),
                    request.batchNo(), quantity, concentration,
                    request.firstOperator(), request.secondOperator(), request.confirmedAt());
        } catch (DuplicateKeyException duplicate) {
            // 运输批号全局唯一：重复批号拒绝入账
            throw new BusinessRejectedException("duplicate batchNo: " + request.batchNo());
        }

        // 原子入账：余额增加 + 版本加 1 + 台账 + 关闭可满足事件，同一事务
        tankRepository.applyMutation(tank.id(), balanceAfter, tank.reservedKg());
        ledgerRepository.append(tank.id(), "REFILL", quantity,
                balanceAfter, tank.reservedKg(), "REFILL", orderId, request.requestId());

        List<Long> closedEventIds = new ArrayList<>();
        if (balanceAfter.subtract(tank.reservedKg()).compareTo(tank.safetyReserveKg()) >= 0) {
            for (RefillEvent event : refillEventRepository.listPendingByTank(tank.id())) {
                refillEventRepository.close(event.id(), request.requestId());
                closedEventIds.add(event.id());
            }
        }

        return new RefillConfirmResponse(orderId, request.batchNo(), quantity, balanceAfter,
                tank.reservedKg(), closedEventIds, tank.version() + 1, false);
    }
}
