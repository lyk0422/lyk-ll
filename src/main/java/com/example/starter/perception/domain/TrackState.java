package com.example.starter.perception.domain;

/**
 * 轨迹状态机状态。
 *
 * <p>合法迁移路径：
 * <ul>
 *   <li>CREATED -&gt; TRACKING：建轨后的首个后续观测</li>
 *   <li>TRACKING -&gt; OCCLUDED：观测间隔超过遮挡门限</li>
 *   <li>OCCLUDED -&gt; TRACKING：短时遮挡期间重新观测到目标</li>
 *   <li>OCCLUDED -&gt; LOST：遮挡持续超过丢失门限</li>
 *   <li>LOST -&gt; REIDENTIFIED：丢失目标在重识别门限内重新出现</li>
 *   <li>REIDENTIFIED -&gt; TRACKING：重识别确认后的后续观测</li>
 *   <li>REIDENTIFIED -&gt; OCCLUDED：重识别后再次失联超时</li>
 *   <li>LOST -&gt; RECYCLED：丢失轨迹被显式回收</li>
 * </ul>
 */
public enum TrackState {
    /** 首条检测建轨，尚未收到第二条检测。 */
    CREATED,
    /** 稳定跟踪中。 */
    TRACKING,
    /** 短时遮挡，最近无观测但尚未判定丢失。 */
    OCCLUDED,
    /** 已丢失，仅可在重识别门限内被重新关联。 */
    LOST,
    /** 丢失后被重新识别，保留原轨迹ID。 */
    REIDENTIFIED,
    /** 已回收，终态，不再接受任何观测。 */
    RECYCLED
}
