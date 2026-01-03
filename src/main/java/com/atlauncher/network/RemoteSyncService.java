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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.jcraft.jsch.ChannelSftp;

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
            log("Waiting for container to start...");
            Thread.sleep(3000);
            String status = ssh.executeCommand(config.statusCommand);

            if (status != null && !status.isEmpty()) {
                log("=== CONTAINER STATUS ===");
                log("Docker container is running: " + status.trim());
                log("========================");
            } else {
                log("WARNING: Container may not have started properly");
            }

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

        // Stop the container before syncing if restart is enabled
        if (config.restartAfterSync) {
            log("Stopping remote server before sync...");
            SyncResult stopResult = stopRemoteServer();
            if (!stopResult.success) {
                log("Warning: Could not stop server (may not be running): " + stopResult.message);
                // Continue anyway - server might not be running
            }
        }

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
        int parallelCount = Math.max(1, config.parallelTransferCount);  // No upper limit
        log("Parallel workers: " + parallelCount);

        // Use parallel execution if more than 1 thread
        if (parallelCount > 1) {
            log("Using parallel native scp with " + parallelCount + " workers (shared file queue)...");

            // Check for SSH key
            if (!SshClient.hasAutoKey()) {
                log("WARNING: No SSH key found. Run 'Setup SSH Key' first for parallel transfers.");
                log("Falling back to sequential SFTP...");
            } else {
                String keyPath = SshClient.getAutoKeyPath().toString().replace("\\", "/");
                log("Using SSH key: " + keyPath);

                // Build a flat queue of ALL individual files from all directories
                BlockingQueue<FileUploadTask> fileQueue = new LinkedBlockingQueue<>();

                try {
                    // First create all remote directories using a single connection
                    try (SshClient setupSsh = new SshClient(config)) {
                        setupSsh.setLogCallback(this::log);
                        setupSsh.connect();

                        for (SyncTask task : tasks) {
                            if (task.isDirectory && Files.exists(task.localPath)) {
                                // Create the base directory and all subdirectories
                                setupSsh.executeCommand("mkdir -p " + task.remotePath);
                                try (Stream<Path> walk = Files.walk(task.localPath)) {
                                    walk.filter(Files::isDirectory).forEach(dir -> {
                                        Path relativePath = task.localPath.relativize(dir);
                                        if (!relativePath.toString().isEmpty()) {
                                            String remoteDir = task.remotePath + "/" + relativePath.toString().replace("\\", "/");
                                            try {
                                                setupSsh.executeCommand("mkdir -p " + remoteDir);
                                            } catch (Exception e) {
                                                // Ignore - directory might already exist
                                            }
                                        }
                                    });
                                }
                            }
                        }
                        log("Remote directories created");
                    }

                    // Now enumerate all files into the queue
                    for (SyncTask task : tasks) {
                        if (!Files.exists(task.localPath)) {
                            log("Skipping (not found): " + task.localPath);
                            continue;
                        }

                        if (task.isDirectory) {
                            // Walk directory and add each file to queue
                            Path baseLocal = task.localPath;
                            String baseRemote = task.remotePath;
                            try (Stream<Path> walk = Files.walk(baseLocal)) {
                                walk.filter(Files::isRegularFile).forEach(file -> {
                                    Path relativePath = baseLocal.relativize(file);
                                    String remoteFile = baseRemote + "/" + relativePath.toString().replace("\\", "/");
                                    String displayName = task.description + "/" + relativePath.toString().replace("\\", "/");
                                    fileQueue.add(new FileUploadTask(file, remoteFile, displayName));
                                });
                            }
                        } else {
                            // Single file
                            fileQueue.add(new FileUploadTask(task.localPath, task.remotePath, task.description));
                        }
                    }

                    int totalFiles = fileQueue.size();
                    log("Total files to transfer: " + totalFiles);

                    if (totalFiles == 0) {
                        log("No files to sync");
                        return new SyncResult(true, "No files to sync");
                    }

                    // Spawn workers - each runs native scp commands
                    ExecutorService executor = Executors.newFixedThreadPool(parallelCount);
                    AtomicInteger completed = new AtomicInteger(0);
                    AtomicInteger failed = new AtomicInteger(0);

                    for (int w = 1; w <= parallelCount; w++) {
                        int workerNum = w;
                        executor.submit(() -> {
                            String workerName = "W" + workerNum;
                            log("[" + workerName + "] Started");

                            // Pull files from shared queue until empty
                            FileUploadTask task;
                            while ((task = fileQueue.poll()) != null) {
                                if (cancelled) {
                                    break;
                                }

                                try {
                                    String localPath = task.localPath.toString().replace("\\", "/");
                                    String scpTarget = config.username + "@" + config.host + ":" + task.remotePath;

                                    ProcessBuilder pb = new ProcessBuilder(
                                        "scp", "-o", "StrictHostKeyChecking=no", "-o", "BatchMode=yes",
                                        "-P", String.valueOf(config.port),
                                        "-i", keyPath,
                                        localPath, scpTarget
                                    );
                                    pb.redirectErrorStream(true);

                                    Process process = pb.start();
                                    // Drain output to prevent blocking
                                    try (BufferedReader reader = new BufferedReader(
                                            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                                        while (reader.readLine() != null) { /* drain */ }
                                    }

                                    int exitCode = process.waitFor();
                                    int done = completed.incrementAndGet();
                                    int pct = (done * 100) / totalFiles;

                                    if (exitCode == 0) {
                                        log("[" + workerName + "] [" + pct + "%] " + task.displayName + " (" + done + "/" + totalFiles + ")");
                                    } else {
                                        failed.incrementAndGet();
                                        log("[" + workerName + "] FAILED: " + task.displayName + " (exit " + exitCode + ")");
                                    }
                                    updateProgress("[" + workerName + "] " + task.displayName, done, totalFiles);

                                } catch (Exception e) {
                                    failed.incrementAndGet();
                                    completed.incrementAndGet();
                                    log("[" + workerName + "] ERROR: " + task.displayName + " - " + e.getMessage());
                                }
                            }

                            log("[" + workerName + "] Done - no more files in queue");
                        });
                    }

                    executor.shutdown();
                    boolean finished = executor.awaitTermination(60, TimeUnit.MINUTES);
                    if (!finished) {
                        executor.shutdownNow();
                        return new SyncResult(false, "Sync timed out");
                    }

                    log("");
                    log("Parallel sync complete: " + completed.get() + "/" + totalFiles + " files" +
                        (failed.get() > 0 ? " (" + failed.get() + " failed)" : ""));
                    updateProgress("Complete", totalFiles, totalFiles);
                    log("=== File sync complete (parallel scp) ===");

                } catch (Exception e) {
                    String msg = "Parallel sync failed: " + e.getMessage();
                    log(msg);
                    LogManager.logStackTrace("Parallel sync failed", e);
                    return new SyncResult(false, msg);
                }

                // Start the container after syncing if restart is enabled
                if (config.restartAfterSync) {
                    log("Starting remote server after sync...");
                    SyncResult startResult = startRemoteServer();
                    if (!startResult.success) {
                        return new SyncResult(false, "Sync completed but failed to start server: " + startResult.message);
                    }
                }

                log("");
                log("========================================");
                log("=== SYNC COMPLETE - ALL DONE! ===");
                log("========================================");
                return new SyncResult(true, "Sync completed successfully");
            }
        }

        // Sequential execution (original behavior) - also fallback if no auto-key
        {
            // Sequential execution (original behavior)
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
                                if (config.useFastTransfer) {
                                    ssh.uploadDirectoryFast(task.localPath, task.remotePath);
                                } else {
                                    ssh.uploadDirectory(task.localPath, task.remotePath, null);
                                }
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
                log("=== File sync complete ===");

            } catch (JSchException e) {
                String msg = "Connection failed: " + e.getMessage();
                log(msg);
                LogManager.logStackTrace("SSH connection failed during sync", e);
                return new SyncResult(false, msg);
            }
        }

        // Start the container after syncing if restart is enabled
        if (config.restartAfterSync) {
            log("Starting remote server after sync...");
            SyncResult startResult = startRemoteServer();
            if (!startResult.success) {
                return new SyncResult(false, "Sync completed but failed to start server: " + startResult.message);
            }
            log("");
            log("Remote server started - it will now download Minecraft " + server.getMinecraftVersion());
            log("The server may take a few minutes to download and start the game.");
        }

        log("");
        log("========================================");
        log("=== SYNC COMPLETE - ALL DONE! ===");
        log("========================================");
        return new SyncResult(true, "Sync completed successfully");
    }

    /**
     * Creates a directory on the remote server, ignoring if it exists.
     */
    private void mkdirSafe(ChannelSftp sftp, String path) {
        try {
            sftp.mkdir(path);
        } catch (SftpException e) {
            // Ignore - directory likely already exists
        }
    }

    /**
     * Creates a directory and all parent directories.
     */
    private void mkdirRecursive(ChannelSftp sftp, String path) {
        String[] parts = path.split("/");
        StringBuilder current = new StringBuilder();

        for (String part : parts) {
            if (part.isEmpty()) continue;
            current.append("/").append(part);
            mkdirSafe(sftp, current.toString());
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
     * Represents a single sync task (directory-level).
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
     * Represents a single file upload task for the shared queue.
     */
    private static class FileUploadTask {
        final Path localPath;
        final String remotePath;
        final String displayName;

        FileUploadTask(Path localPath, String remotePath, String displayName) {
            this.localPath = localPath;
            this.remotePath = remotePath;
            this.displayName = displayName;
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
