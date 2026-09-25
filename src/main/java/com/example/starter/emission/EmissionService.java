package com.example.starter.emission;

import com.example.starter.api.dto.HourEvaluationResponse;
import com.example.starter.api.dto.ReadingBatchRequest;
import com.example.starter.api.dto.ReadingIngestResult;
import java.time.LocalDateTime;

/**
 * 排放读数接入与小时评估：读数按“测点＋采样时刻”去重，乱序按采样时刻归位；
 * 小时均值超标即触发降掺指令，恰等于标准限值判达标。
 */
public interface EmissionService {

    ReadingIngestResult ingest(Long planId, ReadingBatchRequest request);

    HourEvaluationResponse evaluate(Long planId, LocalDateTime hourStart);
}
