package com.example.starter.catalog;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 合成检验项目目录。效力、无菌、异常毒性为关键项目，须全项合格；pH、水分为一般项目。
 */
public final class InspectionCatalog {

    /** 效力：IU/剂，不低于 4.0，恰 4.0 判合格。 */
    public static final String POTENCY = "POTENCY";
    /** 无菌检查：定性，结果须为“无菌”。 */
    public static final String STERILITY = "STERILITY";
    /** 异常毒性：限值 0.10 EU/剂，不高于，恰 0.10 判合格。 */
    public static final String ABNORMAL_TOXICITY = "ABNORMAL_TOXICITY";
    /** pH 值：7.0～8.0，边界含等号。 */
    public static final String PH_VALUE = "PH_VALUE";
    /** 水分：% ，不高于 3.0，恰 3.0 判合格。 */
    public static final String MOISTURE = "MOISTURE";

    private static final Map<String, InspectionSpec> SPECS;

    static {
        Map<String, InspectionSpec> map = new LinkedHashMap<>();
        map.put(POTENCY, InspectionSpec.gte(POTENCY, "效力测定", true, new BigDecimal("4.0")));
        map.put(STERILITY, InspectionSpec.text(STERILITY, "无菌检查", true, "无菌"));
        map.put(ABNORMAL_TOXICITY, InspectionSpec.lte(ABNORMAL_TOXICITY, "异常毒性检查", true,
                new BigDecimal("0.10")));
        map.put(PH_VALUE, InspectionSpec.range(PH_VALUE, "pH 值", false,
                new BigDecimal("7.0"), new BigDecimal("8.0")));
        map.put(MOISTURE, InspectionSpec.lte(MOISTURE, "水分", false, new BigDecimal("3.0")));
        SPECS = Map.copyOf(map);
    }

    private InspectionCatalog() {
    }

    /** 返回全部检验项目规格（按目录顺序）。 */
    public static List<InspectionSpec> all() {
        return List.copyOf(SPECS.values());
    }

    /** 按代码查找规格，未知代码返回 null。 */
    public static InspectionSpec find(String itemCode) {
        return SPECS.get(itemCode);
    }
}
