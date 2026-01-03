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
package com.atlauncher.data;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Configuration for remote server sync connection.
 */
public class RemoteSyncConfig {

    /**
     * Remote server hostname or IP address.
     */
    public String host;

    /**
     * SSH port (default: 22).
     */
    public int port = 22;

    /**
     * SSH username.
     */
    public String username;

    /**
     * Authentication method: "password" or "key".
     */
    public String authMethod = "key";

    /**
     * Password for password authentication.
     * Note: Stored in memory only, not persisted.
     */
    public transient String password;

    /**
     * Path to SSH private key file.
     */
    public String privateKeyPath;

    /**
     * Passphrase for SSH private key (if encrypted).
     */
    public transient String keyPassphrase;

    /**
     * Remote path to Minecraft server directory.
     */
    public String remotePath = "/srv/docker/minecraft";

    /**
     * Sync mode: "commands" for full SSH control, "transfer" for SFTP only.
     */
    public String syncMode = "commands";

    /**
     * Whether to sync server.properties.
     */
    public boolean syncServerProperties = true;

    /**
     * Whether to sync mods folder.
     */
    public boolean syncMods = true;

    /**
     * Whether to sync config folder.
     */
    public boolean syncConfigs = true;

    /**
     * Whether to sync plugins folder.
     */
    public boolean syncPlugins = false;

    /**
     * Whether to sync world folder (WARNING: can be very large).
     */
    public boolean syncWorld = false;

    /**
     * Command to check server status.
     */
    public String statusCommand = "docker ps -f name=minecraft --format '{{.Status}}'";

    /**
     * Command to start the server.
     */
    public String startCommand = "docker start minecraft";

    /**
     * Command to stop the server.
     */
    public String stopCommand = "docker stop minecraft";

    /**
     * Connection timeout in milliseconds.
     */
    public int connectionTimeout = 30000;

    /**
     * Whether to auto-sync before starting remote server.
     */
    public boolean autoSyncOnLaunch = false;

    /**
     * Whether to auto-sync after local server shutdown.
     */
    public boolean autoSyncOnShutdown = false;

    /**
     * Whether to sync the Minecraft version and loader type to the remote server.
     * This updates the Docker container's VERSION and TYPE environment variables.
     */
    public boolean syncVersion = true;

    /**
     * Whether to clean remote mods/config directories before syncing.
     * This ensures a fresh install without leftover files from previous versions.
     */
    public boolean cleanBeforeSync = true;

    /**
     * Whether to restart the Docker container after syncing.
     * This is required for the container to pick up the new Minecraft version.
     */
    public boolean restartAfterSync = true;

    /**
     * Whether to use fast transfer mode (native scp instead of SFTP).
     * Much faster for large directories but requires scp on the system.
     */
    public boolean useFastTransfer = true;

    /**
     * Number of parallel transfer threads (1-8).
     * Higher values upload multiple directories simultaneously.
     */
    public int parallelTransferCount = 3;

    /**
     * Path to docker-compose.yml on the remote server.
     * Used to update VERSION and TYPE for the Minecraft container.
     */
    public String dockerComposePath = "/srv/docker/docker-compose.yml";

    /**
     * The service name in docker-compose.yml for the Minecraft server.
     */
    public String dockerServiceName = "minecraft";

    /**
     * Creates a new empty config.
     */
    public RemoteSyncConfig() {
    }

    /**
     * Creates a config with the specified connection details.
     */
    public RemoteSyncConfig(String host, int port, String username, String remotePath) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.remotePath = remotePath;
    }

    /**
     * Returns the path to the default SSH key (~/.ssh/id_rsa).
     */
    public static String getDefaultKeyPath() {
        return Paths.get(System.getProperty("user.home"), ".ssh", "id_rsa").toString();
    }

    /**
     * Gets the private key path, using default if not specified.
     */
    public String getEffectiveKeyPath() {
        if (privateKeyPath != null && !privateKeyPath.isEmpty()) {
            return privateKeyPath;
        }
        return getDefaultKeyPath();
    }

    /**
     * Validates the configuration.
     * @return null if valid, or an error message if invalid.
     */
    public String validate() {
        if (host == null || host.trim().isEmpty()) {
            return "Host is required";
        }
        if (port <= 0 || port > 65535) {
            return "Port must be between 1 and 65535";
        }
        if (username == null || username.trim().isEmpty()) {
            return "Username is required";
        }
        if (remotePath == null || remotePath.trim().isEmpty()) {
            return "Remote path is required";
        }
        if ("password".equals(authMethod) && (password == null || password.isEmpty())) {
            return "Password is required for password authentication";
        }
        if (parallelTransferCount < 1 || parallelTransferCount > 64) {
            return "Parallel transfer count must be between 1 and 64";
        }
        return null;
    }

    /**
     * Returns a display string for this config.
     */
    @Override
    public String toString() {
        return username + "@" + host + ":" + port + " -> " + remotePath;
    }
}
