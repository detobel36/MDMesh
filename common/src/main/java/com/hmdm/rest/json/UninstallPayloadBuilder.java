package com.hmdm.rest.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Builds an agent-v1 {@code app.uninstall} payload: {@code {"packageName":"com.example.app"}}.
 */
public final class UninstallPayloadBuilder {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private UninstallPayloadBuilder() {}

    public static String build(String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) {
            throw new IllegalArgumentException("packageName cannot be null or blank");
        }
        try {
            ObjectNode p = MAPPER.createObjectNode();
            p.put("packageName", packageName.trim());
            return p.toString();
        } catch (Exception e) {
            throw new IllegalArgumentException("bad uninstall payload input", e);
        }
    }
}
