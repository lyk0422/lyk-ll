package com.example.starter.tf2.service;

import java.util.List;

/**
 * TF2 缓存业务错误基类，携带稳定的错误码与 HTTP 状态映射。
 */
public abstract class Tf2Exception extends RuntimeException {

    private final String errorCode;
    private final int httpStatus;

    protected Tf2Exception(String errorCode, int httpStatus, String message) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    /** 请求中的坐标系不存在（404）。 */
    public static class FrameNotFound extends Tf2Exception {
        public FrameNotFound(String frame) {
            super("FRAME_NOT_FOUND", 404, "坐标系不存在: " + frame);
        }
    }

    /** source 与 target 之间不存在连通链路（409）。 */
    public static class ChainDisconnected extends Tf2Exception {
        public ChainDisconnected(String source, String target) {
            super("CHAIN_DISCONNECTED", 409,
                    "坐标系链路断裂，无法从 " + source + " 到达 " + target);
        }
    }

    /** 查询时刻早于链路中动态边的最早样本（409）。 */
    public static class TimeBeforeEarliest extends Tf2Exception {
        public TimeBeforeEarliest(String edge, long requested, long earliest) {
            super("TIME_BEFORE_EARLIEST", 409,
                    "请求时间 " + requested + " 早于边 " + edge + " 的最早样本 " + earliest);
        }
    }

    /** 查询时刻晚于链路中动态边的最新样本（409）。 */
    public static class TimeAfterLatest extends Tf2Exception {
        public TimeAfterLatest(String edge, long requested, long latest) {
            super("TIME_AFTER_LATEST", 409,
                    "请求时间 " + requested + " 晚于边 " + edge + " 的最新样本 " + latest);
        }
    }

    /** 请求时刻落在缓存保留窗口之外（窗口内最早样本已被淘汰）（409）。 */
    public static class OutsideCacheWindow extends Tf2Exception {
        public OutsideCacheWindow(String edge, long requested, long windowStart) {
            super("OUTSIDE_CACHE_WINDOW", 409,
                    "请求时间 " + requested + " 超出边 " + edge + " 的缓存窗口起点 " + windowStart);
        }
    }

    /** 新边会形成坐标系环（422），携带完整候选环路径。 */
    public static class CycleDetected extends Tf2Exception {
        private final transient List<String> cycle;

        public CycleDetected(List<String> cycle) {
            super("CYCLE_DETECTED", 422, "插入边将形成坐标系环: " + String.join(" -> ", cycle));
            this.cycle = List.copyOf(cycle);
        }

        public List<String> getCycle() {
            return cycle;
        }
    }

    /** 请求参数非法（400），如动态样本缺少时间戳、四元数非有限值。 */
    public static class InvalidRequest extends Tf2Exception {
        public InvalidRequest(String message) {
            super("INVALID_REQUEST", 400, message);
        }
    }

    /** 子坐标系已存在不同父边，违反“有向森林”单父约束（409）。 */
    public static class FrameParentConflict extends Tf2Exception {
        public FrameParentConflict(String child, String existingParent, String newParent) {
            super("FRAME_PARENT_CONFLICT", 409,
                    "子坐标系 " + child + " 已挂在父坐标系 " + existingParent
                            + " 下，不能再挂到 " + newParent);
        }
    }

    /** 同父子、同时间戳、同来源但变换内容不同的重放（409）。 */
    public static class ConflictingReplay extends Tf2Exception {
        public ConflictingReplay(String message) {
            super("CONFLICTING_REPLAY", 409, message);
        }
    }
}
