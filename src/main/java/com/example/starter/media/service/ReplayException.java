package com.example.starter.media.service;

/**
 * 幂等重放早期方案遗留；当前重放直接返回首次 ServiceResult，此类保留以避免文件删除依赖。
 *
 * @deprecated 不再使用，幂等重放见 {@code JdbcMediaRightsService#acquireOrReplay}
 */
@Deprecated(since = "1.0")
class ReplayException {

    private ReplayException() {
    }
}
