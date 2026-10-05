package com.example.starter.epidemic.service;

import com.example.starter.epidemic.api.ClusterEventResponse;
import com.example.starter.epidemic.api.CloseClusterRequest;
import com.example.starter.epidemic.api.ConcludeInvestigationRequest;
import com.example.starter.epidemic.api.CreateInvestigationRequest;
import com.example.starter.epidemic.api.InvestigationResponse;
import com.example.starter.epidemic.api.TimelineEntryResponse;
import com.example.starter.epidemic.domain.CardPo;
import com.example.starter.epidemic.domain.ClusterEventPo;
import com.example.starter.epidemic.domain.ClusterStatus;
import com.example.starter.epidemic.domain.Conclusion;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * 聚集性分析服务：报告卡按合成暴露场所库自动归并，72 小时（UTC）内 3 例及以上触发聚集事件。
 *
 * <p>关键规则：</p>
 * <ul>
 *   <li>触发：同一场所 72 小时窗口内未归并有效卡达 3 例即生成事件，恰 3 例触发；
 *       事件记录首末例时刻与涉及卡号，后续新卡归入未关闭事件并推进末例时刻。</li>
 *   <li>协查单：事件生成时自动推送相关机构（涉及卡片的报告机构）；
 *       同一事件同时刻只允许一张进行中协查单（数据库唯一约束裁决，并发唯一成功）。</li>
 *   <li>幂等：协查单按 requestId 幂等，指纹覆盖 requestId 与全部参数；
 *       同键同参重放返回首次结果，同键异参 409 且不产生新协查单。</li>
 *   <li>结论回写：协查结论与事件状态推进在同一事务内完成。</li>
 *   <li>关闭为终态：重复关闭幂等成功；关闭后迟到的报告卡只记迟到事件，可另行归并成新事件。</li>
 *   <li>时间线与协查轨迹由基础数据复算，重复查询稳定。</li>
 * </ul>
 */
@Service
public class ClusterService {

    /** 协查单幂等场景。 */
    private static final String SCOPE_INVESTIGATION = "INVESTIGATION";
    /** 聚集判定窗口：72 小时（UTC）。 */
    private static final Duration WINDOW = Duration.ofHours(72);
    /** 触发阈值：恰 3 例即生成聚集事件。 */
    private static final int THRESHOLD = 3;

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

    // ---------------- 报告卡归并（提交事务内调用） ----------------

    /**
     * 新卡提交后按暴露场所归并：归入未关闭事件，或达阈值生成新事件并自动推送协查单。
     * 必须在报告卡提交事务内调用，与卡片落库同生共死。
     */
    public void onCardSubmitted(CardPo card) {
        String siteCode = card.getExposureSiteCode();
        if (siteCode == null) {
            return;
        }
        Instant now = card.getCreatedAt();

        var open = clusterRepository.findOpenEventForUpdate(siteCode);
        if (open.isPresent()) {
            ClusterEventPo event = open.get();
            clusterRepository.insertEventCard(event.getId(), card.getCardNo(), now);
            Instant lastCaseAt = event.getLastCaseAt().isAfter(now) ? event.getLastCaseAt() : now;
            clusterRepository.attachCardToEvent(event.getId(), lastCaseAt, event.getCardCount() + 1);
            return;
        }

        // 事件已关闭：迟到的报告卡只记迟到事件，仍可另行归并成新事件。
        clusterRepository.findLatestClosedEventNo(siteCode).ifPresent(closedEventNo ->
                clusterRepository.insertLateRecord(siteCode, card.getCardNo(), closedEventNo, now));

        List<ClusterRepository.WindowCard> window =
                clusterRepository.findWindowCards(siteCode, now.minus(WINDOW));
        if (window.size() < THRESHOLD) {
            return;
        }

        ClusterEventPo event = new ClusterEventPo();
        event.setEventNo(newEventNo());
        event.setSiteCode(siteCode);
        event.setStatus(ClusterStatus.OPEN);
        event.setFirstCaseAt(window.get(0).createdAt());
        event.setLastCaseAt(window.get(window.size() - 1).createdAt());
        event.setCardCount(window.size());
        event.setCreatedAt(now);

        long eventId;
        try {
            eventId = clusterRepository.insertEvent(event);
        } catch (DuplicateKeyException e) {
            // 并发归并：同场所事件已由另一事务生成（唯一约束裁决），本卡归入该事件。
            ClusterEventPo winner = clusterRepository.findOpenEventForUpdate(siteCode)
                    .orElseThrow(() -> new BusinessRuleException("聚集事件状态冲突，请重试"));
            clusterRepository.insertEventCard(winner.getId(), card.getCardNo(), now);
            Instant lastCaseAt = winner.getLastCaseAt().isAfter(now) ? winner.getLastCaseAt() : now;
            clusterRepository.attachCardToEvent(winner.getId(), lastCaseAt, winner.getCardCount() + 1);
            return;
        }
        for (ClusterRepository.WindowCard windowCard : window) {
            clusterRepository.insertEventCard(eventId, windowCard.cardNo(), windowCard.createdAt());
        }

        // 聚集事件生成协查单，推送相关机构（涉及卡片的报告机构）。
        List<String> targets = window.stream()
                .map(ClusterRepository.WindowCard::institutionCode)
                .distinct().sorted().toList();
        insertInvestigation(event.getEventNo(), "auto-" + event.getEventNo(),
                "聚集事件自动生成协查单，推送相关机构", "system", targets, now);
    }

    // ---------------- 协查单 ----------------

    @Transactional
    public InvestigationResponse createInvestigation(String eventNo, CreateInvestigationRequest request) {
        String fingerprint = fingerprinter.fingerprint(new InvestigationFingerprint(eventNo, request));
        var existing = cardRepository.findIdempotency(request.requestId(), SCOPE_INVESTIGATION);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), fingerprint);
        }

        ClusterEventPo event = clusterRepository.findEventByNoForUpdate(eventNo)
                .orElseThrow(() -> new NotFoundException("聚集事件不存在: " + eventNo));
        if (event.getStatus() == ClusterStatus.CLOSED) {
            throw new BusinessRuleException("聚集事件已关闭（终态），不能再生成协查单");
        }
        if (clusterRepository.findOpenInvestigation(eventNo).isPresent()) {
            throw new BusinessRuleException("同一聚集事件同时刻只允许一张进行中协查单");
        }

        List<String> targets = resolveTargets(event, request.institutionCodes());
        Instant now = clock.now();
        long id;
        try {
            id = insertInvestigation(eventNo, request.requestId(), request.note(), "manual",
                    targets, now);
        } catch (DuplicateKeyException e) {
            // 并发生成：唯一约束裁决只有一张进行中协查单成功。
            throw new BusinessRuleException("同一聚集事件已存在进行中协查单（并发唯一成功），本次请求未生效");
        }

        InvestigationResponse response = toResponse(clusterRepository.findInvestigation(id).orElseThrow());
        saveIdempotency(request.requestId(), fingerprint, eventNo, response, now);
        return response;
    }

    @Transactional
    public InvestigationResponse concludeInvestigation(long id, ConcludeInvestigationRequest request) {
        InvestigationPo investigation = clusterRepository.findInvestigationForUpdate(id)
                .orElseThrow(() -> new NotFoundException("协查单不存在: " + id));
        if (investigation.getStatus() == InvestigationStatus.CONCLUDED) {
            throw new BusinessRuleException("协查单已结案，不可重复结案");
        }

        Instant now = clock.now();
        Conclusion conclusion = Conclusion.valueOf(request.conclusion());
        int rows = clusterRepository.concludeInvestigation(id, conclusion, request.operator(),
                request.note(), now);
        if (rows == 0) {
            throw new BusinessRuleException("协查单已被并发结案");
        }

        // 协查结论回写聚集事件状态，与事件推进在同一事务内完成。
        ClusterEventPo event = clusterRepository.findEventByNoForUpdate(investigation.getEventNo())
                .orElseThrow(() -> new IllegalStateException("协查单缺少所属聚集事件"));
        int eventRows = clusterRepository.updateEventStatus(event.getId(),
                conclusion == Conclusion.CONFIRMED ? ClusterStatus.CONFIRMED : ClusterStatus.EXCLUDED);
        if (eventRows == 0) {
            throw new BusinessRuleException("聚集事件已关闭，协查结论无法回写");
        }
        return toResponse(clusterRepository.findInvestigation(id).orElseThrow());
    }

    // ---------------- 事件关闭（终态） ----------------

    @Transactional
    public ClusterEventResponse closeEvent(String eventNo, CloseClusterRequest request) {
        ClusterEventPo event = clusterRepository.findEventByNoForUpdate(eventNo)
                .orElseThrow(() -> new NotFoundException("聚集事件不存在: " + eventNo));
        if (event.getStatus() == ClusterStatus.CLOSED) {
            // 关闭为终态：重复关闭返回幂等成功。
            return toResponse(event);
        }
        if (clusterRepository.findOpenInvestigation(eventNo).isPresent()) {
            throw new BusinessRuleException("存在进行中协查单，须先结案再关闭聚集事件");
        }
        clusterRepository.closeEvent(event.getId(), clock.now());
        return toResponse(clusterRepository.findEventByNo(eventNo).orElseThrow());
    }

    // ---------------- 查询（复算，重复查询稳定） ----------------

    @Transactional(readOnly = true)
    public ClusterEventResponse getEvent(String eventNo) {
        return toResponse(clusterRepository.findEventByNo(eventNo)
                .orElseThrow(() -> new NotFoundException("聚集事件不存在: " + eventNo)));
    }

    @Transactional(readOnly = true)
    public List<InvestigationResponse> getEventInvestigations(String eventNo) {
        if (clusterRepository.findEventByNo(eventNo).isEmpty()) {
            throw new NotFoundException("聚集事件不存在: " + eventNo);
        }
        return clusterRepository.findInvestigationsByEvent(eventNo).stream()
                .map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public InvestigationResponse getInvestigation(long id) {
        return toResponse(clusterRepository.findInvestigation(id)
                .orElseThrow(() -> new NotFoundException("协查单不存在: " + id)));
    }

    /** 事件时间线：由事件、涉及卡片与协查单基础数据复算，排序确定，重复查询稳定。 */
    @Transactional(readOnly = true)
    public List<TimelineEntryResponse> getTimeline(String eventNo) {
        ClusterEventPo event = clusterRepository.findEventByNo(eventNo)
                .orElseThrow(() -> new NotFoundException("聚集事件不存在: " + eventNo));

        List<TimelineEntryResponse> entries = new ArrayList<>();
        entries.add(new TimelineEntryResponse(event.getCreatedAt(), "EVENT_CREATED",
                "聚集事件生成：场所 " + event.getSiteCode() + "，触发时涉及 " + event.getCardCount()
                        + " 例报告"));
        for (ClusterRepository.CardAttachment card : clusterRepository.findEventCards(event.getId())) {
            entries.add(new TimelineEntryResponse(card.attachedAt(), "CARD_ATTACHED",
                    "报告卡归入事件：" + card.cardNo()));
        }
        for (InvestigationPo inv : clusterRepository.findInvestigationsByEvent(eventNo)) {
            entries.add(new TimelineEntryResponse(inv.getCreatedAt(), "INVESTIGATION_CREATED",
                    "协查单生成：" + inv.getId() + "，推送机构 " + String.join(",",
                            clusterRepository.findTargets(inv.getId()))));
            if (inv.getConcludedAt() != null) {
                entries.add(new TimelineEntryResponse(inv.getConcludedAt(), "INVESTIGATION_CONCLUDED",
                        "协查单结案：" + inv.getId() + "，结论 " + inv.getConclusion()
                                + "，操作人 " + inv.getOperator()));
            }
        }
        if (event.getClosedAt() != null) {
            entries.add(new TimelineEntryResponse(event.getClosedAt(), "EVENT_CLOSED",
                    "聚集事件关闭（终态）"));
        }
        entries.sort(Comparator.comparing(TimelineEntryResponse::time)
                .thenComparing(TimelineEntryResponse::type)
                .thenComparing(TimelineEntryResponse::detail));
        return entries;
    }

    // ---------------- 内部辅助 ----------------

    /** 落库一张进行中协查单并写入推送机构，返回协查单号。 */
    private long insertInvestigation(String eventNo, String requestId, String note, String creator,
                                     List<String> targets, Instant now) {
        InvestigationPo po = new InvestigationPo();
        po.setEventNo(eventNo);
        po.setRequestId(requestId);
        po.setStatus(InvestigationStatus.OPEN);
        po.setNote(note);
        po.setCreator(creator);
        po.setCreatedAt(now);
        long id = clusterRepository.insertInvestigation(po);
        for (String target : targets) {
            clusterRepository.insertTarget(id, target);
        }
        return id;
    }

    /** 解析推送机构：手工指定（校验存在）或默认取事件涉及卡片的报告机构。 */
    private List<String> resolveTargets(ClusterEventPo event, List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            List<String> defaults = new ArrayList<>(new LinkedHashSet<>(
                    clusterRepository.findEventCardNos(event.getId()).stream()
                            .map(cardNo -> cardRepository.findCard(cardNo)
                                    .orElseThrow(() -> new IllegalStateException("事件涉及卡片不存在: " + cardNo))
                                    .getInstitutionCode())
                            .toList()));
            defaults.sort(String::compareTo);
            return defaults;
        }
        List<String> targets = new ArrayList<>(new LinkedHashSet<>(requested));
        for (String code : targets) {
            if (cardRepository.findInstitution(code).isEmpty()) {
                throw new BusinessRuleException("医疗机构不存在: " + code);
            }
        }
        targets.sort(String::compareTo);
        return targets;
    }

    /** 解析报告卡暴露场所：显式编码优先（校验存在），否则按现住址关键词自动匹配。 */
    public String resolveExposureSite(String requestedSiteCode, String address) {
        if (requestedSiteCode != null && !requestedSiteCode.isBlank()) {
            return cardRepository.findExposureSite(requestedSiteCode)
                    .map(ExposureSitePo::code)
                    .orElseThrow(() -> new BusinessRuleException("暴露场所不存在: " + requestedSiteCode));
        }
        if (address == null) {
            return null;
        }
        return cardRepository.findAllExposureSites().stream()
                .filter(site -> address.contains(site.addressKeyword()))
                .map(ExposureSitePo::code)
                .findFirst()
                .orElse(null);
    }

    private InvestigationResponse replayOrConflict(IdempotencyPo record, String fingerprint) {
        if (!record.fingerprint().equals(fingerprint)) {
            throw new IdempotencyConflictException(
                    "requestId=" + record.requestId() + " 已用于不同参数的请求（409 冲突），本次请求被拒绝且不产生新协查单");
        }
        return fingerprinter.fromJson(record.responseJson(), InvestigationResponse.class);
    }

    private void saveIdempotency(String requestId, String fingerprint, String eventNo,
                                 InvestigationResponse response, Instant now) {
        try {
            cardRepository.insertIdempotency(new IdempotencyPo(requestId, SCOPE_INVESTIGATION,
                    fingerprint, eventNo, null, fingerprinter.toJson(response), now));
        } catch (DuplicateKeyException e) {
            // 并发同键请求已由另一事务落库：以库中首次结果为准。
            var saved = cardRepository.findIdempotency(requestId, SCOPE_INVESTIGATION);
            if (saved.isPresent() && saved.get().fingerprint().equals(fingerprint)) {
                return;
            }
            throw new IdempotencyConflictException("requestId=" + requestId + " 并发请求参数不一致（409 冲突）");
        }
    }

    private ClusterEventResponse toResponse(ClusterEventPo event) {
        String siteName = cardRepository.findExposureSite(event.getSiteCode())
                .map(ExposureSitePo::name).orElse(event.getSiteCode());
        return new ClusterEventResponse(
                event.getEventNo(), event.getSiteCode(), siteName, event.getStatus(),
                event.getFirstCaseAt(), event.getLastCaseAt(), event.getCardCount(),
                clusterRepository.findEventCardNos(event.getId()),
                event.getCreatedAt(), event.getClosedAt());
    }

    private InvestigationResponse toResponse(InvestigationPo po) {
        return new InvestigationResponse(
                po.getId(), po.getEventNo(), po.getRequestId(), po.getStatus(), po.getConclusion(),
                po.getNote(), po.getCreator(), po.getOperator(),
                clusterRepository.findTargets(po.getId()), po.getCreatedAt(), po.getConcludedAt());
    }

    private String newEventNo() {
        return "E" + Long.toHexString(clock.now().toEpochMilli())
                + UUID.randomUUID().toString().substring(0, 8);
    }

    /** 协查单幂等指纹输入：覆盖 requestId 与全部参数（含路径中的事件编号）。 */
    private record InvestigationFingerprint(String eventNo, CreateInvestigationRequest request) {
    }
}
