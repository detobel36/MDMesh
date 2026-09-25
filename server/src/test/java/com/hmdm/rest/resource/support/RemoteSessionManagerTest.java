package com.hmdm.rest.resource.support;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

public class RemoteSessionManagerTest {

    private RemoteSessionManager manager;

    @Before
    public void setUp() {
        manager = new RemoteSessionManager();
    }

    @Test
    public void testCreateAndGetSession() {
        RemoteSessionManager.RemoteSession session = manager.createSession("sess-1", "dev-100", 1, "view");
        Assert.assertNotNull(session);
        Assert.assertEquals("sess-1", session.getSessionId());
        Assert.assertEquals("dev-100", session.getDeviceNumber());
        Assert.assertEquals(1, session.getCustomerId());
        Assert.assertTrue(session.isActive());

        RemoteSessionManager.RemoteSession retrieved = manager.getSession("sess-1");
        Assert.assertEquals(session, retrieved);
    }

    @Test
    public void testSignalingQueues() {
        RemoteSessionManager.RemoteSession session = manager.createSession("sess-1", "dev-100", 1, "view");

        session.pushAgentSignal("agent-offer");
        List<Object> agentSignals = session.pollAgentSignals();
        Assert.assertEquals(1, agentSignals.size());
        Assert.assertEquals("agent-offer", agentSignals.get(0));
        Assert.assertTrue(session.pollAgentSignals().isEmpty());

        session.pushBrowserSignal("browser-answer");
        List<Object> browserSignals = session.pollBrowserSignals();
        Assert.assertEquals(1, browserSignals.size());
        Assert.assertEquals("browser-answer", browserSignals.get(0));
        Assert.assertTrue(session.pollBrowserSignals().isEmpty());
    }

    @Test
    public void testStopSession() {
        manager.createSession("sess-1", "dev-100", 1, "view");
        manager.stopSession("sess-1");

        Assert.assertNull(manager.getSession("sess-1"));
    }
}
