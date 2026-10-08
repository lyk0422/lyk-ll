package com.example.starter.water;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 日评估：按日输出各池稳定时长与告警处置链；由告警与指令记录纯查询复算，结果可重复复算。
 */
@Service
public class AssessmentService {

    private final WaterRepository repository;
    private final Clock clock;

    public AssessmentService(WaterRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * 告警处置链中的一条处置指令。
     */
    public record OrderLink(long orderId, DisposalType type, DisposalStatus status, Instant issuedAt,
                            Instant confirmDeadline, Instant confirmedAt, Instant escalatedAt) {
    }

    /**
     * 一条告警及其处置链。
     */
    public record AlarmChain(long alarmId, AlarmLevel level, String metric, AlarmStatus status,
                             Instant createdAt, Instant resolvedAt, List<OrderLink> orders) {
    }

    /**
     * 单池日评估：stableSeconds 为当日无生效告警的累计秒数。
     */
    public record TankAssessment(long tankId, String code, String name, long stableSeconds,
                                 List<AlarmChain> alarms) {
    }

    /**
     * 日评估结果。
     */
    public record DailyAssessment(LocalDate date, List<TankAssessment> tanks) {
    }

    /**
     * 复算指定日期的日评估；当日未结束时按当前时间截断。
     */
    @Transactional(readOnly = true)
    public DailyAssessment dailyAssessment(LocalDate date) {
        ZoneId zone = clock.getZone();
        Instant dayStart = date.atStartOfDay(zone).toInstant();
        Instant dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant();
        Instant now = clock.instant();
        Instant effectiveEnd = now.isBefore(dayEnd) ? now : dayEnd;
        if (effectiveEnd.isBefore(dayStart)) {
            effectiveEnd = dayStart;
        }

        List<TankAssessment> tanks = new ArrayList<>();
        for (TankRow tank : repository.findAllTanks()) {
            List<AlarmRow> alarms = repository.findAlarmsOverlapping(tank.id(), dayStart, effectiveEnd);
            List<AlarmChain> chains = new ArrayList<>();
            for (AlarmRow alarm : alarms) {
                List<OrderLink> orders = repository.findOrdersByAlarmId(alarm.id()).stream()
                        .map(order -> new OrderLink(order.id(), order.type(), order.status(),
                                order.issuedAt(), order.confirmDeadline(),
                                order.confirmedAt(), order.escalatedAt()))
                        .toList();
                chains.add(new AlarmChain(alarm.id(), alarm.level(), alarm.metric(), alarm.status(),
                        alarm.createdAt(), alarm.resolvedAt(), orders));
            }
            long stableSeconds = stableSeconds(alarms, dayStart, effectiveEnd);
            tanks.add(new TankAssessment(tank.id(), tank.code(), tank.name(), stableSeconds, chains));
        }
        return new DailyAssessment(date, tanks);
    }

    private long stableSeconds(List<AlarmRow> alarms, Instant dayStart, Instant dayEnd) {
        // 合并全部告警生效区间与当日区间的交集，稳定时长 = 当日时长 - 告警区间并集。
        List<long[]> intervals = new ArrayList<>();
        for (AlarmRow alarm : alarms) {
            Instant from = alarm.createdAt().isBefore(dayStart) ? dayStart : alarm.createdAt();
            Instant resolved = alarm.resolvedAt() == null ? dayEnd : alarm.resolvedAt();
            Instant to = resolved.isAfter(dayEnd) ? dayEnd : resolved;
            if (to.isAfter(from)) {
                intervals.add(new long[]{from.getEpochSecond(), to.getEpochSecond()});
            }
        }
        intervals.sort(java.util.Comparator.comparingLong(interval -> interval[0]));
        long covered = 0;
        long currentStart = -1;
        long currentEnd = -1;
        for (long[] interval : intervals) {
            if (currentStart < 0) {
                currentStart = interval[0];
                currentEnd = interval[1];
            } else if (interval[0] <= currentEnd) {
                currentEnd = Math.max(currentEnd, interval[1]);
            } else {
                covered += currentEnd - currentStart;
                currentStart = interval[0];
                currentEnd = interval[1];
            }
        }
        if (currentStart >= 0) {
            covered += currentEnd - currentStart;
        }
        return Math.max(0L, Duration.between(dayStart, dayEnd).getSeconds() - covered);
    }
}
