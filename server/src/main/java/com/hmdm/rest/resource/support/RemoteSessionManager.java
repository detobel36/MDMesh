/*
 * Headwind MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * Copyright (C) 2019 Headwind Solutions LLC (http://h-sms.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hmdm.rest.resource.support;

import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * In-memory manager for active remote viewing sessions and SDP/ICE signaling queues.
 */
@Singleton
public class RemoteSessionManager {

    public static class RemoteSession {
        private final String sessionId;
        private final String deviceNumber;
        private final int customerId;
        private final String mode;
        private final long createdAt;
        private volatile boolean active;

        private final ConcurrentLinkedQueue<Object> agentSignals = new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<Object> browserSignals = new ConcurrentLinkedQueue<>();

        public RemoteSession(String sessionId, String deviceNumber, int customerId, String mode) {
            this.sessionId = sessionId;
            this.deviceNumber = deviceNumber;
            this.customerId = customerId;
            this.mode = mode;
            this.createdAt = System.currentTimeMillis();
            this.active = true;
        }

        public String getSessionId() { return sessionId; }
        public String getDeviceNumber() { return deviceNumber; }
        public int getCustomerId() { return customerId; }
        public String getMode() { return mode; }
        public long getCreatedAt() { return createdAt; }
        public boolean isActive() { return active; }
        public void stop() { this.active = false; }

        public void pushAgentSignal(Object signal) {
            if (active) agentSignals.add(signal);
        }

        public List<Object> pollAgentSignals() {
            List<Object> list = new ArrayList<>();
            Object item;
            while ((item = agentSignals.poll()) != null) {
                list.add(item);
            }
            return list;
        }

        public void pushBrowserSignal(Object signal) {
            if (active) browserSignals.add(signal);
        }

        public List<Object> pollBrowserSignals() {
            List<Object> list = new ArrayList<>();
            Object item;
            while ((item = browserSignals.poll()) != null) {
                list.add(item);
            }
            return list;
        }
    }

    private final Map<String, RemoteSession> sessions = new ConcurrentHashMap<>();

    public RemoteSession createSession(String sessionId, String deviceNumber, int customerId, String mode) {
        for (RemoteSession existing : sessions.values()) {
            if (existing.getDeviceNumber().equals(deviceNumber)) {
                existing.stop();
                sessions.remove(existing.getSessionId());
            }
        }
        RemoteSession session = new RemoteSession(sessionId, deviceNumber, customerId, mode);
        sessions.put(sessionId, session);
        return session;
    }

    public RemoteSession getSession(String sessionId) {
        RemoteSession s = sessions.get(sessionId);
        if (s == null) return null;
        if (System.currentTimeMillis() - s.getCreatedAt() > 10 * 60 * 1000L) {
            s.stop();
            sessions.remove(sessionId);
            return null;
        }
        return s;
    }

    public void stopSession(String sessionId) {
        RemoteSession s = sessions.remove(sessionId);
        if (s != null) {
            s.stop();
        }
    }
}
