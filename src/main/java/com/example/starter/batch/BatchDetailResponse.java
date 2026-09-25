package com.example.starter.batch;

import java.util.List;

import com.example.starter.event.LateEvent;
import com.example.starter.inspection.RapidTestResponse;
import com.example.starter.inspection.SampleResponse;
import com.example.starter.recheck.Recheck;
import com.example.starter.recheck.Retention;

/**
 * 批次全链路详情：报备、抽样链、快检判定、复检、留样与迟到事件，均可复算核对。
 */
public record BatchDetailResponse(
        BatchResponse batch,
        List<SampleResponse> samples,
        List<RapidTestResponse> rapidTests,
        List<Recheck> rechecks,
        Retention retention,
        List<LateEvent> lateEvents) {
}
