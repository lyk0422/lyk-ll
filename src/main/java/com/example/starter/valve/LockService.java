package com.example.starter.valve;

import com.example.starter.idempotent.IdempotencyService;
import com.example.starter.idempotent.ReplayResult;
import com.example.starter.station.Station;
import com.example.starter.station.StationRepository;
import com.example.starter.web.ResourceNotFoundException;
import com.example.starter.web.dto.LockConfirmRequest;
import com.example.starter.web.dto.LockConfirmResponse;
import org.springframework.stereotype.Service;

/**
 * 异常处置：锁阀（LOCK）与复开（REOPEN）双人确认。
 *
 * <ul>
 *   <li>两名操作人工号不同方为有效确认；单人操作（两人同号）不生效，
 *       阀门与告警状态保持不变，仅留 PENDING 工单；</li>
 *   <li>复开生效时，加注阀解锁、告警解除、连续越限计数归零在同一事务完成；</li>
 *   <li>全部状态更新以 valve_state 行锁串行化。</li>
 * </ul>
 */
@Service
public class LockService {

    private final StationRepository stationRepository;
    private final ValveStateRepository valveStateRepository;
    private final LockOrderRepository lockOrderRepository;
    private final IdempotencyService idempotencyService;

    public LockService(StationRepository stationRepository,
                       ValveStateRepository valveStateRepository,
                       LockOrderRepository lockOrderRepository,
                       IdempotencyService idempotencyService) {
        this.stationRepository = stationRepository;
        this.valveStateRepository = valveStateRepository;
        this.lockOrderRepository = lockOrderRepository;
        this.idempotencyService = idempotencyService;
    }

    public LockConfirmResponse confirm(LockConfirmRequest request) {
        Station station = stationRepository.findByCode(request.stationCode())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "station not found: " + request.stationCode()));
        ReplayResult<LockConfirmResponse> replay = idempotencyService.run(
                request.requestId(), station.id(), "LOCK_CONFIRM", request, 200,
                () -> doConfirm(station, request), LockConfirmResponse.class);
        return replay.result();
    }

    private LockConfirmResponse doConfirm(Station station, LockConfirmRequest request) {
        boolean twoOperators = !request.firstOperator().equals(request.secondOperator());

        ValveState state = valveStateRepository.selectForUpdate(station.id());

        if (!twoOperators) {
            // 单人操作（两签名同号）不生效：仅登记 PENDING 工单，阀门状态不变
            lockOrderRepository.insert(station.id(), request.action(), "PENDING",
                    request.firstOperator(), null, null);
            return new LockConfirmResponse(false, state.injectionLocked(),
                    state.alarmActive(), false);
        }

        if ("LOCK".equals(request.action())) {
            valveStateRepository.updateState(station.id(), true, true, state.breachCount(),
                    state.currentInjectionRateMgM3(), state.currentPressureMpa());
            lockOrderRepository.insert(station.id(), "LOCK", "CONFIRMED",
                    request.firstOperator(), request.secondOperator(), null);
            return new LockConfirmResponse(true, true, true, false);
        }

        // REOPEN：复开与告警解除同一事务完成，越限计数归零
        valveStateRepository.updateState(station.id(), false, false, 0,
                state.currentInjectionRateMgM3(), state.currentPressureMpa());
        lockOrderRepository.insert(station.id(), "REOPEN", "CONFIRMED",
                request.firstOperator(), request.secondOperator(), null);
        return new LockConfirmResponse(true, false, false, false);
    }
}
