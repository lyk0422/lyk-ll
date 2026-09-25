package com.example.starter.metro.fare;

import org.springframework.stereotype.Service;

/**
 * 票价规则：起步价 2 元含 4 公里，超出部分每 5 公里加 1 元，
 * 不足 5 公里按 5 公里计，恰满 5 公里按加价档计。
 * 金额单位为分。
 */
@Service
public class FareService {

    /** 起步价，单位分（2 元）。 */
    public static final long BASE_FARE_FEN = 200L;
    /** 起步价包含的里程，单位公里。 */
    public static final long BASE_KM = 4L;
    /** 加价档步长，单位公里。 */
    public static final long BLOCK_KM = 5L;
    /** 每档加价，单位分（1 元）。 */
    public static final long BLOCK_FARE_FEN = 100L;

    /**
     * 按里程计算单程票价（分）。
     *
     * @param distanceKm 里程，单位公里，非负
     * @return 票价，单位分
     */
    public long fareFenForKm(long distanceKm) {
        if (distanceKm < 0) {
            throw new IllegalArgumentException("distanceKm must be >= 0");
        }
        if (distanceKm <= BASE_KM) {
            return BASE_FARE_FEN;
        }
        long extra = distanceKm - BASE_KM;
        long blocks = (extra + BLOCK_KM - 1) / BLOCK_KM;
        return BASE_FARE_FEN + blocks * BLOCK_FARE_FEN;
    }

    /**
     * 连续换乘链的增量票价：链上只计一次起步价，
     * 本段票价 = 链累计里程票价 - 之前链累计里程票价。
     *
     * @param chainCumBeforeKm 本段之前链上累计里程（公里），无换乘链时传 0
     * @param segmentKm        本段里程（公里）
     * @return 本段应收票价，单位分
     */
    public long chainedFareFen(long chainCumBeforeKm, long segmentKm) {
        if (chainCumBeforeKm <= 0) {
            return fareFenForKm(segmentKm);
        }
        return fareFenForKm(chainCumBeforeKm + segmentKm) - fareFenForKm(chainCumBeforeKm);
    }
}
