package com.example.starter.scene.dto;

/**
 * 碰撞对象主体定义。坐标系与位姿用于世界对象；附着后位姿相对所附 Link。
 *
 * @param id 对象 ID，世界与附着集合合并后全局唯一
 * @param frameId 位姿参考坐标系，必须已存在；为空时默认 world
 * @param pose 位姿，为空时默认单位位姿
 * @param size 盒体尺寸，必须为正有限数
 */
public record CollisionObjectBody(String id, String frameId, Pose pose, Size3 size) {
}
