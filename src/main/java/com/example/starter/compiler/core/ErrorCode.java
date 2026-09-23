package com.example.starter.compiler.core;

import org.springframework.http.HttpStatus;

/**
 * 编译服务的业务错误码及其对应的 HTTP 状态。
 */
public enum ErrorCode {

    /** 请求体或参数本身不合法（缺少字段、参数未定义等）。 */
    BAD_REQUEST(HttpStatus.BAD_REQUEST),
    /** 入口或 include 引用的文件在受控工作目录之外或不存在。 */
    PATH_OUTSIDE_WORKSPACE(HttpStatus.BAD_REQUEST),
    /** 引用了未定义的 xacro 属性/参数。 */
    UNDEFINED_PARAMETER(HttpStatus.BAD_REQUEST),
    /** 使用了受限方言之外的 Xacro 构造（宏、条件、arg 等）。 */
    UNSUPPORTED_XACRO(HttpStatus.BAD_REQUEST),
    /** include 形成循环引用。 */
    CYCLIC_INCLUDE(HttpStatus.UNPROCESSABLE_ENTITY),
    /** 展开后的 URDF 未通过结构或数值校验。 */
    INVALID_URDF(HttpStatus.UNPROCESSABLE_ENTITY);

    private final HttpStatus httpStatus;

    ErrorCode(HttpStatus httpStatus) {
        this.httpStatus = httpStatus;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }
}
