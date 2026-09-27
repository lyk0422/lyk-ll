package com.example.starter.cosmetic.child.service;

/**
 * 儿童备案业务规则拒绝异常，映射为 HTTP 422（Unprocessable Entity）。
 *
 * <p>用于参数合法、对象存在，但当前业务状态/规则不允许该操作的情形，
 * 例如：对已生效备案发起材料补正、把实质变更伪装成包装简易变更等。
 * 形式审查不通过不属于异常，正常返回 FORM_REJECTED 结果。</p>
 */
public class ChildBusinessRejectionException extends RuntimeException {

    /** 拒绝错误码（机器可读）。 */
    private final String code;

    public ChildBusinessRejectionException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
