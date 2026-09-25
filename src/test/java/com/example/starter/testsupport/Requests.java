package com.example.starter.testsupport;

import com.example.starter.dto.AdjustRequest;
import com.example.starter.dto.AdjustStep;
import com.example.starter.dto.ArchiveRequest;
import com.example.starter.dto.EvaluateRequest;
import com.example.starter.dto.TemperatureReadingItem;
import com.example.starter.dto.TemperatureReportRequest;
import com.example.starter.domain.ValveAction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 测试请求构造辅助。
 */
public final class Requests {

    private Requests() {
    }

    public static AdjustStep step(String unitCode, ValveAction action, String delta) {
        return new AdjustStep(unitCode, action, new BigDecimal(delta));
    }

    public static AdjustRequest adjust(String requestId, String stationCode, AdjustStep... steps) {
        return new AdjustRequest(requestId, stationCode, List.of(steps));
    }

    public static EvaluateRequest evaluate(String requestId, String stationCode, String phase) {
        return new EvaluateRequest(requestId, stationCode, phase);
    }

    public static ArchiveRequest archive(String requestId, String stationCode) {
        return new ArchiveRequest(requestId, stationCode);
    }

    public static TemperatureReportRequest temperatures(String requestId, String stationCode,
                                                        List<TemperatureReadingItem> items) {
        return new TemperatureReportRequest(requestId, stationCode, items);
    }

    public static TemperatureReadingItem reading(String unitCode, String supply, String returnTemp, Instant observedAt) {
        return new TemperatureReadingItem(unitCode, new BigDecimal(supply), new BigDecimal(returnTemp), observedAt);
    }

    /** 以相同 observedAt 构造全站各单元读数（只给回水温度，供水统一 60℃）。 */
    public static TemperatureReportRequest stationReadings(String requestId, String stationCode,
                                                           List<String> unitCodes, List<String> returnTemps,
                                                           Instant observedAt) {
        List<TemperatureReadingItem> items = new ArrayList<>();
        for (int i = 0; i < unitCodes.size(); i++) {
            items.add(reading(unitCodes.get(i), "60.000", returnTemps.get(i), observedAt));
        }
        return temperatures(requestId, stationCode, items);
    }
}
