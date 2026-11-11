package com.hmdm.rest.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import static org.junit.Assert.*;

public class UninstallPayloadBuilderTest {
    private final ObjectMapper M = new ObjectMapper();

    @Test
    public void buildsValidUninstallPayload() throws Exception {
        String json = UninstallPayloadBuilder.build("com.acme.app");
        JsonNode n = M.readTree(json);
        assertEquals("com.acme.app", n.get("packageName").asText());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNullPackageName() {
        UninstallPayloadBuilder.build(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsBlankPackageName() {
        UninstallPayloadBuilder.build("   ");
    }
}
