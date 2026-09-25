package com.example.starter.wbb.domain;

/**
 * 舱单生命周期状态。
 * <ul>
 *   <li>PREPARED：预配完成；</li>
 *   <li>CHECK_IN：值机数据已录入；</li>
 *   <li>LOADED：装载确认；</li>
 *   <li>REVIEWED：双岗复核通过并冻结；</li>
 *   <li>CLOSED：放行关闭（终态）；</li>
 *   <li>SUPERSEDED：已被超限重打的新版本取代。</li>
 * </ul>
 */
public enum ManifestStatus {
    PREPARED,
    CHECK_IN,
    LOADED,
    REVIEWED,
    CLOSED,
    SUPERSEDED
}
