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
import java.util.Properties;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.atlauncher.data.RemoteSyncConfig;
import com.atlauncher.managers.LogManager;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
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

    private void log(String message) {
        LogManager.debug("[SSH] " + message);
        if (logCallback != null) {
            logCallback.accept(message);
        }
    }

    /**
     * Connects to the remote server.
     */
    public void connect() throws JSchException {
        log("Connecting to " + config.username + "@" + config.host + ":" + config.port);

        // Configure authentication
        if ("key".equals(config.authMethod)) {
            String keyPath = config.getEffectiveKeyPath();
            log("Using SSH key: " + keyPath);
            if (config.keyPassphrase != null && !config.keyPassphrase.isEmpty()) {
                jsch.addIdentity(keyPath, config.keyPassphrase);
            } else {
                jsch.addIdentity(keyPath);
            }
        }

        // Create session
        session = jsch.getSession(config.username, config.host, config.port);

        if ("password".equals(config.authMethod)) {
            session.setPassword(config.password);
        }

        // Disable strict host key checking for convenience
        // In production, you might want to use known_hosts
        Properties sshConfig = new Properties();
        sshConfig.put("StrictHostKeyChecking", "no");
        sshConfig.put("PreferredAuthentications", "publickey,password");
        session.setConfig(sshConfig);

        session.setTimeout(config.connectionTimeout);
        session.connect();

        log("Connected successfully");
    }

    /**
     * Tests the connection without keeping it open.
     */
    public boolean testConnection() {
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

        ChannelSftp sftp = openSftpChannel();
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

                            log("  Uploading: " + relativePath);
                            if (monitor != null) {
                                sftp.put(localPath.toString(), remoteFilePath, monitor, ChannelSftp.OVERWRITE);
                            } else {
                                sftp.put(localPath.toString(), remoteFilePath, ChannelSftp.OVERWRITE);
                            }
                        }
                    } catch (SftpException e) {
                        LogManager.logStackTrace("Failed to upload: " + localPath, e);
                    }
                });
            }

            log("Directory upload complete");
        } finally {
            sftp.disconnect();
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
            log("Disconnecting");
            session.disconnect();
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
        disconnect();
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
