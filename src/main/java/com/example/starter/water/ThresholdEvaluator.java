package com.example.starter.water;

import com.example.starter.config.WaterQualityProperties;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 水质阈值评估：溶解氧、氨氮、温度任一严格越限即违规，恰等限值判定为正常。
 */
@Component
public class ThresholdEvaluator {

    /**
     * 指标编码：溶解氧。
     */
    public static final String METRIC_DISSOLVED_OXYGEN = "dissolved_oxygen";

    /**
     * 指标编码：氨氮。
     */
    public static final String METRIC_AMMONIA_NITROGEN = "ammonia_nitrogen";

    /**
     * 指标编码：温度。
     */
    public static final String METRIC_TEMPERATURE = "temperature";

    private final WaterQualityProperties properties;

    public ThresholdEvaluator(WaterQualityProperties properties) {
        this.properties = properties;
    }

    /**
     * 评估一次采样，返回越限指标编码列表；空列表表示全部合格。
     */
    public List<String> evaluate(double dissolvedOxygen, double ammoniaNitrogen, double temperature) {
        WaterQualityProperties.Thresholds thresholds = properties.getThresholds();
        List<String> violations = new ArrayList<>(3);
        if (dissolvedOxygen < thresholds.getDissolvedOxygenMin()) {
            violations.add(METRIC_DISSOLVED_OXYGEN);
        }
        if (ammoniaNitrogen > thresholds.getAmmoniaNitrogenMax()) {
            violations.add(METRIC_AMMONIA_NITROGEN);
        }
        if (temperature < thresholds.getTemperatureMin() || temperature > thresholds.getTemperatureMax()) {
            violations.add(METRIC_TEMPERATURE);
        }
        return violations;
    }

    /**
     * 按首要越限指标选择处置指令类型：溶解氧→增氧，氨氮→换水，温度→停展隔离。
     */
    public DisposalType disposalTypeFor(List<String> violations) {
        if (violations.contains(METRIC_DISSOLVED_OXYGEN)) {
            return DisposalType.OXYGENATE;
        }
        if (violations.contains(METRIC_AMMONIA_NITROGEN)) {
            return DisposalType.WATER_CHANGE;
        }
        return DisposalType.ISOLATE;
    }
}
