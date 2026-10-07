package com.steve.ai.action;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure-logic tests for {@link Task} parameter reading.
 *
 * <p>Regression target: {@code getIntParameter} used to return the <b>default</b> whenever the
 * model typed the number as a string, so {@code {"quantity": "4"}} silently became
 * "craft 1" - the AI then made one item no matter what the player asked for, with nothing in
 * the logs to explain it.</p>
 */
public class TaskTest {

    private static Task task(String key, Object value) {
        Map<String, Object> params = new HashMap<>();
        if (value != null) {
            params.put(key, value);
        }
        return new Task("craft", params);
    }

    @Test
    void readsPlainNumbers() {
        assertEquals(4, task("quantity", 4).getIntParameter("quantity", 1));
        assertEquals(4, task("quantity", 4L).getIntParameter("quantity", 1));
        assertEquals(4, task("quantity", 4.0d).getIntParameter("quantity", 1));
        assertEquals(5, task("quantity", 4.6d).getIntParameter("quantity", 1));
    }

    @Test
    void readsNumericStrings() {
        assertEquals(4, task("quantity", "4").getIntParameter("quantity", 1));
        assertEquals(4, task("quantity", " 4 ").getIntParameter("quantity", 1));
        assertEquals(4, task("quantity", "4.0").getIntParameter("quantity", 1));
        assertEquals(4000, task("quantity", "4,000").getIntParameter("quantity", 1));
    }

    @Test
    void readsNumbersWithSurroundingText() {
        assertEquals(4, task("quantity", "4 个").getIntParameter("quantity", 1));
        assertEquals(4, task("quantity", "x4").getIntParameter("quantity", 1));
    }

    @Test
    void fallsBackWhenThereIsNoUsefulNumber() {
        assertEquals(1, task("quantity", null).getIntParameter("quantity", 1));
        assertEquals(1, task("quantity", "").getIntParameter("quantity", 1));
        assertEquals(1, task("quantity", "all").getIntParameter("quantity", 1));
        assertEquals(8, task("quantity", "很多").getIntParameter("quantity", 8));
        assertEquals(-1, task("limit", "-1").getIntParameter("limit", 8));
    }

    @Test
    void toleratesBooleans() {
        assertEquals(1, task("quantity", Boolean.TRUE).getIntParameter("quantity", 8));
        assertEquals(0, task("quantity", Boolean.FALSE).getIntParameter("quantity", 8));
    }

    @Test
    void stringParametersStillWork() {
        assertEquals("oak_planks", task("item", "oak_planks").getStringParameter("item", "x"));
        assertEquals("fallback", task("item", null).getStringParameter("item", "fallback"));
    }
}
