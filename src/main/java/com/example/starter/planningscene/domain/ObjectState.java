package com.example.starter.planningscene.domain;

import java.util.List;

/**
 * 碰撞对象在某一版本下的不可变状态。frameId 与 linkId 互斥：世界对象只有 frameId，附着物只有 linkId。
 *
 * @param objectId 对象 ID（版本内唯一）
 * @param type 对象归属集合
 * @param frameId 世界对象引用的坐标系；附着物为 null
 * @param linkId 附着物附着的机器人 Link；世界对象为 null
 * @param geometry 几何描述（合成 JSON 字符串）
 * @param pose 相对引用坐标系的位姿
 * @param touchLinks 附着物允许接触的 Link 名列表；世界对象为空列表
 */
public record ObjectState(String objectId, ObjectType type, String frameId, String linkId,
                          String geometry, Pose pose, List<String> touchLinks) {

    public List<String> touchLinks() {
        return touchLinks == null ? List.of() : List.copyOf(touchLinks);
    }
}
