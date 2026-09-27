package com.example.starter.slope;

import com.example.starter.slope.SlopeRepository.PolicyVersionRow;
import com.example.starter.slope.SlopeRepository.RainfallHourRow;
import com.example.starter.slope.SlopeRepository.ZoneRow;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 雨量联动阈值解析器：在同一事务快照内解析指定 Asia/Shanghai 时刻实际生效的阈值。
 *
 * <p>规则：</p>
 * <ul>
 *   <li>策略取 effective_at 不晚于评价时刻的最新版本（恰等即生效）；无生效版本时沿用测区静态阈值（旧入口）。</li>
 *   <li>雨量取评价时刻之前24个“完整”UTC小时：评价时刻换算到UTC并截到整点 h，取 [h-24, h) 的24个桶；
 *       任一桶缺失即 dataSufficient=false，缺失绝不按零累计。</li>
 *   <li>累计雨量恰达门槛即启用折减，实际阈值＝基础阈值×折减系数；恰等阈值仍按“严格超过才预警”判定。</li>
 * </ul>
 *
 * 本类无状态，时间换算是纯函数；SQL 读取由调用方在同一事务内完成，保证同一快照。
 */
@org.springframework.stereotype.Component
public class RainThresholdResolver {

    /** 业务时间时区：边坡监测约定 Asia/Shanghai（1991年后无夏令时，偏移恒为+8）。 */
    static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    static final ZoneOffset UTC = ZoneOffset.UTC;

    /** 评价所读取的完整小时数。 */
    static final int RAIN_WINDOW_HOURS = 24;

    /** 一次解析的结果；policy 为 null 表示该时刻无生效策略，沿用静态阈值。 */
    public record Result(
            LocalDateTime at,
            PolicyVersionRow policy,
            boolean dataSufficient,
            boolean reductionApplied,
            Double rainSum24h,
            List<LocalDateTime> missingHoursUtc,
            Map<LocalDateTime, RainfallHourRow> rainByHour) {

        public boolean policyActive() {
            return policy != null;
        }

        /** 实际黄色小时速率阈值；无生效策略时返回测区静态值。 */
        public double effectiveYellowHourlyRate(ZoneRow zone) {
            double base = policy == null ? zone.warnHourlyRate() : policy.yellowHourlyRate();
            return reductionApplied ? base * policy.reductionFactor() : base;
        }

        /** 实际红色日速率阈值；无生效策略时返回测区静态值。 */
        public double effectiveRedDailyRate(ZoneRow zone) {
            double base = policy == null ? zone.alarmDailyRate() : policy.redDailyRate();
            return reductionApplied ? base * policy.reductionFactor() : base;
        }

        /** 实际复工安全线；雨量折减对全部速率阈值一致生效。 */
        public double effectiveSafetyHourlyRate(ZoneRow zone) {
            double factor = reductionApplied ? policy.reductionFactor() : 1.0;
            return zone.safetyHourlyRate() * factor;
        }
    }

    /**
     * 在给定数据访问与快照内解析时刻 at 的有效阈值。
     *
     * @param zone       测区行（提供静态阈值与复工安全线）
     * @param at         评价时刻（Asia/Shanghai 墙钟）
     * @param repository 数据访问（调用方保证处于同一事务）
     */
    public Result resolve(ZoneRow zone, LocalDateTime at, SlopeRepository repository) {
        PolicyVersionRow policy = repository.findEffectivePolicyAt(zone.id(), at).orElse(null);
        LocalDateTime floorUtc = toUtcFloor(at);
        Map<LocalDateTime, RainfallHourRow> present = new LinkedHashMap<>();
        List<LocalDateTime> missing = new ArrayList<>();
        double sum = 0.0;
        for (int i = 1; i <= RAIN_WINDOW_HOURS; i++) {
            LocalDateTime bucket = floorUtc.minusHours(i);
            var row = repository.findRainfallHour(zone.id(), bucket);
            if (row.isPresent()) {
                present.put(bucket, row.get());
                sum += row.get().rainMm();
            } else {
                missing.add(bucket);
            }
        }
        boolean sufficient = missing.isEmpty();
        boolean reduction = false;
        if (policy != null && sufficient) {
            // 恰达门槛即启用折减。
            reduction = sum >= policy.rainGateMm();
        }
        return new Result(at, policy, sufficient, reduction, sufficient ? sum : null,
                List.copyOf(missing), Map.copyOf(present));
    }

    /** Asia/Shanghai 墙钟时刻换算到 UTC 并截断到整点桶。 */
    public static LocalDateTime toUtcFloor(LocalDateTime shanghaiWallTime) {
        return shanghaiWallTime.atZone(SHANGHAI)
                .withZoneSameInstant(UTC)
                .toLocalDateTime()
                .withMinute(0).withSecond(0).withNano(0);
    }

    /**
     * 判断新版本相对上一版本是否“变严”：基础速率阈值降低、雨量门槛降低（更早启用折减）
     * 或折减系数变小，任一成立即判变严（多维度一松一紧按保守原则判变严）。
     */
    public static boolean isTightened(PolicyVersionRow prev, double yellow, double red,
                                      double gate, double factor) {
        return yellow < prev.yellowHourlyRate() || red < prev.redDailyRate()
                || gate < prev.rainGateMm() || factor < prev.reductionFactor();
    }
}
