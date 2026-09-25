package com.example.starter.forestry.po;

import java.math.BigDecimal;

/**
 * 森林小班 PO。
 */
public record SubplotPO(
        String id,
        String subplotCode,
        String speciesCode,
        String forestCategory,
        boolean protectedZone,
        int standAge,
        int matureAge,
        BigDecimal area
) {
}
