package com.example.starter.route;

import java.time.LocalDateTime;

/**
 * 路线请求持久化记录；requestId 为幂等键，执行后版本、候选与结果均不可变。
 *
 * @param requestId    请求业务标识
 * @param mapVersionId 固定绑定的地图版本
 * @param origin       起点匹配输入
 * @param destination  终点匹配输入
 * @param status       SUCCESS 或 FAILED
 * @param errorCode    失败错误码；成功时为 null
 * @param resultJson   匹配结果快照 JSON；失败时为 null
 * @param createdAt    创建时间（Asia/Shanghai）
 */
public record RouteRequestRecord(
        String requestId,
        String mapVersionId,
        MatchPoint origin,
        MatchPoint destination,
        String status,
        String errorCode,
        String resultJson,
        LocalDateTime createdAt) {

    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";

    /**
     * 判断重放参数是否与原请求完全一致（同参重放）。
     */
    public boolean sameParameters(String versionId, MatchPoint o, MatchPoint d) {
        return mapVersionId.equals(versionId) && origin.equals(o) && destination.equals(d);
    }
}
