package com.example.starter.metro.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 行程领域对象。金额单位分，里程单位公里，时间为 Asia/Shanghai 本地时间。
 *
 * @param id                 行程主键
 * @param ticketId           票卡号
 * @param entryTxNo          进闸流水号
 * @param entryStation       进闸站
 * @param entryTime          进闸时间
 * @param exitTxNo           出闸流水号，未出闸为空
 * @param exitStation        出闸站，未出闸为空
 * @param exitTime           出闸时间，未出闸为空
 * @param distanceKm         进出站最短路径里程（公里），未出闸为空
 * @param chainCumKm         换乘链累计里程（含本段，公里）
 * @param fareFen            实收金额（分），未结算为空
 * @param provisionalFareFen 暂记金额（分），未暂记为空
 * @param status             OPEN/PROVISIONAL/SETTLED
 * @param batchDate          入账批次日期，未入账为空
 */
public record Trip(long id, String ticketId, String entryTxNo, String entryStation,
        LocalDateTime entryTime, String exitTxNo, String exitStation, LocalDateTime exitTime,
        Integer distanceKm, Integer chainCumKm, Long fareFen, Long provisionalFareFen,
        String status, LocalDate batchDate) {

    /** 已进闸未出闸。 */
    public static final String STATUS_OPEN = "OPEN";
    /** 出闸缺失，日结已按最远站票价暂记。 */
    public static final String STATUS_PROVISIONAL = "PROVISIONAL";
    /** 已按实结算。 */
    public static final String STATUS_SETTLED = "SETTLED";
}
