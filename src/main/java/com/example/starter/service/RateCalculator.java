package com.example.starter.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 速率曲线复算引擎（纯函数，无副作用，可独立单测）。
 *
 * <p>读数乱序上报时先按时刻升序归位；同一时刻重复读数已由数据库唯一约束去重。
 * 小时/日速率均取“窗口起点时刻的读数 → 目标时刻读数”的位移差按满窗口时长折算：
 * 小时速率单位 毫米/小时，日速率单位 毫米/日。窗口起点缺少读数（跨度不足完整窗口）时
 * 该速率为 null，不对短间隔数据做外推，避免 1 小时数据被放大成日速率误报。</p>
 *
 * <p>阈值一律严格比较：速率恰等预警值/报警值判正常；复工安全线须严格低于。</p>
 */
public final class RateCalculator {

    /** 速率小数位数，折算是中间值，比较仍走 compareTo 精确语义。 */
    private static final int SCALE = 6;

    private RateCalculator() {
    }

    /**
     * 速率曲线上的一个复算点。
     *
     * @param time        读数时刻
     * @param hourlyRate  小时速率（毫米/小时）；窗口内无更早读数时为 null
     * @param dailyRate   日速率（毫米/日）；24 小时窗口内无更早读数时为 null
     */
    public record RatePoint(LocalDateTime time, BigDecimal hourlyRate, BigDecimal dailyRate) {
    }

    /**
     * 将（可能乱序、可能重复的）读数按时刻升序归位并去重，同刻取先出现者。
     */
    public static List<Reading> sortAndDedupe(List<Reading> readings) {
        List<Reading> sorted = new ArrayList<>(readings);
        sorted.sort(Comparator.comparing(Reading::time).thenComparing(Reading::displacementMm));
        List<Reading> result = new ArrayList<>();
        LocalDateTime last = null;
        for (Reading r : sorted) {
            if (last == null || !last.equals(r.time())) {
                result.add(r);
                last = r.time();
            }
        }
        return result;
    }

    /**
     * 复算全量速率曲线（每个读数时刻一个点）。
     */
    public static List<RatePoint> curve(List<Reading> readings) {
        List<Reading> sorted = sortAndDedupe(readings);
        List<RatePoint> points = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            Reading current = sorted.get(i);
            BigDecimal hourly = windowRate(sorted, i, Duration.ofHours(1), BigDecimal.ONE);
            BigDecimal daily = windowRate(sorted, i, Duration.ofHours(24), BigDecimal.valueOf(24));
            points.add(new RatePoint(current.time(), hourly, daily));
        }
        return points;
    }

    /**
     * 复算指定时刻的小时/日速率。该时刻无读数时返回空。
     */
    public static Optional<RatePoint> pointAt(List<Reading> readings, LocalDateTime time) {
        return curve(readings).stream().filter(p -> p.time().equals(time)).findFirst();
    }

    /**
     * 预警分级。日速率严格大于报警值 → RED；否则小时速率严格大于预警值 → YELLOW；
     * 速率恰等任一阈值或窗口数据不足 → NORMAL。
     */
    public static AlarmVerdict classify(List<Reading> readings, LocalDateTime triggerTime,
                                        BigDecimal warnHourlyRate, BigDecimal alarmDailyRate) {
        RatePoint point = pointAt(readings, triggerTime).orElse(null);
        if (point == null) {
            return new AlarmVerdict(AlarmVerdict.Level.NORMAL, null, null, "触发时刻无雷达读数");
        }
        if (point.dailyRate() != null && point.dailyRate().compareTo(alarmDailyRate) > 0) {
            return new AlarmVerdict(AlarmVerdict.Level.RED, point.hourlyRate(), point.dailyRate(),
                    "日速率 " + point.dailyRate() + " 毫米/日 严格大于报警值 " + alarmDailyRate);
        }
        if (point.hourlyRate() != null && point.hourlyRate().compareTo(warnHourlyRate) > 0) {
            return new AlarmVerdict(AlarmVerdict.Level.YELLOW, point.hourlyRate(), point.dailyRate(),
                    "小时速率 " + point.hourlyRate() + " 毫米/小时 严格大于预警值 " + warnHourlyRate);
        }
        return new AlarmVerdict(AlarmVerdict.Level.NORMAL, point.hourlyRate(), point.dailyRate(),
                "速率未严格超过阈值（恰等阈值判正常）");
    }

    /**
     * 复工安全窗口校验：红色预警触发后，(triggerTime, applyTime] 内所有可复算的小时速率
     * 严格低于安全线，且申请时刻有速率点覆盖、自最后一个不安全点（无则触发时刻）起已满 24 小时。
     * 恰满 24 小时允许申请。
     *
     * @return 校验结果；eligible=true 时 safeSince 为连续安全窗口起算点
     */
    public static SafetyWindowResult evaluateSafetyWindow(List<Reading> readings,
                                                          LocalDateTime triggerTime,
                                                          LocalDateTime applyTime,
                                                          BigDecimal safetyHourlyRate) {
        if (applyTime.isBefore(triggerTime)) {
            return SafetyWindowResult.fail("复工申请时刻早于红色预警触发时刻", triggerTime);
        }
        List<RatePoint> points = curve(readings).stream()
                .filter(p -> p.time().isAfter(triggerTime) && !p.time().isAfter(applyTime))
                .filter(p -> p.hourlyRate() != null)
                .toList();
        if (points.isEmpty()) {
            return SafetyWindowResult.fail("申请时刻之前没有可复算的小时速率数据", triggerTime);
        }
        LocalDateTime lastPoint = points.get(points.size() - 1).time();
        if (lastPoint.isBefore(applyTime)) {
            return SafetyWindowResult.fail("速率数据未覆盖到申请时刻 " + applyTime, triggerTime);
        }
        LocalDateTime lastUnsafe = triggerTime;
        for (RatePoint p : points) {
            if (p.hourlyRate().compareTo(safetyHourlyRate) >= 0) {
                lastUnsafe = p.time();
            }
        }
        LocalDateTime earliest = lastUnsafe.plusHours(24);
        if (applyTime.isBefore(earliest)) {
            long remainingMinutes = Duration.between(applyTime, earliest).toMinutes();
            return SafetyWindowResult.fail("速率回落至安全线以下尚未连续满24小时，还差约 " + remainingMinutes + " 分钟",
                    lastUnsafe);
        }
        return SafetyWindowResult.pass(lastUnsafe);
    }

    /**
     * 计算下标 end 处、回看窗口 window 内的折算速率；窗口起点无覆盖（最早读数严格晚于
     * window 起点，即跨度不足一个完整窗口）时返回 null，避免短间隔数据被外推。
     *
     * @param toDailyUnit 换算系数：小时速率传 1（毫米/小时），日速率传 24（毫米/日）
     */
    private static BigDecimal windowRate(List<Reading> sorted, int end, Duration window,
                                         BigDecimal toDailyUnit) {
        Reading current = sorted.get(end);
        LocalDateTime floor = current.time().minus(window);
        Reading earliest = null;
        for (int i = end - 1; i >= 0; i--) {
            Reading r = sorted.get(i);
            if (r.time().isBefore(floor)) {
                break;
            }
            earliest = r;
        }
        // 必须有恰好落在窗口起点或更早（但仍在窗口内）的读数，即覆盖满整个窗口跨度
        if (earliest == null || earliest.time().isAfter(floor)) {
            return null;
        }
        long seconds = Duration.between(earliest.time(), current.time()).getSeconds();
        if (seconds <= 0) {
            return null;
        }
        BigDecimal hours = BigDecimal.valueOf(seconds).divide(BigDecimal.valueOf(3600L), SCALE, RoundingMode.HALF_UP);
        BigDecimal deltaMm = current.displacementMm().subtract(earliest.displacementMm());
        return deltaMm.multiply(toDailyUnit).divide(hours, SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 归位后的读数输入。
     */
    public record Reading(LocalDateTime time, BigDecimal displacementMm) {
    }

    /**
     * 预警分级结论。
     */
    public record AlarmVerdict(Level level, BigDecimal hourlyRate, BigDecimal dailyRate, String reason) {
        public enum Level {
            NORMAL,
            YELLOW,
            RED
        }
    }

    /**
     * 安全窗口结论。
     */
    public record SafetyWindowResult(boolean eligible, LocalDateTime safeSince, String reason) {

        static SafetyWindowResult pass(LocalDateTime safeSince) {
            return new SafetyWindowResult(true, safeSince, "连续24小时小时速率严格低于安全线，满足复工申请条件");
        }

        static SafetyWindowResult fail(String reason, LocalDateTime safeSince) {
            return new SafetyWindowResult(false, safeSince, reason);
        }
    }
}
