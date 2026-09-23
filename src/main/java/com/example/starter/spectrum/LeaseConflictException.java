package com.example.starter.spectrum;

import java.util.List;

/**
 * 租约协调被拒绝（冲突、超功率、版本不符、参数非法等业务语义），映射为 4xx。
 */
public class LeaseConflictException extends RuntimeException {

    /** 机器可读的冲突原因代码。 */
    private final String code;

    /** 冲突解释明细。 */
    private final transient List<ConflictDetail> details;

    /**
     * @param code    原因代码
     * @param message 可读说明
     * @param details 冲突片段解释
     */
    public LeaseConflictException(String code, String message, List<ConflictDetail> details) {
        super(message);
        this.code = code;
        this.details = details == null ? List.of() : List.copyOf(details);
    }

    public String getCode() {
        return code;
    }

    public List<ConflictDetail> getDetails() {
        return details;
    }
}
