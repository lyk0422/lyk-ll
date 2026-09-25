package com.example.starter.cutover.api;

/**
 * 封板视图；封板为终态，重复封板返回幂等成功。
 *
 * @param status SEALED
 * @param replay true 表示此前已封板（重复封板幂等）
 */
public record SealResponse(
        Long applicationId,
        String status,
        boolean replay
) {
}
