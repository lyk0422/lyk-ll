package com.example.starter.scene.exception;

import java.util.List;

/**
 * 请求中的操作违反业务规则（数值非法、引用不存在等），整批回滚，对应 HTTP 400。
 */
public class SceneValidationException extends RuntimeException {

    private final int operationIndex;

    public SceneValidationException(String message) {
        this(-1, message);
    }

    public SceneValidationException(int operationIndex, String message) {
        super(message);
        this.operationIndex = operationIndex;
    }

    public int getOperationIndex() {
        return operationIndex;
    }

    /** 用于批量静态校验时聚合错误，当前逐个抛出即可。 */
    public static SceneValidationException at(int index, String message) {
        return new SceneValidationException(index, message);
    }

    public static List<String> noDetails() {
        return List.of();
    }
}
