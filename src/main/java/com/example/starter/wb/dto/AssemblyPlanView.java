package com.example.starter.wb.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 集装器装配图版本对外视图。
 *
 * @param planId          装配图版本 ID
 * @param flightId        航班 ID
 * @param planVersion     装配图版本号，自 1 起
 * @param manifestId      所匹配舱单版本 ID
 * @param manifestVersion 所匹配舱单版本号
 * @param cargoPayloadKg  总货重=Σ净重，kg，等于舱单货运业载
 * @param cargoMomentKgmm 货运力矩=Σ(皮+净)×毫米力臂，kg·mm
 * @param active          是否当前有效图（作废历史图为 false，只读）
 * @param released        实物占用是否已随关闭放行释放
 * @param confirmedRoles  提交后仍有效的复核岗位；新图使旧复核失效，故为空
 * @param manifestStatus  提交后舱单状态（复核失效后回到 LOADED）
 * @param occurredAt      业务发生精确时刻
 * @param releasedAt      占用释放时间；NULL 为占用中
 * @param items           装配项视图
 */
public record AssemblyPlanView(
        Long planId,
        Long flightId,
        int planVersion,
        Long manifestId,
        int manifestVersion,
        BigDecimal cargoPayloadKg,
        BigDecimal fuelKg,
        long cargoMomentKgmm,
        boolean active,
        boolean released,
        List<String> confirmedRoles,
        String manifestStatus,
        LocalDateTime occurredAt,
        LocalDateTime releasedAt,
        List<AssemblyItemView> items
) {
}
