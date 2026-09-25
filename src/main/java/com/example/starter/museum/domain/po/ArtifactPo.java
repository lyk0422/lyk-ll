package com.example.starter.museum.domain.po;

import java.time.Instant;

/** 文物目录行。grade：1=一级，2=二级，3=三级。 */
public class ArtifactPo {
    public Long id;
    public String artifactNo;
    public String name;
    public Integer grade;
    public String material;
    public Instant createdAt;
}
