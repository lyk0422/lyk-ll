package com.example.starter.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

import com.example.starter.dto.PlanPublishRequest;

/**
 * 日前计划测试夹具：快速构造完整 96 段 × 机组计划，支持单段改写、缺段与重复段。
 */
final class PlanFixtures {

    static final String DATE = "2026-09-27";
    static final Instant OP_TIME = Instant.parse("2026-09-26T01:00:00Z");

    private PlanFixtures() {
    }

    /** 各段所有机组统一取给定出力（按机组编码→kW）。 */
    static PlanPublishRequest uniformPlan(String requestId, String farm, int expectedVersion,
                                          Instant opTime, String... codeKwPairs) {
        return customPlan(requestId, farm, expectedVersion, opTime, segment -> {
            List<PlanPublishRequest.EntryInput> entries = new ArrayList<>();
            for (int i = 0; i < codeKwPairs.length; i += 2) {
                entries.add(new PlanPublishRequest.EntryInput(
                        codeKwPairs[i], Integer.parseInt(codeKwPairs[i + 1])));
            }
            return entries;
        });
    }

    /** 段→机组条目的自定义构造，覆盖全部 0-95 段。 */
    static PlanPublishRequest customPlan(String requestId, String farm, int expectedVersion,
                                         Instant opTime,
                                         IntFunction<List<PlanPublishRequest.EntryInput>> perSegment) {
        List<PlanPublishRequest.SegmentInput> segments = new ArrayList<>();
        for (int i = 0; i < 96; i++) {
            segments.add(new PlanPublishRequest.SegmentInput(i, perSegment.apply(i)));
        }
        return new PlanPublishRequest(requestId, farm, DATE, expectedVersion, opTime, segments);
    }

    /** 在完整计划基础上把某段替换为给定条目（用于制造重复机组/超装机/机组不一致）。 */
    static PlanPublishRequest withOverriddenSegment(PlanPublishRequest base, int segmentIndex,
                                                    List<PlanPublishRequest.EntryInput> entries) {
        List<PlanPublishRequest.SegmentInput> segments = new ArrayList<>(base.segments());
        segments.set(segmentIndex, new PlanPublishRequest.SegmentInput(segmentIndex, entries));
        return new PlanPublishRequest(base.requestId(), base.farmCode(), base.planDate(),
                base.expectedVersion(), base.opTime(), segments);
    }

    /** 删除指定段（制造缺段，共 95 段）。 */
    static PlanPublishRequest withoutSegment(PlanPublishRequest base, int segmentIndex) {
        List<PlanPublishRequest.SegmentInput> segments = new ArrayList<>(base.segments());
        segments.remove(segmentIndex);
        return new PlanPublishRequest(base.requestId(), base.farmCode(), base.planDate(),
                base.expectedVersion(), base.opTime(), segments);
    }

    /** 把某段序号改成与另一段重复（制造重复时段）。 */
    static PlanPublishRequest withDuplicateSegmentIndex(PlanPublishRequest base, int from, int to) {
        List<PlanPublishRequest.SegmentInput> segments = new ArrayList<>();
        for (PlanPublishRequest.SegmentInput s : base.segments()) {
            if (s.segmentIndex() == from) {
                segments.add(new PlanPublishRequest.SegmentInput(to, s.entries()));
            } else {
                segments.add(s);
            }
        }
        return new PlanPublishRequest(base.requestId(), base.farmCode(), base.planDate(),
                base.expectedVersion(), base.opTime(), segments);
    }
}
