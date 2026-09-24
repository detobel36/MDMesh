/*
 * MDMesh agent-v1: queues a device's configuration apps for installation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 */

package com.hmdm.rest.resource.support;

import com.hmdm.notification.AgentWakeHub;
import com.hmdm.rest.json.InstallPayloadBuilder;
import com.hmdm.rest.json.UninstallPayloadBuilder;
import com.hmdm.persistence.AgentCommandDAO;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.AgentCommand;
import com.hmdm.persistence.domain.Application;
import com.hmdm.persistence.domain.Configuration;
import com.hmdm.persistence.domain.Device;
import com.hmdm.util.RolloutProgress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.List;

/**
 * Turns a device's configuration app list into queued {@code app.install} commands — the piece
 * that makes a configuration a "golden image" for the command-driven agent (which never reads the
 * configuration itself). Used at enrollment and by the admin "sync apps" action.
 */
@Singleton
public class ConfigAppInstaller {

    private static final Logger logger = LoggerFactory.getLogger(ConfigAppInstaller.class);

    /** Action value in configurationApplications meaning "install this app". */
    private static final int ACTION_INSTALL = 1;
    /** Action value in configurationApplications meaning "remove/uninstall this app if installed". */
    private static final int ACTION_REMOVE = 2;

    private final UnsecureDAO unsecureDAO;
    private final AgentCommandDAO commandDAO;
    private final AgentWakeHub wakeHub;

    @Inject
    public ConfigAppInstaller(UnsecureDAO unsecureDAO, AgentCommandDAO commandDAO, AgentWakeHub wakeHub) {
        this.unsecureDAO = unsecureDAO;
        this.commandDAO = commandDAO;
        this.wakeHub = wakeHub;
    }

    /**
     * Queue {@code app.install} for action=install apps and {@code app.uninstall} for action=remove apps.
     * Returns the total number queued. Never throws — callers treat this as best-effort.
     */
    public int enqueueConfigApps(Device device) {
        if (device == null || device.getConfigurationId() == null) {
            return 0;
        }
        int queued = 0;
        try {
            List<Application> apps = unsecureDAO.getPlainConfigurationApplications(
                    device.getCustomerId(), device.getConfigurationId());
            long now = System.currentTimeMillis();
            for (Application app : apps) {
                if (app == null) {
                    continue;
                }
                if (app.getAction() == ACTION_INSTALL) {
                    String url = firstUsableUrl(app);
                    boolean hasParts = app.getParts() != null && !app.getParts().trim().isEmpty();
                    if ((url == null && !hasParts) || app.getPkg() == null || app.getPkg().trim().isEmpty()) {
                        // Catalog placeholder / web app / seed leftover — nothing downloadable.
                        continue;
                    }
                    AgentCommand cmd = new AgentCommand();
                    cmd.setDeviceNumber(device.getNumber());
                    cmd.setType("app.install");
                    cmd.setPayload(InstallPayloadBuilder.build(app.getPkg().trim(), app.getVersionCode(), url, app.getParts()));
                    cmd.setRequiresCapability(RolloutProgress.INSTALL_CAPABILITY);
                    cmd.setStatus("pending");
                    cmd.setCreatedAt(now);
                    commandDAO.insert(cmd);
                    queued++;
                } else if (app.getAction() == ACTION_REMOVE) {
                    if (app.getPkg() == null || app.getPkg().trim().isEmpty()) {
                        continue;
                    }
                    AgentCommand cmd = new AgentCommand();
                    cmd.setDeviceNumber(device.getNumber());
                    cmd.setType("app.uninstall");
                    cmd.setPayload(UninstallPayloadBuilder.build(app.getPkg().trim()));
                    cmd.setRequiresCapability(RolloutProgress.INSTALL_CAPABILITY);
                    cmd.setStatus("pending");
                    cmd.setCreatedAt(now);
                    commandDAO.insert(cmd);
                    queued++;
                }
            }
            Configuration cfg = unsecureDAO.getConfigurationById(device.getConfigurationId());
            if (cfg != null && cfg.getDns() != null && !cfg.getDns().trim().isEmpty()) {
                AgentCommand cmd = new AgentCommand();
                cmd.setDeviceNumber(device.getNumber());
                cmd.setType("policy.apply");
                cmd.setPayload("{\"policy\":\"dns\",\"value\":\"" + cfg.getDns().trim().replace("\"", "\\\"") + "\"}");
                cmd.setRequiresCapability("policy.dns");
                cmd.setStatus("pending");
                cmd.setCreatedAt(now);
                commandDAO.insert(cmd);
                queued++;
            }

            if (queued > 0) {
                wakeHub.wake(device.getNumber(), "commands");
            }
        } catch (Exception e) {
            logger.warn("Failed to queue configuration apps for device {}", device.getNumber(), e);
        }
        return queued;
    }

    /**
     * Queue {@code app.install} / {@code app.uninstall} commands for all devices attached to [configurationId].
     */
    public int enqueueConfigAppsForConfiguration(int configurationId) {
        int totalQueued = 0;
        try {
            List<Device> devices = unsecureDAO.getDevicesByConfigurationId(configurationId);
            for (Device device : devices) {
                totalQueued += enqueueConfigApps(device);
            }
        } catch (Exception e) {
            logger.warn("Failed to queue config apps for configuration {}", configurationId, e);
        }
        return totalQueued;
    }

    /**
     * Only http(s) URLs are installable by the agent, and the upstream seed ships literal
     * placeholder URLs (e.g. {@code .../_HMDM_APK_}) that must never reach a device.
     */
    private static String firstUsableUrl(Application app) {
        for (String candidate : new String[]{app.getUrl(), app.getUrlArm64(), app.getUrlArmeabi()}) {
            if (candidate == null) {
                continue;
            }
            String u = candidate.trim();
            if ((u.startsWith("https://") || u.startsWith("http://")) && !u.contains("_HMDM_")) {
                return u;
            }
        }
        return null;
    }
}
