package com.example.starter;

import com.example.starter.web.dto.AlarmRequest;
import com.example.starter.web.dto.ArchiveRequest;
import com.example.starter.web.dto.CreateFanRequest;
import com.example.starter.web.dto.CreateSiloRequest;
import com.example.starter.web.dto.ReadingPoint;
import com.example.starter.web.dto.RetestRequest;
import com.example.starter.web.dto.VentilationRequest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 合成请求构造工具：默认值可被命名参数覆盖，便于聚焦边界。
 */
public final class TestDataFactory {

    private TestDataFactory() {
    }

    public static CreateSiloRequest silo(String code) {
        return new CreateSiloRequest(code, "粮仓-" + code, "WHEAT", 20.0, 2.0, 70.0);
    }

    public static CreateSiloRequest silo(String code, double safeTemp, double maxGradient,
                                         double equilibriumHumidity) {
        return new CreateSiloRequest(code, "粮仓-" + code, "WHEAT", safeTemp, maxGradient,
                equilibriumHumidity);
    }

    public static CreateFanRequest fan(String code) {
        return new CreateFanRequest(code, "风机-" + code);
    }

    public static ReadingPoint point(String code, Double temperature) {
        return new ReadingPoint(code, temperature, null);
    }

    public static AlarmRequest alarm(String requestId, String siloCode, LocalDateTime measuredAt,
                                     double... temperatures) {
        List<ReadingPoint> points = new ArrayList<>();
        for (int i = 0; i < temperatures.length; i++) {
            points.add(point("P" + (i + 1), temperatures[i]));
        }
        return new AlarmRequest(requestId, siloCode, measuredAt, points);
    }

    public static RetestRequest retest(String requestId, String siloCode, LocalDateTime measuredAt,
                                       double... temperatures) {
        List<ReadingPoint> points = new ArrayList<>();
        for (int i = 0; i < temperatures.length; i++) {
            points.add(point("P" + (i + 1), temperatures[i]));
        }
        return new RetestRequest(requestId, siloCode, measuredAt, points);
    }

    public static VentilationRequest ventilation(String requestId, String siloCode, String fanCode,
                                                 double ambientHumidity, LocalDateTime start,
                                                 LocalDateTime end) {
        return new VentilationRequest(requestId, siloCode, fanCode, "COOLING", ambientHumidity, start, end);
    }

    public static ArchiveRequest archive(String requestId, String siloCode) {
        return new ArchiveRequest(requestId, siloCode);
    }
}
