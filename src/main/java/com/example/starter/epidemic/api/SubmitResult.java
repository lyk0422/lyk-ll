package com.example.starter.epidemic.api;

/**
 * 初报/订正提交结果。
 *
 * @param duplicated        是否命中“同一患者+病种+发病日期”重复去重（未新建卡）
 * @param replayed          是否命中同 requestId 幂等重放（返回首次结果）
 * @param existingCardNumber 去重命中时已存在的卡片编号
 */
public record SubmitResult(
        String cardNumber,
        int versionNo,
        String status,
        boolean timely,
        boolean duplicated,
        boolean replayed,
        String existingCardNumber
) {
}
