package com.example.starter.pest;

import java.util.List;

/**
 * 档案库区。
 *
 * @param id 主键
 * @param code 库区编码，唯一
 * @param name 库区名称
 * @param adjacentCodes 相邻库区编码列表，空列表表示无相邻库区
 */
public record Zone(long id, String code, String name, List<String> adjacentCodes) {
}
