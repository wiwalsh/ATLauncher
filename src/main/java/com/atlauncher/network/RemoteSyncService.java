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
package com.atlauncher.network;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.atlauncher.data.RemoteSyncConfig;
import com.atlauncher.data.Server;
import com.atlauncher.data.minecraft.loaders.LoaderVersion;
import com.atlauncher.managers.LogManager;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.SftpException;

/**
 * Service for synchronizing server files to a remote server.
 */
public class RemoteSyncService {

    private final Server server;
    private final RemoteSyncConfig config;
    private Consumer<String> logCallback;
    private Consumer<SyncProgress> progressCallback;
    private volatile boolean cancelled = false;

    public RemoteSyncService(Server server, RemoteSyncConfig config) {
        this.server = server;
        this.config = config;
    }

    /**
     * Sets a callback for log messages.
     */
    public void setLogCallback(Consumer<String> callback) {
        this.logCallback = callback;
    }

    /**
     * Sets a callback for progress updates.
     */
    public void setProgressCallback(Consumer<SyncProgress> callback) {
        this.progressCallback = callback;
    }

    private void log(String message) {
        LogManager.info("[RemoteSync] " + message);
        if (logCallback != null) {
            logCallback.accept(message);
        }
    }

    private void updateProgress(String task, int current, int total) {
        if (progressCallback != null) {
            progressCallback.accept(new SyncProgress(task, current, total));
        }
    }

    /**
     * Cancels the current sync operation.
     */
    public void cancel() {
        this.cancelled = true;
        log("Sync cancelled by user");
    }

    /**
     * Tests the connection to the remote server.
     */
    public SyncResult testConnection() {
        log("Testing connection to " + config.host + "...");

        try (SshClient ssh = new SshClient(config)) {
            ssh.setLogCallback(this::log);
            ssh.connect();

            // Test basic command execution
            String result = ssh.executeCommand("echo 'ATLauncher connection test' && uname -a");
            log("Remote system: " + result);

            // Check if remote path exists
            boolean pathExists = ssh.exists(config.remotePath);
            if (!pathExists) {
                log("WARNING: Remote path does not exist: " + config.remotePath);
                return new SyncResult(false, "Remote path does not exist: " + config.remotePath);
            }

            log("Connection test successful!");
            return new SyncResult(true, "Connection successful");

        } catch (JSchException e) {
            String msg = "Connection failed: " + e.getMessage();
            log(msg);
            LogManager.logStackTrace("SSH connection failed", e);
            return new SyncResult(false, msg);
        } catch (IOException e) {
            String msg = "IO error: " + e.getMessage();
            log(msg);
            return new SyncResult(false, msg);
        }
    }

    /**
     * Gets the status of the remote Minecraft server.
     */
    public SyncResult getServerStatus() {
        log("Checking remote server status...");

        try (SshClient ssh = new SshClient(config)) {
            ssh.setLogCallback(this::log);
            ssh.connect();

            String result = ssh.executeCommand(config.statusCommand);

            if (result.isEmpty()) {
                return new SyncResult(true, "Server is not running");
            } else {
                return new SyncResult(true, "Server status: " + result);
            }

        } catch (Exception e) {
            String msg = "Failed to check status: " + e.getMessage();
            log(msg);
            return new SyncResult(false, msg);
        }
    }

    /**
     * Starts the remote Minecraft server.
     */
    public SyncResult startRemoteServer() {
        log("Starting remote server...");

        try (SshClient ssh = new SshClient(config)) {
            ssh.setLogCallback(this::log);
            ssh.connect();

            String result = ssh.executeCommand(config.startCommand);
            log("Start command result: " + result);

            // Wait a moment and check status
            Thread.sleep(2000);
            String status = ssh.executeCommand(config.statusCommand);

            return new SyncResult(true, "Server started. Status: " + status);

        } catch (Exception e) {
            String msg = "Failed to start server: " + e.getMessage();
            log(msg);
            return new SyncResult(false, msg);
        }
    }

    /**
     * Stops the remote Minecraft server.
     */
    public SyncResult stopRemoteServer() {
        log("Stopping remote server...");

        try (SshClient ssh = new SshClient(config)) {
            ssh.setLogCallback(this::log);
            ssh.connect();

            String result = ssh.executeCommand(config.stopCommand);
            log("Stop command result: " + result);

            return new SyncResult(true, "Server stopped");

        } catch (Exception e) {
            String msg = "Failed to stop server: " + e.getMessage();
            log(msg);
            return new SyncResult(false, msg);
        }
    }

    /**
     * Gets the Docker TYPE value for the itzg/minecraft-server image based on the loader.
     */
    private String getDockerType() {
        LoaderVersion loader = server.getLoaderVersion();
        if (loader == null) {
            return "VANILLA";
        }
        if (loader.isForge()) {
            return "FORGE";
        }
        if (loader.isNeoForge()) {
            return "NEOFORGE";
        }
        if (loader.isFabric()) {
            return "FABRIC";
        }
        if (loader.isQuilt()) {
            return "QUILT";
        }
        if (loader.isPaper()) {
            return "PAPER";
        }
        if (loader.isPurpur()) {
            return "PURPUR";
        }
        return "VANILLA";
    }

    /**
     * Gets the loader version string for Docker (e.g., Forge version).
     */
    private String getLoaderVersion() {
        LoaderVersion loader = server.getLoaderVersion();
        if (loader == null) {
            return null;
        }
        return loader.version;
    }

    /**
     * Cleans remote directories before sync to ensure fresh install.
     */
    public SyncResult cleanRemoteDirectories() {
        log("Cleaning remote directories...");

        try (SshClient ssh = new SshClient(config)) {
            ssh.setLogCallback(this::log);
            ssh.connect();

            // Clean mods directory
            if (config.syncMods) {
                log("Cleaning mods/...");
                ssh.executeCommand("rm -rf " + config.remotePath + "/mods/*");
            }

            // Clean config directory
            if (config.syncConfigs) {
                log("Cleaning config/...");
                ssh.executeCommand("rm -rf " + config.remotePath + "/config/*");
            }

            // Clean plugins directory
            if (config.syncPlugins) {
                log("Cleaning plugins/...");
                ssh.executeCommand("rm -rf " + config.remotePath + "/plugins/*");
            }

            log("Remote directories cleaned");
            return new SyncResult(true, "Directories cleaned");

        } catch (Exception e) {
            String msg = "Failed to clean directories: " + e.getMessage();
            log(msg);
            return new SyncResult(false, msg);
        }
    }

    /**
     * Updates the remote Docker container's Minecraft version and type.
     * Creates a .env file that docker-compose can use.
     */
    public SyncResult updateRemoteVersion() {
        String mcVersion = server.getMinecraftVersion();
        String dockerType = getDockerType();
        String loaderVer = getLoaderVersion();

        log("Updating remote server version...");
        log("  Minecraft version: " + mcVersion);
        log("  Server type: " + dockerType);
        if (loaderVer != null) {
            log("  Loader version: " + loaderVer);
        }

        try (SshClient ssh = new SshClient(config)) {
            ssh.setLogCallback(this::log);
            ssh.connect();

            // Create .env file for docker-compose
            StringBuilder envContent = new StringBuilder();
            envContent.append("# Auto-generated by ATLauncher Remote Sync\n");
            envContent.append("MC_VERSION=").append(mcVersion).append("\n");
            envContent.append("MC_TYPE=").append(dockerType).append("\n");
            if (loaderVer != null) {
                // Set loader-specific version variable
                if ("FORGE".equals(dockerType)) {
                    envContent.append("FORGE_VERSION=").append(loaderVer).append("\n");
                } else if ("NEOFORGE".equals(dockerType)) {
                    envContent.append("NEOFORGE_VERSION=").append(loaderVer).append("\n");
                } else if ("FABRIC".equals(dockerType)) {
                    envContent.append("FABRIC_LOADER_VERSION=").append(loaderVer).append("\n");
                } else if ("QUILT".equals(dockerType)) {
                    envContent.append("QUILT_LOADER_VERSION=").append(loaderVer).append("\n");
                }
            }

            // Write .env file to remote
            String envPath = config.remotePath + "/.atlauncher.env";
            String cmd = "cat > " + envPath + " << 'ATLENV'\n" + envContent.toString() + "ATLENV";
            ssh.executeCommand(cmd);

            log("Created " + envPath);
            log("");
            log("NOTE: Update your docker-compose.yml to use these variables:");
            log("  environment:");
            log("    VERSION: ${MC_VERSION}");
            log("    TYPE: ${MC_TYPE}");
            log("  env_file:");
            log("    - " + config.remotePath + "/.atlauncher.env");

            return new SyncResult(true, "Version updated: " + mcVersion + " (" + dockerType + ")");

        } catch (Exception e) {
            String msg = "Failed to update version: " + e.getMessage();
            log(msg);
            return new SyncResult(false, msg);
        }
    }

    /**
     * Synchronizes server files to the remote server.
     */
    public SyncResult sync() {
        cancelled = false;
        log("=== Starting sync to " + config.host + " ===");
        log("Local server: " + server.getName());
        log("Remote path: " + config.remotePath);
        log("Minecraft version: " + server.getMinecraftVersion());
        log("Server type: " + getDockerType());

        // Update version info on remote if enabled
        if (config.syncVersion) {
            SyncResult versionResult = updateRemoteVersion();
            if (!versionResult.success) {
                return versionResult;
            }
        }

        // Clean remote directories if enabled
        if (config.cleanBeforeSync) {
            SyncResult cleanResult = cleanRemoteDirectories();
            if (!cleanResult.success) {
                return cleanResult;
            }
        }

        List<SyncTask> tasks = buildSyncTasks();
        if (tasks.isEmpty()) {
            log("No files selected for sync");
            return new SyncResult(false, "No files selected for sync");
        }

        log("Sync tasks: " + tasks.size());

        try (SshClient ssh = new SshClient(config)) {
            ssh.setLogCallback(this::log);
            ssh.connect();

            int completed = 0;
            for (SyncTask task : tasks) {
                if (cancelled) {
                    return new SyncResult(false, "Sync cancelled");
                }

                updateProgress(task.description, completed, tasks.size());
                log("Syncing: " + task.description);

                try {
                    if (task.isDirectory) {
                        if (Files.exists(task.localPath) && Files.isDirectory(task.localPath)) {
                            ssh.uploadDirectory(task.localPath, task.remotePath, null);
                        } else {
                            log("  Skipping (not found): " + task.localPath);
                        }
                    } else {
                        if (Files.exists(task.localPath)) {
                            ssh.uploadFile(task.localPath, task.remotePath);
                        } else {
                            log("  Skipping (not found): " + task.localPath);
                        }
                    }
                } catch (SftpException e) {
                    log("  ERROR: " + e.getMessage());
                    LogManager.logStackTrace("SFTP error during sync", e);
                } catch (IOException e) {
                    log("  ERROR: " + e.getMessage());
                    LogManager.logStackTrace("IO error during sync", e);
                }

                completed++;
            }

            updateProgress("Complete", tasks.size(), tasks.size());
            log("=== Sync complete ===");
            return new SyncResult(true, "Sync completed successfully");

        } catch (JSchException e) {
            String msg = "Connection failed: " + e.getMessage();
            log(msg);
            LogManager.logStackTrace("SSH connection failed during sync", e);
            return new SyncResult(false, msg);
        }
    }

    /**
     * Builds the list of sync tasks based on configuration.
     */
    private List<SyncTask> buildSyncTasks() {
        List<SyncTask> tasks = new ArrayList<>();
        Path serverRoot = server.getRoot();

        // server.properties
        if (config.syncServerProperties) {
            Path propsFile = serverRoot.resolve("server.properties");
            if (Files.exists(propsFile)) {
                tasks.add(new SyncTask(
                        "server.properties",
                        propsFile,
                        config.remotePath + "/server.properties",
                        false
                ));
            }
        }

        // mods folder
        if (config.syncMods) {
            Path modsDir = serverRoot.resolve("mods");
            if (Files.exists(modsDir) && Files.isDirectory(modsDir)) {
                tasks.add(new SyncTask(
                        "mods/",
                        modsDir,
                        config.remotePath + "/mods",
                        true
                ));
            }
        }

        // config folder
        if (config.syncConfigs) {
            Path configDir = serverRoot.resolve("config");
            if (Files.exists(configDir) && Files.isDirectory(configDir)) {
                tasks.add(new SyncTask(
                        "config/",
                        configDir,
                        config.remotePath + "/config",
                        true
                ));
            }
        }

        // plugins folder
        if (config.syncPlugins) {
            Path pluginsDir = serverRoot.resolve("plugins");
            if (Files.exists(pluginsDir) && Files.isDirectory(pluginsDir)) {
                tasks.add(new SyncTask(
                        "plugins/",
                        pluginsDir,
                        config.remotePath + "/plugins",
                        true
                ));
            }
        }

        // world folder (optional, can be very large)
        if (config.syncWorld) {
            Path worldDir = serverRoot.resolve("world");
            if (Files.exists(worldDir) && Files.isDirectory(worldDir)) {
                tasks.add(new SyncTask(
                        "world/",
                        worldDir,
                        config.remotePath + "/world",
                        true
                ));
            }
        }

        return tasks;
    }

    /**
     * Represents a single sync task.
     */
    private static class SyncTask {
        final String description;
        final Path localPath;
        final String remotePath;
        final boolean isDirectory;

        SyncTask(String description, Path localPath, String remotePath, boolean isDirectory) {
            this.description = description;
            this.localPath = localPath;
            this.remotePath = remotePath;
            this.isDirectory = isDirectory;
        }
    }

    /**
     * Result of a sync operation.
     */
    public static class SyncResult {
        public final boolean success;
        public final String message;

        public SyncResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }

    /**
     * Progress information for sync operations.
     */
    public static class SyncProgress {
        public final String currentTask;
        public final int completedTasks;
        public final int totalTasks;

        public SyncProgress(String currentTask, int completedTasks, int totalTasks) {
            this.currentTask = currentTask;
            this.completedTasks = completedTasks;
            this.totalTasks = totalTasks;
        }

        public int getPercentage() {
            if (totalTasks == 0) return 0;
            return (completedTasks * 100) / totalTasks;
        }
    }
}
