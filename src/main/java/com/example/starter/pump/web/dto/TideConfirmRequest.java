package com.example.starter.pump.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 强排切换候选确认请求。闸门合成到位回执须逐一覆盖候选固定的全部相关站，
 * 任一不到位则零切换，不允许一半站先启机。
 *
 * @param requestId       幂等键
 * @param candidateId     待确认候选序列ID
 * @param expectedVersion 期望工况版本（旧分区序列版本），须与当前版本一致
 * @param confirmTime     切换生效时刻（Asia/Shanghai），新序列与各站任务的时刻
 * @param gateReceipts    各站闸门合成到位回执，须与候选固定目标一一吻合
 */
public record TideConfirmRequest(
        @NotBlank String requestId,
        @NotNull Long candidateId,
        @NotNull Integer expectedVersion,
        @NotNull LocalDateTime confirmTime,
        @NotEmpty @Valid List<GateReceipt> gateReceipts
) {

    /**
     * 单站闸门合成到位回执。
     *
     * @param stationCode 泵站代码
     * @param opening     到位开度（百分比，0-100），须等于候选固定目标开度
     */
    public record GateReceipt(
            @NotBlank String stationCode,
            @NotNull BigDecimal opening
    ) {
    }
}
