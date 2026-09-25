package com.example.starter.legality.service;

import com.example.starter.legality.api.dto.CheckRequest;
import com.example.starter.legality.api.dto.CheckResponse;
import com.example.starter.legality.api.dto.DelayRequest;
import com.example.starter.legality.api.dto.DelayResponse;
import com.example.starter.legality.api.dto.MemberDutyRequest;
import com.example.starter.legality.api.dto.MemberDutyResponse;
import com.example.starter.legality.api.dto.SealResponse;
import com.example.starter.legality.domain.LegalityResult;
import com.example.starter.legality.domain.LegalityRules;
import com.example.starter.legality.domain.ViolationReasons;
import com.example.starter.legality.exception.ConflictException;
import com.example.starter.legality.exception.NotFoundException;
import com.example.starter.legality.po.AirportPo;
import com.example.starter.legality.po.CheckBatchPo;
import com.example.starter.legality.po.DelayEventPo;
import com.example.starter.legality.po.DutyRecordPo;
import com.example.starter.legality.po.FlightPo;
import com.example.starter.legality.repo.LegalityRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 值勤合规应用服务：整组校验、延误延长、封板与幂等。
 *
 * <p>所有写操作在单一事务内执行：整组校验逐人独立判定，同组结论与成员状态一次性提交；
 * 同键异参抛 409 触发回滚，不写入任何记录。并发下撞唯一索引时整事务重试，
 * 随后读到先提交记录并按幂等重放返回首次判定。</p>
 */
@Service
public class LegalityService {

    /** 并发撞唯一索引时的整事务重试上限。 */
    private static final int MAX_IDEMPOTENT_RETRIES = 5;

    private final LegalityRepository repository;
    private final LegalityRules rules;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public LegalityService(LegalityRepository repository, LegalityRules rules, ObjectMapper objectMapper,
                           PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.rules = rules;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 整组排班校验：对每名成员独立判定 FDP 上限、每日延长、7 日工时与休息期，
     * 并在同一事务内给出整组结果；任一名成员违规则整组 ILLEGAL。
     */
    public CheckResponse check(CheckRequest request) {
        DuplicateKeyException lastDuplicate = null;
        for (int attempt = 0; attempt < MAX_IDEMPOTENT_RETRIES; attempt++) {
            try {
                return transactionTemplate.execute(status -> doCheck(request));
            } catch (DuplicateKeyException ex) {
                // 并发同 requestId 或同槽位（成员+航班+报到时刻）：先提交者受理，本轮回滚后重试走重放。
                lastDuplicate = ex;
            }
        }
        throw lastDuplicate;
    }

    private CheckResponse doCheck(CheckRequest request) {
        Instant now = rules.now();
        CheckBatchPo batch = null;
        List<ResolvedDuty> resolved = new ArrayList<>();

        for (MemberDutyRequest duty : request.duties()) {
            validateShape(duty);

            DutyRecordPo existingByKey = repository.findDutyByRequestId(duty.requestId());
            if (existingByKey != null) {
                // 同键重放：参数一致返回首次判定；同键异参 409 且整事务回滚、不写入。
                if (!fingerprint(duty).equals(existingByKey.getFingerprint())) {
                    throw new ConflictException("requestId=" + duty.requestId() + " 已用于不同参数的值勤提交");
                }
                resolved.add(new ResolvedDuty(existingByKey, true));
                continue;
            }

            // 同一成员同一时段（成员+航班+报到时刻）的并发提交：唯一索引只放行一条，
            // 后到者读到先提交记录并按幂等重放处理。
            DutyRecordPo slot = repository.findDutyBySlot(duty.memberCode(), duty.flightNo(), duty.reportTime());
            if (slot != null) {
                resolved.add(new ResolvedDuty(slot, true));
                continue;
            }

            Evaluation evaluation = evaluate(duty.memberCode(), duty.flightNo(), duty.reportTime(),
                    duty.plannedEnd(), 0, null);

            if (batch == null) {
                batch = newBatch(now);
                repository.insertBatch(batch);
            }

            DutyRecordPo po = new DutyRecordPo();
            po.setBatchId(batch.getId());
            po.setRequestId(duty.requestId());
            po.setMemberCode(duty.memberCode());
            po.setFlightNo(duty.flightNo());
            po.setReportTimeUtc(duty.reportTime());
            po.setPlannedEndUtc(duty.plannedEnd());
            po.setTierLimitMinutes(evaluation.tierLimitMinutes());
            po.setTotalExtensionMinutes(0);
            po.setDutyEndUtc(duty.plannedEnd());
            po.setResult(evaluation.legal() ? LegalityResult.LEGAL : LegalityResult.ILLEGAL);
            po.setReasons(writeReasons(evaluation.reasons()));
            po.setAccepted(evaluation.legal());
            po.setFingerprint(fingerprint(duty));
            po.setCreatedAt(now);
            repository.insertDuty(po);
            resolved.add(new ResolvedDuty(po, false));
        }

        List<DutyRecordPo> allDuties = resolved.stream().map(ResolvedDuty::po).toList();
        if (batch == null) {
            // 整组全部为同键/同槽位重放：沿用首条记录所属批次作为对外批次号。
            batch = repository.findBatchById(allDuties.get(0).getBatchId());
        } else {
            String overall = resolveOverall(allDuties);
            repository.updateBatchOverall(batch.getId(), overall);
            batch.setOverallResult(overall);
        }

        return toCheckResponse(batch, resolved);
    }

    /**
     * 延误延长上报：未封板时在每日 2 小时、FDP 上限、7 日工时、休息期约束内受理并重算结论；
     * 已封板后只登记迟到事件，不推翻终态结论；同键重放返回既有状态。
     */
    public DelayResponse reportDelay(DelayRequest request) {
        DuplicateKeyException lastDuplicate = null;
        for (int attempt = 0; attempt < MAX_IDEMPOTENT_RETRIES; attempt++) {
            try {
                return transactionTemplate.execute(status -> doReportDelay(request));
            } catch (DuplicateKeyException ex) {
                // 并发同 requestId：先提交者受理，回滚后重试走重放。
                lastDuplicate = ex;
            }
        }
        throw lastDuplicate;
    }

    private DelayResponse doReportDelay(DelayRequest request) {
        Instant now = rules.now();

        DelayEventPo existingEvent = repository.findDelayByRequestId(request.requestId());
        if (existingEvent != null) {
            DutyRecordPo current = mustFindDuty(existingEvent.getDutyRecordId());
            return toDelayResponse(current, existingEvent, true);
        }

        DutyRecordPo duty = repository.findDutyByRequestId(request.dutyRequestId());
        if (duty == null) {
            throw new NotFoundException("值勤记录不存在: " + request.dutyRequestId());
        }
        CheckBatchPo batch = repository.findBatchById(duty.getBatchId());

        if (batch.isSealed()) {
            // 封板终态：补交延误仅记为迟到事件，结论与成员状态不变。
            DelayEventPo lateEvent = newDelayEvent(request, duty, true, now);
            repository.insertDelay(lateEvent);
            return toDelayResponse(duty, lateEvent, false);
        }

        int prospectiveTotal = duty.getTotalExtensionMinutes() + request.extensionMinutes();
        Evaluation evaluation = evaluate(duty.getMemberCode(), duty.getFlightNo(),
                duty.getReportTimeUtc(), duty.getPlannedEndUtc(), prospectiveTotal, duty.getId());
        if (!evaluation.legal()) {
            throw new ConflictException("延误延长违反合规约束: " + String.join(",", evaluation.reasons()));
        }

        DelayEventPo event = newDelayEvent(request, duty, false, now);
        repository.insertDelay(event);

        duty.setTotalExtensionMinutes(prospectiveTotal);
        duty.setDutyEndUtc(duty.getPlannedEndUtc().plus(Duration.ofMinutes(prospectiveTotal)));
        duty.setResult(LegalityResult.LEGAL);
        duty.setReasons(writeReasons(List.of()));
        duty.setAccepted(true);
        repository.updateDutyAfterExtension(duty);

        // 成员状态与整组结论保持一致：刷新所在批次结论。
        List<DutyRecordPo> batchDuties = repository.findDutiesByBatchId(batch.getId());
        repository.updateBatchOverall(batch.getId(), resolveOverall(batchDuties));

        return toDelayResponse(duty, event, false);
    }

    /** 封板终态；重复封板幂等成功。 */
    public SealResponse seal(String batchId) {
        return transactionTemplate.execute(status -> doSeal(batchId));
    }

    private SealResponse doSeal(String batchId) {
        CheckBatchPo batch = repository.findBatchByBatchId(batchId);
        if (batch == null) {
            throw new NotFoundException("校验批次不存在: " + batchId);
        }
        if (batch.isSealed()) {
            return new SealResponse(batch.getBatchId(), batch.getOverallResult(), true,
                    batch.getFinalizedAt(), true);
        }
        Instant finalizedAt = rules.now();
        repository.sealBatch(batchId, finalizedAt);
        return new SealResponse(batch.getBatchId(), batch.getOverallResult(), true, finalizedAt, false);
    }

    // ---------------------------------------------------------------------
    // 规则评估
    // ---------------------------------------------------------------------

    /**
     * 对单个候选值勤安排做四项规则评估。
     *
     * @param prospectiveExtension 该 FDP 拟累计的延误延长分钟数（初次提交为 0）
     * @param excludeDutyId        重算既有记录时排除自身，避免重复统计；初次提交传 null
     */
    private Evaluation evaluate(String memberCode, String flightNo, Instant reportTime, Instant plannedEnd,
                                int prospectiveExtension, Long excludeDutyId) {
        if (!repository.existsCrewMember(memberCode)) {
            throw new NotFoundException("机组成员不存在: " + memberCode);
        }
        FlightPo flight = repository.findFlight(flightNo);
        if (flight == null) {
            throw new NotFoundException("航班不存在: " + flightNo);
        }
        AirportPo airport = repository.findAirport(flight.departureAirportCode());
        if (airport == null) {
            throw new NotFoundException("起飞机场不存在: " + flight.departureAirportCode());
        }

        List<String> reasons = new ArrayList<>();
        ZoneId departureZone = ZoneId.of(airport.timeZoneId());
        int tierLimit = rules.tierLimitMinutes(reportTime, departureZone);

        long plannedFdpMinutes = Duration.between(reportTime, plannedEnd).toMinutes();
        long totalFdpMinutes = plannedFdpMinutes + prospectiveExtension;
        if (totalFdpMinutes > tierLimit) {
            reasons.add(ViolationReasons.FDP_LIMIT_EXCEEDED);
        }

        // 每日（UTC，按 FDP 报到日归属）累计延长上限 2 小时；重算时剔除自身既有延长。
        LocalDate reportDay = reportTime.atOffset(ZoneOffset.UTC).toLocalDate();
        int usedDayExtension = repository.sumAcceptedExtensionForDay(memberCode, reportDay);
        if (excludeDutyId != null) {
            DutyRecordPo self = repository.findDutyById(excludeDutyId);
            if (self != null) {
                usedDayExtension -= self.getTotalExtensionMinutes();
            }
        }
        if (usedDayExtension + prospectiveExtension > LegalityRules.DAILY_EXTENSION_LIMIT_MINUTES) {
            reasons.add(ViolationReasons.DAILY_EXTENSION_EXCEEDED);
        }

        // 仅已受理（合法）值勤参与历史窗口计算；驳回记录不占时段、不计工时。
        List<DutyRecordPo> history = repository.findAcceptedDutiesByMember(memberCode).stream()
                .filter(d -> excludeDutyId == null || !d.getId().equals(excludeDutyId))
                .sorted(Comparator.comparing(DutyRecordPo::getReportTimeUtc))
                .toList();

        // 休息期：取报到时刻之前最近一段已受理值勤，间隔须连续满 10 小时。
        history.stream()
                .filter(d -> d.getReportTimeUtc().isBefore(reportTime))
                .max(Comparator.comparing(DutyRecordPo::getReportTimeUtc))
                .ifPresent(previous -> {
                    long restMinutes = Duration.between(previous.getDutyEndUtc(), reportTime).toMinutes();
                    if (restMinutes < LegalityRules.MIN_REST_MINUTES) {
                        reasons.add(ViolationReasons.REST_PERIOD_SHORT);
                    }
                });

        // 连续 7 个 UTC 日历日窗口（以候选报到日为末日），含候选本次执勤时长；恰 60 小时合法。
        LocalDate windowStart = reportDay.minusDays(6);
        long windowMinutes = history.stream()
                .filter(d -> {
                    LocalDate day = d.getReportTimeUtc().atOffset(ZoneOffset.UTC).toLocalDate();
                    return !day.isBefore(windowStart) && !day.isAfter(reportDay);
                })
                .mapToLong(d -> Duration.between(d.getReportTimeUtc(), d.getDutyEndUtc()).toMinutes())
                .sum()
                + totalFdpMinutes;
        if (windowMinutes > LegalityRules.WEEKLY_DUTY_LIMIT_MINUTES) {
            reasons.add(ViolationReasons.WEEKLY_DUTY_EXCEEDED);
        }

        return new Evaluation(tierLimit, reasons.isEmpty(), reasons);
    }

    // ---------------------------------------------------------------------
    // 组装与工具
    // ---------------------------------------------------------------------

    private void validateShape(MemberDutyRequest duty) {
        if (!duty.plannedEnd().isAfter(duty.reportTime())) {
            throw new IllegalArgumentException("plannedEnd 必须晚于 reportTime");
        }
    }

    private CheckBatchPo newBatch(Instant now) {
        CheckBatchPo po = new CheckBatchPo();
        po.setBatchId(UUID.randomUUID().toString());
        po.setOverallResult(LegalityResult.LEGAL);
        po.setSealed(false);
        po.setCreatedAt(now);
        return po;
    }

    private DelayEventPo newDelayEvent(DelayRequest request, DutyRecordPo duty, boolean late, Instant now) {
        DelayEventPo po = new DelayEventPo();
        po.setDutyRecordId(duty.getId());
        po.setRequestId(request.requestId());
        po.setExtensionMinutes(request.extensionMinutes());
        po.setLate(late);
        po.setEventDayUtc(duty.getReportTimeUtc().atOffset(ZoneOffset.UTC).toLocalDate());
        po.setNote(request.note());
        po.setCreatedAt(now);
        return po;
    }

    private String resolveOverall(List<DutyRecordPo> duties) {
        boolean anyIllegal = duties.stream().anyMatch(d -> LegalityResult.ILLEGAL.equals(d.getResult()));
        return anyIllegal ? LegalityResult.ILLEGAL : LegalityResult.LEGAL;
    }

    private DutyRecordPo mustFindDuty(Long id) {
        DutyRecordPo po = repository.findDutyById(id);
        if (po == null) {
            throw new NotFoundException("值勤记录不存在: id=" + id);
        }
        return po;
    }

    private CheckResponse toCheckResponse(CheckBatchPo batch, List<ResolvedDuty> duties) {
        List<MemberDutyResponse> items = duties.stream()
                .map(resolved -> {
                    DutyRecordPo d = resolved.po();
                    return new MemberDutyResponse(
                            d.getRequestId(),
                            d.getMemberCode(),
                            d.getFlightNo(),
                            d.getReportTimeUtc(),
                            d.getPlannedEndUtc(),
                            d.getDutyEndUtc(),
                            d.getTierLimitMinutes(),
                            d.getTotalExtensionMinutes(),
                            d.getResult(),
                            readReasons(d.getReasons()),
                            d.isAccepted(),
                            resolved.replay());
                })
                .toList();
        return new CheckResponse(batch.getBatchId(), batch.getOverallResult(), batch.isSealed(),
                batch.getFinalizedAt(), items);
    }

    private DelayResponse toDelayResponse(DutyRecordPo duty, DelayEventPo event, boolean replay) {
        return new DelayResponse(
                event.getRequestId(),
                duty.getRequestId(),
                event.getExtensionMinutes(),
                duty.getTotalExtensionMinutes(),
                duty.getDutyEndUtc(),
                event.isLate(),
                !event.isLate(),
                duty.getResult(),
                readReasons(duty.getReasons()),
                replay);
    }

    private List<String> readReasons(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("原因字段解析失败", ex);
        }
    }

    private String writeReasons(List<String> reasons) {
        try {
            return objectMapper.writeValueAsString(reasons);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("原因字段序列化失败", ex);
        }
    }

    /** 请求指纹：覆盖 requestId、机组成员、航班、报到时刻与计划结束时刻。 */
    public static String fingerprint(MemberDutyRequest duty) {
        String canonical = String.join("|",
                duty.requestId(),
                duty.memberCode(),
                duty.flightNo(),
                duty.reportTime().toString(),
                duty.plannedEnd().toString());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    /** 单次评估中间结果。 */
    private record Evaluation(int tierLimitMinutes, boolean legal, List<String> reasons) {
    }

    /** 解析后的成员结果及其是否为幂等重放。 */
    private record ResolvedDuty(DutyRecordPo po, boolean replay) {
    }
}
