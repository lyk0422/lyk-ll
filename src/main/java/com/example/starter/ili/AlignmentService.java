package com.example.starter.ili;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

/**
 * 缺陷对齐与分级纯逻辑：按里程容差 ±0.5 m（含边界）贪心 对齐旧新读数，
 * 一对多取里程差最小者，并按深度增长率（%/年）分级。
 */
@Service
public class AlignmentService {

    /** 匹配容差，单位米；里程差恰好等于 0.5 m 视为匹配成功。 */
    public static final BigDecimal TOLERANCE_M = new BigDecimal("0.5");

    /** 每年天数基准，用于把两回合日期间隔折算为年。 */
    private static final BigDecimal DAYS_PER_YEAR = new BigDecimal("365.25");

    /** 增长分级阈值，单位 %/年。 */
    private static final BigDecimal GROWING_THRESHOLD = new BigDecimal("5");

    /** 严重分级阈值，单位 %/年。 */
    private static final BigDecimal SEVERE_THRESHOLD = new BigDecimal("15");

    /**
     * 对齐用读数输入。
     *
     * @param defectRef    检测方缺陷编号
     * @param absPositionM 缺陷绝对里程，单位米
     * @param depthPct     缺陷深度，占壁厚百分比
     */
    public record ReadingInput(String defectRef, BigDecimal absPositionM, BigDecimal depthPct) {
    }

    /**
     * 一个匹配对：oldIndex/newIndex 为输入清单下标。
     *
     * @param oldIndex             上一回合读数下标
     * @param newIndex             本回合读数下标
     * @param distanceM            里程差绝对值，单位米
     * @param growthRatePctPerYear 深度增长率，单位 %/年，保留 4 位小数
     * @param grade                分级结果
     */
    public record AlignedPair(int oldIndex, int newIndex, BigDecimal distanceM,
            BigDecimal growthRatePctPerYear, Grade grade) {
    }

    /**
     * 对齐结果。
     *
     * @param pairs              匹配对清单，按里程差升序贪心产生
     * @param newFindingIndexes  未匹配的本回合读数下标（新发现）
     * @param disappearedIndexes 未匹配的上一回合读数下标（已消失）
     */
    public record AlignmentResult(List<AlignedPair> pairs,
            List<Integer> newFindingIndexes,
            List<Integer> disappearedIndexes) {
    }

    /**
     * 对齐两个回合的缺陷读数并分级。
     *
     * @param oldReadings 上一回合读数
     * @param newReadings 本回合读数
     * @param runOldDate  上一回合检测日期
     * @param runNewDate  本回合检测日期，必须晚于上一回合
     */
    public AlignmentResult align(List<ReadingInput> oldReadings, List<ReadingInput> newReadings,
            LocalDate runOldDate, LocalDate runNewDate) {
        long days = ChronoUnit.DAYS.between(runOldDate, runNewDate);
        if (days <= 0) {
            throw new IllegalArgumentException("本回合检测日期必须晚于上一回合检测日期");
        }

        record Candidate(int oldIndex, int newIndex, BigDecimal distance) {
        }
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < oldReadings.size(); i++) {
            for (int j = 0; j < newReadings.size(); j++) {
                BigDecimal distance = oldReadings.get(i).absPositionM()
                        .subtract(newReadings.get(j).absPositionM()).abs();
                if (distance.compareTo(TOLERANCE_M) <= 0) {
                    candidates.add(new Candidate(i, j, distance));
                }
            }
        }
        // 里程差最小者优先；并列时按下标确定次序，保证结果可重现。
        candidates.sort(Comparator.comparing(Candidate::distance)
                .thenComparing(Candidate::oldIndex)
                .thenComparing(Candidate::newIndex));

        Set<Integer> usedOld = new HashSet<>();
        Set<Integer> usedNew = new HashSet<>();
        List<AlignedPair> pairs = new ArrayList<>();
        for (Candidate c : candidates) {
            if (usedOld.contains(c.oldIndex()) || usedNew.contains(c.newIndex())) {
                continue;
            }
            usedOld.add(c.oldIndex());
            usedNew.add(c.newIndex());
            ReadingInput oldR = oldReadings.get(c.oldIndex());
            ReadingInput newR = newReadings.get(c.newIndex());
            BigDecimal depthDiff = newR.depthPct().subtract(oldR.depthPct());
            BigDecimal rate = depthDiff.multiply(DAYS_PER_YEAR)
                    .divide(BigDecimal.valueOf(days), 4, RoundingMode.HALF_UP);
            pairs.add(new AlignedPair(c.oldIndex(), c.newIndex(),
                    c.distance().setScale(3, RoundingMode.HALF_UP), rate, gradeOf(depthDiff, days)));
        }

        List<Integer> newFindings = new ArrayList<>();
        for (int j = 0; j < newReadings.size(); j++) {
            if (!usedNew.contains(j)) {
                newFindings.add(j);
            }
        }
        List<Integer> disappeared = new ArrayList<>();
        for (int i = 0; i < oldReadings.size(); i++) {
            if (!usedOld.contains(i)) {
                disappeared.add(i);
            }
        }
        return new AlignmentResult(pairs, newFindings, disappeared);
    }

    /**
     * 按精确口径分级：增长率 &gt;= 15%/年为严重，&gt;= 5%/年为增长，其余（含负值）为稳定。
     * 用 depthDiff * 365.25 与 threshold * days 直接比较，避免除法舍入影响边界判定。
     */
    private Grade gradeOf(BigDecimal depthDiff, long days) {
        BigDecimal annualized = depthDiff.multiply(DAYS_PER_YEAR);
        BigDecimal dayCount = BigDecimal.valueOf(days);
        if (annualized.compareTo(SEVERE_THRESHOLD.multiply(dayCount)) >= 0) {
            return Grade.SEVERE;
        }
        if (annualized.compareTo(GROWING_THRESHOLD.multiply(dayCount)) >= 0) {
            return Grade.GROWING;
        }
        return Grade.STABLE;
    }
}
