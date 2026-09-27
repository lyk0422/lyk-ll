package com.example.starter.pressure;

import com.example.starter.close.DailyCloseRepository;
import com.example.starter.idempotent.IdempotencyService;
import com.example.starter.idempotent.ReplayResult;
import com.example.starter.reading.LateEventRepository;
import com.example.starter.station.Station;
import com.example.starter.station.StationRepository;
import com.example.starter.tank.TankService;
import com.example.starter.valve.ValveState;
import com.example.starter.valve.ValveStateRepository;
import com.example.starter.web.ResourceNotFoundException;
import com.example.starter.web.TaskNotAcceptedException;
import com.example.starter.web.dto.PressureDetectRequest;
import com.example.starter.web.dto.PressureDetectResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * 压力调节：分级判定与联动。
 *
 * <ul>
 *   <li>压力恰等于上/下限判 NORMAL 合格；低于下限 LOW、超上限 HIGH 触发联动；</li>
 *   <li>同一门站同一检测时刻仅允许一个执行中联动任务：先预检、再由
 *       linkage_task(station_id, detected_at) 唯一约束兜底，并发下达唯一受理，
 *       未受理方返回 409；</li>
 *   <li>PRESSURE 调压（回归目标压力）与 INJECTION 比例加注调整在同一事务下发，
 *       任一失败整体回滚；</li>
 *   <li>门站绑定储罐时，加注量先经储罐可用量闸控：可用量−消耗不得低于安全储备
 *       （恰等允许），不足时调压照常执行但加注不下发、加注量记 0、速率维持不变，
 *       不伪报加注成功，并生成待补液事件；未绑定储罐的旧门站行为不变；</li>
 *   <li>对应业务日期已封存时只落迟到事件，不联动。</li>
 * </ul>
 */
@Service
public class PressureService {

    private final StationRepository stationRepository;
    private final ValveStateRepository valveStateRepository;
    private final PressureReadingRepository readingRepository;
    private final LateEventRepository lateEventRepository;
    private final DailyCloseRepository dailyCloseRepository;
    private final StationTaskLockRepository taskLockRepository;
    private final LinkageTaskRepository linkageTaskRepository;
    private final AdjustmentIssueRepository adjustmentIssueRepository;
    private final IdempotencyService idempotencyService;
    private final TankService tankService;

    public PressureService(StationRepository stationRepository,
                           ValveStateRepository valveStateRepository,
                           PressureReadingRepository readingRepository,
                           LateEventRepository lateEventRepository,
                           DailyCloseRepository dailyCloseRepository,
                           StationTaskLockRepository taskLockRepository,
                           LinkageTaskRepository linkageTaskRepository,
                           AdjustmentIssueRepository adjustmentIssueRepository,
                           IdempotencyService idempotencyService,
                           TankService tankService) {
        this.stationRepository = stationRepository;
        this.valveStateRepository = valveStateRepository;
        this.readingRepository = readingRepository;
        this.lateEventRepository = lateEventRepository;
        this.dailyCloseRepository = dailyCloseRepository;
        this.taskLockRepository = taskLockRepository;
        this.linkageTaskRepository = linkageTaskRepository;
        this.adjustmentIssueRepository = adjustmentIssueRepository;
        this.idempotencyService = idempotencyService;
        this.tankService = tankService;
    }

    public PressureDetectResponse detect(PressureDetectRequest request) {
        Station station = stationRepository.findByCode(request.stationCode())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "station not found: " + request.stationCode()));
        ReplayResult<PressureDetectResponse> replay = idempotencyService.run(
                request.requestId(), station.id(), "PRESSURE_DETECT", request, 200,
                () -> doDetect(station, request), PressureDetectResponse.class);
        return replay.result();
    }

    private PressureDetectResponse doDetect(Station station, PressureDetectRequest request) {
        BigDecimal measured = request.pressureMpa();
        String grade = grade(station, measured);
        LocalDate bizDate = request.detectedAt().toLocalDate();

        if (dailyCloseRepository.isSealed(station.id(), bizDate)) {
            // 封存终态：迟到压力检测只记事件，不触发联动
            lateEventRepository.insert(station.id(), "PRESSURE", request.requestId(),
                    request.detectedAt(), request);
            return new PressureDetectResponse(grade, false, null,
                    measured, measured, null, null, BigDecimal.ZERO, false, true, false);
        }

        ValveState state = valveStateRepository.selectForUpdate(station.id());

        if ("NORMAL".equals(grade)) {
            // 恰等限值或区间内：合格，无联动
            readingRepository.insert(station.id(), request.requestId(), request.detectedAt(),
                    measured, grade, null);
            return new PressureDetectResponse(grade, false, null,
                    measured, measured,
                    state.currentInjectionRateMgM3(), state.currentInjectionRateMgM3(),
                    BigDecimal.ZERO, false, false, false);
        }

        if (taskLockRepository.existsAtSameInstant(station.id(), request.detectedAt())) {
            throw new TaskNotAcceptedException(
                    "linkage task already running for station at detectedAt=" + request.detectedAt());
        }

        Long taskId;
        try {
            taskId = linkageTaskRepository.insertAccepted(station.id(), request.requestId(),
                    request.detectedAt(), measured, state.currentInjectionRateMgM3(),
                    request.gasFlowM3());
        } catch (DuplicateKeyException concurrent) {
            // 同刻并发：唯一约束裁决，仅首个请求受理
            throw new TaskNotAcceptedException(
                    "linkage task already running for station at detectedAt=" + request.detectedAt());
        }

        BigDecimal targetPressure = station.pressureTargetMpa();
        BigDecimal rateBefore = state.currentInjectionRateMgM3();
        BigDecimal rateAfter;
        BigDecimal dose;
        boolean injectionDispatched;
        if (state.injectionLocked()) {
            // 加注阀锁定中：加注指令以“维持、零加注量”下发，调压仍执行
            rateAfter = rateBefore;
            dose = BigDecimal.ZERO.setScale(3, RoundingMode.HALF_UP);
            injectionDispatched = false;
        } else {
            // 比例投加：联动后速率折算浓度回归目标浓度，本次加注量 = 流量 × 目标浓度
            rateAfter = station.targetConcentrationMgM3();
            dose = request.gasFlowM3()
                    .multiply(station.targetConcentrationMgM3())
                    .setScale(3, RoundingMode.HALF_UP);
            // 绑定储罐的门站：加注量（mg 折算 kg，三位小数）先经储罐可用量闸控
            BigDecimal doseKg = dose.movePointLeft(6).setScale(3, RoundingMode.HALF_UP);
            injectionDispatched = tankService.consumeForLinkage(station.id(), doseKg, taskId,
                    request.requestId(), request.detectedAt());
            if (!injectionDispatched) {
                // 储罐可用量低于安全储备：调压照常，加注不下发、不伪报加注成功
                rateAfter = rateBefore;
                dose = BigDecimal.ZERO.setScale(3, RoundingMode.HALF_UP);
            }
        }

        // 调压与加注调整同事务下发（两条指令 + 状态更新 + 任务完成原子提交）
        adjustmentIssueRepository.insert(taskId, station.id(), "PRESSURE", measured, targetPressure);
        adjustmentIssueRepository.insert(taskId, station.id(), "INJECTION", rateBefore, rateAfter);
        valveStateRepository.updateInjectionAndPressure(station.id(), rateAfter, targetPressure);
        linkageTaskRepository.complete(taskId, targetPressure, rateAfter, dose);
        readingRepository.insert(station.id(), request.requestId(), request.detectedAt(),
                measured, grade, taskId);

        return new PressureDetectResponse(grade, true, taskId,
                measured, targetPressure, rateBefore, rateAfter, dose,
                injectionDispatched, false, false);
    }

    private String grade(Station station, BigDecimal measured) {
        // 闭区间：恰等于 0.3000 / 0.5000 均判合格
        if (measured.compareTo(station.pressureLowLimitMpa()) < 0) {
            return "LOW";
        }
        if (measured.compareTo(station.pressureHighLimitMpa()) > 0) {
            return "HIGH";
        }
        return "NORMAL";
    }
}
