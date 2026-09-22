package com.example.starter.map;

/**
 * 地图版本状态：ACTIVE=可服务；DEPRECATED=已废弃（不接受新请求，已绑定请求仍可回放）。
 */
public enum MapVersionStatus {
    ACTIVE,
    DEPRECATED
}
