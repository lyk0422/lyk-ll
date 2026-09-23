package com.example.starter.slam.service;

import com.example.starter.slam.api.EdgeRequest;
import com.example.starter.slam.api.EdgeView;
import com.example.starter.slam.api.KeyFrameRequest;
import com.example.starter.slam.api.KeyFrameView;

/**
 * 位姿图写入服务：关键帧/边写入、幂等与冲突、回环阈值校验。
 */
public interface GraphService {

    /**
     * @param graphId 路径中的图 ID
     * @param frameId 路径中的关键帧 ID，需与请求体 id 一致
     */
    KeyFrameView putFrame(String graphId, String frameId, KeyFrameRequest request);

    /**
     * @param graphId 路径中的图 ID
     * @param edgeId  路径中的边 ID，需与请求体 id 一致
     */
    EdgeView putEdge(String graphId, String edgeId, EdgeRequest request);
}
