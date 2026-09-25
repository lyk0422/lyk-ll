package com.example.starter.fermentation;

import com.example.starter.fermentation.dto.CoolingRequest;
import com.example.starter.fermentation.dto.CreateBatchRequest;
import com.example.starter.fermentation.dto.DiacetylRequest;
import com.example.starter.fermentation.dto.JudgeRequest;
import com.example.starter.fermentation.dto.SegmentSpec;
import com.example.starter.fermentation.dto.StartMaturationRequest;
import com.example.starter.fermentation.dto.TemperatureRequest;
import com.example.starter.fermentation.dto.TransferRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 测试请求构造工厂与固定曲线，时间均由测试显式给出。
 */
final class TestData {

    static final Instant T0 = Instant.parse("2026-09-01T00:00:00Z");

    private TestData() {
    }

    static SegmentSpec segment(int from, int to, String temp) {
        return new SegmentSpec(from, to, new BigDecimal(temp));
    }

    /** 主酵曲线：满罐后 0~1000 小时目标 10.00℃。 */
    static List<SegmentSpec> primaryCurve() {
        return List.of(segment(0, 1000, "10.00"));
    }

    /** 降温后曲线：切换版本后 0~1000 小时目标 -1.00℃。 */
    static List<SegmentSpec> coolingCurve() {
        return List.of(segment(0, 1000, "-1.00"));
    }

    static CreateBatchRequest createBatch(String batchNo, String tankCode, Instant filledAt) {
        return new CreateBatchRequest(batchNo, tankCode, filledAt, primaryCurve());
    }

    static TemperatureRequest temp(String requestId, String batchNo, Instant at, String temp) {
        return new TemperatureRequest(requestId, batchNo, at, new BigDecimal(temp));
    }

    static DiacetylRequest diacetyl(String requestId, String batchNo, Instant at, String value) {
        return new DiacetylRequest(requestId, batchNo, at, new BigDecimal(value));
    }

    static CoolingRequest cooling(String requestId, String batchNo, Instant at) {
        return new CoolingRequest(requestId, batchNo, at, coolingCurve());
    }

    static CoolingRequest cooling(String requestId, String batchNo, Instant at,
                                  List<SegmentSpec> curve) {
        return new CoolingRequest(requestId, batchNo, at, curve);
    }

    static StartMaturationRequest maturation(Instant at) {
        return new StartMaturationRequest(at);
    }

    static JudgeRequest judge(String requestId, String batchNo, Instant at) {
        return new JudgeRequest(requestId, batchNo, at);
    }

    static TransferRequest transfer(String requestId, String batchNo, Instant at) {
        return new TransferRequest(requestId, batchNo, at);
    }
}
