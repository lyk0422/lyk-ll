package com.example.starter.schedule;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import com.example.starter.schedule.ScheduleRepository.ScheduleRow;
import com.example.starter.schedule.ScheduleRequests.ActualItem;
import com.example.starter.schedule.ScheduleRequests.CreateSchedule;
import com.example.starter.schedule.ScheduleRequests.RegisterRollChange;
import com.example.starter.schedule.ScheduleRequests.ReportActuals;
import com.example.starter.schedule.ScheduleRequests.ReviseSchedule;
import com.example.starter.schedule.ScheduleRequests.RollWindowSpec;
import com.example.starter.schedule.ScheduleResponses.ActualsResult;
import com.example.starter.schedule.ScheduleResponses.FreezeResult;
import com.example.starter.schedule.ScheduleResponses.ScheduleView;
import com.example.starter.schedule.ScheduleResponses.ValidationResult;
import com.example.starter.schedule.ScheduleResponses.Violation;

/**
 * 排产业务服务：编制 → 校验 → 下达 → 执行 → 冻结（终态）。
 * 所有写操作在事务内完成，违例整单拒绝，不产生部分落程。
 */
@Service
public class ScheduleService {

    private final ScheduleRepository repository;
    private final ScheduleValidator validator;

    public ScheduleService(ScheduleRepository repository, ScheduleValidator validator) {
        this.repository = repository;
        this.validator = validator;
    }

    /** 编制排产单（DRAFT）。同产线同轧制单元只允许一条编制链。 */
    @Transactional
    public ScheduleView create(CreateSchedule request) {
        long lineId = requireLine(request.lineCode());
        if (repository.unitExists(lineId, request.unitNo())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "产线 " + request.lineCode() + " 已存在轧制单元 " + request.unitNo());
        }
        long id = repository.insertSchedule(request.requestId(),
                Fingerprint.ofSchedule(request.requestId(), request.lineCode(), request.unitNo(),
                        request.items(), request.rollChangeWindows()),
                lineId, request.unitNo(), 1, ScheduleStatus.DRAFT);
        repository.insertItems(id, request.items());
        repository.insertWindows(id,
                request.rollChangeWindows() == null ? List.of() : request.rollChangeWindows());
        return view(id);
    }

    /** 查询排产单详情。 */
    @Transactional(readOnly = true)
    public ScheduleView getView(long scheduleId) {
        return view(scheduleId);
    }

    /**
     * 校验排产单：违例时返回全部明细并整单拒绝（HTTP 422，不落任何状态）；
     * 通过则置为 VALIDATED。
     */
    @Transactional
    public ValidationResult validate(long scheduleId) {
        ScheduleRow row = requireSchedule(scheduleId);
        if (row.status() != ScheduleStatus.DRAFT && row.status() != ScheduleStatus.VALIDATED) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "状态 " + row.status() + " 不允许重新校验");
        }
        List<Violation> violations = validator.validate(
                toItemSpecs(scheduleId), toWindowSpecs(scheduleId));
        if (!violations.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "排产校验未通过，共 " + violations.size() + " 条违例，整单拒绝", violations);
        }
        repository.updateStatus(scheduleId, ScheduleStatus.VALIDATED);
        return new ValidationResult(scheduleId, ScheduleStatus.VALIDATED.name(), List.of());
    }

    /**
     * 下达排产单：要求已校验。同产线同时刻只允许一个生效版本；
     * 同单元新版本下达取代旧版本，不同单元冲突返回 409，并发下达由产线行锁与
     * line_effective_schedule 主键保证唯一成功。
     */
    @Transactional
    public ScheduleView release(long scheduleId) {
        ScheduleRow row = requireSchedule(scheduleId);
        if (row.status() == ScheduleStatus.RELEASED || row.status() == ScheduleStatus.EXECUTING) {
            // 重复下达：幂等返回当前状态
            return view(scheduleId);
        }
        if (row.status() != ScheduleStatus.VALIDATED) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "状态 " + row.status() + " 不允许下达，须先校验通过");
        }
        repository.lockLine(row.lineId());
        var effective = repository.findEffectiveSchedule(row.lineId());
        if (effective.isPresent()) {
            ScheduleRow current = effective.get();
            boolean sameUnit = current.unitNo().equals(row.unitNo());
            boolean newerVersion = sameUnit && current.version() < row.version();
            if (!newerVersion) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "产线已存在生效排产（单元 " + current.unitNo() + " 版本 " + current.version()
                                + "），同产线同时刻只允许一个生效版本");
            }
            repository.updateEffective(current.id(), false);
            repository.replaceEffectiveSchedule(row.lineId(), scheduleId);
        } else {
            try {
                repository.insertEffectiveSchedule(row.lineId(), scheduleId);
            } catch (DuplicateKeyException e) {
                throw new ApiException(HttpStatus.CONFLICT, "并发下达冲突，仅一个版本可生效");
            }
        }
        repository.updateStatus(scheduleId, ScheduleStatus.RELEASED);
        repository.updateEffective(scheduleId, true);
        return view(scheduleId);
    }

    /** 下达后修改：基于当前版本生成 version+1 的新 DRAFT 版本，须重新校验与下达。 */
    @Transactional
    public ScheduleView revise(long scheduleId, ReviseSchedule request) {
        ScheduleRow row = requireSchedule(scheduleId);
        if (row.status() != ScheduleStatus.RELEASED && row.status() != ScheduleStatus.EXECUTING) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "状态 " + row.status() + " 不允许修订，须先下达");
        }
        int newVersion = repository.maxVersion(row.lineId(), row.unitNo()) + 1;
        long newId = repository.insertSchedule(request.requestId(),
                Fingerprint.ofSchedule(request.requestId(), String.valueOf(row.lineId()), row.unitNo(),
                        request.items(), request.rollChangeWindows()),
                row.lineId(), row.unitNo(), newVersion, ScheduleStatus.DRAFT);
        repository.insertItems(newId, request.items());
        repository.insertWindows(newId,
                request.rollChangeWindows() == null ? List.of() : request.rollChangeWindows());
        return view(newId);
    }

    /**
     * 实绩回传：按 合同号+道次 去重归位；累计里程达换辊阈值（10km）自动锁定后续排程
     * 并提示换辊；已冻结排产只记迟到事件，不改已冻结里程与换辊结论。
     */
    @Transactional
    public ActualsResult reportActuals(long scheduleId, ReportActuals request) {
        repository.lockSchedule(scheduleId);
        ScheduleRow row = requireSchedule(scheduleId);

        if (row.status() == ScheduleStatus.FROZEN) {
            for (ActualItem item : request.actuals()) {
                repository.insertLateEvent(scheduleId, item);
            }
            return new ActualsResult(scheduleId, row.status().name(), 0, 0,
                    request.actuals().size(), row.actualMileageKm(), row.locked(),
                    row.rollChangeRequired());
        }
        if (row.status() != ScheduleStatus.RELEASED && row.status() != ScheduleStatus.EXECUTING) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "状态 " + row.status() + " 不允许实绩回传，须先下达");
        }
        if (row.locked()) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "实绩累计里程已达换辊阈值，排程已锁定，须先登记换辊，不得静默放行超限轧制");
        }

        int accepted = 0;
        int duplicates = 0;
        BigDecimal mileage = row.actualMileageKm();
        for (ActualItem item : request.actuals()) {
            if (repository.actualExists(scheduleId, item.contractNo(), item.passNo())) {
                duplicates++;
                continue;
            }
            try {
                repository.insertActual(scheduleId, item);
            } catch (DuplicateKeyException e) {
                duplicates++;
                continue;
            }
            accepted++;
            mileage = mileage.add(item.mileageKm());
        }

        boolean locked = mileage.compareTo(ScheduleValidator.ROLL_CYCLE_LIMIT_KM) >= 0;
        repository.updateActualMileage(scheduleId, mileage);
        if (locked) {
            repository.updateLock(scheduleId, true, true);
        }
        if (row.status() == ScheduleStatus.RELEASED) {
            repository.updateStatus(scheduleId, ScheduleStatus.EXECUTING);
        }
        return new ActualsResult(scheduleId, ScheduleStatus.EXECUTING.name(),
                accepted, duplicates, 0, mileage, locked, locked);
    }

    /** 登记换辊：清零本周期实绩里程并解除锁定。 */
    @Transactional
    public ScheduleView registerRollChange(long scheduleId, RegisterRollChange request) {
        repository.lockSchedule(scheduleId);
        ScheduleRow row = requireSchedule(scheduleId);
        if (row.status() != ScheduleStatus.EXECUTING && row.status() != ScheduleStatus.RELEASED) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "状态 " + row.status() + " 不允许登记换辊");
        }
        if (!request.start().isBefore(request.end())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "换辊窗口开始须早于结束");
        }
        repository.insertWindows(scheduleId, List.of(new RollWindowSpec(request.start(), request.end())));
        repository.updateActualMileage(scheduleId, BigDecimal.ZERO);
        repository.updateLock(scheduleId, false, false);
        return view(scheduleId);
    }

    /**
     * 日终冻结（终态）：重复冻结幂等返回相同结果；
     * 冻结后迟到实绩只记事件，不改里程与换辊结论。
     */
    @Transactional
    public FreezeResult freeze(long scheduleId) {
        repository.lockSchedule(scheduleId);
        ScheduleRow row = requireSchedule(scheduleId);
        if (row.status() == ScheduleStatus.FROZEN) {
            return new FreezeResult(scheduleId, ScheduleStatus.FROZEN.name(),
                    row.actualMileageKm(), row.rollChangeRequired());
        }
        if (row.status() != ScheduleStatus.RELEASED && row.status() != ScheduleStatus.EXECUTING) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "状态 " + row.status() + " 不允许日冻结，须先下达");
        }
        repository.updateStatus(scheduleId, ScheduleStatus.FROZEN);
        repository.updateEffective(scheduleId, false);
        repository.deleteEffectiveSchedule(row.lineId());
        return new FreezeResult(scheduleId, ScheduleStatus.FROZEN.name(),
                row.actualMileageKm(), row.rollChangeRequired());
    }

    private ScheduleView view(long scheduleId) {
        ScheduleRow row = requireSchedule(scheduleId);
        String lineCode = repository.findLineCode(row.lineId());
        return new ScheduleView(row.id(), lineCode, row.unitNo(), row.version(), row.status().name(),
                row.effective(), row.locked(), row.rollChangeRequired(), row.actualMileageKm(),
                repository.findItems(scheduleId), repository.findWindows(scheduleId));
    }

    private long requireLine(String lineCode) {
        return repository.findLineId(lineCode)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "产线不存在: " + lineCode));
    }

    private ScheduleRow requireSchedule(long scheduleId) {
        return repository.findSchedule(scheduleId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "排产单不存在: " + scheduleId));
    }

    private List<com.example.starter.schedule.ScheduleRequests.ItemSpec> toItemSpecs(long scheduleId) {
        return repository.findItems(scheduleId).stream()
                .map(i -> new com.example.starter.schedule.ScheduleRequests.ItemSpec(
                        i.seq(), i.contractNo(), i.widthMm(), i.mileageKm(), i.warmup(),
                        i.plannedStart(), i.plannedEnd()))
                .toList();
    }

    private List<RollWindowSpec> toWindowSpecs(long scheduleId) {
        return repository.findWindows(scheduleId).stream()
                .map(w -> new RollWindowSpec(w.start(), w.end()))
                .toList();
    }
}
