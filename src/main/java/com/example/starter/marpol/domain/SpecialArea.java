package com.example.starter.marpol.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * 特殊区域合成多边形（表 special_area + 顶点）。
 */
public class SpecialArea {

    private Long areaId;
    private String areaCode;
    private String areaName;
    /** 顶点顺序列表，每个元素为 {纬度, 经度}（十进制度）。 */
    private List<double[]> vertices;

    public Long getAreaId() {
        return areaId;
    }

    public void setAreaId(Long areaId) {
        this.areaId = areaId;
    }

    public String getAreaCode() {
        return areaCode;
    }

    public void setAreaCode(String areaCode) {
        this.areaCode = areaCode;
    }

    public String getAreaName() {
        return areaName;
    }

    public void setAreaName(String areaName) {
        this.areaName = areaName;
    }

    public List<double[]> getVertices() {
        return vertices;
    }

    public void setVertices(List<double[]> vertices) {
        this.vertices = vertices = vertices == null ? List.of() : List.copyOf(vertices);
    }

    /** 顶点载体：seq 与经纬度。 */
    public record Vertex(int seq, BigDecimal latitude, BigDecimal longitude) {
    }
}
