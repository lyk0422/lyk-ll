package com.example.starter.marpol.service;

import com.example.starter.marpol.domain.DischargeType;
import com.example.starter.marpol.domain.Port;
import com.example.starter.marpol.domain.SpecialArea;
import com.example.starter.marpol.repo.GeoReferenceRepository;
import com.example.starter.marpol.support.GeoUtils;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 排放合规规则：
 * <ul>
 *   <li>油类/生活污水：特殊区域多边形内（含边界线）禁止；</li>
 *   <li>垃圾：距最近陆地（合成岸线参考点）不少于 12 海里，恰 12 海里合法。</li>
 * </ul>
 */
@Service
public class ComplianceService {

    /** 海里口径比较容差，约毫米级，仅用于消除浮点误差以保证“恰 12 海里合法”。 */
    private static final double DISTANCE_EPSILON_NM = 1e-6;

    private final GeoReferenceRepository geoRepository;
    private final double garbageMinDistanceNm;

    public ComplianceService(GeoReferenceRepository geoRepository,
                             @Value("${marpol.garbage-min-distance-nm:12}")
                             double garbageMinDistanceNm) {
        this.geoRepository = geoRepository;
        this.garbageMinDistanceNm = garbageMinDistanceNm;
    }

    public ComplianceResult evaluate(DischargeType type, BigDecimal latitude,
                                     BigDecimal longitude) {
        double lat = latitude.doubleValue();
        double lon = longitude.doubleValue();
        if (type == DischargeType.OIL || type == DischargeType.SEWAGE) {
            return evaluateAreaRestricted(type, lat, lon);
        }
        return evaluateGarbage(lat, lon);
    }

    private ComplianceResult evaluateAreaRestricted(DischargeType type, double lat, double lon) {
        for (SpecialArea area : geoRepository.findAllAreas()) {
            if (GeoUtils.isInsideOrOnBoundary(lat, lon, area.getVertices())) {
                return new ComplianceResult(false,
                        type.name() + " 排放位于特殊区域 " + area.getAreaCode()
                                + " 内（含边界线），禁止排放");
            }
        }
        return new ComplianceResult(true, type.name() + " 排放位于所有特殊区域之外");
    }

    private ComplianceResult evaluateGarbage(double lat, double lon) {
        List<double[]> referencePoints = geoRepository.findAllPorts().stream()
                .map((Port p) -> new double[] {p.getLatitude().doubleValue(),
                        p.getLongitude().doubleValue()})
                .toList();
        double minDistance = GeoUtils.minNauticalMiles(lat, lon, referencePoints);
        BigDecimal rounded = BigDecimal.valueOf(minDistance).setScale(6, RoundingMode.HALF_UP);
        boolean compliant = minDistance + DISTANCE_EPSILON_NM >= garbageMinDistanceNm;
        String detail = "距最近陆地 " + rounded.toPlainString() + " 海里，要求不少于 "
                + (long) garbageMinDistanceNm + " 海里（恰 " + (long) garbageMinDistanceNm
                + " 海里合法）";
        return new ComplianceResult(compliant, compliant ? detail : detail + "，不合规");
    }
}
