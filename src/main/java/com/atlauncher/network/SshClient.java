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
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.atlauncher.data.RemoteSyncConfig;
import com.atlauncher.managers.LogManager;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.KeyPair;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import com.jcraft.jsch.SftpProgressMonitor;

/**
 * SSH/SFTP client for remote server operations.
 */
public class SshClient implements Closeable {

    private final RemoteSyncConfig config;
    private final JSch jsch;
    private Session session;
    private Consumer<String> logCallback;
    private String workerPrefix = "";
    private boolean forcePasswordAuth = false;

    public SshClient(RemoteSyncConfig config) {
        this.config = config;
        this.jsch = new JSch();
    }

    /**
     * Sets a callback for log messages.
     */
    public void setLogCallback(Consumer<String> callback) {
        this.logCallback = callback;
    }

    /**
     * Sets a prefix for log messages to identify which worker this client belongs to.
     */
    public void setWorkerPrefix(String prefix) {
        this.workerPrefix = prefix != null ? prefix : "";
    }

    private void log(String message) {
        String prefixedMessage = workerPrefix.isEmpty() ? message : "[" + workerPrefix + "] " + message;
        LogManager.debug("[SSH] " + prefixedMessage);
        if (logCallback != null) {
            logCallback.accept(prefixedMessage);
        }
    }

    /**
     * Connects to the remote server.
     */
    public void connect() throws JSchException {
        log("Connecting to " + config.username + "@" + config.host + ":" + config.port);

        // Configure authentication
        boolean usingAutoKey = false;
        if ("key".equals(config.authMethod)) {
            // Check if we should use auto-generated key
            String keyPath = config.getEffectiveKeyPath();
            boolean useAutoKey = hasAutoKey() && (
                keyPath == null ||
                keyPath.isEmpty() ||
                keyPath.equals(RemoteSyncConfig.getDefaultKeyPath()) ||
                !Files.exists(Paths.get(keyPath))
            );

            if (useAutoKey) {
                // Use auto-generated key
                Path autoKeyPath = getAutoKeyPath();
                log("Using auto-generated SSH key: " + autoKeyPath);
                try {
                    byte[] keyBytes = Files.readAllBytes(autoKeyPath);
                    jsch.addIdentity("atlauncher-auto", keyBytes, null, null);
                } catch (IOException e) {
                    throw new JSchException("Failed to read SSH key: " + e.getMessage());
                }
            } else {
                log("Using SSH key: " + keyPath);
                if (config.keyPassphrase != null && !config.keyPassphrase.isEmpty()) {
                    jsch.addIdentity(keyPath, config.keyPassphrase);
                } else {
                    jsch.addIdentity(keyPath);
                }
            }
        } else if ("password".equals(config.authMethod) && hasAutoKey() && !forcePasswordAuth) {
            // If password auth is configured but we have an auto-generated key, try key first
            Path autoKeyPath = getAutoKeyPath();
            log("Using auto-generated SSH key (faster): " + autoKeyPath);
            try {
                // Use file path with forward slashes (JSch prefers Unix-style paths)
                String keyPathStr = autoKeyPath.toString().replace("\\", "/");
                jsch.addIdentity(keyPathStr);
                usingAutoKey = true;
                log("SSH key loaded from: " + keyPathStr);
            } catch (JSchException e) {
                log("Failed to load key file: " + e.getMessage());
                // Try reading bytes as fallback
                try {
                    byte[] prvKeyBytes = Files.readAllBytes(autoKeyPath);
                    jsch.addIdentity("atlauncher-auto", prvKeyBytes, null, null);
                    usingAutoKey = true;
                    log("SSH key loaded from bytes");
                } catch (IOException ioe) {
                    log("Failed to read auto-key, falling back to password: " + ioe.getMessage());
                }
            }
        }

        // Create session
        session = jsch.getSession(config.username, config.host, config.port);

        if ("password".equals(config.authMethod) && !usingAutoKey) {
            session.setPassword(config.password);
        }

        // Disable strict host key checking for convenience
        // In production, you might want to use known_hosts
        Properties sshConfig = new Properties();
        sshConfig.put("StrictHostKeyChecking", "no");
        sshConfig.put("PreferredAuthentications", "publickey,password");
        session.setConfig(sshConfig);

        // Enable keepalive to prevent timeout during long transfers
        session.setServerAliveInterval(30000);  // Send keepalive every 30 seconds
        session.setServerAliveCountMax(10);     // Allow 10 missed keepalives before disconnect

        session.setTimeout(config.connectionTimeout);
        try {
            session.connect();
            log("Connected successfully");
        } catch (JSchException e) {
            log("Connection error: " + e.getMessage());
            // If key auth failed and we have password, try password auth
            if (usingAutoKey && config.password != null && !config.password.isEmpty()) {
                log("Key auth failed, retrying with password...");
                // Create new session with password
                session = jsch.getSession(config.username, config.host, config.port);
                session.setPassword(config.password);
                session.setConfig(sshConfig);
                session.setServerAliveInterval(30000);
                session.setServerAliveCountMax(10);
                session.setTimeout(config.connectionTimeout);
                session.connect();
                log("Connected with password fallback");
                return;
            }
            throw e;
        }
    }

    /**
     * Tests the connection without keeping it open.
     * Uses native ssh command if auto-key exists (bypasses JSch key auth issues).
     */
    public boolean testConnection() {
        // Try native ssh first if we have an auto-generated key (more reliable)
        if (hasAutoKey()) {
            try {
                String keyPath = getAutoKeyPath().toString().replace("\\", "/");
                ProcessBuilder pb = new ProcessBuilder(
                    "ssh", "-i", keyPath,
                    "-o", "StrictHostKeyChecking=no",
                    "-o", "BatchMode=yes",
                    "-o", "ConnectTimeout=10",
                    "-p", String.valueOf(config.port),
                    config.username + "@" + config.host,
                    "echo 'Connection test successful'"
                );
                pb.redirectErrorStream(true);
                Process process = pb.start();

                StringBuilder output = new StringBuilder();
                try (java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line);
                    }
                }

                boolean success = process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
                if (success && process.exitValue() == 0 && output.toString().contains("successful")) {
                    log("Connection test successful (native ssh with key)");
                    return true;
                }
                log("Native ssh test failed, trying JSch fallback...");
            } catch (Exception e) {
                log("Native ssh failed: " + e.getMessage() + ", trying JSch...");
            }
        }

        // Fall back to JSch
        try {
            connect();
            String result = executeCommand("echo 'Connection test successful'");
            disconnect();
            return result.contains("successful");
        } catch (Exception e) {
            log("Connection test failed: " + e.getMessage());
            LogManager.logStackTrace("SSH connection test failed", e);
            return false;
        }
    }

    /**
     * Executes a command on the remote server.
     */
    public String executeCommand(String command) throws JSchException, IOException {
        if (session == null || !session.isConnected()) {
            throw new JSchException("Not connected");
        }

        log("Executing: " + command);

        ChannelExec channel = (ChannelExec) session.openChannel("exec");
        channel.setCommand(command);
        channel.setInputStream(null);
        channel.setErrStream(System.err);

        InputStream inputStream = channel.getInputStream();
        InputStream errStream = channel.getErrStream();

        channel.connect();

        StringBuilder output = new StringBuilder();
        StringBuilder errors = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
             BufferedReader errReader = new BufferedReader(new InputStreamReader(errStream, StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                log("  > " + line);
            }

            while ((line = errReader.readLine()) != null) {
                errors.append(line).append("\n");
                log("  [ERR] " + line);
            }
        }

        int exitStatus = channel.getExitStatus();
        channel.disconnect();

        if (exitStatus != 0 && errors.length() > 0) {
            log("Command exited with status " + exitStatus);
        }

        return output.toString().trim();
    }

    /**
     * Opens an SFTP channel.
     */
    public ChannelSftp openSftpChannel() throws JSchException {
        if (session == null || !session.isConnected()) {
            throw new JSchException("Not connected");
        }

        ChannelSftp channel = (ChannelSftp) session.openChannel("sftp");
        channel.connect();
        return channel;
    }

    /**
     * Uploads a file to the remote server.
     */
    public void uploadFile(Path localPath, String remotePath) throws JSchException, SftpException {
        uploadFile(localPath, remotePath, null);
    }

    /**
     * Uploads a file to the remote server with progress monitoring.
     */
    public void uploadFile(Path localPath, String remotePath, SftpProgressMonitor monitor)
            throws JSchException, SftpException {
        log("Uploading: " + localPath.getFileName() + " -> " + remotePath);

        ChannelSftp sftp = openSftpChannel();
        try {
            if (monitor != null) {
                sftp.put(localPath.toString(), remotePath, monitor, ChannelSftp.OVERWRITE);
            } else {
                sftp.put(localPath.toString(), remotePath, ChannelSftp.OVERWRITE);
            }
            log("Upload complete: " + localPath.getFileName());
        } finally {
            sftp.disconnect();
        }
    }

    /**
     * Uploads a directory recursively to the remote server.
     */
    public void uploadDirectory(Path localDir, String remoteDir, SftpProgressMonitor monitor)
            throws JSchException, SftpException, IOException {
        log("Uploading directory: " + localDir + " -> " + remoteDir);

        // Count files first for progress
        long totalFiles;
        try (Stream<Path> countWalk = Files.walk(localDir)) {
            totalFiles = countWalk.filter(Files::isRegularFile).count();
        }
        log("  Total files to upload: " + totalFiles);

        ChannelSftp sftp = openSftpChannel();
        final int[] uploaded = {0};
        final int[] failed = {0};

        try {
            // Ensure remote directory exists
            mkdirRecursive(sftp, remoteDir);

            // Upload all files
            try (Stream<Path> walk = Files.walk(localDir)) {
                walk.forEach(localPath -> {
                    try {
                        Path relativePath = localDir.relativize(localPath);
                        String remoteFilePath = remoteDir + "/" + relativePath.toString().replace("\\", "/");

                        if (Files.isDirectory(localPath)) {
                            mkdirSafe(sftp, remoteFilePath);
                        } else {
                            // Ensure parent directory exists
                            String parentDir = remoteFilePath.substring(0, remoteFilePath.lastIndexOf('/'));
                            mkdirRecursive(sftp, parentDir);

                            uploaded[0]++;
                            log("  [" + uploaded[0] + "/" + totalFiles + "] Uploading: " + relativePath);
                            if (monitor != null) {
                                sftp.put(localPath.toString(), remoteFilePath, monitor, ChannelSftp.OVERWRITE);
                            } else {
                                sftp.put(localPath.toString(), remoteFilePath, ChannelSftp.OVERWRITE);
                            }
                        }
                    } catch (SftpException e) {
                        failed[0]++;
                        log("  ✗ FAILED: " + localPath.getFileName() + " - " + e.getMessage());
                        LogManager.logStackTrace("Failed to upload: " + localPath, e);
                    } catch (Exception e) {
                        failed[0]++;
                        log("  ✗ ERROR: " + localPath.getFileName() + " - " + e.getMessage());
                        LogManager.logStackTrace("Unexpected error uploading: " + localPath, e);
                    }
                });
            }

            log("Directory upload complete: " + uploaded[0] + " files uploaded" +
                (failed[0] > 0 ? ", " + failed[0] + " failed" : ""));
        } finally {
            sftp.disconnect();
        }
    }

    /**
     * Uploads a directory using native scp command (much faster than SFTP).
     * Falls back to SFTP if scp is not available.
     */
    public void uploadDirectoryFast(Path localDir, String remoteDir)
            throws JSchException, SftpException, IOException {
        log("Fast upload using scp: " + localDir + " -> " + remoteDir);

        // Count files for info
        long totalFiles;
        try (Stream<Path> countWalk = Files.walk(localDir)) {
            totalFiles = countWalk.filter(Files::isRegularFile).count();
        }
        log("  Total files to upload: " + totalFiles);

        // Build scp command
        String scpTarget = config.username + "@" + config.host + ":" + remoteDir + "/";
        String localPath = localDir.toString().replace("\\", "/");

        // Determine which key to use
        String keyPath = null;
        if (hasAutoKey()) {
            // Prefer auto-generated key (it works with command-line SSH)
            keyPath = getAutoKeyPath().toString().replace("\\", "/");
            log("  Using auto-generated SSH key for scp: " + keyPath);
        } else if ("key".equals(config.authMethod)) {
            keyPath = config.getEffectiveKeyPath().replace("\\", "/");
        }

        // Build scp command based on auth method
        ProcessBuilder pb;
        if (keyPath != null) {
            // Use key authentication (preferred - works with auto-generated key)
            pb = new ProcessBuilder(
                "scp", "-r", "-o", "StrictHostKeyChecking=no",
                "-o", "BatchMode=yes",
                "-P", String.valueOf(config.port),
                "-i", keyPath,
                localPath, scpTarget
            );
        } else if ("password".equals(config.authMethod) && config.password != null) {
            // For password auth without key, check if sshpass is available
            try {
                Process check = new ProcessBuilder("sshpass", "-V").start();
                check.waitFor();
            } catch (Exception e) {
                log("  sshpass not available for password auth, falling back to SFTP...");
                uploadDirectory(localDir, remoteDir, null);
                return;
            }

            pb = new ProcessBuilder(
                "sshpass", "-p", config.password,
                "scp", "-r", "-o", "StrictHostKeyChecking=no",
                "-P", String.valueOf(config.port),
                localPath, scpTarget
            );
        } else {
            log("  No valid auth method for scp, falling back to SFTP...");
            uploadDirectory(localDir, remoteDir, null);
            return;
        }

        pb.redirectErrorStream(true);

        log("  Running: scp -r " + localPath + " " + scpTarget);

        try {
            Process process = pb.start();

            // Read output in real-time
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log("  " + line);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                log("Fast upload complete: " + totalFiles + " files uploaded");
            } else {
                throw new IOException("scp failed with exit code: " + exitCode);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("scp interrupted", e);
        } catch (IOException e) {
            // scp not available, fall back to SFTP
            log("  scp not available, falling back to SFTP...");
            uploadDirectory(localDir, remoteDir, null);
        }
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
     * Downloads a file from the remote server.
     */
    public void downloadFile(String remotePath, Path localPath) throws JSchException, SftpException {
        log("Downloading: " + remotePath + " -> " + localPath);

        ChannelSftp sftp = openSftpChannel();
        try {
            sftp.get(remotePath, localPath.toString());
            log("Download complete");
        } finally {
            sftp.disconnect();
        }
    }

    /**
     * Checks if a remote file or directory exists.
     */
    public boolean exists(String remotePath) throws JSchException {
        ChannelSftp sftp = openSftpChannel();
        try {
            sftp.lstat(remotePath);
            return true;
        } catch (SftpException e) {
            return false;
        } finally {
            sftp.disconnect();
        }
    }

    /**
     * Disconnects from the remote server.
     */
    public void disconnect() {
        if (session != null && session.isConnected()) {
            log("DEBUG: Disconnecting session...");
            long start = System.currentTimeMillis();
            session.disconnect();
            log("DEBUG: Session disconnected in " + (System.currentTimeMillis() - start) + "ms");
        } else {
            log("DEBUG: Disconnect called but session is null or not connected");
        }
    }

    /**
     * Checks if connected.
     */
    public boolean isConnected() {
        return session != null && session.isConnected();
    }

    @Override
    public void close() {
        log("DEBUG: SshClient.close() called [Thread-" + Thread.currentThread().getId() + "]");
        disconnect();
        log("DEBUG: SshClient.close() completed [Thread-" + Thread.currentThread().getId() + "]");
    }

    /**
     * Returns the path where ATLauncher stores auto-generated SSH keys.
     */
    public static Path getAutoKeyPath() {
        return Paths.get(System.getProperty("user.home"), ".ssh", "atlauncher_id_rsa");
    }

    /**
     * Checks if an auto-generated key already exists.
     */
    public static boolean hasAutoKey() {
        return Files.exists(getAutoKeyPath());
    }

    /**
     * Generates an SSH key pair and saves it locally.
     * @return true if key was generated successfully
     */
    public static boolean generateKeyPair() throws JSchException, IOException {
        Path keyPath = getAutoKeyPath();
        Path pubKeyPath = Paths.get(keyPath.toString() + ".pub");

        // Ensure .ssh directory exists
        Files.createDirectories(keyPath.getParent());

        JSch jsch = new JSch();
        KeyPair keyPair = KeyPair.genKeyPair(jsch, KeyPair.RSA, 4096);

        // Save private key
        keyPair.writePrivateKey(keyPath.toString());

        // Save public key
        keyPair.writePublicKey(pubKeyPath.toString(), "ATLauncher-auto-generated");

        keyPair.dispose();

        LogManager.info("[SSH] Generated new SSH key pair: " + keyPath);
        return true;
    }

    /**
     * Installs the auto-generated public key on the remote server using password auth.
     * After this, key-based auth can be used for faster connections.
     * @return true if key was installed successfully
     */
    public boolean installAutoKey() throws JSchException, IOException {
        Path pubKeyPath = Paths.get(getAutoKeyPath().toString() + ".pub");

        if (!Files.exists(pubKeyPath)) {
            throw new IOException("Public key does not exist: " + pubKeyPath);
        }

        // Read the public key
        String publicKey = new String(Files.readAllBytes(pubKeyPath), StandardCharsets.UTF_8).trim();

        log("Installing SSH public key on remote server...");

        // Connect with password auth to install the key
        if (!"password".equals(config.authMethod) || config.password == null) {
            throw new JSchException("Password auth required to install SSH key");
        }

        // Force password auth for key installation (the key isn't on server yet!)
        forcePasswordAuth = true;
        connect();

        // Install the key
        String installCmd = "mkdir -p ~/.ssh && chmod 700 ~/.ssh && " +
                "echo '" + publicKey + "' >> ~/.ssh/authorized_keys && " +
                "chmod 600 ~/.ssh/authorized_keys && " +
                "echo 'SSH key installed successfully'";

        String result = executeCommand(installCmd);
        disconnect();

        if (result.contains("successfully")) {
            log("SSH key installed on remote server");
            return true;
        } else {
            log("Failed to install SSH key: " + result);
            return false;
        }
    }

    /**
     * Generates a key pair and installs it on the remote server.
     * Uses password auth for initial installation, then key auth can be used.
     * @return true if key was generated and installed successfully
     */
    public boolean setupAutoKey() throws JSchException, IOException {
        // Generate key if it doesn't exist
        if (!hasAutoKey()) {
            log("Generating new SSH key pair...");
            generateKeyPair();
        } else {
            log("Using existing SSH key: " + getAutoKeyPath());
        }

        // Install key on remote server
        return installAutoKey();
    }

    /**
     * Progress monitor that reports to a callback.
     */
    public static class ProgressMonitor implements SftpProgressMonitor {
        private final Consumer<Long> progressCallback;
        private long totalBytes;
        private long transferredBytes;

        public ProgressMonitor(Consumer<Long> progressCallback) {
            this.progressCallback = progressCallback;
        }

        @Override
        public void init(int op, String src, String dest, long max) {
            this.totalBytes = max;
            this.transferredBytes = 0;
        }

        @Override
        public boolean count(long count) {
            transferredBytes += count;
            if (progressCallback != null) {
                progressCallback.accept(transferredBytes);
            }
            return true; // Continue transfer
        }

        @Override
        public void end() {
            // Transfer complete
        }

        public long getTotalBytes() {
            return totalBytes;
        }

        public long getTransferredBytes() {
            return transferredBytes;
        }
    }
}
