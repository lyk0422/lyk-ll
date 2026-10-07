package com.example.starter.coldchain;

import com.example.starter.domain.ColdChainReading;
import com.example.starter.domain.ColdChainSegment;
import com.example.starter.dto.ColdChainSegmentInput;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 冷链证据核验器（纯函数，可复算）。
 *
 * <p>合成温度规则：闭区间 [2,8] 摄氏度，恰边界合格，任一点越界即不通过，
 * 不按平均温度掩盖异常。结构规则：1 至 5 个顺序片段，[start,end) 正长度且相邻端点
 * 完全相接；每片读数带递增序号，须含 start 与 end 两端样本，相邻样本间隔不超过 5 分钟，
 * 读数落在片段闭区间内且不晚于提交时刻。缺样本只记缺口，绝不当作零度。
 */
public final class ColdChainEvaluator {

    /** 合成温度下限（含），摄氏度。 */
    public static final BigDecimal MIN_TEMPERATURE = new BigDecimal("2");
    /** 合成温度上限（含），摄氏度。 */
    public static final BigDecimal MAX_TEMPERATURE = new BigDecimal("8");
    /** 相邻样本最大间隔（恰 5 分钟合格）。 */
    public static final Duration MAX_SAMPLE_INTERVAL = Duration.ofMinutes(5);
    /** 每版本片段数上限。 */
    public static final int MAX_SEGMENTS = 5;

    private ColdChainEvaluator() {
    }

    /** 单个越界/越时点。 */
    public record Violation(int segmentSeq, int readingSeq, Instant at,
                            BigDecimal temperature, String reason) {
    }

    /** 核验结果：缺口（结构/样本缺失）与越界点（温度/时刻越界）全为空才合格。 */
    public record Evaluation(List<String> gaps, List<Violation> violations) {

        /** 完整证据合格：无缺口且无越界点。 */
        public boolean qualified() {
            return gaps.isEmpty() && violations.isEmpty();
        }
    }

    /**
     * 校验片段结构：1 至 5 个、序号从 1 连续递增、正长度、相邻端点完全相接。
     * 返回问题列表，空列表表示结构合法。
     */
    public static List<String> validateSegmentStructure(List<ColdChainSegmentInput> segments) {
        List<String> problems = new ArrayList<>();
        if (segments == null || segments.isEmpty() || segments.size() > MAX_SEGMENTS) {
            problems.add("运输片段数量须为 1 至 " + MAX_SEGMENTS + " 个");
            return problems;
        }
        for (int i = 0; i < segments.size(); i++) {
            ColdChainSegmentInput segment = segments.get(i);
            int expectedSeq = i + 1;
            if (segment.seq() == null || segment.seq() != expectedSeq) {
                problems.add("片段序号须从 1 连续递增，第 " + expectedSeq + " 个片段序号为 "
                        + segment.seq());
                continue;
            }
            if (segment.startUtc() == null || segment.endUtc() == null) {
                problems.add("片段 " + expectedSeq + " 起止时刻必填");
                continue;
            }
            if (!segment.endUtc().isAfter(segment.startUtc())) {
                problems.add("片段 " + expectedSeq + " 必须为正长度 [start,end)");
            }
            if (i > 0) {
                ColdChainSegmentInput previous = segments.get(i - 1);
                if (previous.endUtc() != null && !previous.endUtc().equals(segment.startUtc())) {
                    problems.add("片段 " + i + " 终点与片段 " + expectedSeq + " 起点未完全相接");
                }
            }
        }
        return problems;
    }

    /**
     * 核验当前版本完整证据。
     *
     * @param segments          当前版本片段（按序号升序）
     * @param readingsBySegment 按片段序号分组的读数（缺片对应空列表）
     * @param submittedAt       提交时刻；为 null 时（查询展示）跳过“不晚于提交时刻”检查
     */
    public static Evaluation evaluate(List<ColdChainSegment> segments,
                                      Map<Integer, List<ColdChainReading>> readingsBySegment,
                                      Instant submittedAt) {
        List<String> gaps = new ArrayList<>();
        List<Violation> violations = new ArrayList<>();
        for (ColdChainSegment segment : segments) {
            int seq = segment.getSegmentSeq();
            List<ColdChainReading> readings = readingsBySegment
                    .getOrDefault(seq, List.of()).stream()
                    .sorted(Comparator.comparingInt(ColdChainReading::getReadingSeq))
                    .toList();
            if (readings.isEmpty()) {
                // 缺片：记缺口，不补零度样本。
                gaps.add("片段 " + seq + " 缺少温度读数");
                continue;
            }
            checkDuplicateSeqs(seq, readings, gaps);
            checkEndpoints(seq, segment, readings, gaps);
            for (ColdChainReading reading : readings) {
                checkPoint(seq, segment, reading, submittedAt, violations);
            }
            checkIntervals(seq, readings, gaps);
        }
        return new Evaluation(List.copyOf(gaps), List.copyOf(violations));
    }

    /** 缺序号/序号重复：读数序号必须递增唯一。 */
    private static void checkDuplicateSeqs(int segmentSeq, List<ColdChainReading> readings,
                                           List<String> gaps) {
        for (int i = 1; i < readings.size(); i++) {
            if (readings.get(i).getReadingSeq() == readings.get(i - 1).getReadingSeq()) {
                gaps.add("片段 " + segmentSeq + " 读数序号重复: "
                        + readings.get(i).getReadingSeq());
            }
        }
    }

    /** 端点样本：首样本须恰为片段 start，末样本须恰为片段 end。 */
    private static void checkEndpoints(int segmentSeq, ColdChainSegment segment,
                                       List<ColdChainReading> readings, List<String> gaps) {
        if (!readings.get(0).getReadAt().equals(segment.getStartUtc())) {
            gaps.add("片段 " + segmentSeq + " 缺少起点（" + segment.getStartUtc() + "）样本");
        }
        if (!readings.get(readings.size() - 1).getReadAt().equals(segment.getEndUtc())) {
            gaps.add("片段 " + segmentSeq + " 缺少终点（" + segment.getEndUtc() + "）样本");
        }
    }

    /** 单点检查：温度闭区间 [2,8] 恰边界合格；时刻落在片段闭区间且不晚于提交时刻。 */
    private static void checkPoint(int segmentSeq, ColdChainSegment segment,
                                   ColdChainReading reading, Instant submittedAt,
                                   List<Violation> violations) {
        if (reading.getTemperature().compareTo(MIN_TEMPERATURE) < 0
                || reading.getTemperature().compareTo(MAX_TEMPERATURE) > 0) {
            violations.add(new Violation(segmentSeq, reading.getReadingSeq(), reading.getReadAt(),
                    reading.getTemperature(),
                    "温度越出合成闭区间 [" + MIN_TEMPERATURE.toPlainString() + ","
                            + MAX_TEMPERATURE.toPlainString() + "]"));
        }
        if (reading.getReadAt().isBefore(segment.getStartUtc())
                || reading.getReadAt().isAfter(segment.getEndUtc())) {
            violations.add(new Violation(segmentSeq, reading.getReadingSeq(), reading.getReadAt(),
                    reading.getTemperature(), "读数时刻超出片段闭区间"));
        }
        if (submittedAt != null && reading.getReadAt().isAfter(submittedAt)) {
            violations.add(new Violation(segmentSeq, reading.getReadingSeq(), reading.getReadAt(),
                    reading.getTemperature(), "读数时刻晚于提交时刻"));
        }
    }

    /** 相邻样本：时刻须随序号递增，间隔不超过 5 分钟（恰 5 分钟合格）。 */
    private static void checkIntervals(int segmentSeq, List<ColdChainReading> readings,
                                       List<String> gaps) {
        for (int i = 1; i < readings.size(); i++) {
            Instant previous = readings.get(i - 1).getReadAt();
            Instant current = readings.get(i).getReadAt();
            if (!current.isAfter(previous)) {
                gaps.add("片段 " + segmentSeq + " 读数时刻未随序号递增（序号 "
                        + readings.get(i).getReadingSeq() + "）");
                continue;
            }
            if (Duration.between(previous, current).compareTo(MAX_SAMPLE_INTERVAL) > 0) {
                gaps.add("片段 " + segmentSeq + " 相邻读数（序号 "
                        + readings.get(i - 1).getReadingSeq() + " 与 "
                        + readings.get(i).getReadingSeq() + "）间隔超过 5 分钟");
            }
        }
    }
}
