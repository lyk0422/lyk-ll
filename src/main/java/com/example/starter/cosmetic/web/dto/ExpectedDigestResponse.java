package com.example.starter.cosmetic.web.dto;

import java.util.Map;

/**
 * 产品当前有效备案的标签摘要期望响应（登记印次前查询，供客户端按口径计算/核对摘要）。
 *
 * @param productCode     产品编码
 * @param filingNo        当前有效备案编号
 * @param filingVersionNo 当前有效备案修订号
 * @param labelDigest     期望的标签文本摘要（儿童为逐档摘要集合的结构化摘要）
 * @param bandDigests     儿童备案逐档期望标识摘要（分龄档编码-&gt;摘要）；成人为 null
 */
public record ExpectedDigestResponse(
        String productCode,
        String filingNo,
        int filingVersionNo,
        String labelDigest,
        Map<String, String> bandDigests) {
}
