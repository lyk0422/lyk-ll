package com.example.starter.support;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;

import com.example.starter.dto.PlanOrderReceiveRequest;
import com.example.starter.dto.PlanPublishRequest;

/**
 * 结构化幂等指纹输入串：所有可变长文本按“类型:UTF-8 字节长度:原文”编码，
 * 集合显式给出元素个数，数值采用唯一十进制写法，因此即使合法文本本身含分隔符 '|'、':'
 * 或换行，也不会与其它参数组合产生歧义。集合先按业务键（时段号/机组编码）排序，
 * 使同参数不同传输顺序得到同一指纹；不同内容（多一个分隔符/少一个元素）必得到不同串。
 */
public final class Canonical {

    private Canonical() {
    }

    private static String token(String tag, String value) {
        String safe = value == null ? "" : value;
        return tag + ":s:" + safe.getBytes(StandardCharsets.UTF_8).length + ":" + safe;
    }

    private static String number(String tag, long value) {
        return tag + ":n:" + value;
    }

    /** 日前计划发布指纹输入：requestId＋风场＋日期＋期望版本＋精确时刻＋96 段机组计划。 */
    public static String planPublish(PlanPublishRequest request) {
        StringBuilder sb = new StringBuilder("PLAN_PUBLISH|v:1");
        sb.append('|').append(token("rf", request.requestId()));
        sb.append('|').append(token("farm", request.farmCode()));
        sb.append('|').append(token("date", request.planDate()));
        sb.append('|').append(number("ev", request.expectedVersion()));
        sb.append('|').append(token("op", request.opTime() == null ? "" : request.opTime().toString()));

        List<PlanPublishRequest.SegmentInput> segments = request.segments() == null
                ? List.of()
                : request.segments().stream()
                        .sorted(Comparator.comparingInt(PlanPublishRequest.SegmentInput::segmentIndex))
                        .toList();
        sb.append('|').append(number("segCount", segments.size()));
        for (PlanPublishRequest.SegmentInput seg : segments) {
            List<PlanPublishRequest.EntryInput> entries = seg.entries() == null
                    ? List.of()
                    : seg.entries().stream()
                            .sorted(Comparator.comparing(PlanPublishRequest.EntryInput::turbineCode))
                            .toList();
            sb.append('|').append(number("seg", seg.segmentIndex()));
            sb.append('|').append(number("entryCount", entries.size()));
            for (PlanPublishRequest.EntryInput e : entries) {
                sb.append('|').append(token("tb", e.turbineCode()));
                sb.append('|').append(number("kw", e.plannedPowerKw()));
            }
        }
        return sb.toString();
    }

    /** 计划绑定指令指纹输入：requestId＋风场＋目标＋日期＋时段＋期望版本＋精确时刻。 */
    public static String planOrder(PlanOrderReceiveRequest request) {
        return "PLAN_ORDER|v:1"
                + "|" + token("rf", request.requestId())
                + "|" + token("farm", request.farmCode())
                + "|" + number("target", request.targetPowerKw())
                + "|" + token("date", request.planDate())
                + "|" + number("seg", request.segmentIndex())
                + "|" + number("ev", request.expectedVersion())
                + "|" + token("op", request.opTime() == null ? "" : request.opTime().toString());
    }
}
