package com.example.starter.service;

import com.example.starter.dto.ReadingInput;
import com.example.starter.dto.SegmentInput;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 冷链片段核验器（纯函数，无持久化依赖）：
 * 对一次提交的完整运输证据逐项核验，收集全部缺口/越界点。
 *
 * <p>规则（合成温度模型）：
 * 1 至 5 个顺序片段，各为 [start,end) 正长度且相邻端点完全相接；
 * 每片读数序号从 1 连续递增、时刻严格递增，必须含 start 与 end 两端样本，
 * 相邻样本间隔不超过 5 分钟（恰等合格）；读数落在片段闭区间内且不晚于提交时刻；
 * 温度闭区间 [2,8] 摄氏度，恰边界合格，任一点越界即不合格，不按平均温度掩盖。
 * 缺样本只记缺口，绝不按零度或其他默认值补齐。
 */
public final class ColdChainVerifier {

    /** 片段顺序号未从 1 起连续递增（缺序号/跳号）。 */
    public static final String SEGMENT_SEQ_GAP = "SEGMENT_SEQ_GAP";
    /** 片段长度非正（start 不早于 end）。 */
    public static final String SEGMENT_NOT_POSITIVE = "SEGMENT_NOT_POSITIVE";
    /** 相邻片段端点未完全相接。 */
    public static final String SEGMENT_NOT_CONTIGUOUS = "SEGMENT_NOT_CONTIGUOUS";
    /** 读数顺序号未从 1 起连续递增（缺序号/跳号）。 */
    public static final String READING_SEQ_GAP = "READING_SEQ_GAP";
    /** 读数时刻未随序号严格递增。 */
    public static final String READING_TIME_NOT_INCREASING = "READING_TIME_NOT_INCREASING";
    /** 缺片段起点样本。 */
    public static final String MISSING_START_SAMPLE = "MISSING_START_SAMPLE";
    /** 缺片段终点样本。 */
    public static final String MISSING_END_SAMPLE = "MISSING_END_SAMPLE";
    /** 相邻样本间隔超过 5 分钟。 */
    public static final String SAMPLE_INTERVAL_EXCEEDED = "SAMPLE_INTERVAL_EXCEEDED";
    /** 读数时刻落在片段闭区间之外。 */
    public static final String READING_OUT_OF_SEGMENT = "READING_OUT_OF_SEGMENT";
    /** 读数时刻晚于提交时刻。 */
    public static final String READING_AFTER_SUBMISSION = "READING_AFTER_SUBMISSION";
    /** 温度越出 [2,8] 闭区间。 */
    public static final String TEMPERATURE_OUT_OF_RANGE = "TEMPERATURE_OUT_OF_RANGE";

    /** 合成温度合格下限（含边界，单位：摄氏度）。 */
    public static final BigDecimal TEMP_MIN = new BigDecimal("2");
    /** 合成温度合格上限（含边界，单位：摄氏度）。 */
    public static final BigDecimal TEMP_MAX = new BigDecimal("8");
    /** 相邻样本最大间隔（恰等合格）。 */
    public static final Duration MAX_SAMPLE_INTERVAL = Duration.ofMinutes(5);

    private ColdChainVerifier() {
    }

    /**
     * 核验缺口/越界点（值对象，由服务层持久化）。
     *
     * @param code       缺口代码（本类常量）
     * @param segmentSeq 相关片段顺序号，可空
     * @param readingSeq 相关读数顺序号，可空
     * @param at         相关精确时刻，可空
     * @param value      相关数值文本，可空
     * @param message    人读描述
     */
    public record Gap(String code, Integer segmentSeq, Integer readingSeq,
                      Instant at, String value, String message) {
    }

    /**
     * 核验完整证据，返回全部缺口/越界点；空列表表示证据合格可冻结。
     *
     * @param segments    1 至 5 个顺序运输片段（数量边界由请求校验保证）
     * @param submittedAt 证据提交精确时刻（UTC）
     */
    public static List<Gap> verify(List<SegmentInput> segments, Instant submittedAt) {
        List<Gap> gaps = new ArrayList<>();

        for (int i = 0; i < segments.size(); i++) {
            SegmentInput segment = segments.get(i);
            Integer seq = segment.seq();
            // 片段序号须从 1 起连续递增（缺序号/跳号即缺口）。
            if (seq == null || seq != i + 1) {
                gaps.add(new Gap(SEGMENT_SEQ_GAP, seq, null, null, String.valueOf(i + 1),
                        "片段序号应为 " + (i + 1) + "，实际为 " + seq));
            }
            // 正长度：[start,end) 要求 start 严格早于 end。
            if (!segment.start().isBefore(segment.end())) {
                gaps.add(new Gap(SEGMENT_NOT_POSITIVE, seq, null, segment.start(), null,
                        "片段 " + seq + " 长度非正：start=" + segment.start() + " 不早于 end=" + segment.end()));
            }
            // 相邻片段端点完全相接：前片 end 恰等于本片刻度 start。
            if (i > 0 && !segments.get(i - 1).end().equals(segment.start())) {
                gaps.add(new Gap(SEGMENT_NOT_CONTIGUOUS, seq, null, segment.start(), null,
                        "片段 " + seq + " 起点 " + segment.start() + " 与前片终点 "
                                + segments.get(i - 1).end() + " 未完全相接"));
            }
            verifyReadings(segment, submittedAt, gaps);
        }
        return gaps;
    }

    private static void verifyReadings(SegmentInput segment, Instant submittedAt, List<Gap> gaps) {
        Integer segmentSeq = segment.seq();
        List<ReadingInput> readings = segment.readings();

        // 读数序号从 1 起连续递增（缺序号即缺口，不按位置猜测）。
        for (int i = 0; i < readings.size(); i++) {
            Integer seq = readings.get(i).seq();
            if (seq == null || seq != i + 1) {
                gaps.add(new Gap(READING_SEQ_GAP, segmentSeq, seq, null, String.valueOf(i + 1),
                        "片段 " + segmentSeq + " 读数序号应为 " + (i + 1) + "，实际为 " + seq));
            }
        }

        // 读数时刻随序号严格递增。
        for (int i = 1; i < readings.size(); i++) {
            Instant prev = readings.get(i - 1).at();
            Instant curr = readings.get(i).at();
            if (!curr.isAfter(prev)) {
                gaps.add(new Gap(READING_TIME_NOT_INCREASING, segmentSeq, readings.get(i).seq(), curr, null,
                        "片段 " + segmentSeq + " 读数时刻 " + curr + " 未严格晚于前一读数 " + prev));
            }
        }

        // 必须含 start 与 end 两端样本（缺端点不得通过，缺口不按零度补齐）。
        if (readings.isEmpty() || !readings.get(0).at().equals(segment.start())) {
            gaps.add(new Gap(MISSING_START_SAMPLE, segmentSeq, null, segment.start(), null,
                    "片段 " + segmentSeq + " 缺起点 " + segment.start() + " 样本"));
        }
        if (readings.isEmpty() || !readings.get(readings.size() - 1).at().equals(segment.end())) {
            gaps.add(new Gap(MISSING_END_SAMPLE, segmentSeq, null, segment.end(), null,
                    "片段 " + segmentSeq + " 缺终点 " + segment.end() + " 样本"));
        }

        // 相邻样本间隔不超过 5 分钟（恰等 5 分钟合格）。
        for (int i = 1; i < readings.size(); i++) {
            Instant prev = readings.get(i - 1).at();
            Instant curr = readings.get(i).at();
            if (curr.isAfter(prev)) {
                Duration interval = Duration.between(prev, curr);
                if (interval.compareTo(MAX_SAMPLE_INTERVAL) > 0) {
                    gaps.add(new Gap(SAMPLE_INTERVAL_EXCEEDED, segmentSeq, readings.get(i).seq(), curr,
                            String.valueOf(interval.getSeconds()),
                            "片段 " + segmentSeq + " 相邻样本间隔 " + interval.getSeconds()
                                    + " 秒超过 300 秒上限"));
                }
            }
        }

        for (ReadingInput reading : readings) {
            // 读数须落在片段闭区间 [start,end] 内。
            if (reading.at().isBefore(segment.start()) || reading.at().isAfter(segment.end())) {
                gaps.add(new Gap(READING_OUT_OF_SEGMENT, segmentSeq, reading.seq(), reading.at(), null,
                        "片段 " + segmentSeq + " 读数时刻 " + reading.at() + " 落在片段闭区间 ["
                                + segment.start() + "," + segment.end() + "] 之外"));
            }
            // 读数不得晚于提交时刻。
            if (reading.at().isAfter(submittedAt)) {
                gaps.add(new Gap(READING_AFTER_SUBMISSION, segmentSeq, reading.seq(), reading.at(), null,
                        "片段 " + segmentSeq + " 读数时刻 " + reading.at()
                                + " 晚于提交时刻 " + submittedAt));
            }
            // 温度闭区间 [2,8]，恰边界合格；任一点越界即不合格，不按平均温度掩盖。
            BigDecimal temp = reading.tempCelsius();
            if (temp.compareTo(TEMP_MIN) < 0 || temp.compareTo(TEMP_MAX) > 0) {
                gaps.add(new Gap(TEMPERATURE_OUT_OF_RANGE, segmentSeq, reading.seq(), reading.at(),
                        temp.stripTrailingZeros().toPlainString(),
                        "片段 " + segmentSeq + " 读数温度 " + temp.stripTrailingZeros().toPlainString()
                                + "℃ 越出合格闭区间 [2,8]"));
            }
        }
    }
}
