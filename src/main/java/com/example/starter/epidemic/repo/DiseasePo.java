package com.example.starter.epidemic.repo;

/**
 * disease_catalog 表实体：病种目录。category A=甲类(2h)，B=乙类(24h)。
 */
public class DiseasePo {
    public String code;
    public String name;
    public String category;
    public boolean enabled;
}
