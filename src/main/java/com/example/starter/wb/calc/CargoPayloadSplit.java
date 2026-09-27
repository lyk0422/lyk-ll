package com.example.starter.wb.calc;

import com.example.starter.wb.domain.Aircraft;
import com.example.starter.wb.domain.LoadDetailRow;
import com.example.starter.wb.domain.LoadStation;
import com.example.starter.wb.enums.LoadCategory;
import com.example.starter.wb.exception.BadRequestException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 货运业载拆分：货物与邮件（CARGO/MAIL）由集装器承载，装配模式下其力矩取自装配图；
 * 旅客与行李仍按站位力臂计算。
 */
public final class CargoPayloadSplit {

    /** 货运业载类别：货物与邮件。 */
    public static final Set<String> CARGO_CATEGORIES = Set.of(
            LoadCategory.CARGO.name(), LoadCategory.MAIL.name());

    private CargoPayloadSplit() {
    }

    /** 舱单货运业载（CARGO+MAIL 明细合计），kg。 */
    public static BigDecimal cargoPayloadKg(List<LoadDetailRow> details) {
        BigDecimal sum = BigDecimal.ZERO;
        for (LoadDetailRow d : details) {
            if (CARGO_CATEGORIES.contains(d.category())) {
                sum = sum.add(d.totalWeightKg());
            }
        }
        return sum.setScale(2, RoundingMode.HALF_UP);
    }

    /** 非货运业载（旅客/行李）计算项：同（类别,站位）聚合，力臂取站位主数据（英寸）。 */
    public static List<CalcLoadItem> nonCargoItems(List<LoadDetailRow> details, Aircraft ac) {
        Map<String, BigDecimal> weightByKey = new LinkedHashMap<>();
        Map<String, Integer> piecesByKey = new LinkedHashMap<>();
        for (LoadDetailRow d : details) {
            if (CARGO_CATEGORIES.contains(d.category())) {
                continue;
            }
            String key = d.category() + "|" + d.stationCode();
            weightByKey.merge(key, d.totalWeightKg(), BigDecimal::add);
            piecesByKey.merge(key, d.pieces(), Integer::sum);
        }
        List<CalcLoadItem> items = new ArrayList<>();
        weightByKey.forEach((key, weight) -> {
            String[] parts = key.split("\\|", 2);
            items.add(new CalcLoadItem(parts[0], parts[1], piecesByKey.get(key),
                    weight.setScale(2, RoundingMode.HALF_UP), stationArm(ac, parts[1])));
        });
        return items;
    }

    private static BigDecimal stationArm(Aircraft ac, String stationCode) {
        return ac.stations().stream()
                .filter(s -> s.stationCode().equals(stationCode))
                .map(LoadStation::arm)
                .findFirst()
                .orElseThrow(() -> new BadRequestException("飞机 " + ac.tailNo() + " 不存在站位 " + stationCode));
    }
}
