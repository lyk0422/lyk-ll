package com.example.starter.cems.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.cems.CemsConstants;
import com.example.starter.cems.repo.EventRepository;
import com.example.starter.cems.repo.MarkRepository;
import com.example.starter.cems.repo.NoticeRepository;
import com.example.starter.cems.repo.OrderRepository;
import com.example.starter.cems.repo.OutfallRepository;
import com.example.starter.cems.repo.ReadingRepository;
import com.example.starter.cems.web.ConflictException;
import com.example.starter.cems.web.NotFoundException;
import com.example.starter.cems.web.UnprocessableException;
import com.example.starter.cems.web.dto.ArchiveRequest;
import com.example.starter.cems.web.dto.ConfirmRequest;
import com.example.starter.cems.web.dto.ConfirmResult;
import com.example.starter.cems.web.dto.MarkView;
import com.example.starter.cems.web.dto.MutationResponse;
import com.example.starter.cems.web.dto.NoticeView;
import com.example.starter.cems.web.dto.OrderView;
import com.example.starter.cems.web.dto.OutfallView;
import com.example.starter.cems.web.dto.ReadingRequest;
import com.example.starter.cems.web.dto.ReadingResult;
import com.example.starter.cems.web.dto.ReadingView;
import com.example.starter.cems.web.dto.RegisterRequest;
import com.example.starter.cems.web.dto.ReleaseRequest;
import com.example.starter.cems.web.dto.ReleaseResult;

/**
 * 污水排放在线监测与限排联动领域服务。
 */
@Service
public class CemsService {

    private final Clock clock;
    private final OutfallRepository outfallRepository;
    private final ReadingRepository readingRepository;
    private final MarkRepository markRepository;
    private final OrderRepository orderRepository;
    private final NoticeRepository noticeRepository;
    private final EventRepository eventRepository;
    private final IdempotencySupport idempotencySupport;
    private final MissingDetectionService missingDetectionService;

    public CemsService(Clock cemsClock,
                       OutfallRepository outfallRepository,
                       ReadingRepository readingRepository,
                       MarkRepository markRepository,
                       OrderRepository orderRepository,
                       NoticeRepository noticeRepository,
                       EventRepository eventRepository,
                       IdempotencySupport idempotencySupport,
                       MissingDetectionService missingDetectionService) {
        this.clock = cemsClock;
        this.outfallRepository = outfallRepository;
        this.readingRepository = readingRepository;
        this.markRepository = markRepository;
        this.orderRepository = orderRepository;
        this.noticeRepository = noticeRepository;
        this.eventRepository = eventRepository;
        this.idempotencySupport = idempotencySupport;
        this.missingDetectionService = missingDetectionService;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    /** 许可登记。 */
    @Transactional
    public MutationResponse<OutfallView> register(RegisterRequest request) {
        return idempotencySupport.execute(request.requestId(), CemsConstants.ACT_REGISTER,
                request.outfallCode(), request, OutfallView.class,
                () -> doRegister(request));
    }

    private OutfallView doRegister(RegisterRequest request) {
        LocalDateTime ts = now();
        OutfallView view = new OutfallView(
                request.outfallCode(),
                request.outfallName(),
                CemsConstants.OUT_REGISTERED,
                request.dailyLimit(),
                request.hourlyLimit(),
                null, null, ts, ts, false);
        outfallRepository.insert(view);
        return outfallRepository.findByCode(request.outfallCode());
    }

    /** 在线监测读数上报（含超标标记、连续 3 次超标限排、缺失督办、归档迟到判定）。 */
    @Transactional
    public MutationResponse<ReadingResult> reading(ReadingRequest request) {
        return idempotencySupport.execute(request.requestId(),
                CemsConstants.PERIOD_HOUR.equals(request.periodType())
                        ? CemsConstants.ACT_HOURLY_READING
                        : CemsConstants.ACT_DAILY_READING,
                request.outfallCode(), request, ReadingResult.class,
                () -> doReading(request));
    }

    private ReadingResult doReading(ReadingRequest request) {
        OutfallView outfall = requireOutfall(request.outfallCode());
        LocalDateTime ts = now();
        validateReadingRequest(request);

        // 归档为终态：归档后迟到监测数据只记迟到事件，不入读数表、不参与统计与联动
        if (outfall.archived()) {
            eventRepository.insertOutfallEvent(outfall.outfallCode(), "LATE",
                    "归档后迟到" + ("HOUR".equals(request.periodType()) ? "小时" : "日均")
                            + "监测数据 " + request.periodStart() + " 均值 " + request.avgValue()
                            + "，仅记录不参与统计", ts);
            ReadingView lateView = new ReadingView(null, outfall.outfallCode(),
                    request.periodType(), request.periodStart(), request.avgValue(), true, ts);
            return new ReadingResult(lateView, true, null, null, List.of());
        }

        // 按“排口＋时段”去重，重复时段由唯一索引拒绝并转成语义化 409
        try {
            readingRepository.insert(request.outfallCode(), request.periodType(),
                    request.periodStart(), request.avgValue(), request.requestId(), ts, false);
        } catch (org.springframework.dao.DuplicateKeyException dup) {
            throw new ConflictException("同一排口同时段监测数据已存在（排口＋时段去重）: "
                    + request.outfallCode() + " " + request.periodType() + " "
                    + request.periodStart());
        }

        if (CemsConstants.OUT_REGISTERED.equals(outfall.status())) {
            outfallRepository.updateStatus(outfall.outfallCode(),
                    CemsConstants.OUT_MONITORING, ts);
        }

        MarkView mark = null;
        OrderView order = null;
        List<NoticeView> notices = List.of();

        if (CemsConstants.PERIOD_HOUR.equals(request.periodType())) {
            order = maybeIssueRestriction(outfall, request.periodStart(), ts);
            notices = missingDetectionService.recompute(
                    outfall.outfallCode(), outfall.registeredAt(), ts);
        } else {
            mark = maybeCreateDailyMark(outfall, request, ts);
        }

        ReadingView readingView = readingRepository.findSlot(
                request.outfallCode(), request.periodType(), request.periodStart());
        return new ReadingResult(readingView, false, mark, order,
                notices == null ? List.of() : notices);
    }

    private void validateReadingRequest(ReadingRequest request) {
        if (!CemsConstants.PERIOD_HOUR.equals(request.periodType())
                && !CemsConstants.PERIOD_DAY.equals(request.periodType())) {
            throw new UnprocessableException("periodType 仅支持 HOUR 或 DAY: " + request.periodType());
        }
        if (CemsConstants.PERIOD_HOUR.equals(request.periodType())
                && !PeriodUtil.isHourStart(request.periodStart())) {
            throw new IllegalArgumentException("小时读数 periodStart 必须为整点");
        }
        if (CemsConstants.PERIOD_DAY.equals(request.periodType())
                && !PeriodUtil.isDayStart(request.periodStart())) {
            throw new IllegalArgumentException("日均读数 periodStart 必须为自然日零点");
        }
    }

    /**
     * 小时均值连续 3 次超标自动下达限排令：恰连续 3 次（第 3 个整点）触发；
     * 已存在执行中限排令时不重复下达。
     */
    private OrderView maybeIssueRestriction(OutfallView outfall, LocalDateTime slot,
                                            LocalDateTime ts) {
        List<ReadingView> tail = new ArrayList<>();
        LocalDateTime cursor = slot;
        for (int i = 0; i < CemsConstants.HOURLY_EXCEED_STREAK; i++) {
            ReadingView r = readingRepository.findSlot(
                    outfall.outfallCode(), CemsConstants.PERIOD_HOUR, cursor);
            if (r == null || r.avgValue().compareTo(outfall.hourlyLimit()) <= 0) {
                break;
            }
            tail.add(0, r);
            cursor = cursor.minusHours(1);
        }
        // 恰连续 3 次：尾部连续超标长度恰好为 3，且更早一个小时不超标（或已到序列起点）
        if (tail.size() != CemsConstants.HOURLY_EXCEED_STREAK) {
            return null;
        }
        if (orderRepository.findActiveByCode(outfall.outfallCode()) != null) {
            return null;
        }
        List<LocalDateTime> slots = tail.stream().map(ReadingView::periodStart).toList();
        String orderRequestId = "AUTO-" + outfall.outfallCode() + "-" + slot;
        return issueRestrictionOrder(outfall.outfallCode(), orderRequestId, slots, ts);
    }

    /**
     * 下达限排令：限排生效与排放总量冻结基数在同一事务内完成。
     * 基数冻结与限排令插入任一失败整体回滚，不会出现限排生效而基数未定。
     */
    @Transactional
    public OrderView issueRestrictionOrder(String outfallCode, String orderRequestId,
                                           List<LocalDateTime> triggerSlots, LocalDateTime ts) {
        OutfallView outfall = requireOutfall(outfallCode);
        if (orderRepository.findActiveByCode(outfallCode) != null) {
            throw new ConflictException("同一排口同时刻只允许一个执行中限排令: " + outfallCode);
        }
        // 冻结基数：下达时刻（含）全部小时均值累计的合成代理排放总量
        BigDecimal baseline = readingRepository.sumHourlyUpTo(outfallCode, ts);
        outfallRepository.freezeBaseline(outfallCode, baseline, ts, ts);

        String slotsText = triggerSlots.stream().map(LocalDateTime::toString)
                .reduce((a, b) -> a + "," + b).orElse("");
        OrderView view = new OrderView(
                null, outfallCode, orderRequestId, slotsText,
                CemsConstants.ORDER_ISSUED, ts, ts.plusHours(CemsConstants.CONFIRM_DEADLINE_HOURS),
                false, null, false, null, false,
                baseline, ts, null, null, null, null);
        orderRepository.insert(view);
        eventRepository.insertOutfallEvent(outfallCode, "RESTRICT",
                "连续3次小时均值超标，自动下达限排令 " + orderRequestId
                        + "，同事务冻结排放总量基数 " + baseline, ts);
        return orderRepository.findByRequestId(orderRequestId);
    }

    /** 日均值严格大于许可限值才生成超标标记；恰等限值判达标。 */
    private MarkView maybeCreateDailyMark(OutfallView outfall, ReadingRequest request,
                                          LocalDateTime ts) {
        if (request.avgValue().compareTo(outfall.dailyLimit()) <= 0) {
            return null;
        }
        if (markRepository.findByDay(outfall.outfallCode(), request.periodStart()) != null) {
            return null;
        }
        MarkView view = new MarkView(null, outfall.outfallCode(), request.requestId(),
                request.periodStart(), request.avgValue(), outfall.dailyLimit(),
                CemsConstants.MARK_ACTIVE, ts);
        markRepository.insert(view);
        if (!CemsConstants.OUT_RESTRICTED.equals(outfall.status())) {
            outfallRepository.updateStatus(outfall.outfallCode(),
                    CemsConstants.OUT_MARKED, ts);
        }
        return markRepository.findByDay(outfall.outfallCode(), request.periodStart());
    }

    /** 限排令确认执行（须 2 小时内确认，恰满/超过 2 小时未确认则升级并联动限产）。 */
    @Transactional
    public MutationResponse<ConfirmResult> confirm(ConfirmRequest request) {
        return idempotencySupport.execute(request.requestId(), CemsConstants.ACT_CONFIRM,
                request.outfallCode(), request, ConfirmResult.class,
                () -> doConfirm(request));
    }

    private ConfirmResult doConfirm(ConfirmRequest request) {
        LocalDateTime ts = now();
        OrderView order = requireActiveOrder(request.outfallCode());
        int rows = orderRepository.confirmIfWithinDeadline(order.id(), ts);
        if (rows == 1) {
            return new ConfirmResult(orderRepository.findByRequestId(order.requestId()), false);
        }
        // 到达截止时刻仍未确认：升级并联动限产
        int escalated = orderRepository.escalateIfDeadlineReached(order.id(), ts);
        if (escalated != 1) {
            throw new UnprocessableException("限排令当前状态不可确认: " + order.requestId());
        }
        eventRepository.insertOutfallEvent(request.outfallCode(), "ESCALATE",
                "限排令 " + order.requestId() + " 恰满2小时未确认，升级并联动限产", ts);
        return new ConfirmResult(orderRepository.findByRequestId(order.requestId()), true);
    }

    /** 复测合格解除限排（解除与限排令关闭同一事务）。 */
    @Transactional
    public MutationResponse<ReleaseResult> release(ReleaseRequest request) {
        return idempotencySupport.execute(request.requestId(), CemsConstants.ACT_RELEASE,
                request.outfallCode(), request, ReleaseResult.class,
                () -> doRelease(request));
    }

    private ReleaseResult doRelease(ReleaseRequest request) {
        LocalDateTime ts = now();
        if (!PeriodUtil.isHourStart(request.periodStart())) {
            throw new IllegalArgumentException("复测时段 periodStart 必须为整点");
        }
        OutfallView outfall = requireOutfall(request.outfallCode());
        OrderView order = requireActiveOrder(request.outfallCode());

        // 复测合格：小于等于小时限值为达标，恰等判达标；超标不得解除
        if (request.avgValue().compareTo(outfall.hourlyLimit()) > 0) {
            throw new UnprocessableException(
                    "复测小时均值 " + request.avgValue() + " 高于限值 " + outfall.hourlyLimit()
                            + "，复测不合格，不能解除限排");
        }

        // 复测读数同样纳入监测（重复时段冲突由唯一索引拒绝并转成语义化 409）
        try {
            readingRepository.insert(request.outfallCode(), CemsConstants.PERIOD_HOUR,
                    request.periodStart(), request.avgValue(), request.requestId(), ts, false);
        } catch (org.springframework.dao.DuplicateKeyException dup) {
            throw new ConflictException("复测时段监测数据已存在（排口＋时段去重）: "
                    + request.outfallCode() + " " + request.periodStart());
        }

        // 解除与限排令关闭在同一事务内完成
        int closed = orderRepository.close(order.id(), request.periodStart(),
                request.avgValue(), request.requestId(), ts);
        if (closed != 1) {
            throw new ConflictException("限排令已被并发关闭: " + order.requestId());
        }
        int resolvedMarks = markRepository.resolveActive(request.outfallCode());
        outfallRepository.backToMonitoring(request.outfallCode(), ts);
        eventRepository.insertOutfallEvent(request.outfallCode(), "RELEASE",
                "复测合格（" + request.periodStart() + " 均值 " + request.avgValue()
                        + "），限排令 " + order.requestId() + " 关闭并解除限排", ts);

        return new ReleaseResult(orderRepository.findByRequestId(order.requestId()),
                outfallRepository.findByCode(request.outfallCode()), resolvedMarks);
    }

    /** 归档（终态；重复归档返回幂等成功）。 */
    @Transactional
    public MutationResponse<OutfallView> archive(ArchiveRequest request) {
        return idempotencySupport.execute(request.requestId(), CemsConstants.ACT_ARCHIVE,
                request.outfallCode(), request, OutfallView.class,
                () -> doArchive(request));
    }

    private OutfallView doArchive(ArchiveRequest request) {
        LocalDateTime ts = now();
        OutfallView outfall = requireOutfall(request.outfallCode());
        if (!outfall.archived()) {
            outfallRepository.markArchived(request.outfallCode(), ts);
            eventRepository.insertOutfallEvent(request.outfallCode(), "ARCHIVE",
                    "排口归档，进入终态", ts);
        }
        return outfallRepository.findByCode(request.outfallCode());
    }

    /** 超时升级扫描：对全部执行中限排令，恰满 2 小时未确认即升级并联动限产。返回被升级限排令。 */
    @Transactional
    public List<OrderView> scanEscalations() {
        LocalDateTime ts = now();
        List<OrderView> escalated = new ArrayList<>();
        for (OrderView order : orderRepository.findAllOpen()) {
            if (!order.confirmed() && !order.escalated()
                    && !ts.isBefore(order.confirmDeadline())) {
                int rows = orderRepository.escalateIfDeadlineReached(order.id(), ts);
                if (rows == 1) {
                    OrderView updated = orderRepository.findByRequestId(order.requestId());
                    escalated.add(updated);
                    eventRepository.insertOutfallEvent(order.outfallCode(), "ESCALATE",
                            "限排令 " + order.requestId() + " 恰满2小时未确认，升级并联动限产", ts);
                }
            }
        }
        return escalated;
    }

    /** 查询排口。 */
    @Transactional(readOnly = true)
    public OutfallView getOutfall(String code) {
        return requireOutfall(code);
    }

    /** 查询执行中限排令。 */
    @Transactional(readOnly = true)
    public OrderView getActiveOrder(String code) {
        requireOutfall(code);
        OrderView order = orderRepository.findActiveByCode(code);
        if (order == null) {
            throw new NotFoundException("排口无执行中限排令: " + code);
        }
        return order;
    }

    /** 查询执行中限排令，不存在返回 null（不抛异常）。 */
    @Transactional(readOnly = true)
    public OrderView getActiveOrderOrNull(String code) {
        return orderRepository.findActiveByCode(code);
    }

    /** 查询缺失督办链（可复算）。 */
    @Transactional(readOnly = true)
    public List<NoticeView> getNotices(String code) {
        requireOutfall(code);
        return noticeRepository.findAllByCode(code);
    }

    /** 依据真实读数重新复算缺失督办（删除重建，不依赖历史标记）。 */
    @Transactional
    public List<NoticeView> recomputeMissing(String code) {
        OutfallView outfall = requireOutfall(code);
        return missingDetectionService.recompute(code, outfall.registeredAt(), now());
    }

    /** 查询超标标记链（按自然日正序，可复算核对）。 */
    @Transactional(readOnly = true)
    public List<MarkView> getMarks(String code) {
        requireOutfall(code);
        return markRepository.findAllByCode(code);
    }

    /** 查询限排记录链（按时间正序，可复算核对）。 */
    @Transactional(readOnly = true)
    public List<OrderView> getOrders(String code) {
        requireOutfall(code);
        return orderRepository.findAllByCode(code);
    }

    /** 查询排口事件流（限排、解除、升级、缺失督办、迟到、归档）。 */
    @Transactional(readOnly = true)
    public List<EventRepository.OutfallEventRow> getEvents(String code) {
        requireOutfall(code);
        return eventRepository.findEvents(code);
    }

    private OutfallView requireOutfall(String code) {
        OutfallView outfall = outfallRepository.findByCode(code);
        if (outfall == null) {
            throw new NotFoundException("排口不存在: " + code);
        }
        return outfall;
    }

    private OrderView requireActiveOrder(String code) {
        OrderView order = orderRepository.findActiveByCode(code);
        if (order == null) {
            throw new NotFoundException("排口无执行中限排令: " + code);
        }
        return order;
    }
}
