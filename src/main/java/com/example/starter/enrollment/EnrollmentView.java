package com.example.starter.enrollment;

/**
 * 盲态查询视图：仅暴露盲态组别编码；actualArm 仅在受试者已揭盲后返回，否则为 null。
 */
public record EnrollmentView(
        String screeningNo,
        long randomNo,
        String armCode,
        String status,
        boolean unblinded,
        String actualArm) {
}
