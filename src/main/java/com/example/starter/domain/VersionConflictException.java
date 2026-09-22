package com.example.starter.domain;

/**
 * 乐观锁冲突：关联/迁移请求携带的 expectedVersion 与轨迹当前版本不一致，映射 HTTP 409。
 */
public class VersionConflictException extends BusinessException {

    private final long expectedVersion;
    private final long actualVersion;

    public VersionConflictException(String trackUid, long expectedVersion, long actualVersion) {
        super("VERSION_CONFLICT",
                "轨迹 " + trackUid + " 版本冲突：期望 " + expectedVersion + "，实际 " + actualVersion);
        this.expectedVersion = expectedVersion;
        this.actualVersion = actualVersion;
    }

    public long getExpectedVersion() {
        return expectedVersion;
    }

    public long getActualVersion() {
        return actualVersion;
    }
}
