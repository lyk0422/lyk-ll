package com.example.starter.costmap;

/**
 * 请求区域非法（坐标反转、为空或越界），HTTP 400。
 */
public class InvalidRegionException extends CostmapException {

    public InvalidRegionException(String message) {
        super("INVALID_REGION", message);
    }
}
