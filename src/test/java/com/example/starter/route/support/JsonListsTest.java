package com.example.starter.route.support;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link JsonLists} 字符串数组往返编解码测试。
 */
class JsonListsTest {

    @Test
    void round_trips_simple_string_list() {
        List<String> values = List.of("N1", "N2", "N3");
        String json = JsonLists.writeStringList(values);
        assertEquals("[\"N1\",\"N2\",\"N3\"]", json);
        assertEquals(values, JsonLists.readStringList(json));
    }

    @Test
    void round_trips_empty_list() {
        assertEquals("[]", JsonLists.writeStringList(List.of()));
        assertEquals(List.of(), JsonLists.readStringList("[]"));
    }

    @Test
    void blank_or_null_decodes_to_empty() {
        assertEquals(List.of(), JsonLists.readStringList(null));
        assertEquals(List.of(), JsonLists.readStringList("   "));
    }

    @Test
    void escapes_special_characters() {
        List<String> values = List.of("a\"b", "c\\d", "e\nf");
        String json = JsonLists.writeStringList(values);
        assertEquals(values, JsonLists.readStringList(json));
    }
}
