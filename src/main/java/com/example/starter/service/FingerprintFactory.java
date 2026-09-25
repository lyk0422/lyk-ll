package com.example.starter.service;

import com.example.starter.dto.AdjustRequest;
import com.example.starter.dto.AdjustStep;
import com.example.starter.dto.ArchiveRequest;
import com.example.starter.dto.EvaluateRequest;
import com.example.starter.dto.TemperatureReadingItem;
import com.example.starter.dto.TemperatureReportRequest;
import com.example.starter.support.FingerprintUtil;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 幂等指纹构造：指纹覆盖 requestId 与全部业务字段。
 *
 * <p>对数值做去尾零规范化（10 与 10.000 视为同参），时间统一为 UTC 毫秒；
 * 列表按业务编码排序，保证同一批数据以不同顺序提交仍判定为同参。</p>
 */
@Component
public class FingerprintFactory {

    public String adjust(AdjustRequest request) {
        String steps = request.steps().stream()
                .sorted(Comparator.comparing(AdjustStep::unitCode)
                        .thenComparing(s -> s.action().name())
                        .thenComparing(s -> normalize(s.delta())))
                .map(s -> s.unitCode() + "|" + s.action() + "|" + normalize(s.delta()))
                .collect(Collectors.joining(";"));
        return FingerprintUtil.sha256("ADJUST|" + request.requestId() + "|" + request.stationCode() + "|" + steps);
    }

    public String evaluate(EvaluateRequest request) {
        return FingerprintUtil.sha256(
                "EVALUATE|" + request.requestId() + "|" + request.stationCode() + "|" + request.phase());
    }

    public String archive(ArchiveRequest request) {
        return FingerprintUtil.sha256(
                "ARCHIVE|" + request.requestId() + "|" + request.stationCode());
    }

    public String temperature(TemperatureReportRequest request) {
        List<TemperatureReadingItem> items = request.readings().stream()
                .sorted(Comparator.comparing(TemperatureReadingItem::unitCode)
                        .thenComparing(i -> i.observedAt().toEpochMilli())
                        .thenComparing(i -> normalize(i.supplyTemp()))
                        .thenComparing(i -> normalize(i.returnTemp())))
                .toList();
        String body = items.stream()
                .map(i -> i.unitCode() + "|" + normalize(i.supplyTemp()) + "|"
                        + normalize(i.returnTemp()) + "|" + i.observedAt().toEpochMilli())
                .collect(Collectors.joining(";"));
        return FingerprintUtil.sha256(
                "TEMPERATURE|" + request.requestId() + "|" + request.stationCode() + "|" + body);
    }

    private String normalize(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
