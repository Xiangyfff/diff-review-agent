package com.codereview.agent.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonRepliesTest {

    @Test
    void readsAPlainObject() {
        assertThat(JsonReplies.object("{\"a\":1}").path("a").asInt()).isEqualTo(1);
    }

    @Test
    void stripsAMarkdownFence() {
        String reply = "```json\n{\"a\":\"b\"}\n```";
        assertThat(JsonReplies.text(JsonReplies.object(reply), "a")).isEqualTo("b");
    }

    @Test
    void ignoresProseAroundTheObject() {
        String reply = "好的，结果如下：{\"a\":true} 希望有帮助。";
        assertThat(JsonReplies.bool(JsonReplies.object(reply), "a", false)).isTrue();
    }

    @Test
    void refusesAnEmptyReply() {
        assertThatThrownBy(() -> JsonReplies.object("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void refusesAJsonArray() {
        assertThatThrownBy(() -> JsonReplies.object("[1,2]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no JSON object");
    }

    @Test
    void refusesBrokenJson() {
        assertThatThrownBy(() -> JsonReplies.object("{\"a\": }"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not valid JSON");
    }

    @Test
    void collectsOnlyNonBlankStrings() {
        var node = JsonReplies.object("{\"a\":[\"x\",\" \",3,\"y\"]}");
        assertThat(JsonReplies.strings(node, "a")).containsExactly("x", "y");
    }

    @Test
    void fallsBackWhenAFieldHasTheWrongType() {
        var node = JsonReplies.object("{\"n\":\"3\",\"b\":\"true\"}");
        assertThat(JsonReplies.integer(node, "n", 7)).isEqualTo(7);
        assertThat(JsonReplies.bool(node, "b", false)).isFalse();
    }
}
