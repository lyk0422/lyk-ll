package com.example.starter.service;

import com.example.starter.domain.DisposalChain;
import com.example.starter.domain.EvacuationArrival;
import com.example.starter.domain.MonitorZone;
import com.example.starter.domain.RadarReading;
import com.example.starter.domain.ResumeAssessmentRecord;
import com.example.starter.domain.ZonePerson;
import com.example.starter.exception.ApiException;
import com.example.starter.repository.DisposalRepository;
import com.example.starter.repository.MonitorRepository;
import com.example.starter.web.ServiceResult;
import com.example.starter.web.dto.AlarmEvaluateRequest;
import com.example.starter.web.dto.ArchiveRequest;
import com.example.starter.web.dto.ArrivalReportRequest;
import com.example.starter.web.dto.CreateZoneRequest;
import com.example.starter.web.dto.EvacConfirmRequest;
import com.example.starter.web.dto.PersonLocationRequest;
import com.example.starter.web.dto.ReadingBatchRequest;
import com.example.starter.web.dto.ReadingItem;
import com.example.starter.web.dto.ResumeApplyRequest;
import com.example.starter.web.dto.ResumeDecisionRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 边坡雷达监测预警与停产撤人总服务：监测→预警分级→处置→撤人→复工评估→归档。
 *
 * <p>关键边界：</p>
 * <ul>
 *   <li>阈值严格比较，恰等预警值/报警值判正常，恰满 30 分钟、恰满 24 小时判满足；</li>
 *   <li>预警/撤人/复工/归档均以 requestId 幂等，同键异参 409 且状态不变；</li>
 *   <li>同一测区同时刻仅一条执行中处置链（active_key 唯一索引），并发预警唯一受理；</li>
 *   <li>撤人确认与作业封锁同一事务；停产→复工为互斥终态，条件更新防并发翻转；</li>
 *   <li>归档为终态，归档后上报的位移数据置迟到标记并记 LATE_READING 事件，不影响处置链。</li>
 * </ul>
 */
@Service
public class SlopeMonitorService {

    /** 撤人全员到位时限（分钟），恰满 30 分钟判定满足。 */
    static final long EVAC_DEADLINE_MINUTES = 30L;
    /** 复工前速率连续低于安全线的窗口（小时），恰满 24 小时允许申请。 */
    static final long RESUME_SAFE_WINDOW_HOURS = 24L;

    private final MonitorRepository monitorRepository;
    private final DisposalRepository disposalRepository;
    private final Idempotency idempotency;

    public SlopeMonitorService(MonitorRepository monitorRepository,
                               DisposalRepository disposalRepository,
                               Idempotency idempotency) {
        this.monitorRepository = monitorRepository;
        this.disposalRepository = disposalRepository;
        this.idempotency = idempotency;
    }

    // ---------------------------------------------------------------- 监测数据

    @Transactional
    public ServiceResult createZone(CreateZoneRequest request) {
        if (request.warnHourlyRate().signum() <= 0 || request.alarmDailyRate().signum() <= 0
                || request.safetyHourlyRate().signum() < 0) {
            throw ApiException.unprocessable("INVALID_THRESHOLD", "速率阈值必须为正数（安全线允许 0）");
        }
        if (request.safetyHourlyRate().compareTo(request.warnHourlyRate()) > 0) {
            throw ApiException.unprocessable("INVALID_THRESHOLD", "复工安全线不得高于黄色预警值");
        }
        if (monitorRepository.findZone(request.zoneCode()) != null) {
            throw ApiException.conflict("ZONE_EXISTS", "测区 " + request.zoneCode() + " 已存在");
        }
        MonitorZone zone = new MonitorZone();
        zone.setZoneCode(request.zoneCode());
        zone.setZoneName(request.zoneName());
        zone.setWarnHourlyRate(request.warnHourlyRate());
        zone.setAlarmDailyRate(request.alarmDailyRate());
        zone.setSafetyHourlyRate(request.safetyHourlyRate());
        zone.setProductionStatus("ACTIVE");
        zone.setOperationLocked(false);
        try {
            monitorRepository.insertZone(zone);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("ZONE_EXISTS", "测区 " + request.zoneCode() + " 已存在");
        }
        return ServiceResult.created(zoneView(monitorRepository.findZone(request.zoneCode())));
    }

    @Transactional
    public ServiceResult reportReadings(ReadingBatchRequest request) {
        Map<String, Integer> inserted = new LinkedHashMap<>();
        Map<String, Integer> duplicated = new LinkedHashMap<>();
        Map<String, Integer> late = new LinkedHashMap<>();
        for (ReadingItem item : request.readings()) {
            MonitorZone zone = monitorRepository.findZone(item.zoneCode());
            if (zone == null) {
                throw ApiException.notFound("测区不存在: " + item.zoneCode());
            }
            DisposalChain latestArchived = disposalRepository.findLatestArchivedChain(item.zoneCode());
            // 无执行中链、读数时刻不晚于最近归档时刻 → 归档后迟到的历史数据
            boolean archived = disposalRepository.findActiveChainByZone(item.zoneCode()) == null
                    && latestArchived != null
                    && !item.readingTime().isAfter(latestArchived.getArchiveTime());
            boolean isNew = monitorRepository.insertReadingIgnoreDuplicate(
                    item.zoneCode(), item.readingTime(), item.displacementMm(), archived);
            if (isNew && archived) {
                monitorRepository.insertEvent(item.zoneCode(), "LATE_READING", item.readingTime(),
                        "处置链归档后迟到位移数据，位移=" + item.displacementMm() + "mm，仅记迟到事件");
                late.merge(item.zoneCode(), 1, Integer::sum);
            }
            if (isNew) {
                inserted.merge(item.zoneCode(), 1, Integer::sum);
            } else {
                duplicated.merge(item.zoneCode(), 1, Integer::sum);
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("received", request.readings().size());
        body.put("inserted", inserted);
        body.put("duplicated", duplicated);
        body.put("late", late);
        return ServiceResult.ok(body);
    }

    @Transactional
    public ServiceResult reportPersonLocation(PersonLocationRequest request) {
        if (monitorRepository.findZone(request.zoneCode()) == null) {
            throw ApiException.notFound("测区不存在: " + request.zoneCode());
        }
        monitorRepository.upsertPersonLocation(request.zoneCode(), request.personId(),
                Boolean.TRUE.equals(request.onSite()), request.locatedAt());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("zoneCode", request.zoneCode());
        body.put("personId", request.personId());
        body.put("onSite", request.onSite());
        body.put("locatedAt", request.locatedAt());
        return ServiceResult.ok(body);
    }

    public ServiceResult rateCurve(String zoneCode, LocalDateTime from, LocalDateTime to) {
        MonitorZone zone = requireZone(zoneCode);
        if (from.isAfter(to)) {
            throw ApiException.badRequest("INVALID_RANGE", "from 不得晚于 to");
        }
        List<RateCalculator.RatePoint> points = RateCalculator.curve(toReadings(monitorRepository.findReadings(zoneCode)))
                .stream()
                .filter(p -> !p.time().isBefore(from) && !p.time().isAfter(to))
                .toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("zoneCode", zoneCode);
        body.put("from", from);
        body.put("to", to);
        body.put("warnHourlyRate", zone.getWarnHourlyRate());
        body.put("alarmDailyRate", zone.getAlarmDailyRate());
        body.put("safetyHourlyRate", zone.getSafetyHourlyRate());
        body.put("points", points.stream().map(this::pointView).toList());
        return ServiceResult.ok(body);
    }

    // ---------------------------------------------------------------- 预警分级

    @Transactional
    public ServiceResult evaluateAlarm(AlarmEvaluateRequest request) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("zoneCode", request.zoneCode());
        fields.put("triggerTime", request.triggerTime().toString());
        return idempotency.execute(request.requestId(), "ALARM", request.zoneCode(), fields, () -> {
            MonitorZone zone = requireZone(request.zoneCode());
            List<RateCalculator.Reading> readings = toReadings(
                    monitorRepository.findReadings(request.zoneCode()));
            RateCalculator.AlarmVerdict verdict = RateCalculator.classify(
                    readings, request.triggerTime(),
                    zone.getWarnHourlyRate(), zone.getAlarmDailyRate());

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("requestId", request.requestId());
            body.put("zoneCode", request.zoneCode());
            body.put("triggerTime", request.triggerTime());
            body.put("hourlyRateMmPerHour", verdict.hourlyRate());
            body.put("dailyRateMmPerDay", verdict.dailyRate());
            body.put("reason", verdict.reason());

            if (verdict.level() == RateCalculator.AlarmVerdict.Level.NORMAL) {
                body.put("level", "NORMAL");
                return ServiceResult.ok(body);
            }
            if (disposalRepository.findActiveChainByZone(request.zoneCode()) != null) {
                throw ApiException.conflict("ACTIVE_CHAIN_EXISTS",
                        "测区 " + request.zoneCode() + " 已存在执行中处置链，并发预警唯一受理");
            }
            DisposalChain chain = new DisposalChain();
            chain.setChainNo(newChainNo(request.zoneCode()));
            chain.setZoneCode(request.zoneCode());
            chain.setRequestId(request.requestId());
            chain.setAlarmLevel(verdict.level().name());
            chain.setTriggerTime(request.triggerTime());
            chain.setStatus("ACTIVE");
            chain.setActiveKey(request.zoneCode());

            if (verdict.level() == RateCalculator.AlarmVerdict.Level.RED) {
                // 红色预警立即停产；作业封锁在撤人确认事务内置位
                int stopped = monitorRepository.updateZoneStatusForStop(request.zoneCode());
                if (stopped == 0) {
                    throw ApiException.conflict("ZONE_STATE_CONFLICT",
                            "测区当前不允许停产（已停产或存在封锁），停产/复工终态冲突");
                }
                chain.setEvacStatus("PENDING");
                try {
                    disposalRepository.insertChain(chain);
                } catch (DuplicateKeyException e) {
                    throw ApiException.conflict("ACTIVE_CHAIN_EXISTS",
                            "测区 " + request.zoneCode() + " 已存在执行中处置链，并发预警唯一受理");
                }
                List<String> personIds = monitorRepository
                        .findOnSitePersonsAt(request.zoneCode(), request.triggerTime())
                        .stream().map(ZonePerson::getPersonId).distinct().toList();
                disposalRepository.insertEvacSnapshot(chain.getId(), request.zoneCode(), personIds);
                body.put("evacuation", evacView(chain));
            } else {
                chain.setEvacStatus(null);
                try {
                    disposalRepository.insertChain(chain);
                } catch (DuplicateKeyException e) {
                    throw ApiException.conflict("ACTIVE_CHAIN_EXISTS",
                            "测区 " + request.zoneCode() + " 已存在执行中处置链，并发预警唯一受理");
                }
            }
            body.put("level", verdict.level().name());
            body.put("chainNo", chain.getChainNo());
            body.put("productionStatus", "RED".equals(chain.getAlarmLevel()) ? "STOPPED" : "UNCHANGED");
            return ServiceResult.created(body);
        });
    }

    // ---------------------------------------------------------------- 撤人

    @Transactional
    public ServiceResult reportArrival(ArrivalReportRequest request) {
        DisposalChain chain = requireChain(request.chainNo());
        if (!"ACTIVE".equals(chain.getStatus())) {
            throw ApiException.unprocessable("CHAIN_ARCHIVED", "处置链已归档，不能再上报到位");
        }
        if (!"RED".equals(chain.getAlarmLevel())) {
            throw ApiException.unprocessable("EVAC_NOT_REQUIRED", "黄色预警处置链无需撤人");
        }
        if (!"PENDING".equals(chain.getEvacStatus())) {
            throw ApiException.conflict("EVAC_STATUS_CONFLICT",
                    "撤人已" + ("TIMEOUT".equals(chain.getEvacStatus()) ? "超时" : "确认") + "，不能再上报到位");
        }
        boolean exists = disposalRepository.reportArrival(chain.getId(), request.personId(), request.arrivedAt());
        if (!exists) {
            throw ApiException.notFound("人员 " + request.personId() + " 不在该处置链的撤人名册内");
        }
        return ServiceResult.ok(evacView(requireChain(request.chainNo())));
    }

    @Transactional
    public ServiceResult confirmEvacuation(EvacConfirmRequest request) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("chainNo", request.chainNo());
        fields.put("confirmTime", request.confirmTime().toString());
        return idempotency.execute(request.requestId(), "EVAC_CONFIRM", null, fields, () -> {
            DisposalChain chain = requireChain(request.chainNo());
            if (!"ACTIVE".equals(chain.getStatus())) {
                throw ApiException.unprocessable("CHAIN_ARCHIVED", "处置链已归档，不能再确认撤人");
            }
            if (!"RED".equals(chain.getAlarmLevel())) {
                throw ApiException.unprocessable("EVAC_NOT_REQUIRED", "黄色预警处置链无需撤人确认");
            }
            if ("CONFIRMED".equals(chain.getEvacStatus())) {
                throw ApiException.conflict("EVAC_ALREADY_CONFIRMED", "撤人已全员确认，请勿重复确认");
            }
            LocalDateTime deadline = chain.getTriggerTime().plusMinutes(EVAC_DEADLINE_MINUTES);
            if (request.confirmTime().isBefore(chain.getTriggerTime())) {
                throw ApiException.unprocessable("EVAC_TIME_INVALID", "确认时刻早于预警触发时刻");
            }
            List<EvacuationArrival> arrivals = disposalRepository.findArrivals(chain.getId());
            int total = arrivals.size();
            List<EvacuationArrival> missing = arrivals.stream()
                    .filter(a -> a.getArrivedAt() == null).toList();
            List<EvacuationArrival> lateArrivals = arrivals.stream()
                    .filter(a -> a.getArrivedAt() != null && a.getArrivedAt().isAfter(deadline)).toList();

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("requestId", request.requestId());
            body.put("chainNo", chain.getChainNo());
            body.put("triggerTime", chain.getTriggerTime());
            body.put("deadline", deadline);
            body.put("confirmTime", request.confirmTime());
            body.put("totalPeople", total);
            body.put("arrivedPeople", total - missing.size());

            if (request.confirmTime().isAfter(deadline) || !lateArrivals.isEmpty()) {
                // 超过 30 分钟或有人晚于 30 分钟到位：TIMEOUT 终态并落库（恰满 30 分钟不算超时）。
                // 返回 422 但事务正常提交，TIMEOUT 与幂等首次结果均持久化，重放同得 422。
                disposalRepository.markEvacTimeout(chain.getChainNo());
                body.put("evacStatus", "TIMEOUT");
                body.put("missingPeople", missing.stream().map(EvacuationArrival::getPersonId).toList());
                body.put("latePeople", lateArrivals.stream().map(EvacuationArrival::getPersonId).toList());
                body.put("reason", "未在30分钟内全员到位（恰满30分钟判定满足）");
                return new ServiceResult(422, body);
            }
            if (!missing.isEmpty()) {
                body.put("evacStatus", "PENDING");
                body.put("missingPeople", missing.stream().map(EvacuationArrival::getPersonId).toList());
                body.put("reason", "尚有人员未到位");
                return new ServiceResult(422, body);
            }

            // 撤人确认与作业封锁在同一事务内完成
            disposalRepository.markEvacConfirmed(chain.getChainNo(), request.confirmTime());
            monitorRepository.lockOperation(chain.getZoneCode());
            body.put("evacStatus", "CONFIRMED");
            body.put("operationLocked", true);
            body.put("reason", "30分钟内全员到位（含恰满30分钟），撤人确认与作业封锁同一事务完成");
            return ServiceResult.ok(body);
        });
    }

    // ---------------------------------------------------------------- 复工评估

    @Transactional
    public ServiceResult applyResume(ResumeApplyRequest request) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("chainNo", request.chainNo());
        fields.put("applyTime", request.applyTime().toString());
        return idempotency.execute(request.requestId(), "RESUME_APPLY", null, fields, () -> {
            DisposalChain chain = requireChain(request.chainNo());
            if (!"ACTIVE".equals(chain.getStatus())) {
                throw ApiException.unprocessable("CHAIN_ARCHIVED", "处置链已归档，不能申请复工");
            }
            if (!"RED".equals(chain.getAlarmLevel())) {
                throw ApiException.unprocessable("RESUME_NOT_REQUIRED", "黄色预警处置链无需复工评估");
            }
            if (!"CONFIRMED".equals(chain.getEvacStatus())) {
                throw ApiException.unprocessable("EVAC_NOT_CONFIRMED", "撤人未全员确认，不能申请复工评估");
            }
            MonitorZone zone = requireZone(chain.getZoneCode());
            ResumeAssessmentRecord existing = disposalRepository.findResumeByChain(chain.getId());
            if (existing != null && "APPLIED".equals(existing.getStatus())) {
                throw ApiException.conflict("RESUME_ALREADY_APPLIED", "复工评估已申请，等待双确认结论");
            }
            if (existing != null && "APPROVED".equals(existing.getStatus())) {
                throw ApiException.conflict("RESUME_ALREADY_APPROVED", "复工已批准，终态不可重复申请");
            }

            List<RateCalculator.Reading> readings = toReadings(
                    monitorRepository.findReadings(chain.getZoneCode()));
            RateCalculator.SafetyWindowResult window = RateCalculator.evaluateSafetyWindow(
                    readings, chain.getTriggerTime(), request.applyTime(), zone.getSafetyHourlyRate());
            if (!window.eligible()) {
                throw ApiException.unprocessable("SAFETY_WINDOW_NOT_MET", window.reason());
            }

            if (existing == null) {
                ResumeAssessmentRecord record = new ResumeAssessmentRecord();
                record.setChainId(chain.getId());
                record.setApplyRequestId(request.requestId());
                record.setApplyTime(request.applyTime());
                record.setSafeSince(window.safeSince());
                disposalRepository.insertResume(record);
            } else {
                // 前次驳回后满足条件可重新申请
                disposalRepository.reopenResume(chain.getId(), request.requestId(),
                        request.applyTime(), window.safeSince());
            }
            disposalRepository.markResumeApplied(chain.getChainNo(), request.applyTime());

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("requestId", request.requestId());
            body.put("chainNo", chain.getChainNo());
            body.put("resumeStatus", "APPLIED");
            body.put("applyTime", request.applyTime());
            body.put("safeSince", window.safeSince());
            body.put("continuousSafeHours", RESUME_SAFE_WINDOW_HOURS);
            body.put("reason", window.reason());
            return ServiceResult.created(body);
        });
    }

    @Transactional
    public ServiceResult decideResume(ResumeDecisionRequest request) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("chainNo", request.chainNo());
        fields.put("decideTime", request.decideTime().toString());
        fields.put("siteConfirmed", request.siteConfirmed());
        fields.put("monitorConfirmed", request.monitorConfirmed());
        fields.put("remark", request.remark());
        return idempotency.execute(request.requestId(), "RESUME_DECISION", null, fields, () -> {
            DisposalChain chain = requireChain(request.chainNo());
            if (!"ACTIVE".equals(chain.getStatus())) {
                throw ApiException.unprocessable("CHAIN_ARCHIVED", "处置链已归档，不能作出复工结论");
            }
            ResumeAssessmentRecord resume = disposalRepository.findResumeByChain(chain.getId());
            if (resume == null) {
                throw ApiException.unprocessable("RESUME_NOT_APPLIED", "复工评估未申请，不能作出结论");
            }
            if ("APPROVED".equals(resume.getStatus())) {
                // 复工批准为唯一终态：并发/重复结论一律 409，防止既停产又复工
                throw ApiException.conflict("RESUME_ALREADY_APPROVED", "复工已批准，终态不可重复作出结论");
            }
            if (!"APPLIED".equals(resume.getStatus())) {
                throw ApiException.unprocessable("RESUME_NOT_APPLIED", "复工评估未在申请中，不能作出结论");
            }
            if (request.decideTime().isBefore(resume.getApplyTime())) {
                throw ApiException.unprocessable("RESUME_TIME_INVALID", "结论时刻早于申请时刻");
            }
            boolean approved = Boolean.TRUE.equals(request.siteConfirmed())
                    && Boolean.TRUE.equals(request.monitorConfirmed());
            String status = approved ? "APPROVED" : "REJECTED";

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("requestId", request.requestId());
            body.put("chainNo", chain.getChainNo());
            body.put("siteConfirmed", request.siteConfirmed());
            body.put("monitorConfirmed", request.monitorConfirmed());
            body.put("decideTime", request.decideTime());

            if (approved) {
                // 条件更新保证停产/复工唯一终态：仅 STOPPED+封锁中 可翻转为 RESUMED，并发只有一个成功
                int rows = monitorRepository.updateZoneStatusForResume(chain.getZoneCode());
                if (rows == 0) {
                    throw ApiException.conflict("ZONE_STATE_CONFLICT",
                            "测区不处于已停产封锁状态，停产/复工终态竞态被拒绝");
                }
                disposalRepository.saveResumeDecision(chain.getId(), chain.getChainNo(),
                        request.requestId(), request.decideTime(), true, true, "APPROVED", request.remark());
                body.put("resumeStatus", "APPROVED");
                body.put("productionStatus", "RESUMED");
                body.put("operationLocked", false);
                body.put("reason", "现场核查与监测复核双确认通过，解除封锁并复工");
            } else {
                disposalRepository.saveResumeDecision(chain.getId(), chain.getChainNo(),
                        request.requestId(), request.decideTime(),
                        Boolean.TRUE.equals(request.siteConfirmed()),
                        Boolean.TRUE.equals(request.monitorConfirmed()), "REJECTED", request.remark());
                body.put("resumeStatus", "REJECTED");
                body.put("productionStatus", "STOPPED");
                body.put("operationLocked", true);
                body.put("reason", "双确认未全部通过，维持停产封锁");
            }
            return ServiceResult.ok(body);
        });
    }

    // ---------------------------------------------------------------- 归档

    @Transactional
    public ServiceResult archive(ArchiveRequest request) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("chainNo", request.chainNo());
        fields.put("archiveTime", request.archiveTime().toString());
        return idempotency.execute(request.requestId(), "ARCHIVE", null, fields, () -> {
            DisposalChain chain = requireChain(request.chainNo());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("requestId", request.requestId());
            body.put("chainNo", chain.getChainNo());
            body.put("archiveTime", request.archiveTime());
            if ("ARCHIVED".equals(chain.getStatus())) {
                // 归档为终态：重复归档幂等成功
                body.put("chainStatus", "ARCHIVED");
                body.put("alreadyArchived", true);
                body.put("originalArchiveTime", chain.getArchiveTime());
                return ServiceResult.ok(body);
            }
            if ("RED".equals(chain.getAlarmLevel()) && !"APPROVED".equals(chain.getResumeStatus())) {
                throw ApiException.unprocessable("CHAIN_NOT_TERMINABLE",
                        "红色处置链须经复工评估双确认批准后方可归档");
            }
            if (request.archiveTime().isBefore(chain.getTriggerTime())) {
                throw ApiException.unprocessable("ARCHIVE_TIME_INVALID", "归档时刻早于预警触发时刻");
            }
            disposalRepository.archiveChain(chain.getChainNo(), request.archiveTime());
            body.put("chainStatus", "ARCHIVED");
            body.put("alreadyArchived", false);
            body.put("reason", "处置链归档完成，执行中槽位释放，迟到数据只记迟到事件");
            return ServiceResult.ok(body);
        });
    }

    // ---------------------------------------------------------------- 查询

    public ServiceResult getChain(String chainNo) {
        return ServiceResult.ok(chainView(requireChain(chainNo)));
    }

    // ---------------------------------------------------------------- 辅助

    private MonitorZone requireZone(String zoneCode) {
        MonitorZone zone = monitorRepository.findZone(zoneCode);
        if (zone == null) {
            throw ApiException.notFound("测区不存在: " + zoneCode);
        }
        return zone;
    }

    private DisposalChain requireChain(String chainNo) {
        DisposalChain chain = disposalRepository.findChainByNo(chainNo);
        if (chain == null) {
            throw ApiException.notFound("处置链不存在: " + chainNo);
        }
        return chain;
    }

    private List<RateCalculator.Reading> toReadings(List<RadarReading> source) {
        List<RateCalculator.Reading> list = new ArrayList<>();
        for (RadarReading r : source) {
            list.add(new RateCalculator.Reading(r.getReadingTime(), r.getDisplacementMm()));
        }
        return list;
    }

    private String newChainNo(String zoneCode) {
        return "CHAIN-" + zoneCode + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }

    private Map<String, Object> zoneView(MonitorZone zone) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("zoneCode", zone.getZoneCode());
        m.put("zoneName", zone.getZoneName());
        m.put("warnHourlyRate", zone.getWarnHourlyRate());
        m.put("alarmDailyRate", zone.getAlarmDailyRate());
        m.put("safetyHourlyRate", zone.getSafetyHourlyRate());
        m.put("productionStatus", zone.getProductionStatus());
        m.put("operationLocked", zone.isOperationLocked());
        return m;
    }

    private Map<String, Object> pointView(RateCalculator.RatePoint p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("time", p.time());
        m.put("hourlyRateMmPerHour", p.hourlyRate());
        m.put("dailyRateMmPerDay", p.dailyRate());
        return m;
    }

    private Map<String, Object> evacView(DisposalChain chain) {
        List<EvacuationArrival> arrivals = disposalRepository.findArrivals(chain.getId());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("evacStatus", chain.getEvacStatus());
        m.put("totalPeople", arrivals.size());
        m.put("arrivedPeople", arrivals.stream().filter(a -> a.getArrivedAt() != null).count());
        m.put("deadline", chain.getTriggerTime().plusMinutes(EVAC_DEADLINE_MINUTES));
        m.put("arrivals", arrivals.stream().map(a -> {
            Map<String, Object> am = new LinkedHashMap<>();
            am.put("personId", a.getPersonId());
            am.put("arrivedAt", a.getArrivedAt());
            return am;
        }).collect(Collectors.toList()));
        return m;
    }

    private Map<String, Object> chainView(DisposalChain chain) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("chainNo", chain.getChainNo());
        m.put("zoneCode", chain.getZoneCode());
        m.put("requestId", chain.getRequestId());
        m.put("alarmLevel", chain.getAlarmLevel());
        m.put("triggerTime", chain.getTriggerTime());
        m.put("chainStatus", chain.getStatus());
        m.put("evacStatus", chain.getEvacStatus());
        m.put("evacConfirmTime", chain.getEvacConfirmTime());
        m.put("resumeStatus", chain.getResumeStatus());
        m.put("resumeApplyTime", chain.getResumeApplyTime());
        m.put("resumeDecideTime", chain.getResumeDecideTime());
        m.put("archiveTime", chain.getArchiveTime());
        if ("RED".equals(chain.getAlarmLevel())) {
            m.put("evacuation", evacView(chain));
        }
        ResumeAssessmentRecord resume = disposalRepository.findResumeByChain(chain.getId());
        if (resume != null) {
            Map<String, Object> rm = new LinkedHashMap<>();
            rm.put("applyRequestId", resume.getApplyRequestId());
            rm.put("applyTime", resume.getApplyTime());
            rm.put("safeSince", resume.getSafeSince());
            rm.put("status", resume.getStatus());
            rm.put("siteConfirmed", resume.getSiteConfirmed());
            rm.put("monitorConfirmed", resume.getMonitorConfirmed());
            rm.put("decideTime", resume.getDecideTime());
            rm.put("remark", resume.getRemark());
            m.put("resumeAssessment", rm);
        }
        MonitorZone zone = monitorRepository.findZone(chain.getZoneCode());
        if (zone != null) {
            m.put("productionStatus", zone.getProductionStatus());
            m.put("operationLocked", zone.isOperationLocked());
        }
        return m;
    }
}
