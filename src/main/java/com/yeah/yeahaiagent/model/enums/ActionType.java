package com.yeah.yeahaiagent.model.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 行程多轮微调动作：替换、追加、插到锚点前，或删除一站。
 */
public enum ActionType {
    REPLACE,
    APPEND,
    INSERT_BEFORE,
    DELETE;

    @JsonValue
    public String json() {
        return name();
    }

    @JsonCreator
    public static ActionType from(String raw) {
        if (raw == null || raw.isBlank()) {
            return APPEND;
        }
        String normalized = raw.trim().replace('-', '_').replace(' ', '_');
        if ("REPLACE".equalsIgnoreCase(normalized)
                || "SWAP".equalsIgnoreCase(normalized)
                || "CHANGE".equalsIgnoreCase(normalized)) {
            return REPLACE;
        }
        if ("APPEND".equalsIgnoreCase(normalized)
                || "ADD".equalsIgnoreCase(normalized)
                || "INSERT".equalsIgnoreCase(normalized)
                || "INSERT_AFTER".equalsIgnoreCase(normalized)) {
            return APPEND;
        }
        if ("INSERT_BEFORE".equalsIgnoreCase(normalized)
                || "INSERTBEFORE".equalsIgnoreCase(normalized)
                || "PREPEND".equalsIgnoreCase(normalized)) {
            return INSERT_BEFORE;
        }
        if ("DELETE".equalsIgnoreCase(normalized) || "REMOVE".equalsIgnoreCase(normalized)) {
            return DELETE;
        }
        return APPEND;
    }

    public boolean inserts() {
        return this == APPEND || this == INSERT_BEFORE;
    }
}
