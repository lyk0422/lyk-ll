package com.example.starter.weigh;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.casefile.CaseRecord;
import com.example.starter.casefile.CaseRepository;
import com.example.starter.common.ApiException;
import com.example.starter.common.Fingerprints;

import java.util.Optional;

/**
 * 过磅判定服务：按轴型限值计算超限率，处理幂等、去重、静态复检优先与迟到数据。
 */
@Service
public class WeighService {

    private final WeighRecordRepository weighRecords;
    private final CaseRepository cases;
    private final LateWeighEventRepository lateEvents;

    public WeighService(WeighRecordRepository weighRecords, CaseRepository cases,
            LateWeighEventRepository lateEvents) {
        this.weighRecords = weighRecords;
        this.cases = cases;
        this.lateEvents = lateEvents;
    }

    /**
     * 生成检测记录。同 requestId 同参返回首次结果，同键异参 409；
     * 同一“车牌+检测站+过磅时刻+称重方式”去重返回已有记录。
     */
    @Transactional
    public WeighRecordResponse create(WeighRecordRequest req) {
        String fingerprint = Fingerprints.of(req.requestId(), req.plateNo(), req.stationId(),
                req.weighedAt(), req.axleType(), req.weighType(), req.totalWeightKg());

        Optional<WeighRecord> existing = weighRecords.findByRequestId(req.requestId());
        if (existing.isPresent()) {
            WeighRecord found = existing.get();
            if (!found.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId 已存在且业务字段不一致");
            }
            return WeighRecordResponse.of(found, false, true);
        }

        int limitKg = weighRecords.findLimitKg(req.axleType())
                .orElseThrow(() -> ApiException.unprocessable("UNKNOWN_AXLE_TYPE", "未知轴型: " + req.axleType()));
        BigDecimal overRate = BigDecimal.valueOf(req.totalWeightKg() - limitKg)
                .divide(BigDecimal.valueOf(limitKg), 2, RoundingMode.HALF_UP);
        boolean overLimit = overRate.compareTo(BigDecimal.ZERO) > 0;

        // 静态复检优先：静态到达则取代同时刻动态记录；动态晚于静态到达则不作为判定依据
        boolean authoritative = true;
        if ("STATIC".equals(req.weighType())) {
            weighRecords.supersedeDynamicAt(req.plateNo(), req.stationId(), req.weighedAt());
        } else if (weighRecords.findStaticAt(req.plateNo(), req.stationId(), req.weighedAt()).isPresent()) {
            authoritative = false;
        }

        // 结案后迟到数据：仅登记迟到事件入证据备查，不改判已结案件
        Optional<CaseRecord> closedCase = cases.findClosedCaseFor(req.plateNo(), req.weighedAt());
        boolean lateEvidence = closedCase.isPresent();

        WeighRecord record = new WeighRecord(null, req.requestId(), fingerprint, req.plateNo(),
                req.stationId(), req.weighedAt(), req.axleType(), req.weighType(), req.totalWeightKg(),
                limitKg, overRate, overLimit, authoritative, lateEvidence);
        long id;
        try {
            id = weighRecords.insert(record);
        } catch (DuplicateKeyException e) {
            // 并发下同 requestId 或同去重键：返回已存在记录
            Optional<WeighRecord> byRequest = weighRecords.findByRequestId(req.requestId());
            if (byRequest.isPresent()) {
                WeighRecord found = byRequest.get();
                if (!found.fingerprint().equals(fingerprint)) {
                    throw ApiException.conflict("requestId 已存在且业务字段不一致");
                }
                return WeighRecordResponse.of(found, false, true);
            }
            WeighRecord dup = weighRecords.findByDedupKey(req.plateNo(), req.stationId(),
                    req.weighedAt(), req.weighType()).orElseThrow(() -> e);
            return WeighRecordResponse.of(dup, true, false);
        }

        if (lateEvidence) {
            lateEvents.insert(closedCase.get().id(), id, req.plateNo(), req.stationId(), req.weighedAt());
        }
        return WeighRecordResponse.of(
                weighRecords.findById(id).orElseThrow(), false, false);
    }

    /**
     * 按编号查询检测记录（被取代的动态记录保留可查）。
     */
    @Transactional(readOnly = true)
    public WeighRecordResponse get(long id) {
        return WeighRecordResponse.of(weighRecords.findById(id)
                .orElseThrow(() -> ApiException.notFound("检测记录不存在: " + id)), false, false);
    }
}
