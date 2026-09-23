package com.example.starter.robot;

import org.springframework.http.HttpStatus;

/**
 * 编译服务业务错误码及对应的 HTTP 状态。
 */
public enum ErrorCode {

    /** 请求体或字段校验失败。 */
    INVALID_REQUEST(HttpStatus.BAD_REQUEST),
    /** 入口文件不存在或不是普通文件。 */
    ENTRY_NOT_FOUND(HttpStatus.BAD_REQUEST),
    /** include 引用的文件不存在。 */
    INCLUDE_NOT_FOUND(HttpStatus.BAD_REQUEST),
    /** include 路径解析后越出受控工作目录。 */
    INCLUDE_OUT_OF_BOUNDS(HttpStatus.BAD_REQUEST),
    /** ${name} 引用了未定义的参数。 */
    UNDEFINED_PARAMETER(HttpStatus.BAD_REQUEST),
    /** 展开后的 URDF 不满足结构、名称、引用、数值等约束。 */
    INVALID_URDF(HttpStatus.BAD_REQUEST),
    /** 使用了受限子集之外的 Xacro 构造。 */
    UNSUPPORTED_XACRO(HttpStatus.UNPROCESSABLE_ENTITY),
    /** include 形成循环引用。 */
    INCLUDE_CYCLE(HttpStatus.UNPROCESSABLE_ENTITY),
    /** 服务端未预期错误。 */
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
