package com.example.starter.tank;

import com.example.starter.idempotent.IdempotencyService;
import com.example.starter.idempotent.ReplayResult;
import com.example.starter.station.Station;
import com.example.starter.station.StationRepository;
import com.example.starter.web.BusinessRejectedException;
import com.example.starter.web.ResourceNotFoundException;
import com.example.starter.web.dto.TankAdjustRequest;
import com.example.starter.web.dto.TankLedgerEntryView;
import com.example.starter.web.dto.TankRegisterRequest;
import com.example.starter.web.dto.TankResponse;
import com.example.starter.web.dto.TankStatusResponse;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 加臭剂储罐：登记、配置调整与状态/台账查询。
 *
 * <ul>
 *   <li>一站一罐，登记版本从 1 开始；0 ≤ 安全储备 &lt; 容量，余额不超容量，
 *       千克数值三位小数（整数或小数文本等价按规范值处理）；</li>
 *   <li>配置调整携带 expectedVersion，版本不一致 409，成功变更版本加 1；</li>
 *   <li>未绑定储罐的门站：查询/登记外的写操作返回 404，旧接口行为不变；</li>
 *   <li>台账只插不改，余额与预占变动守恒；日结只冻当日台账，后续补液不改旧行。</li>
 * </ul>
 */
@Service
public class TankService {

    private final StationRepository stationRepository;
    private final TankRepository tankRepository;
    private final TankLedgerRepository ledgerRepository;
    private final RefillEventRepository refillEventRepository;
    private final IdempotencyService idempotencyService;

    public TankService(StationRepository stationRepository,
                       TankRepository tankRepository,
                       TankLedgerRepository ledgerRepository,
                       RefillEventRepository refillEventRepository,
                       IdempotencyService idempotencyService) {
        this.stationRepository = stationRepository;
        this.tankRepository = tankRepository;
        this.ledgerRepository = ledgerRepository;
        this.refillEventRepository = refillEventRepository;
        this.idempotencyService = idempotencyService;
    }

    public TankResponse register(TankRegisterRequest request) {
        Station station = stationRepository.findByCode(request.stationCode())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "station not found: " + request.stationCode()));
        ReplayResult<TankResponse> replay = idempotencyService.run(
                request.requestId(), station.id(), "TANK_REGISTER", request, 200,
                () -> doRegister(station, request), TankResponse.class);
        return replay.result();
    }

    public TankResponse adjust(TankAdjustRequest request) {
        Station station = stationRepository.findByCode(request.stationCode())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "station not found: " + request.stationCode()));
        ReplayResult<TankResponse> replay = idempotencyService.run(
                request.requestId(), station.id(), "TANK_ADJUST", request, 200,
                () -> doAdjust(station, request), TankResponse.class);
        return replay.result();
    }

    /**
     * 储罐状态查询（只读）；未绑定储罐的门站返回 404。
     */
    public TankStatusResponse status(String stationCode) {
        Station station = stationRepository.findByCode(stationCode)
                .orElseThrow(() -> new ResourceNotFoundException("station not found: " + stationCode));
        Tank tank = tankRepository.find(station.id())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "tank not found for station: " + stationCode));
        return new TankStatusResponse(tank.id(), stationCode, tank.capacityKg(),
                tank.safetyReserveKg(), tank.balanceKg(), tank.reservedKg(), tank.availableKg(),
                tank.version(), refillEventRepository.findPendingIds(tank.id()));
    }

    /**
     * 台账查询（只读）：按门站与业务日期返回守恒明细。
     */
    public List<TankLedgerEntryView> ledger(String stationCode, LocalDate bizDate) {
        Station station = stationRepository.findByCode(stationCode)
                .orElseThrow(() -> new ResourceNotFoundException("station not found: " + stationCode));
        return ledgerRepository.findByStationAndDate(station.id(), bizDate);
    }

    private TankResponse doRegister(Station station, TankRegisterRequest request) {
        BigDecimal capacity = TankSupport.kg(request.capacityKg(), "capacityKg");
        BigDecimal reserve = TankSupport.kg(request.safetyReserveKg(), "safetyReserveKg");
        BigDecimal balance = TankSupport.kg(request.initialBalanceKg(), "initialBalanceKg");
        requireReserveBelowCapacity(reserve, capacity);
        if (balance.compareTo(capacity) > 0) {
            throw new BusinessRejectedException(
                    "initial balance exceeds capacity: " + balance + " > " + capacity);
        }

        Tank tank = tankRepository.findForUpdate(station.id()).orElse(null);
        if (tank != null) {
            throw new BusinessRejectedException(
                    "tank already registered for station: " + station.stationCode());
        }

        Long tankId = tankRepository.insert(station.id(), capacity, reserve, balance);
        ledgerRepository.insert(tankId, station.id(), "REGISTER", request.requestId(),
                balance, BigDecimal.ZERO.setScale(3), balance, BigDecimal.ZERO.setScale(3),
                request.registeredAt().toLocalDate());
        return new TankResponse(tankId, station.stationCode(), capacity, reserve, balance,
                BigDecimal.ZERO.setScale(3), balance, 1L, false);
    }

    private TankResponse doAdjust(Station station, TankAdjustRequest request) {
        BigDecimal capacity = TankSupport.kg(request.capacityKg(), "capacityKg");
        BigDecimal reserve = TankSupport.kg(request.safetyReserveKg(), "safetyReserveKg");
        requireReserveBelowCapacity(reserve, capacity);

        Tank tank = tankRepository.findForUpdate(station.id())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "tank not found for station: " + station.stationCode()));
        TankSupport.requireVersion(tank, request.expectedVersion());
        if (capacity.compareTo(tank.balanceKg()) < 0) {
            throw new BusinessRejectedException(
                    "capacity below current balance: " + capacity + " < " + tank.balanceKg());
        }

        long newVersion = tank.version() + 1;
        tankRepository.updateConfig(tank.id(), capacity, reserve, newVersion);
        ledgerRepository.insert(tank.id(), station.id(), "ADJUST", request.requestId(),
                BigDecimal.ZERO.setScale(3), BigDecimal.ZERO.setScale(3),
                tank.balanceKg(), tank.reservedKg(), request.adjustedAt().toLocalDate());
        return new TankResponse(tank.id(), station.stationCode(), capacity, reserve,
                tank.balanceKg(), tank.reservedKg(), tank.availableKg(), newVersion, false);
    }

    private void requireReserveBelowCapacity(BigDecimal reserve, BigDecimal capacity) {
        if (reserve.compareTo(capacity) >= 0) {
            throw new BusinessRejectedException(
                    "safety reserve must be below capacity: " + reserve + " >= " + capacity);
        }
    }
}
