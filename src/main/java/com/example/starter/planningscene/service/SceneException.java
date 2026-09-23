package com.example.starter.planningscene.service;

import java.util.List;

/**
 * 提交与业务规则冲突异常基类，映射为 HTTP 4xx。
 */
public class SceneException extends RuntimeException {

    /**
     * @param message 可读原因
     */
    public SceneException(String message) {
        super(message);
    }

    /** HTTP 状态码。 */
    public int httpStatus() {
        return 400;
    }

    /** 错误代码。 */
    public String errorCode() {
        return "BAD_REQUEST";
    }

    /**
     * 业务规则非法（数值越界、引用不存在、引用未维护等），整批回滚，返回 400。
     */
    public static class InvalidBatch extends SceneException {
        public InvalidBatch(String message) {
            super(message);
        }
    }

    /**
     * 历史版本不存在，返回 404。
     */
    public static class NotFound extends SceneException {
        public NotFound(String message) {
            super(message);
        }

        @Override
        public int httpStatus() {
            return 404;
        }

        @Override
        public String errorCode() {
            return "NOT_FOUND";
        }
    }

    /**
     * 版本过期冲突，返回 409 及变化对象/关节 ID 列表。
     */
    public static class VersionConflict extends SceneException {
        private final long currentVersion;
        private final long expectedVersion;
        private final List<String> changedObjectIds;
        private final List<String> changedJointNames;

        public VersionConflict(long currentVersion, long expectedVersion,
                               List<String> changedObjectIds, List<String> changedJointNames) {
            super("expected version " + expectedVersion + " but current is " + currentVersion);
            this.currentVersion = currentVersion;
            this.expectedVersion = expectedVersion;
            this.changedObjectIds = List.copyOf(changedObjectIds);
            this.changedJointNames = List.copyOf(changedJointNames);
        }

        @Override
        public int httpStatus() {
            return 409;
        }

        @Override
        public String errorCode() {
            return "VERSION_CONFLICT";
        }

        public long getCurrentVersion() {
            return currentVersion;
        }

        public long getExpectedVersion() {
            return expectedVersion;
        }

        public List<String> getChangedObjectIds() {
            return changedObjectIds;
        }

        public List<String> getChangedJointNames() {
            return changedJointNames;
        }
    }

    /**
     * 同一 requestId 携带不同参数，返回 409。
     */
    public static class IdempotencyConflict extends SceneException {
        public IdempotencyConflict(String requestId) {
            super("requestId " + requestId + " already used with different parameters");
        }

        @Override
        public int httpStatus() {
            return 409;
        }

        @Override
        public String errorCode() {
            return "IDEMPOTENCY_CONFLICT";
        }
    }
}
