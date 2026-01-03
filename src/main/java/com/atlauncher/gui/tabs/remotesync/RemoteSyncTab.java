/*
 * ATLauncher - https://github.com/ATLauncher/ATLauncher
 * Copyright (C) 2013-2022 ATLauncher
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.atlauncher.gui.tabs.remotesync;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.SpinnerNumberModel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;

import org.mini2Dx.gettext.GetText;

import com.atlauncher.data.RemoteSyncConfig;
import com.atlauncher.data.Server;
import com.atlauncher.gui.panels.HierarchyPanel;
import com.atlauncher.gui.panels.ServerProfilesPanel;
import com.atlauncher.gui.tabs.Tab;
import com.atlauncher.managers.ServerManager;
import com.atlauncher.network.RemoteSyncService;
import com.atlauncher.network.SshClient;

/**
 * Tab for configuring and managing remote server synchronization.
 * Allows pushing Minecraft server configurations to a remote server.
 */
public class RemoteSyncTab extends HierarchyPanel implements Tab {

    private JTabbedPane configTabbedPane;

    // Server selector
    private JComboBox<ServerItem> serverSelector;
    private Server selectedServer;
    private ServerProfilesPanel profilesPanel;

    // Connection fields
    private JTextField hostField;
    private JTextField portField;
    private JTextField usernameField;
    private JPasswordField passwordField;
    private JTextField remotePathField;
    private JComboBox<String> authMethodCombo;

    // Sync mode
    private JRadioButton remoteCommandsRadio;
    private JRadioButton fileTransferRadio;

    // Sync options
    private JCheckBox syncServerProperties;
    private JCheckBox syncMods;
    private JCheckBox syncConfigs;
    private JCheckBox syncWorld;
    private JCheckBox syncPlugins;

    // Version sync options
    private JCheckBox syncVersion;
    private JCheckBox cleanBeforeSync;
    private JCheckBox restartAfterSync;
    private JCheckBox useFastTransfer;
    private JSpinner parallelTransferSpinner;

    // Auto sync triggers
    private JCheckBox autoSyncOnLaunch;
    private JCheckBox autoSyncOnShutdown;

    // Log area
    private JTextArea logArea;

    // Current sync service (for cancellation)
    private volatile RemoteSyncService currentSyncService;
    private JButton cancelSyncBtn;

    public RemoteSyncTab() {
        super(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
    }

    @Override
    protected void onShow() {
        // Top - Server selector
        JPanel serverSelectorPanel = createServerSelectorPanel();
        add(serverSelectorPanel, BorderLayout.NORTH);

        // Main container with config on left, log on right
        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));

        // Left side - Configuration tabs
        configTabbedPane = new JTabbedPane(JTabbedPane.TOP);
        configTabbedPane.addTab(GetText.tr("Connection"), createConnectionPanel());
        configTabbedPane.addTab(GetText.tr("Sync Mode"), createSyncModePanel());
        configTabbedPane.addTab(GetText.tr("Sync Options"), createSyncOptionsPanel());

        // Profiles tab - initially empty, populated when server is selected
        profilesPanel = new ServerProfilesPanel(null);
        configTabbedPane.addTab(GetText.tr("Profiles"), profilesPanel);
        configTabbedPane.setEnabledAt(3, false); // Disabled until server selected

        mainPanel.add(configTabbedPane, BorderLayout.CENTER);

        // Right side - Log output
        JPanel logPanel = createLogPanel();
        mainPanel.add(logPanel, BorderLayout.EAST);

        // Bottom - Action buttons
        JPanel buttonPanel = createButtonPanel();
        mainPanel.add(buttonPanel, BorderLayout.SOUTH);

        add(mainPanel, BorderLayout.CENTER);

        // Subscribe to server list changes
        addDisposable(ServerManager.getServersObservable().subscribe(this::updateServerList));
    }

    private JPanel createServerSelectorPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, java.awt.Color.GRAY),
                BorderFactory.createEmptyBorder(5, 5, 5, 5)));

        panel.add(new JLabel(GetText.tr("Server") + ":"));

        serverSelector = new JComboBox<>();
        serverSelector.setPrototypeDisplayValue(new ServerItem(null)); // Sets minimum width
        serverSelector.addActionListener(e -> onServerSelected());
        panel.add(serverSelector);

        JButton refreshServersBtn = new JButton(GetText.tr("Refresh"));
        refreshServersBtn.addActionListener(e -> ServerManager.loadServers());
        panel.add(refreshServersBtn);

        JLabel infoLabel = new JLabel("<html><i>" + GetText.tr("Select a server to manage remote sync and profiles") + "</i></html>");
        infoLabel.setBorder(BorderFactory.createEmptyBorder(0, 20, 0, 0));
        panel.add(infoLabel);

        return panel;
    }

    private void updateServerList(List<Server> servers) {
        SwingUtilities.invokeLater(() -> {
            Server previousSelection = selectedServer;
            serverSelector.removeAllItems();

            // Add placeholder item
            serverSelector.addItem(new ServerItem(null));

            for (Server server : servers) {
                serverSelector.addItem(new ServerItem(server));
            }

            // Restore selection if possible
            if (previousSelection != null) {
                for (int i = 0; i < serverSelector.getItemCount(); i++) {
                    ServerItem item = serverSelector.getItemAt(i);
                    if (item.server != null && item.server.getName().equals(previousSelection.getName())) {
                        serverSelector.setSelectedIndex(i);
                        break;
                    }
                }
            }
        });
    }

    private void onServerSelected() {
        ServerItem item = (ServerItem) serverSelector.getSelectedItem();
        selectedServer = (item != null) ? item.server : null;

        // Update profiles tab
        if (selectedServer != null) {
            // Replace profiles panel with one for selected server
            int tabIndex = configTabbedPane.indexOfTab(GetText.tr("Profiles"));
            if (tabIndex != -1) {
                configTabbedPane.removeTabAt(tabIndex);
            }
            profilesPanel = new ServerProfilesPanel(selectedServer);
            configTabbedPane.addTab(GetText.tr("Profiles"), profilesPanel);
            configTabbedPane.setEnabledAt(configTabbedPane.indexOfTab(GetText.tr("Profiles")), true);

            // Load saved remote sync config
            loadConfigFromServer();

            appendLog(GetText.tr("Selected server: {0}", selectedServer.getName()));
        } else {
            // Disable profiles tab
            int tabIndex = configTabbedPane.indexOfTab(GetText.tr("Profiles"));
            if (tabIndex != -1) {
                configTabbedPane.setEnabledAt(tabIndex, false);
            }
        }
    }

    /**
     * Loads the remote sync config from the selected server into the UI fields.
     */
    private void loadConfigFromServer() {
        if (selectedServer == null) return;

        RemoteSyncConfig config = selectedServer.remoteSyncConfig;
        if (config == null) {
            // No saved config - use defaults
            return;
        }

        // Connection settings
        if (config.host != null) hostField.setText(config.host);
        portField.setText(String.valueOf(config.port));
        if (config.username != null) usernameField.setText(config.username);
        if (config.remotePath != null) remotePathField.setText(config.remotePath);

        // Auth method
        if ("password".equals(config.authMethod)) {
            authMethodCombo.setSelectedItem("Password");
        } else {
            authMethodCombo.setSelectedItem("SSH Key");
        }

        // Sync mode
        if ("transfer".equals(config.syncMode)) {
            fileTransferRadio.setSelected(true);
        } else {
            remoteCommandsRadio.setSelected(true);
        }

        // Sync options
        syncServerProperties.setSelected(config.syncServerProperties);
        syncMods.setSelected(config.syncMods);
        syncConfigs.setSelected(config.syncConfigs);
        syncPlugins.setSelected(config.syncPlugins);
        syncWorld.setSelected(config.syncWorld);

        // Version sync options
        syncVersion.setSelected(config.syncVersion);
        cleanBeforeSync.setSelected(config.cleanBeforeSync);
        restartAfterSync.setSelected(config.restartAfterSync);
        useFastTransfer.setSelected(config.useFastTransfer);
        parallelTransferSpinner.setValue(config.parallelTransferCount);

        // Auto sync
        autoSyncOnLaunch.setSelected(config.autoSyncOnLaunch);
        autoSyncOnShutdown.setSelected(config.autoSyncOnShutdown);

        appendLog("Loaded saved remote sync settings");
    }

    /**
     * Saves the current UI field values to the selected server's remote sync config.
     */
    private void saveConfigToServer() {
        if (selectedServer == null) return;

        selectedServer.remoteSyncConfig = buildConfig();
        selectedServer.save();
        appendLog("Remote sync settings saved");
    }

    private JPanel createConnectionPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;

        int row = 0;

        // Host
        gbc.gridx = 0; gbc.gridy = row;
        panel.add(new JLabel("Host:"), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        hostField = new JTextField(20);
        hostField.setToolTipText("Remote server hostname or IP address");
        panel.add(hostField, gbc);

        // Port
        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        panel.add(new JLabel("Port:"), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        portField = new JTextField("22", 6);
        portField.setToolTipText("SSH port (default: 22)");
        panel.add(portField, gbc);

        // Username
        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        panel.add(new JLabel("Username:"), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        usernameField = new JTextField(20);
        panel.add(usernameField, gbc);

        // Auth Method
        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        panel.add(new JLabel("Auth Method:"), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        authMethodCombo = new JComboBox<>(new String[]{"SSH Key", "Password"});
        panel.add(authMethodCombo, gbc);

        // Password
        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        panel.add(new JLabel("Password:"), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        passwordField = new JPasswordField(20);
        panel.add(passwordField, gbc);

        // Remote Path
        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        panel.add(new JLabel("Remote Path:"), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        remotePathField = new JTextField("/opt/minecraft/server", 20);
        remotePathField.setToolTipText("Path to Minecraft server directory on remote host");
        panel.add(remotePathField, gbc);

        // Spacer
        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.weighty = 1.0;
        panel.add(new JLabel(), gbc);

        return panel;
    }

    private JPanel createSyncModePanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.gridx = 0;

        int row = 0;

        // Mode selection header
        gbc.gridy = row;
        JLabel modeLabel = new JLabel("Select synchronization mode:");
        panel.add(modeLabel, gbc);

        // Remote Commands option
        row++;
        gbc.gridy = row;
        remoteCommandsRadio = new JRadioButton("Remote Commands (SSH)");
        remoteCommandsRadio.setSelected(true);
        panel.add(remoteCommandsRadio, gbc);

        row++;
        gbc.gridy = row;
        gbc.insets = new Insets(0, 25, 5, 5);
        JLabel remoteDesc = new JLabel("<html><i>Full control: start/stop server, stream logs, execute commands</i></html>");
        panel.add(remoteDesc, gbc);

        // File Transfer option
        row++;
        gbc.gridy = row;
        gbc.insets = new Insets(15, 5, 5, 5);
        fileTransferRadio = new JRadioButton("File Transfer Only (SFTP)");
        panel.add(fileTransferRadio, gbc);

        row++;
        gbc.gridy = row;
        gbc.insets = new Insets(0, 25, 5, 5);
        JLabel fileDesc = new JLabel("<html><i>Upload files only, no remote execution</i></html>");
        panel.add(fileDesc, gbc);

        // Group the radio buttons
        ButtonGroup modeGroup = new ButtonGroup();
        modeGroup.add(remoteCommandsRadio);
        modeGroup.add(fileTransferRadio);

        // Auto-sync triggers section
        row++;
        gbc.gridy = row;
        gbc.insets = new Insets(20, 5, 5, 5);
        JLabel triggersLabel = new JLabel("Auto-sync triggers:");
        panel.add(triggersLabel, gbc);

        row++;
        gbc.gridy = row;
        gbc.insets = new Insets(5, 15, 5, 5);
        autoSyncOnLaunch = new JCheckBox("Sync before starting remote server");
        panel.add(autoSyncOnLaunch, gbc);

        row++;
        gbc.gridy = row;
        autoSyncOnShutdown = new JCheckBox("Sync after local server shutdown");
        panel.add(autoSyncOnShutdown, gbc);

        // Spacer
        row++;
        gbc.gridy = row;
        gbc.weighty = 1.0;
        panel.add(new JLabel(), gbc);

        return panel;
    }

    private JPanel createSyncOptionsPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.gridx = 0;

        int row = 0;

        // Header
        gbc.gridy = row;
        JLabel headerLabel = new JLabel("Select what to synchronize:");
        panel.add(headerLabel, gbc);

        // server.properties
        row++;
        gbc.gridy = row;
        gbc.insets = new Insets(10, 15, 5, 5);
        syncServerProperties = new JCheckBox("server.properties");
        syncServerProperties.setSelected(true);
        syncServerProperties.setToolTipText("Core server configuration");
        panel.add(syncServerProperties, gbc);

        // mods/
        row++;
        gbc.gridy = row;
        gbc.insets = new Insets(5, 15, 5, 5);
        syncMods = new JCheckBox("mods/ folder");
        syncMods.setSelected(true);
        syncMods.setToolTipText("Mod JAR files (can be large)");
        panel.add(syncMods, gbc);

        // config/
        row++;
        gbc.gridy = row;
        syncConfigs = new JCheckBox("config/ folder");
        syncConfigs.setSelected(true);
        syncConfigs.setToolTipText("Mod configuration files");
        panel.add(syncConfigs, gbc);

        // plugins/
        row++;
        gbc.gridy = row;
        syncPlugins = new JCheckBox("plugins/ folder");
        syncPlugins.setToolTipText("For Paper/Spigot servers");
        panel.add(syncPlugins, gbc);

        // world/
        row++;
        gbc.gridy = row;
        gbc.insets = new Insets(15, 15, 5, 5);
        syncWorld = new JCheckBox("world/ folder (Warning: Very Large!)");
        syncWorld.setToolTipText("World data - can be very large and take significant time");
        panel.add(syncWorld, gbc);

        row++;
        gbc.gridy = row;
        gbc.insets = new Insets(0, 35, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        JLabel worldWarning = new JLabel("<html><font color='orange'>Only sync world data if you know what you're doing!</font></html>");
        panel.add(worldWarning, gbc);
        gbc.fill = GridBagConstraints.NONE;

        // Version sync section
        row++;
        gbc.gridy = row;
        gbc.insets = new Insets(20, 5, 5, 5);
        JLabel versionHeader = new JLabel("Version management:");
        panel.add(versionHeader, gbc);

        row++;
        gbc.gridy = row;
        gbc.insets = new Insets(5, 15, 5, 5);
        syncVersion = new JCheckBox("Sync Minecraft version to Docker");
        syncVersion.setSelected(true);
        syncVersion.setToolTipText("Creates .atlauncher.env with VERSION and TYPE for docker-compose");
        panel.add(syncVersion, gbc);

        row++;
        gbc.gridy = row;
        cleanBeforeSync = new JCheckBox("Clean remote folders before sync");
        cleanBeforeSync.setSelected(true);
        cleanBeforeSync.setToolTipText("Removes old mods/configs before uploading new ones");
        panel.add(cleanBeforeSync, gbc);

        row++;
        gbc.gridy = row;
        gbc.insets = new Insets(0, 35, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        JLabel cleanInfo = new JLabel("<html><i>Recommended to avoid leftover files from previous versions</i></html>");
        cleanInfo.setFont(cleanInfo.getFont().deriveFont(11f));
        panel.add(cleanInfo, gbc);
        gbc.fill = GridBagConstraints.NONE;

        row++;
        gbc.gridy = row;
        gbc.insets = new Insets(5, 20, 5, 5);
        restartAfterSync = new JCheckBox("Restart Docker container after sync");
        restartAfterSync.setSelected(true);
        restartAfterSync.setToolTipText("Stops container before sync and starts it after to apply version changes");
        panel.add(restartAfterSync, gbc);

        row++;
        gbc.gridy = row;
        gbc.insets = new Insets(0, 35, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        JLabel restartInfo = new JLabel("<html><i>Required for the container to download and run the new Minecraft version</i></html>");
        restartInfo.setFont(restartInfo.getFont().deriveFont(11f));
        panel.add(restartInfo, gbc);
        gbc.fill = GridBagConstraints.NONE;

        row++;
        gbc.gridy = row;
        gbc.insets = new Insets(10, 20, 5, 5);
        useFastTransfer = new JCheckBox("Use fast transfer (native scp)");
        useFastTransfer.setSelected(true);
        useFastTransfer.setToolTipText("Uses system scp command for much faster directory uploads");
        panel.add(useFastTransfer, gbc);

        row++;
        gbc.gridy = row;
        gbc.insets = new Insets(0, 35, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        JLabel fastInfo = new JLabel("<html><i>Much faster than SFTP, requires scp installed on system</i></html>");
        fastInfo.setFont(fastInfo.getFont().deriveFont(11f));
        panel.add(fastInfo, gbc);
        gbc.fill = GridBagConstraints.NONE;

        row++;
        gbc.gridy = row;
        gbc.insets = new Insets(10, 20, 5, 5);
        JPanel parallelPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        parallelPanel.add(new JLabel("Parallel transfers:"));
        parallelTransferSpinner = new JSpinner(new SpinnerNumberModel(3, 1, 32, 1));
        parallelTransferSpinner.setToolTipText("Number of simultaneous SFTP connections (1-32)");
        parallelPanel.add(parallelTransferSpinner);
        parallelPanel.add(new JLabel("(1=sequential, higher=faster)"));
        panel.add(parallelPanel, gbc);

        // Spacer
        row++;
        gbc.gridy = row;
        gbc.weighty = 1.0;
        panel.add(new JLabel(), gbc);

        return panel;
    }

    private JPanel createLogPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createTitledBorder("Sync Log"));
        panel.setPreferredSize(new java.awt.Dimension(350, 0));

        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12));
        logArea.setText("Remote sync log will appear here...\n\n" +
                "Configure connection settings and click\n" +
                "'Test Connection' to verify connectivity.\n\n" +
                "Then select your sync options and click\n" +
                "'Sync Now' to push configuration to remote.");

        JScrollPane scrollPane = new JScrollPane(logArea);
        panel.add(scrollPane, BorderLayout.CENTER);

        // Clear log button
        JButton clearLogBtn = new JButton("Clear Log");
        clearLogBtn.addActionListener(e -> logArea.setText(""));
        JPanel clearPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        clearPanel.add(clearLogBtn);
        panel.add(clearPanel, BorderLayout.SOUTH);

        return panel;
    }

    private JPanel createButtonPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.CENTER, 15, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));

        JButton saveSettingsBtn = new JButton("Save Settings");
        saveSettingsBtn.addActionListener(e -> saveConfigToServer());
        saveSettingsBtn.setToolTipText("Save connection settings for this server");

        JButton testConnectionBtn = new JButton("Test Connection");
        testConnectionBtn.addActionListener(e -> testConnection());

        JButton setupKeyBtn = new JButton("Setup SSH Key");
        setupKeyBtn.addActionListener(e -> setupSshKey());
        setupKeyBtn.setToolTipText("Generate SSH key for faster transfers (uses password to install)");

        JButton syncNowBtn = new JButton("Sync Now");
        syncNowBtn.addActionListener(e -> syncNow());

        JButton remoteStatusBtn = new JButton("Server Status");
        remoteStatusBtn.addActionListener(e -> checkServerStatus());
        remoteStatusBtn.setToolTipText("Check if Minecraft server is running on remote");

        JButton startServerBtn = new JButton("Start Remote Server");
        startServerBtn.addActionListener(e -> startRemoteServer());

        JButton stopServerBtn = new JButton("Stop Remote Server");
        stopServerBtn.addActionListener(e -> stopRemoteServer());

        cancelSyncBtn = new JButton("Cancel Sync");
        cancelSyncBtn.addActionListener(e -> cancelSync());
        cancelSyncBtn.setEnabled(false);
        cancelSyncBtn.setToolTipText("Cancel the current sync operation");

        panel.add(saveSettingsBtn);
        panel.add(testConnectionBtn);
        panel.add(setupKeyBtn);
        panel.add(syncNowBtn);
        panel.add(cancelSyncBtn);
        panel.add(remoteStatusBtn);
        panel.add(startServerBtn);
        panel.add(stopServerBtn);

        return panel;
    }

    private void cancelSync() {
        if (currentSyncService != null) {
            appendLog("[CANCEL] Cancelling sync operation...");
            currentSyncService.cancel();
            cancelSyncBtn.setEnabled(false);
        }
    }

    private void setupSshKey() {
        // Check password auth is selected
        if (!"Password".equals(authMethodCombo.getSelectedItem())) {
            appendLog("[ERROR] SSH key setup requires password authentication mode");
            return;
        }

        String password = new String(passwordField.getPassword());
        if (password.isEmpty()) {
            appendLog("[ERROR] Password required to install SSH key on remote server");
            return;
        }

        String validationError = validateConfig();
        if (validationError != null) {
            appendLog("[ERROR] " + validationError);
            return;
        }

        appendLog("--- Setting up SSH Key ---");
        appendLog("This will generate an SSH key pair and install it on the remote server.");
        appendLog("After setup, syncs will be faster using key-based authentication.");
        appendLog("");

        // Run in background
        new SwingWorker<Boolean, String>() {
            @Override
            protected Boolean doInBackground() {
                try {
                    RemoteSyncConfig config = buildConfig();
                    SshClient ssh = new SshClient(config);
                    ssh.setLogCallback(msg -> publish(msg));

                    return ssh.setupAutoKey();
                } catch (Exception e) {
                    publish("[ERROR] " + e.getMessage());
                    return false;
                }
            }

            @Override
            protected void process(List<String> chunks) {
                for (String msg : chunks) {
                    appendLog(msg);
                }
            }

            @Override
            protected void done() {
                try {
                    boolean success = get();
                    appendLog("");
                    if (success) {
                        appendLog("[SUCCESS] SSH key installed! Future syncs will use key authentication.");
                        appendLog("Key saved at: " + SshClient.getAutoKeyPath());
                    } else {
                        appendLog("[FAILED] Could not set up SSH key");
                    }
                    appendLog("--- Setup Complete ---");
                } catch (Exception e) {
                    appendLog("[ERROR] " + e.getMessage());
                }
            }
        }.execute();
    }

    /**
     * Builds a RemoteSyncConfig from the current UI field values.
     */
    private RemoteSyncConfig buildConfig() {
        RemoteSyncConfig config = new RemoteSyncConfig();
        config.host = hostField.getText().trim();
        try {
            config.port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException e) {
            config.port = 22;
        }
        config.username = usernameField.getText().trim();
        config.remotePath = remotePathField.getText().trim();

        // Auth method
        if ("Password".equals(authMethodCombo.getSelectedItem())) {
            config.authMethod = "password";
            config.password = new String(passwordField.getPassword());
        } else {
            config.authMethod = "key";
            // Uses default key path (~/.ssh/id_rsa)
        }

        // Sync mode
        config.syncMode = remoteCommandsRadio.isSelected() ? "commands" : "transfer";

        // Sync options
        config.syncServerProperties = syncServerProperties.isSelected();
        config.syncMods = syncMods.isSelected();
        config.syncConfigs = syncConfigs.isSelected();
        config.syncPlugins = syncPlugins.isSelected();
        config.syncWorld = syncWorld.isSelected();

        // Version sync options
        config.syncVersion = syncVersion.isSelected();
        config.cleanBeforeSync = cleanBeforeSync.isSelected();
        config.restartAfterSync = restartAfterSync.isSelected();
        config.useFastTransfer = useFastTransfer.isSelected();
        config.parallelTransferCount = (Integer) parallelTransferSpinner.getValue();

        // Auto sync
        config.autoSyncOnLaunch = autoSyncOnLaunch.isSelected();
        config.autoSyncOnShutdown = autoSyncOnShutdown.isSelected();

        return config;
    }

    /**
     * Validates the configuration and returns an error message, or null if valid.
     */
    private String validateConfig() {
        RemoteSyncConfig config = buildConfig();
        return config.validate();
    }

    private void testConnection() {
        String validationError = validateConfig();
        if (validationError != null) {
            appendLog("[ERROR] " + validationError);
            return;
        }

        appendLog("Testing connection to " + hostField.getText() + ":" + portField.getText() + "...");

        // Run in background thread
        new SwingWorker<RemoteSyncService.SyncResult, String>() {
            @Override
            protected RemoteSyncService.SyncResult doInBackground() {
                RemoteSyncConfig config = buildConfig();
                RemoteSyncService service = new RemoteSyncService(selectedServer, config);
                service.setLogCallback(msg -> publish(msg));
                return service.testConnection();
            }

            @Override
            protected void process(List<String> chunks) {
                for (String msg : chunks) {
                    appendLog(msg);
                }
            }

            @Override
            protected void done() {
                try {
                    RemoteSyncService.SyncResult result = get();
                    appendLog("");
                    if (result.success) {
                        appendLog("[SUCCESS] " + result.message);
                    } else {
                        appendLog("[FAILED] " + result.message);
                    }
                } catch (Exception e) {
                    appendLog("[ERROR] " + e.getMessage());
                }
            }
        }.execute();
    }

    private void syncNow() {
        // Check if server is selected
        if (selectedServer == null) {
            appendLog("[ERROR] No server selected. Please select a server first.");
            return;
        }

        // Check for unsaved changes before syncing
        if (profilesPanel != null && !profilesPanel.checkUnsavedChangesBeforeSync()) {
            appendLog("[INFO] Sync cancelled - please save a profile first if desired.");
            return;
        }

        String validationError = validateConfig();
        if (validationError != null) {
            appendLog("[ERROR] " + validationError);
            return;
        }

        appendLog("--- Starting Sync ---");
        appendLog("Server: " + selectedServer.getName());
        appendLog("Remote path: " + remotePathField.getText());
        appendLog("Mode: " + (remoteCommandsRadio.isSelected() ? "Remote Commands" : "File Transfer"));
        appendLog("");
        appendLog("Files to sync:");
        if (syncServerProperties.isSelected()) appendLog("  - server.properties");
        if (syncMods.isSelected()) appendLog("  - mods/");
        if (syncConfigs.isSelected()) appendLog("  - config/");
        if (syncPlugins.isSelected()) appendLog("  - plugins/");
        if (syncWorld.isSelected()) appendLog("  - world/ (WARNING: Large!)");
        appendLog("");

        // Enable cancel button
        cancelSyncBtn.setEnabled(true);

        // Run sync in background thread
        new SwingWorker<RemoteSyncService.SyncResult, String>() {
            @Override
            protected RemoteSyncService.SyncResult doInBackground() {
                RemoteSyncConfig config = buildConfig();
                RemoteSyncService service = new RemoteSyncService(selectedServer, config);
                currentSyncService = service;  // Store for cancellation
                service.setLogCallback(msg -> publish(msg));
                service.setProgressCallback(progress -> {
                    publish(String.format("[%d%%] %s (%d/%d)",
                            progress.getPercentage(),
                            progress.currentTask,
                            progress.completedTasks,
                            progress.totalTasks));
                });
                return service.sync();
            }

            @Override
            protected void process(List<String> chunks) {
                for (String msg : chunks) {
                    appendLog(msg);
                }
            }

            @Override
            protected void done() {
                currentSyncService = null;  // Clear service reference
                cancelSyncBtn.setEnabled(false);  // Disable cancel button
                try {
                    RemoteSyncService.SyncResult result = get();
                    appendLog("");
                    if (result.success) {
                        appendLog("[SUCCESS] " + result.message);
                    } else {
                        appendLog("[FAILED] " + result.message);
                    }
                    appendLog("--- Sync Complete ---");
                } catch (Exception e) {
                    appendLog("[ERROR] " + e.getMessage());
                    appendLog("--- Sync Failed ---");
                }
            }
        }.execute();
    }

    private void checkServerStatus() {
        String validationError = validateConfig();
        if (validationError != null) {
            appendLog("[ERROR] " + validationError);
            return;
        }

        appendLog("Checking remote server status...");

        new SwingWorker<RemoteSyncService.SyncResult, String>() {
            @Override
            protected RemoteSyncService.SyncResult doInBackground() {
                RemoteSyncConfig config = buildConfig();
                RemoteSyncService service = new RemoteSyncService(selectedServer, config);
                service.setLogCallback(msg -> publish(msg));
                return service.getServerStatus();
            }

            @Override
            protected void process(List<String> chunks) {
                for (String msg : chunks) {
                    appendLog(msg);
                }
            }

            @Override
            protected void done() {
                try {
                    RemoteSyncService.SyncResult result = get();
                    if (result.success) {
                        appendLog("[STATUS] " + result.message);
                    } else {
                        appendLog("[ERROR] " + result.message);
                    }
                } catch (Exception e) {
                    appendLog("[ERROR] " + e.getMessage());
                }
            }
        }.execute();
    }

    private void startRemoteServer() {
        String validationError = validateConfig();
        if (validationError != null) {
            appendLog("[ERROR] " + validationError);
            return;
        }

        appendLog("Starting remote Minecraft server...");

        new SwingWorker<RemoteSyncService.SyncResult, String>() {
            @Override
            protected RemoteSyncService.SyncResult doInBackground() {
                RemoteSyncConfig config = buildConfig();
                RemoteSyncService service = new RemoteSyncService(selectedServer, config);
                service.setLogCallback(msg -> publish(msg));
                return service.startRemoteServer();
            }

            @Override
            protected void process(List<String> chunks) {
                for (String msg : chunks) {
                    appendLog(msg);
                }
            }

            @Override
            protected void done() {
                try {
                    RemoteSyncService.SyncResult result = get();
                    if (result.success) {
                        appendLog("[SUCCESS] " + result.message);
                    } else {
                        appendLog("[ERROR] " + result.message);
                    }
                } catch (Exception e) {
                    appendLog("[ERROR] " + e.getMessage());
                }
            }
        }.execute();
    }

    private void stopRemoteServer() {
        String validationError = validateConfig();
        if (validationError != null) {
            appendLog("[ERROR] " + validationError);
            return;
        }

        appendLog("Stopping remote Minecraft server...");

        new SwingWorker<RemoteSyncService.SyncResult, String>() {
            @Override
            protected RemoteSyncService.SyncResult doInBackground() {
                RemoteSyncConfig config = buildConfig();
                RemoteSyncService service = new RemoteSyncService(selectedServer, config);
                service.setLogCallback(msg -> publish(msg));
                return service.stopRemoteServer();
            }

            @Override
            protected void process(List<String> chunks) {
                for (String msg : chunks) {
                    appendLog(msg);
                }
            }

            @Override
            protected void done() {
                try {
                    RemoteSyncService.SyncResult result = get();
                    if (result.success) {
                        appendLog("[SUCCESS] " + result.message);
                    } else {
                        appendLog("[ERROR] " + result.message);
                    }
                } catch (Exception e) {
                    appendLog("[ERROR] " + e.getMessage());
                }
            }
        }.execute();
    }

    private void appendLog(String message) {
        logArea.append(message + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    @Override
    public String getTitle() {
        return GetText.tr("Remote");
    }

    @Override
    public String getAnalyticsScreenViewName() {
        return "Remote";
    }

    @Override
    protected void createViewModel() {
        // No view model needed for now - this is a UI mockup
    }

    @Override
    protected void onDestroy() {
        removeAll();
        configTabbedPane = null;
        serverSelector = null;
        selectedServer = null;
        profilesPanel = null;
        hostField = null;
        portField = null;
        usernameField = null;
        passwordField = null;
        remotePathField = null;
        authMethodCombo = null;
        remoteCommandsRadio = null;
        fileTransferRadio = null;
        syncServerProperties = null;
        syncMods = null;
        syncConfigs = null;
        syncWorld = null;
        syncPlugins = null;
        autoSyncOnLaunch = null;
        autoSyncOnShutdown = null;
        logArea = null;
    }

    /**
     * Helper class to display Server in combo box with friendly name.
     */
    private static class ServerItem {
        final Server server;

        ServerItem(Server server) {
            this.server = server;
        }

        @Override
        public String toString() {
            if (server == null) {
                return GetText.tr("-- Select a Server --");
            }
            return server.getName();
        }
    }
}
