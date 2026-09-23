package com.hmdm.rest.resource.support;

import com.hmdm.notification.AgentWakeHub;
import com.hmdm.persistence.AgentCommandDAO;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.AgentCommand;
import com.hmdm.persistence.domain.Application;
import com.hmdm.persistence.domain.Device;
import com.hmdm.util.RolloutProgress;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class ConfigAppInstallerTest {

    static class FakeUnsecureDAO extends UnsecureDAO {
        List<Application> appsToReturn = new ArrayList<>();
        List<Device> devicesToReturn = new ArrayList<>();

        public FakeUnsecureDAO() {
            super(null, null, null, null, null, null, null, null, null, null, null, null, null, null, "/tmp", 1, "com.hmdm.launcher");
        }

        @Override
        public List<Application> getPlainConfigurationApplications(Integer customerId, Integer id) {
            return appsToReturn;
        }

        @Override
        public List<Device> getDevicesByConfigurationId(int configurationId) {
            return devicesToReturn;
        }
    }

    static class FakeCommandDAO extends AgentCommandDAO {
        List<AgentCommand> insertedCommands = new ArrayList<>();

        public FakeCommandDAO() {
            super(null, null, null, null);
        }

        @Override
        public void insert(AgentCommand command) {
            insertedCommands.add(command);
        }
    }

    static class FakeWakeHub extends AgentWakeHub {
        List<String> wokenDevices = new ArrayList<>();

        public FakeWakeHub() {
            super(null);
        }

        @Override
        public void wake(String deviceNumber, String topic) {
            wokenDevices.add(deviceNumber);
        }
    }

    @Test
    public void enqueuesInstallAndUninstallCommands() {
        FakeUnsecureDAO unsecureDAO = new FakeUnsecureDAO();
        FakeCommandDAO commandDAO = new FakeCommandDAO();
        FakeWakeHub wakeHub = new FakeWakeHub();
        ConfigAppInstaller installer = new ConfigAppInstaller(unsecureDAO, commandDAO, wakeHub);

        Device device = new Device();
        device.setNumber("DEV001");
        device.setCustomerId(1);
        device.setConfigurationId(10);

        Application installApp = new Application();
        installApp.setAction(1);
        installApp.setPkg("com.example.install");
        installApp.setUrl("https://example.com/app.apk");

        Application removeApp = new Application();
        removeApp.setAction(2);
        removeApp.setPkg("com.example.remove");

        unsecureDAO.appsToReturn = Arrays.asList(installApp, removeApp);

        int queued = installer.enqueueConfigApps(device);

        assertEquals(2, queued);
        assertEquals(2, commandDAO.insertedCommands.size());

        AgentCommand cmd1 = commandDAO.insertedCommands.get(0);
        assertEquals("DEV001", cmd1.getDeviceNumber());
        assertEquals("app.install", cmd1.getType());
        assertEquals(RolloutProgress.INSTALL_CAPABILITY, cmd1.getRequiresCapability());

        AgentCommand cmd2 = commandDAO.insertedCommands.get(1);
        assertEquals("DEV001", cmd2.getDeviceNumber());
        assertEquals("app.uninstall", cmd2.getType());
        assertEquals("{\"packageName\":\"com.example.remove\"}", cmd2.getPayload());
        assertEquals(RolloutProgress.INSTALL_CAPABILITY, cmd2.getRequiresCapability());

        assertEquals(1, wakeHub.wokenDevices.size());
        assertEquals("DEV001", wakeHub.wokenDevices.get(0));
    }

    @Test
    public void enqueueConfigAppsForConfigurationProcessesAllDevices() {
        FakeUnsecureDAO unsecureDAO = new FakeUnsecureDAO();
        FakeCommandDAO commandDAO = new FakeCommandDAO();
        FakeWakeHub wakeHub = new FakeWakeHub();
        ConfigAppInstaller installer = new ConfigAppInstaller(unsecureDAO, commandDAO, wakeHub);

        Device device1 = new Device();
        device1.setNumber("DEV1");
        device1.setCustomerId(1);
        device1.setConfigurationId(10);

        Device device2 = new Device();
        device2.setNumber("DEV2");
        device2.setCustomerId(1);
        device2.setConfigurationId(10);

        Application removeApp = new Application();
        removeApp.setAction(2);
        removeApp.setPkg("com.example.remove");

        unsecureDAO.devicesToReturn = Arrays.asList(device1, device2);
        unsecureDAO.appsToReturn = Collections.singletonList(removeApp);

        int totalQueued = installer.enqueueConfigAppsForConfiguration(10);

        assertEquals(2, totalQueued);
        assertEquals(2, commandDAO.insertedCommands.size());
        assertEquals(2, wakeHub.wokenDevices.size());
        assertEquals("DEV1", wakeHub.wokenDevices.get(0));
        assertEquals("DEV2", wakeHub.wokenDevices.get(1));
    }
}
