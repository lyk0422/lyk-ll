package com.example.starter.epidemic.service;

import com.example.starter.epidemic.api.ClusterCloseResponse;
import com.example.starter.epidemic.api.ClusterEventCardView;
import com.example.starter.epidemic.api.ClusterEventResponse;
import com.example.starter.epidemic.api.ClusterEventSummary;
import com.example.starter.epidemic.api.ConcludeInvestigationRequest;
import com.example.starter.epidemic.api.CreateInvestigationRequest;
import com.example.starter.epidemic.api.InvestigationResponse;
import com.example.starter.epidemic.domain.ArrivalType;
import com.example.starter.epidemic.domain.ClusterEventCardPo;
import com.example.starter.epidemic.domain.ClusterEventPo;
import com.example.starter.epidemic.domain.ClusterStatus;
import com.example.starter.epidemic.domain.ExposureSitePo;
import com.example.starter.epidemic.domain.IdempotencyPo;
import com.example.starter.epidemic.domain.InvestigationPo;
import com.example.starter.epidemic.domain.InvestigationStatus;
import com.example.starter.epidemic.repository.CardRepository;
import com.example.starter.epidemic.repository.ClusterRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 聚集性疫情分析服务：以报告卡为输入，按合成暴露场所库自动归并。
 *
 * <p>关键规则：</p>
 * <ul>
 *   <li>归并：报告卡现住址包含场所关键词即归入该场所；同一场所 72 小时（UTC）内
 *       3 例及以上报告生成聚集事件，恰 3 例即触发，事件记录首末例时刻与涉及卡号。</li>
 *   <li>协查单：按 requestId 幂等（指纹覆盖 requestId 与全部参数），同键同参重放首响，
 *       同键异参 409 且不产生新协查单；同一事件同一时刻仅一张进行中协查单，
 *       由 {@code open_investigation_id} 条件 UPDATE 裁决，并发生成唯一成功。</li>
 *   <li>结论回写：协查结论回写事件状态，与事件推进在同一事务内完成。</li>
 *   <li>关闭为终态：重复关闭幂等成功；关闭后迟到的报告卡只记迟到事件，
 *       可另行归并成新事件。</li>
 *   <li>时间线与协查轨迹为只读查询，稳定排序，重复查询结果一致。</li>
 * </ul>
 */
@Service
public class ClusterService {

    /** 聚集判定窗口：72 小时（UTC）。 */
    static final Duration WINDOW = Duration.ofHours(72);
    /** 聚集触发阈值：恰 3 例即触发。 */
    static final int THRESHOLD = 3;

    private static final String SCOPE_INVESTIGATION = "INVESTIGATION";

    private final ClusterRepository clusterRepository;
    private final CardRepository cardRepository;
    private final ClockService clock;
    private final Fingerprinter fingerprinter;

    public ClusterService(ClusterRepository clusterRepository, CardRepository cardRepository,
                          ClockService clock, Fingerprinter fingerprinter) {
        this.clusterRepository = clusterRepository;
        this.cardRepository = cardRepository;
        this.clock = clock;
        this.fingerprinter = fingerprinter;
    }

    // ---------------- 报告卡归并（在初报事务内调用） ----------------

    /**
     * 报告卡提交后按暴露场所库自动归并；地址不匹配任何场所时不产生归并记录。
     * 同场所归并通过场所行锁串行化，保证并发报告只生成一个聚集事件。
     */
    public void onCardSubmitted(String cardNo, String address, Instant reportedAt) {
        if (address == null || address.isBlank()) {
            return;
        }
        ExposureSitePo site = matchSite(address);
        if (site == null) {
            return;
        }
        clusterRepository.lockSite(site.code());
        clusterRepository.insertCardExposure(cardNo, site.code(), reportedAt);

        var active = clusterRepository.findActiveEvent(site.code());
        if (active.isPresent() && withinWindow(active.get().getFirstCaseAt(), reportedAt)) {
            ClusterEventPo event = active.get();
            clusterRepository.insertEventCard(event.getId(), cardNo, reportedAt, ArrivalType.NORMAL);
            if (reportedAt.isAfter(event.getLastCaseAt())) {
                clusterRepository.updateEventLastCase(event.getId(), reportedAt);
            }
            return;
        }

        // 事件已关闭：迟到的报告卡只记迟到事件，不影响终态。
        var closed = clusterRepository.findLatestClosedEvent(site.code());
        if (closed.isPresent() && withinWindow(closed.get().getFirstCaseAt(), reportedAt)) {
            clusterRepository.insertEventCard(closed.get().getId(), cardNo, reportedAt, ArrivalType.LATE);
        }

        // 窗口内未正常归并的报告卡（含迟到卡）可另行归并成新事件，恰 3 例即触发。
        var candidates = clusterRepository.findUnassignedExposures(
                site.code(), reportedAt.minus(WINDOW), reportedAt);
        if (candidates.size() >= THRESHOLD) {
            ClusterEventPo event = new ClusterEventPo();
            event.setSiteCode(site.code());
            event.setStatus(ClusterStatus.OPEN);
            event.setFirstCaseAt(candidates.get(0).reportedAt());
            event.setLastCaseAt(candidates.get(candidates.size() - 1).reportedAt());
            event.setCreatedAt(reportedAt);
            long eventId = clusterRepository.insertEvent(event);
            for (ClusterEventCardPo candidate : candidates) {
                clusterRepository.insertEventCard(eventId, candidate.cardNo(),
                        candidate.reportedAt(), ArrivalType.NORMAL);
            }
        }
    }

    // ---------------- 协查单 ----------------

    @Transactional
    public InvestigationResponse createInvestigation(long eventId, CreateInvestigationRequest request) {
        String fingerprint = fingerprinter.fingerprint(
                new InvestigationKey(eventId, request.requestId(), request.institutionCode(), request.note()));
        var existing = cardRepository.findIdempotency(request.requestId(), SCOPE_INVESTIGATION);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), fingerprint);
        }

        ClusterEventPo event = clusterRepository.findEventForUpdate(eventId)
                .orElseThrow(() -> new NotFoundException("聚集事件不存在: " + eventId));
        if (event.getStatus() == ClusterStatus.CLOSED) {
            throw new BusinessRuleException("聚集事件已关闭（终态），不可生成协查单");
        }
        if (cardRepository.findInstitution(request.institutionCode()).isEmpty()) {
            throw new BusinessRuleException("医疗机构不存在: " + request.institutionCode());
        }

        Instant now = clock.now();
        InvestigationPo po = new InvestigationPo();
        po.setEventId(eventId);
        po.setRequestId(request.requestId());
        po.setInstitutionCode(request.institutionCode());
        po.setNote(request.note());
        po.setStatus(InvestigationStatus.IN_PROGRESS);
        po.setCreatedAt(now);

        long id;
        try {
            id = clusterRepository.insertInvestigation(po);
        } catch (DuplicateKeyException e) {
            // 并发同 requestId：以库中首次结果为准。
            var saved = cardRepository.findIdempotency(request.requestId(), SCOPE_INVESTIGATION);
            if (saved.isPresent()) {
                return replayOrConflict(saved.get(), fingerprint);
            }
            throw new IdempotencyConflictException(
                    "requestId=" + request.requestId() + " 并发请求冲突（409），本次请求被拒绝");
        }

        // 同一事件同一时刻仅一张进行中协查单：条件 UPDATE 裁决，并发生成唯一成功；
        // 落败方抛业务异常回滚，不产生新协查单。
        int rows = clusterRepository.occupyOpenInvestigation(eventId, id);
        if (rows == 0) {
            throw new BusinessRuleException("同一聚集事件已存在进行中协查单，本次生成被拒绝");
        }

        InvestigationResponse response = toResponse(
                clusterRepository.findInvestigationForUpdate(id).orElseThrow());
        saveIdempotency(request.requestId(), fingerprint, eventId, response, now);
        return response;
    }

    @Transactional
    public InvestigationResponse concludeInvestigation(long investigationId,
                                                       ConcludeInvestigationRequest request) {
        InvestigationPo investigation = clusterRepository.findInvestigationForUpdate(investigationId)
                .orElseThrow(() -> new NotFoundException("协查单不存在: " + investigationId));
        if (investigation.getStatus() == InvestigationStatus.CONCLUDED) {
            return toResponse(investigation);
        }

        Instant now = clock.now();
        int rows = clusterRepository.concludeInvestigation(investigationId, request.conclusion(), now);
        if (rows == 0) {
            throw new BusinessRuleException("协查单已被并发办结");
        }
        // 结论回写与事件推进在同一事务内：任一步失败整体回滚。
        ClusterStatus result = ClusterStatus.valueOf(request.result());
        int written = clusterRepository.writeBackConclusion(
                investigation.getEventId(), investigationId, result, request.conclusion());
        if (written == 0) {
            throw new BusinessRuleException("聚集事件已关闭或协查单位置异常，结论回写失败");
        }
        return toResponse(clusterRepository.findInvestigationForUpdate(investigationId).orElseThrow());
    }

    // ---------------- 事件关闭（终态） ----------------

    @Transactional
    public ClusterCloseResponse closeEvent(long eventId) {
        ClusterEventPo event = clusterRepository.findEventForUpdate(eventId)
                .orElseThrow(() -> new NotFoundException("聚集事件不存在: " + eventId));
        if (event.getStatus() == ClusterStatus.CLOSED) {
            return new ClusterCloseResponse(eventId, ClusterStatus.CLOSED, "事件已关闭，重复关闭幂等成功");
        }
        int rows = clusterRepository.closeEvent(eventId, clock.now());
        if (rows == 0) {
            // 并发关闭：以库中终态为准，幂等成功。
            return new ClusterCloseResponse(eventId, ClusterStatus.CLOSED, "事件已关闭，重复关闭幂等成功");
        }
        return new ClusterCloseResponse(eventId, ClusterStatus.CLOSED, "聚集事件已关闭（终态）");
    }

    // ---------------- 查询（时间线与协查轨迹可复算，重复查询稳定） ----------------

    @Transactional(readOnly = true)
    public ClusterEventResponse getEvent(long eventId) {
        ClusterEventPo event = clusterRepository.findEvent(eventId)
                .orElseThrow(() -> new NotFoundException("聚集事件不存在: " + eventId));
        List<ClusterEventCardView> cards = clusterRepository.findEventCards(eventId).stream()
                .map(c -> new ClusterEventCardView(c.cardNo(), c.reportedAt(), c.arrival().name()))
                .toList();
        return new ClusterEventResponse(event.getId(), event.getSiteCode(), event.getStatus(),
                event.getFirstCaseAt(), event.getLastCaseAt(), cards, event.getConclusion(),
                event.getOpenInvestigationId(), event.getCreatedAt(), event.getClosedAt());
    }

    @Transactional(readOnly = true)
    public List<ClusterEventSummary> listEvents() {
        return clusterRepository.findAllEvents().stream()
                .map(e -> new ClusterEventSummary(e.getId(), e.getSiteCode(), e.getStatus(),
                        e.getFirstCaseAt(), e.getLastCaseAt(),
                        clusterRepository.countEventCards(e.getId()),
                        e.getCreatedAt(), e.getClosedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<InvestigationResponse> getInvestigations(long eventId) {
        if (clusterRepository.findEvent(eventId).isEmpty()) {
            throw new NotFoundException("聚集事件不存在: " + eventId);
        }
        return clusterRepository.findInvestigationsByEvent(eventId).stream()
                .map(this::toResponse)
                .toList();
    }

    // ---------------- 内部辅助 ----------------

    /** 场所匹配：现住址包含关键词即归入；多场所命中时按场所编码序取第一个，保证稳定。 */
    private ExposureSitePo matchSite(String address) {
        return clusterRepository.findAllSites().stream()
                .filter(site -> address.contains(site.keyword()))
                .findFirst()
                .orElse(null);
    }

    /** 窗口判定（含端点）：报告时刻不晚于首例时刻＋72 小时。 */
    private boolean withinWindow(Instant firstCaseAt, Instant reportedAt) {
        return !reportedAt.isBefore(firstCaseAt) && !reportedAt.isAfter(firstCaseAt.plus(WINDOW));
    }

    private InvestigationResponse replayOrConflict(IdempotencyPo record, String fingerprint) {
        if (!record.fingerprint().equals(fingerprint)) {
            throw new IdempotencyConflictException(
                    "requestId=" + record.requestId() + " 已用于不同参数的请求（409 冲突），本次请求被拒绝且不产生新协查单");
        }
        return fingerprinter.fromJson(record.responseJson(), InvestigationResponse.class);
    }

    private void saveIdempotency(String requestId, String fingerprint, long eventId,
                                 InvestigationResponse response, Instant now) {
        try {
            cardRepository.insertIdempotency(new IdempotencyPo(requestId, SCOPE_INVESTIGATION,
                    fingerprint, String.valueOf(eventId), null,
                    fingerprinter.toJson(response), now));
        } catch (DuplicateKeyException e) {
            // 并发同键请求已由另一事务落库：以库中首次结果为准。
            var saved = cardRepository.findIdempotency(requestId, SCOPE_INVESTIGATION);
            if (saved.isPresent() && saved.get().fingerprint().equals(fingerprint)) {
                return;
            }
            throw new IdempotencyConflictException("requestId=" + requestId + " 并发请求参数不一致（409 冲突）");
        }
    }

    private InvestigationResponse toResponse(InvestigationPo po) {
        return new InvestigationResponse(po.getId(), po.getEventId(), po.getInstitutionCode(),
                po.getStatus(), po.getNote(), po.getConclusion(),
                po.getCreatedAt(), po.getConcludedAt());
    }

    /** 协查单幂等指纹输入：覆盖 requestId 与全部参数（含路径中的事件编号）。 */
    private record InvestigationKey(long eventId, String requestId, String institutionCode, String note) {
    }
}
