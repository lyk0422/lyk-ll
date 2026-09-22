package com.example.starter.tf.domain;

import java.util.List;

/**
 * TF 业务异常基类；每种失败语义对应固定 HTTP 状态与错误码。
 * 题干要求的不同失败分支通过互不相同的 {@link #code()} 区分。
 */
public abstract class TfException extends RuntimeException {

    protected TfException(String message) {
        super(message);
    }

    /** 错误码，供 API 响应体区分不同失败分支。 */
    public abstract String code();

    /** 对应的 HTTP 状态码。 */
    public abstract int httpStatus();

    /** 坐标系不存在。 */
    public static class FrameNotFound extends TfException {
        public FrameNotFound(String frame) {
            super("坐标系不存在: " + frame);
        }

        @Override
        public String code() {
            return "FRAME_NOT_FOUND";
        }

        @Override
        public int httpStatus() {
            return 404;
        }
    }

    /** 插入边会形成坐标系环；携带完整候选环路径。 */
    public static class CycleDetected extends TfException {
        private final List<String> cycle;

        public CycleDetected(List<String> cycle) {
            super("插入边会形成坐标系环: " + String.join(" -> ", cycle));
            this.cycle = List.copyOf(cycle);
        }

        public List<String> cycle() {
            return cycle;
        }

        @Override
        public String code() {
            return "CYCLE_DETECTED";
        }

        @Override
        public int httpStatus() {
            return 422;
        }
    }

    /** source 与 target 分属不同树，森林链路断裂。 */
    public static class NoTransformPath extends TfException {
        public NoTransformPath(String source, String target) {
            super("坐标系链路断裂，无法从 " + source + " 变换到 " + target);
        }

        @Override
        public String code() {
            return "NO_TRANSFORM_PATH";
        }

        @Override
        public int httpStatus() {
            return 422;
        }
    }

    /** 查询时刻早于动态链路上最早样本。 */
    public static class BeforeEarliestSample extends TfException {
        public BeforeEarliestSample(String edge, long requested, long earliest) {
            super("查询时刻 " + requested + " 早于边 " + edge + " 的最早样本 " + earliest);
        }

        @Override
        public String code() {
            return "BEFORE_EARLIEST_SAMPLE";
        }

        @Override
        public int httpStatus() {
            return 422;
        }
    }

    /** 查询时刻晚于动态链路上最新样本。 */
    public static class AfterLatestSample extends TfException {
        public AfterLatestSample(String edge, long requested, long latest) {
            super("查询时刻 " + requested + " 晚于边 " + edge + " 的最新样本 " + latest);
        }

        @Override
        public String code() {
            return "AFTER_LATEST_SAMPLE";
        }

        @Override
        public int httpStatus() {
            return 422;
        }
    }

    /** 查询时刻落在已被缓存淘汰的历史窗口内，样本不可再获得。 */
    public static class CacheWindowExceeded extends TfException {
        public CacheWindowExceeded(String edge, long requested, long retainedFrom) {
            super("查询时刻 " + requested + " 已超出边 " + edge + " 的缓存窗口，仅保留不早于 "
                    + retainedFrom + " 的样本");
        }

        @Override
        public String code() {
            return "CACHE_WINDOW_EXCEEDED";
        }

        @Override
        public int httpStatus() {
            return 410;
        }
    }

    /** 同一边同一时间戳同一来源重放，但变换内容与已存样本不同。 */
    public static class ReplayConflict extends TfException {
        public ReplayConflict(String message) {
            super(message);
        }

        @Override
        public String code() {
            return "REPLAY_CONFLICT";
        }

        @Override
        public int httpStatus() {
            return 409;
        }
    }
}
