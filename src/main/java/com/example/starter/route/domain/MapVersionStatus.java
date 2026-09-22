package com.example.starter.route.domain;

/**
 * 地图版本状态。
 */
public enum MapVersionStatus {

    /** 有效：可接受新的路线请求 */
    ACTIVE,

    /** 已废弃：拒绝新请求，但已绑定该版本的历史请求重放仍返回原始结果 */
    DEPRECATED
}
