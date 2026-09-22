package com.example.starter.route.domain;

/**
 * 路线请求状态。
 */
public enum RequestState {

    /** 匹配成功，存在选中起终路段与连通路径 */
    MATCHED,

    /** 匹配失败，errorCode 给出稳定错误码 */
    FAILED
}
