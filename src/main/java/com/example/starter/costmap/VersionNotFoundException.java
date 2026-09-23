package com.example.starter.costmap;

/**
 * 地图或快照版本不存在，HTTP 404。
 */
public class VersionNotFoundException extends CostmapException {

    public VersionNotFoundException(String message) {
        super("VERSION_NOT_FOUND", message);
    }
}
