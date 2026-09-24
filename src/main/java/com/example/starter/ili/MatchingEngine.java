package com.example.starter.ili;

import com.example.starter.ili.model.AlignmentOutcome;
import com.example.starter.ili.model.Grade;
import com.example.starter.ili.model.MatchResult;
import com.example.starter.ili.model.ReadingInput;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 缺陷对齐引擎：按里程容差 ±0.5 m 匹配旧新读数并按深度年增长率分级。
 * 纯内存计算，不依赖数据库，便于单元测试。
 */
@Component
public class MatchingEngine {

    /** 里程容差，单位米；里程差恰好等于 0.5 m 视为匹配成功。 */
    public static final BigDecimal TOLERANCE_M = new BigDecimal("0.5");

    /** 年增长率分级阈值：达到或超过 5%/年 为增长。 */
    public static final BigDecimal GROWING_THRESHOLD = new BigDecimal("5");

    /** 年增长率分级阈值：达到或超过 15%/年 为严重。 */
    public static final BigDecimal SEVERE_THRESHOLD = new BigDecimal("15");

    /** 年折算天数（365.25 天/年）。 */
    private static final BigDecimal DAYS_PER_YEAR = new BigDecimal("365.25");

    /**
     * 对齐两个回合的缺陷读数。
     * 候选匹配对按里程差升序贪心分配，保证一对多取里程差最小者，
     * 且同一读数不会同时出现在两个匹配对中。
     *
     * @param oldReadings    旧回合读数
     * @param newReadings    新回合读数
     * @param oldInspectedAt 旧回合检测日期
     * @param newInspectedAt 新回合检测日期（必须晚于旧回合）
     * @return 对齐结果
     */
    public AlignmentOutcome align(List<ReadingInput> oldReadings, List<ReadingInput> newReadings,
                                  LocalDate oldInspectedAt, LocalDate newInspectedAt) {
        long days = ChronoUnit.DAYS.between(oldInspectedAt, newInspectedAt);
        if (days <= 0) {
            throw new IllegalArgumentException("新回合检测日期必须晚于旧回合检测日期");
        }
        BigDecimal years = BigDecimal.valueOf(days).divide(DAYS_PER_YEAR, 6, RoundingMode.HALF_UP);

        // 候选对：里程差 ≤ 0.5 m（含边界）
        record Candidate(int oldIdx, int newIdx, BigDecimal distance) {
        }
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < oldReadings.size(); i++) {
            for (int j = 0; j < newReadings.size(); j++) {
                BigDecimal distance = oldReadings.get(i).stationM()
                        .subtract(newReadings.get(j).stationM()).abs();
                if (distance.compareTo(TOLERANCE_M) <= 0) {
                    candidates.add(new Candidate(i, j, distance));
                }
            }
        }
        // 里程差最小者优先；并列时按读数键字典序保证结果确定
        candidates.sort(Comparator.comparing(Candidate::distance)
                .thenComparing(c -> oldReadings.get(c.oldIdx()).readingKey())
                .thenComparing(c -> newReadings.get(c.newIdx()).readingKey()));

        Set<Integer> usedOld = new HashSet<>();
        Set<Integer> usedNew = new HashSet<>();
        List<MatchResult> matches = new ArrayList<>();
        for (Candidate c : candidates) {
            if (usedOld.contains(c.oldIdx()) || usedNew.contains(c.newIdx())) {
                continue;
            }
            usedOld.add(c.oldIdx());
            usedNew.add(c.newIdx());
            ReadingInput oldR = oldReadings.get(c.oldIdx());
            ReadingInput newR = newReadings.get(c.newIdx());
            BigDecimal rate = newR.depthPct().subtract(oldR.depthPct())
                    .divide(years, 4, RoundingMode.HALF_UP);
            matches.add(new MatchResult(oldR.readingKey(), newR.readingKey(),
                    c.distance(), rate, gradeOf(rate)));
        }
        matches.sort(Comparator.comparing(MatchResult::oldKey));

        List<String> newFindings = new ArrayList<>();
        for (int j = 0; j < newReadings.size(); j++) {
            if (!usedNew.contains(j)) {
                newFindings.add(newReadings.get(j).readingKey());
            }
        }
        newFindings.sort(String::compareTo);

        List<String> disappeared = new ArrayList<>();
        for (int i = 0; i < oldReadings.size(); i++) {
            if (!usedOld.contains(i)) {
                disappeared.add(oldReadings.get(i).readingKey());
            }
        }
        disappeared.sort(String::compareTo);

        return new AlignmentOutcome(matches, newFindings, disappeared);
    }

    /**
     * 按年增长率分级：&lt;5% 稳定，[5%,15%) 增长，≥15% 严重。
     */
    public Grade gradeOf(BigDecimal growthRatePct) {
        if (growthRatePct.compareTo(SEVERE_THRESHOLD) >= 0) {
            return Grade.SEVERE;
        }
        if (growthRatePct.compareTo(GROWING_THRESHOLD) >= 0) {
            return Grade.GROWING;
        }
        return Grade.STABLE;
    }
}
