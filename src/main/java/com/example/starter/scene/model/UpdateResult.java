package com.example.starter.scene.model;

import java.util.List;

/**
 * 成功提交修改后的响应。
 *
 * @param requestId 透传的请求 ID
 * @param version   新场景版本号
 * @param replayed  true 表示这是同 requestId 同参数请求的幂等重放，返回的是原结果
 */
public record UpdateResult(String requestId, long version, boolean replayed) {
}
