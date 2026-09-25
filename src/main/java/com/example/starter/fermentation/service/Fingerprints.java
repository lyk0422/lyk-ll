package com.example.starter.fermentation.service;

import com.example.starter.fermentation.DomainRules;
import com.example.starter.fermentation.dto.CoolingRequest;
import com.example.starter.fermentation.dto.DiacetylRequest;
import com.example.starter.fermentation.dto.JudgeRequest;
import com.example.starter.fermentation.dto.SegmentSpec;
import com.example.starter.fermentation.dto.TemperatureRequest;
import com.example.starter.fermentation.dto.TransferRequest;

import java.math.BigDecimal;

/**
 * 幂等指纹构造：指纹覆盖 requestId 与全部业务字段；数值先去尾零，
 * 使 0.10 与 0.1 等同一数值视为同参，字段顺序固定保证可复算。
 */
final class Fingerprints {

    static final String TYPE_TEMPERATURE = "TEMPERATURE_READING";
    static final String TYPE_DIACETYL = "DIACETYL_TEST";
    static final String TYPE_COOLING = "COOLING_COMMAND";
    static final String TYPE_JUDGE = "MATURITY_JUDGMENT";
    static final String TYPE_TRANSFER = "TRANSFER";

    private Fingerprints() {
    }

    static String temperature(TemperatureRequest request) {
        return DomainRules.sha256(String.join("|",
                TYPE_TEMPERATURE,
                request.requestId(),
                request.batchNo(),
                request.readingAt().toString(),
                normalize(request.temperature())));
    }

    static String diacetyl(DiacetylRequest request) {
        return DomainRules.sha256(String.join("|",
                TYPE_DIACETYL,
                request.requestId(),
                request.batchNo(),
                request.testedAt().toString(),
                normalize(request.valueMgL())));
    }

    static String cooling(CoolingRequest request) {
        StringBuilder builder = new StringBuilder(String.join("|",
                TYPE_COOLING,
                request.requestId(),
                request.batchNo(),
                request.commandAt().toString()));
        for (SegmentSpec segment : request.newCurve()) {
            builder.append('|').append(segment.fromHour())
                    .append(',').append(segment.toHour())
                    .append(',').append(normalize(segment.targetTemp()));
        }
        return DomainRules.sha256(builder.toString());
    }

    static String judge(JudgeRequest request) {
        return DomainRules.sha256(String.join("|",
                TYPE_JUDGE,
                request.requestId(),
                request.batchNo(),
                request.judgedAt().toString()));
    }

    static String transfer(TransferRequest request) {
        return DomainRules.sha256(String.join("|",
                TYPE_TRANSFER,
                request.requestId(),
                request.batchNo(),
                request.transferredAt().toString()));
    }

    private static String normalize(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
