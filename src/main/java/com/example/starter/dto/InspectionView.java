package com.example.starter.dto;

/**
 * 检验链单项视图（含规格与复算结论）。
 */
public record InspectionView(
        String itemCode,
        String itemName,
        boolean critical,
        String measuredValue,
        String comparison,
        String limitMin,
        String limitMax,
        boolean qualified
) {
}
