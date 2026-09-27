package com.example.starter.tank;

import com.example.starter.idempotent.IdempotencyService;
import com.example.starter.idempotent.ReplayResult;
import com.example.starter.station.Station;
import com.example.starter.station.StationRepository;
import com.example.starter.web.BusinessRejectedException;
import com.example.starter.web.ResourceNotFoundException;
import com.example.starter.web.dto.RefillRequest;
import com.example.starter.web.dto.RefillResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 补液交接：双人确认的补液单原子入账。
 *
 * <ul>
 *   <li>运输批号全局唯一，重复批号（含并发）拒绝入账；</li>
 *   <li>须两名不同交接人共同确认，同一人双签拒绝；</li>
 *   <li>合成验收浓度须在许可闭区间 [80.000, 120.000] mg/L，恰等边界允许；</li>
 *   <li>入账后余额不得超容量；满足则原子入账并关闭可满足的待补液事件，
 *       不因一次入账自动重发旧加注命令；</li>
 *   <li>全程储罐行锁串行，与消耗/取消并发保持唯一计量，不超罐也不重复返还。</li>
 * </ul>
 */
@Service
public class RefillService {

    /** 合成验收浓度许可闭区间下限 mg/L。 */
    public static final BigDecimal ACCEPT_MIN_MG_L = new BigDecimal("80.000");
    /** 合成验收浓度许可闭区间上限 mg/L。 */
    public static final BigDecimal ACCEPT_MAX_MG_L = new BigDecimal("120.000");

    private static final BigDecimal ZERO_KG = BigDecimal.ZERO.setScale(3, RoundingMode.UNNECESSARY);

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

    public RefillResponse confirm(RefillRequest request) {
        Station station = stationRepository.findByCode(request.stationCode())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "station not found: " + request.stationCode()));
        ReplayResult<RefillResponse> replay = idempotencyService.run(
                request.requestId(), station.id(), "TANK_REFILL", request, 200,
                () -> doConfirm(station, request), RefillResponse.class);
        return replay.result();
    }

    private RefillResponse doConfirm(Station station, RefillRequest request) {
        BigDecimal quantity = TankSupport.kg(request.quantityKg(), "quantityKg");
        BigDecimal concentration = request.acceptConcentrationMgL();

        String first = request.handlers().get(0);
        String second = request.handlers().get(1);
        if (first.equals(second)) {
            // 须两名不同交接人共同确认，单人双签拒绝
            throw new BusinessRejectedException("two distinct handlers required: " + first);
        }
        // 闭区间：恰等 80.000 / 120.000 边界允许
        if (concentration.compareTo(ACCEPT_MIN_MG_L) < 0
                || concentration.compareTo(ACCEPT_MAX_MG_L) > 0) {
            throw new BusinessRejectedException(
                    "accept concentration out of permitted range [80.000, 120.000]: "
                            + concentration);
        }

        Tank tank = tankRepository.findForUpdate(station.id())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "tank not found for station: " + station.stationCode()));
        TankSupport.requireVersion(tank, request.expectedVersion());

        BigDecimal balanceAfter = tank.balanceKg().add(quantity);
        if (balanceAfter.compareTo(tank.capacityKg()) > 0) {
            throw new BusinessRejectedException(
                    "refill exceeds tank capacity: " + balanceAfter + " > " + tank.capacityKg());
        }

        Long refillId;
        try {
            refillId = refillOrderRepository.insert(station.id(), tank.id(), request.requestId(),
                    request.transportBatchNo(), quantity, concentration, first, second,
                    request.confirmedAt());
        } catch (DuplicateKeyException duplicate) {
            // 唯一运输批号：重复批号（含并发）拒绝入账
            throw new BusinessRejectedException(
                    "duplicate transport batch: " + request.transportBatchNo());
        }

        long newVersion = tank.version() + 1;
        tankRepository.updateBalance(tank.id(), balanceAfter, tank.reservedKg(), newVersion);
        ledgerRepository.insert(tank.id(), station.id(), "REFILL", request.requestId(),
                quantity, ZERO_KG, balanceAfter, tank.reservedKg(),
                request.confirmedAt().toLocalDate());

        // 原子关闭可满足的待补液事件；不自动重发旧加注命令
        List<Long> closedEventIds = new ArrayList<>();
        BigDecimal availableAfter = balanceAfter.subtract(tank.reservedKg());
        for (RefillEventRepository.EventRow event : refillEventRepository.findPending(tank.id())) {
            if (availableAfter.subtract(event.requiredKg()).compareTo(tank.safetyReserveKg()) >= 0) {
                refillEventRepository.close(event.id(), refillId, request.confirmedAt());
                closedEventIds.add(event.id());
            }
        }

        return new RefillResponse(refillId, request.transportBatchNo(), quantity, balanceAfter,
                closedEventIds, newVersion, false);
    }
}
