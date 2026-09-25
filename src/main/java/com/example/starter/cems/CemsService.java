package com.example.starter.cems;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.cems.CemsTypes.ArchiveOutletRequest;
import com.example.starter.cems.CemsTypes.ArchiveResult;
import com.example.starter.cems.CemsTypes.ConfirmOrderRequest;
import com.example.starter.cems.CemsTypes.DailyEvalRequest;
import com.example.starter.cems.CemsTypes.DailyEvalResult;
import com.example.starter.cems.CemsTypes.GapCheckRequest;
import com.example.starter.cems.CemsTypes.GapResult;
import com.example.starter.cems.CemsTypes.HourlyReadingRequest;
import com.example.starter.cems.CemsTypes.HourlyReadingResult;
import com.example.starter.cems.CemsTypes.OrderView;
import com.example.starter.cems.CemsTypes.OutletView;
import com.example.starter.cems.CemsTypes.Receipt;
import com.example.starter.cems.CemsTypes.RecomputeRequest;
import com.example.starter.cems.CemsTypes.RecomputeResult;
import com.example.starter.cems.CemsTypes.RegisterOutletRequest;
import com.example.starter.cems.CemsTypes.ReleaseRestrictionRequest;
import com.example.starter.cems.CemsTypes.ReleaseResult;

/**
 * 核心业务服务：许可登记→在线监测→超标标记→限排执行→复测解除→归档。
 * 所有多表写操作均在同一事务内完成；边界判定一律“含本值达标 / 恰满触发”。
 */
@Service
public class CemsService {

    /** 连续小时超标触发限排令的次数：恰连续 3 次触发。 */
    static final int RESTRICTION_STREAK = 3;
    /** 确认执行期限（小时）：恰满 2 小时未确认即升级。 */
    static final long CONFIRM_DEADLINE_HOURS = 2;
    /** 数据缺失督办阈值（分钟）：严格大于 120 分钟才督办，恰 120 分钟不标记。 */
    static final long GAP_THRESHOLD_MINUTES = 120;
    /** 升级时联动限产比例：限产 30%。 */
    static final BigDecimal ESCALATION_CUT_RATIO = new BigDecimal("0.30");

    private static final DateTimeFormatter ORDER_HOUR_FMT = DateTimeFormatter.ofPattern("yyyyMMddHH");

    private final CemsRepository repo;
    private final CemsClock clock;
    private final IdempotencySupport idem;

    public CemsService(CemsRepository repo, CemsClock clock, IdempotencySupport idem) {
        this.repo = repo;
        this.clock = clock;
        this.idem = idem;
    }

    // ---------------- 许可登记 ----------------

    @Transactional
    public OutletView registerOutlet(RegisterOutletRequest req) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("outletCode", req.outletCode());
        fields.put("outletName", req.outletName());
        fields.put("dailyLimit", req.dailyLimit());
        fields.put("hourlyLimit", req.hourlyLimit());
        fields.put("unit", req.unit());
        return replayable(req.requestId(), "REGISTER", fields, OutletView.class, () -> doRegister(req));
    }

    private OutletView doRegister(RegisterOutletRequest req) {
        String code = requireText(req.outletCode(), "outletCode");
        String name = requireText(req.outletName(), "outletName");
        requireNonNullNonNegative(req.dailyLimit(), "dailyLimit");
        requireNonNullNonNegative(req.hourlyLimit(), "hourlyLimit");
        if (repo.findOutlet(code) != null) {
            throw new ConflictException("排口已登记: " + code);
        }
        String unit = req.unit() == null || req.unit().isBlank() ? "mg/L" : req.unit();
        LocalDateTime now = clock.now();
        repo.insertOutlet(code, name, req.dailyLimit(), req.hourlyLimit(), unit, now);
        repo.insertEvent(code, "REGISTER", now, false,
                "dailyLimit=" + req.dailyLimit() + ",hourlyLimit=" + req.hourlyLimit());
        return toOutletView(repo.findOutlet(code));
    }

    // ---------------- 日均值评价 ----------------

    @Transactional
    public DailyEvalResult evaluateDaily(DailyEvalRequest req) {
        if (req.day() == null) {
            throw new IllegalArgumentException("day 不能为空");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("outletCode", req.outletCode());
        fields.put("day", req.day().toString());
        fields.put("avgValue", req.avgValue());
        return replayable(req.requestId(), "DAILY_EVAL", fields, DailyEvalResult.class, () -> doEvaluateDaily(req));
    }

    private DailyEvalResult doEvaluateDaily(DailyEvalRequest req) {
        String code = requireText(req.outletCode(), "outletCode");
        requireNonNullNonNegative(req.avgValue(), "avgValue");
        Map<String, Object> outlet = loadOutlet(code);
        BigDecimal dailyLimit = bd(outlet.get("daily_limit"));
        LocalDateTime dayStart = req.day().atStartOfDay();
        LocalDateTime dayEnd = dayStart.plusDays(1);
        LocalDateTime now = clock.now();

        if (isArchived(outlet)) {
            // 归档为终态：迟到监测数据只记迟到事件，不参与评价、不改变排口状态。
            repo.insertEvent(code, "LATE_READING", now, true,
                    "DAY " + req.day() + " avgValue=" + req.avgValue());
            return new DailyEvalResult(req.requestId(), code, req.day(), req.avgValue(),
                    dailyLimit, false, true, null);
        }

        repo.markMonitoring(code, now);
        boolean fresh = repo.insertDailyReading(code, dayStart, req.avgValue(), now);
        if (!fresh) {
            Map<String, Object> existingReadings = repo.findDailyReadings(code).stream()
                    .filter(r -> dayStart.equals(lt(r.get("period_start"))))
                    .findFirst().orElse(null);
            if (existingReadings != null && req.avgValue().compareTo(bd(existingReadings.get("avg_value"))) != 0) {
                throw new ConflictException("排口＋日期读数已存在且数值不同: " + code + " " + req.day());
            }
        }
        boolean exceeded = req.avgValue().compareTo(dailyLimit) > 0;
        Long markId = null;
        Map<String, Object> mark = repo.findDailyMark(code, dayStart);
        if (exceeded && mark == null) {
            markId = repo.insertMark(code, "DAILY_AVG", dayStart, dayEnd, dailyLimit, req.avgValue(),
                    0, req.requestId(), "ACTIVE", now);
            repo.insertEvent(code, "MARK", now, false,
                    "DAILY_AVG actual=" + req.avgValue() + " limit=" + dailyLimit);
        } else if (mark != null) {
            markId = ((Number) mark.get("id")).longValue();
        }
        if (fresh) {
            repo.insertEvent(code, "READING", now, false,
                    "DAY " + req.day() + " avgValue=" + req.avgValue());
        }
        return new DailyEvalResult(req.requestId(), code, req.day(), req.avgValue(),
                dailyLimit, exceeded, false, markId);
    }

    // ---------------- 小时均值上报与连续超标链 ----------------

    @Transactional
    public HourlyReadingResult reportHourly(HourlyReadingRequest req) {
        String code = requireText(req.outletCode(), "outletCode");
        requireNonNullNonNegative(req.avgValue(), "avgValue");
        if (req.hour() == null) {
            throw new IllegalArgumentException("hour 不能为空");
        }
        LocalDateTime hour = req.hour().truncatedTo(ChronoUnit.HOURS);
        Map<String, Object> outlet = loadOutlet(code);
        BigDecimal hourlyLimit = bd(outlet.get("hourly_limit"));
        LocalDateTime now = clock.now();

        if (isArchived(outlet)) {
            repo.insertEvent(code, "LATE_READING", now, true,
                    "HOUR " + hour + " avgValue=" + req.avgValue());
            return new HourlyReadingResult(code, hour, req.avgValue(), hourlyLimit,
                    false, false, true, 0, false, null, null);
        }

        repo.markMonitoring(code, now);
        boolean fresh = repo.insertHourlyReading(code, hour, req.avgValue(), now);
        if (!fresh) {
            // 排口＋时段去重：重复上报不重复评价、不重复触发限排。
            Map<String, Object> mark = repo.findHourlyMark(code, hour);
            Map<String, Object> occupying = repo.findOccupyingOrder(code);
            return new HourlyReadingResult(code, hour, req.avgValue(), hourlyLimit,
                    true, mark != null, false, mark == null ? 0 : ((Number) mark.get("chain_index")).intValue(),
                    false, occupying == null ? null : (String) occupying.get("order_no"),
                    occupying == null ? null : (String) occupying.get("status"));
        }
        repo.insertEvent(code, "READING", now, false, "HOUR " + hour + " avgValue=" + req.avgValue());

        List<ChainPoint> chain = rebuildHourlyChain(code, hourlyLimit);
        ChainPoint current = chain.stream().filter(p -> p.hour().equals(hour)).findFirst().orElseThrow();

        String orderNo = null;
        String orderStatus = null;
        boolean triggered = false;
        if (current.streak() == RESTRICTION_STREAK
                && repo.findOrderByTrigger(code, hour) == null
                && repo.findOccupyingOrder(code) == null) {
            orderNo = issueOrder(code, hour, hourlyLimit, now);
            orderStatus = "PENDING_CONFIRM";
            triggered = true;
        }
        if (triggered) {
            repo.updateOutletStatus(code, "RESTRICTED", now);
            repo.insertEvent(code, "ORDER_ISSUED", now, false,
                    "orderNo=" + orderNo + " triggerHour=" + hour);
        } else {
            Map<String, Object> occupying = repo.findOccupyingOrder(code);
            if (occupying != null) {
                orderNo = (String) occupying.get("order_no");
                orderStatus = (String) occupying.get("status");
            }
        }
        return new HourlyReadingResult(code, hour, req.avgValue(), hourlyLimit,
                false, current.exceeded(), false, current.streak(), triggered, orderNo, orderStatus);
    }

    private String issueOrder(String code, LocalDateTime triggerHour, BigDecimal hourlyLimit, LocalDateTime now) {
        String orderNo = "RO-" + code + "-" + triggerHour.format(ORDER_HOUR_FMT);
        LocalDateTime deadline = now.plusHours(CONFIRM_DEADLINE_HOURS);
        repo.insertOrder(orderNo, code, triggerHour, now, deadline, code);
        return orderNo;
    }

    /**
     * 按全部小时读数重算连续超标链并重建小时标记：恰连续 3 次（链序号=3）为触发点。
     * 相邻整点、逐时连续且均超标才累计链序号；任一合格读数或中间缺测都使链重置。
     * 已随限排令复测合格而解除（RESOLVED）的历史标记，复算后仍保持 RESOLVED。
     */
    private List<ChainPoint> rebuildHourlyChain(String code, BigDecimal hourlyLimit) {
        List<Map<String, Object>> readings = repo.findHourlyReadings(code);
        Map<String, String> priorStatus = new LinkedHashMap<>();
        for (Map<String, Object> m : repo.findHourlyMarks(code)) {
            priorStatus.put(lt(m.get("period_start")).toString(), (String) m.get("status"));
        }
        List<ChainPoint> points = new ArrayList<>();
        LocalDateTime prevHour = null;
        int streak = 0;
        for (Map<String, Object> r : readings) {
            LocalDateTime hour = lt(r.get("period_start"));
            boolean exceeded = bd(r.get("avg_value")).compareTo(hourlyLimit) > 0;
            if (exceeded) {
                streak = (prevHour != null && hour.equals(prevHour.plusHours(1)) && streak > 0)
                        ? streak + 1 : 1;
            } else {
                streak = 0;
            }
            points.add(new ChainPoint(hour, exceeded, streak));
            prevHour = hour;
        }
        repo.deleteHourlyMarks(code);
        LocalDateTime now = clock.now();
        for (ChainPoint p : points) {
            if (p.exceeded()) {
                BigDecimal value = readings.stream()
                        .filter(r -> p.hour().equals(lt(r.get("period_start"))))
                        .map(r -> bd(r.get("avg_value"))).findFirst().orElseThrow();
                String markStatus = "RESOLVED".equals(priorStatus.get(p.hour().toString()))
                        ? "RESOLVED" : "ACTIVE";
                repo.insertMark(code, "HOURLY_AVG", p.hour(), p.hour().plusHours(1),
                        hourlyLimit, value, p.streak(), null, markStatus, now);
            }
        }
        return points;
    }

    // ---------------- 连续缺失督办 ----------------

    @Transactional
    public GapResult checkGap(GapCheckRequest req) {
        String code = requireText(req.outletCode(), "outletCode");
        if (req.missingFrom() == null || req.missingTo() == null) {
            throw new IllegalArgumentException("missingFrom/missingTo 不能为空");
        }
        if (!req.missingTo().isAfter(req.missingFrom())) {
            throw new IllegalArgumentException("missingTo 必须晚于 missingFrom");
        }
        Map<String, Object> outlet = loadOutlet(code);
        if (isArchived(outlet)) {
            throw new ConflictException("排口已归档（终态），不再督办数据缺失: " + code);
        }
        long minutes = Duration.between(req.missingFrom(), req.missingTo()).toMinutes();
        boolean marked = minutes > GAP_THRESHOLD_MINUTES;
        LocalDateTime now = clock.now();
        if (marked) {
            boolean created = repo.insertGap(code, req.missingFrom(), req.missingTo(),
                    (int) minutes, "SUPERVISED", now);
            if (created) {
                repo.insertEvent(code, "GAP_SUPERVISE", now, false,
                        "gap=" + minutes + "min [" + req.missingFrom() + "," + req.missingTo() + ")");
            }
        }
        return new GapResult(code, req.missingFrom(), req.missingTo(), minutes, marked,
                marked ? now : null);
    }

    // ---------------- 限排令确认执行（基数冻结同事务） ----------------

    @Transactional
    public Receipt confirmOrder(ConfirmOrderRequest req) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("orderNo", req.orderNo());
        return replayable(req.requestId(), "CONFIRM", fields, Receipt.class, () -> doConfirm(req));
    }

    private Receipt doConfirm(ConfirmOrderRequest req) {
        String orderNo = requireText(req.orderNo(), "orderNo");
        Map<String, Object> order = loadOrder(orderNo);
        String code = (String) order.get("outlet_code");
        Map<String, Object> outlet = loadOutlet(code);
        if (isArchived(outlet)) {
            throw new ConflictException("排口已归档，不能再确认限排令: " + code);
        }
        String status = (String) order.get("status");
        LocalDateTime now = clock.now();
        return switch (status) {
            case "CLOSED" -> throw new ConflictException("限排令已关闭，不能重复确认: " + orderNo);
            case "ACTIVE" -> throw new ConflictException("限排令已确认执行: " + orderNo);
            case "ESCALATED" -> throw new ConflictException("限排令已超期升级，不能再确认: " + orderNo);
            default -> {
                LocalDateTime deadline = lt(order.get("confirm_deadline"));
                if (!now.isBefore(deadline)) {
                    throw new ConflictException("已超过确认执行期限（恰满2小时），限排令应升级: " + orderNo);
                }
                // 限排生效与排放总量冻结基数在同一 UPDATE/同一事务内完成，不存在“生效而基数未定”。
                BigDecimal frozenQty = repo.sumHourlyValuesUpTo(code, now);
                int n = repo.confirmOrder(orderNo, frozenQty, now);
                if (n == 0) {
                    throw new ConflictException("限排令状态已变化，确认未受理: " + orderNo);
                }
                repo.updateOutletStatus(code, "RESTRICTED", now);
                repo.insertEvent(code, "CONFIRM", now, false,
                        "orderNo=" + orderNo + " frozenTotalQty=" + frozenQty);
                yield new Receipt(req.requestId(), "CONFIRM_RESTRICTION", false);
            }
        };
    }

    // ---------------- 超时升级并联动限产 ----------------

    @Transactional
    public List<OrderView> escalateSweep(String outletCode) {
        Map<String, Object> outlet = loadOutlet(outletCode);
        if (isArchived(outlet)) {
            throw new ConflictException("排口已归档: " + outletCode);
        }
        List<OrderView> escalated = new ArrayList<>();
        LocalDateTime now = clock.now();
        for (Map<String, Object> order : repo.findPendingConfirmOrders(outletCode)) {
            String orderNo = (String) order.get("order_no");
            LocalDateTime deadline = lt(order.get("confirm_deadline"));
            if (!now.isBefore(deadline)) {
                BigDecimal frozenQty = repo.sumHourlyValuesUpTo(outletCode, now);
                int n = repo.escalateOrder(orderNo, frozenQty, now);
                if (n > 0) {
                    // 升级与联动限产同事务：升级提交则限产记录必然存在。
                    repo.insertProductionCut(orderNo, outletCode, ESCALATION_CUT_RATIO,
                            "限排令下达后恰满2小时未确认，联动限产30%", now);
                    repo.updateOutletStatus(outletCode, "RESTRICTED", now);
                    repo.insertEvent(outletCode, "ESCALATE", now, false,
                            "orderNo=" + orderNo + " cutRatio=" + ESCALATION_CUT_RATIO);
                    escalated.add(toOrderView(repo.findOrder(orderNo)));
                }
            }
        }
        return escalated;
    }

    // ---------------- 复测合格解除（关闭同事务） ----------------

    @Transactional
    public ReleaseResult release(ReleaseRestrictionRequest req) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("orderNo", req.orderNo());
        fields.put("retestHour", req.retestHour() == null ? null : req.retestHour().toString());
        fields.put("retestValue", req.retestValue());
        return replayable(req.requestId(), "RELEASE", fields, ReleaseResult.class, () -> doRelease(req));
    }

    private ReleaseResult doRelease(ReleaseRestrictionRequest req) {
        String orderNo = requireText(req.orderNo(), "orderNo");
        requireNonNullNonNegative(req.retestValue(), "retestValue");
        if (req.retestHour() == null) {
            throw new IllegalArgumentException("retestHour 不能为空");
        }
        Map<String, Object> order = loadOrder(orderNo);
        String code = (String) order.get("outlet_code");
        Map<String, Object> outlet = loadOutlet(code);
        if (isArchived(outlet)) {
            throw new ConflictException("排口已归档，不能解除限排令: " + code);
        }
        String status = (String) order.get("status");
        BigDecimal hourlyLimit = bd(outlet.get("hourly_limit"));
        LocalDateTime retestHour = req.retestHour().truncatedTo(ChronoUnit.HOURS);
        LocalDateTime now = clock.now();
        return switch (status) {
            case "CLOSED" -> throw new ConflictException("限排令已关闭: " + orderNo);
            case "PENDING_CONFIRM" -> throw new ConflictException("限排令尚未确认执行，不能解除: " + orderNo);
            default -> {
                // 复测合格：含本值达标；不合格不得解除，限排令状态不变。
                if (req.retestValue().compareTo(hourlyLimit) > 0) {
                    throw new ConflictException("复测小时均值 " + req.retestValue()
                            + " 超过限值 " + hourlyLimit + "，复测不合格，不能解除限排");
                }
                LocalDateTime issuedAt = lt(order.get("issued_at"));
                if (!retestHour.isAfter(issuedAt.truncatedTo(ChronoUnit.HOURS))) {
                    throw new IllegalArgumentException("复测时段必须晚于限排令下达时段");
                }
                boolean freshRetest = repo.insertHourlyReading(code, retestHour, req.retestValue(), now);
                if (!freshRetest) {
                    Map<String, Object> existing = repo.findHourlyReadings(code).stream()
                            .filter(r -> retestHour.equals(lt(r.get("period_start"))))
                            .findFirst().orElse(null);
                    if (existing != null && req.retestValue().compareTo(bd(existing.get("avg_value"))) != 0) {
                        throw new ConflictException("复测时段读数已存在且数值不同: " + retestHour);
                    }
                }
                // 解除限排与限排令关闭在同一事务：任一失败整体回滚。
                int n = repo.closeOrder(orderNo, retestHour, retestHour.plusHours(1),
                        req.retestValue(), req.requestId(), now);
                if (n == 0) {
                    throw new ConflictException("限排令状态已变化，解除未受理: " + orderNo);
                }
                repo.resolveActiveHourlyMarks(code);
                repo.updateOutletStatus(code, "MONITORING", now);
                repo.insertEvent(code, "RELEASE", now, false,
                        "orderNo=" + orderNo + " retestValue=" + req.retestValue());
                yield new ReleaseResult(req.requestId(), orderNo, code, "CLOSED", now);
            }
        };
    }

    // ---------------- 归档（终态、重复幂等） ----------------

    @Transactional
    public ArchiveResult archive(ArchiveOutletRequest req) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("outletCode", req.outletCode());
        return replayable(req.requestId(), "ARCHIVE", fields, ArchiveResult.class, () -> doArchive(req));
    }

    private ArchiveResult doArchive(ArchiveOutletRequest req) {
        String code = requireText(req.outletCode(), "outletCode");
        Map<String, Object> outlet = loadOutlet(code);
        LocalDateTime archivedAt = isArchived(outlet)
                ? lt(outlet.get("archived_at")) : clock.now();
        if (isArchived(outlet)) {
            // 归档为终态：重复归档返回幂等成功，不重复写事件、不改变状态。
            return new ArchiveResult(req.requestId(), code, false, archivedAt);
        }
        if (repo.findOccupyingOrder(code) != null) {
            throw new ConflictException("存在执行中限排令，须复测解除后方可归档: " + code);
        }
        int n = repo.archiveOutlet(code, archivedAt);
        if (n == 0) {
            throw new ConflictException("排口状态已变化，归档未受理: " + code);
        }
        repo.insertEvent(code, "ARCHIVE", archivedAt, false, "排口归档（终态）");
        return new ArchiveResult(req.requestId(), code, true, archivedAt);
    }

    // ---------------- 复算 ----------------

    @Transactional
    public RecomputeResult recompute(RecomputeRequest req) {
        String code = requireText(req.outletCode(), "outletCode");
        Map<String, Object> outlet = loadOutlet(code);
        if (isArchived(outlet)) {
            throw new ConflictException("排口已归档（终态），不能复算: " + code);
        }
        BigDecimal hourlyLimit = bd(outlet.get("hourly_limit"));
        BigDecimal dailyLimit = bd(outlet.get("daily_limit"));
        List<String> details = new ArrayList<>();

        // 1) 小时超标链标记重建，并对缺失的触发限排记录做补建（不改变既有令状态）。
        List<ChainPoint> chain = rebuildHourlyChain(code, hourlyLimit);
        int orderCreated = 0;
        for (ChainPoint p : chain) {
            if (p.streak() == RESTRICTION_STREAK && repo.findOrderByTrigger(code, p.hour()) == null) {
                if (repo.findOccupyingOrder(code) == null) {
                    String orderNo = issueOrder(code, p.hour(), hourlyLimit, clock.now());
                    orderCreated++;
                    details.add("补建限排令 " + orderNo + "（触发时段 " + p.hour() + "）");
                } else {
                    details.add("触发时段 " + p.hour() + " 存在执行中限排令，跳过补建");
                }
            }
        }

        // 2) 日均超标标记按日读数重建（保留原标记携带的 requestId）。
        Map<String, String> dailyRequestByPeriod = new LinkedHashMap<>();
        for (Map<String, Object> m : repo.findDailyMarks(code)) {
            Object rid = m.get("request_id");
            dailyRequestByPeriod.put(lt(m.get("period_start")).toString(), rid == null ? null : rid.toString());
        }
        int dailyMarkCount = 0;
        repo.deleteDailyMarks(code);
        LocalDateTime now = clock.now();
        for (Map<String, Object> r : repo.findDailyReadings(code)) {
            LocalDateTime dayStart = lt(r.get("period_start"));
            BigDecimal value = bd(r.get("avg_value"));
            if (value.compareTo(dailyLimit) > 0) {
                repo.insertMark(code, "DAILY_AVG", dayStart, dayStart.plusDays(1), dailyLimit, value,
                        0, dailyRequestByPeriod.get(dayStart.toString()), "ACTIVE", now);
                dailyMarkCount++;
            }
        }

        // 3) 缺失督办按首末读数之间的连续整点缺槽重算：仅连续缺槽严格大于 2 小时才督办。
        repo.deleteGaps(code);
        int gapCount = rebuildGaps(code, details);

        long activeOrderCount = repo.findOrders(code).stream()
                .filter(o -> !"CLOSED".equals(o.get("status"))).count();
        int hourlyMarkCount = (int) chain.stream().filter(ChainPoint::exceeded).count();
        return new RecomputeResult(code, hourlyMarkCount, dailyMarkCount, (int) activeOrderCount,
                gapCount, details);
    }

    /**
     * 依据小时读数序列找出连续缺失整点区间，缺失严格大于 2 小时（&gt;120 分钟）才重新登记督办。
     */
    private int rebuildGaps(String code, List<String> details) {
        List<Map<String, Object>> readings = repo.findHourlyReadings(code);
        if (readings.size() < 2) {
            return 0;
        }
        int gapCount = 0;
        LocalDateTime now = clock.now();
        for (int i = 1; i < readings.size(); i++) {
            LocalDateTime prev = lt(readings.get(i - 1).get("period_start"));
            LocalDateTime cur = lt(readings.get(i).get("period_start"));
            long missingHours = ChronoUnit.HOURS.between(prev, cur) - 1;
            if (missingHours > 0) {
                LocalDateTime gapStart = prev.plusHours(1);
                LocalDateTime gapEnd = cur;
                long minutes = Duration.between(gapStart, gapEnd).toMinutes();
                if (minutes > GAP_THRESHOLD_MINUTES) {
                    repo.insertGap(code, gapStart, gapEnd, (int) minutes, "SUPERVISED", now);
                    gapCount++;
                    details.add("复算缺失督办 " + minutes + " 分钟 [" + gapStart + "," + gapEnd + ")");
                }
            }
        }
        return gapCount;
    }

    // ---------------- 查询 ----------------

    @Transactional(readOnly = true)
    public OutletView getOutlet(String code) {
        return toOutletView(loadOutlet(code));
    }

    @Transactional(readOnly = true)
    public OrderView getOrder(String orderNo) {
        return toOrderView(loadOrder(orderNo));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getEvents(String code) {
        loadOutlet(code);
        return repo.findEvents(code);
    }

    // ---------------- 幂等支撑 ----------------

    /**
     * 幂等执行：指纹覆盖 requestId 与全部业务字段。
     * 同键同参返回首次结果；同键异参抛 409 且不执行动作、不改变排口状态。
     * 先插入占位行（主键串行化同键并发），业务动作成功后回写结果；动作失败则随事务回滚释放该键。
     */
    private <T> T replayable(String requestId, String requestType, Map<String, Object> fields,
                             Class<T> resultType, Supplier<T> action) {
        String rid = requireText(requestId, "requestId");
        String fingerprint = idem.fingerprint(rid, fields);
        Map<String, Object> existing = repo.findIdempotent(rid);
        if (existing != null) {
            return replayOrConflict(rid, fingerprint, existing, resultType);
        }
        while (true) {
            if (repo.insertIdempotent(rid, requestType, fingerprint, "", clock.now())) {
                T result = action.get();
                repo.updateIdempotentResult(rid, idem.toJson(result));
                return result;
            }
            // 同键并发：等待首个事务提交结果；若其回滚（占位行消失）则重新抢占。
            Map<String, Object> raced = waitForIdempotentResult(rid);
            if (raced != null) {
                return replayOrConflict(rid, fingerprint, raced, resultType);
            }
        }
    }

    private <T> T replayOrConflict(String rid, String fingerprint, Map<String, Object> row, Class<T> resultType) {
        if (!fingerprint.equals(row.get("fingerprint"))) {
            throw new ConflictException("requestId=" + rid + " 请求参数与首次调用不一致（409 幂等冲突）");
        }
        return idem.fromJson((String) row.get("result_json"), resultType);
    }

    /**
     * 并发同键后等待首个事务提交结果（真实并发协调，带超时）。
     * @return 已回写结果的幂等行；占位行消失（首个事务回滚）时返回 null 供调用方重新抢占
     */
    private Map<String, Object> waitForIdempotentResult(String rid) {
        long deadlineMillis = System.currentTimeMillis() + 15_000L;
        while (true) {
            Map<String, Object> row = repo.findIdempotent(rid);
            if (row == null) {
                return null;
            }
            if (!"".equals(row.get("result_json"))) {
                return row;
            }
            if (System.currentTimeMillis() >= deadlineMillis) {
                throw new ConflictException("requestId=" + rid + " 并发请求等待首次结果超时");
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ConflictException("并发幂等等待被中断");
            }
        }
    }

    // ---------------- 公共辅助 ----------------

    private Map<String, Object> loadOutlet(String code) {
        Map<String, Object> outlet = repo.findOutlet(code);
        if (outlet == null) {
            throw new NotFoundException("排口不存在: " + code);
        }
        return outlet;
    }

    private Map<String, Object> loadOrder(String orderNo) {
        Map<String, Object> order = repo.findOrder(orderNo);
        if (order == null) {
            throw new NotFoundException("限排令不存在: " + orderNo);
        }
        return order;
    }

    private boolean isArchived(Map<String, Object> outlet) {
        return "ARCHIVED".equals(outlet.get("status"));
    }

    private static BigDecimal bd(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof BigDecimal b ? b : new BigDecimal(value.toString());
    }

    private static LocalDateTime lt(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof Timestamp t ? t.toLocalDateTime() : LocalDateTime.parse(value.toString());
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }

    private static void requireNonNullNonNegative(BigDecimal value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        if (value.signum() < 0) {
            throw new IllegalArgumentException(field + " 不能为负数");
        }
    }

    private static OutletView toOutletView(Map<String, Object> o) {
        return new OutletView(
                (String) o.get("outlet_code"),
                (String) o.get("outlet_name"),
                (String) o.get("status"),
                bd(o.get("daily_limit")),
                bd(o.get("hourly_limit")),
                (String) o.get("limit_unit"),
                lt(o.get("created_at")),
                lt(o.get("archived_at")));
    }

    private static OrderView toOrderView(Map<String, Object> o) {
        return new OrderView(
                (String) o.get("order_no"),
                (String) o.get("outlet_code"),
                (String) o.get("status"),
                bd(o.get("frozen_total_qty")),
                lt(o.get("issued_at")),
                lt(o.get("confirm_deadline")),
                lt(o.get("confirmed_at")),
                lt(o.get("escalated_at")),
                (String) o.get("escalate_reason"),
                bd(o.get("retest_value")),
                lt(o.get("closed_at")));
    }

    /** 小时连续超标链上的一个计算点。 */
    private record ChainPoint(LocalDateTime hour, boolean exceeded, int streak) {
    }
}
