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
package com.atlauncher.utils;

import org.zeroturnaround.zip.NameMapper;

import com.atlauncher.data.BackupMode;

/**
 * Name mapper for server profile archives.
 * Determines which files to include based on the backup mode.
 *
 * Unlike the client-side ZipNameMapper (for instances), this is tailored
 * for Minecraft server directories which have different file structures.
 */
public class ServerProfileNameMapper {

    /**
     * NORMAL mode: Configuration files only.
     * Includes server.properties, whitelist, bans, config folder.
     * Does NOT include mods, plugins, or world data.
     */
    public static final NameMapper NORMAL = name -> {
        // Normalize path separators for cross-platform compatibility
        String normalized = name.replace('\\', '/');

        // Server configuration files
        if (normalized.equals("server.properties") ||
            normalized.equals("ops.json") ||
            normalized.equals("whitelist.json") ||
            normalized.equals("banned-players.json") ||
            normalized.equals("banned-ips.json") ||
            normalized.equals("bukkit.yml") ||
            normalized.equals("spigot.yml") ||
            normalized.equals("paper.yml") ||
            normalized.equals("paper-global.yml") ||
            normalized.equals("paper-world-defaults.yml") ||
            normalized.equals("eula.txt") ||
            normalized.equals("server.json")) {  // ATLauncher metadata
            return name;
        }

        // Mod/plugin configuration folders
        if (normalized.startsWith("config/") || normalized.equals("config")) {
            return name;
        }

        return null;
    };

    /**
     * NORMAL_PLUS_MODS mode: Configuration + mods/plugins.
     * Includes everything from NORMAL, plus mods, plugins, coremods, jarmods.
     * Does NOT include world data.
     */
    public static final NameMapper NORMAL_PLUS_MODS = name -> {
        // Include everything from NORMAL
        if (NORMAL.map(name) != null) {
            return name;
        }

        // Normalize path separators
        String normalized = name.replace('\\', '/');

        // Mods folders
        if (normalized.startsWith("mods/") || normalized.equals("mods") ||
            normalized.startsWith("plugins/") || normalized.equals("plugins") ||
            normalized.startsWith("coremods/") || normalized.equals("coremods") ||
            normalized.startsWith("jarmods/") || normalized.equals("jarmods")) {
            return name;
        }

        return null;
    };

    /**
     * FULL mode: Everything in the server directory.
     * Includes world data, which can be very large.
     */
    public static final NameMapper FULL = name -> name;

    /**
     * Returns the appropriate NameMapper for the given backup mode.
     */
    public static NameMapper getMapperForBackupMode(BackupMode backupMode) {
        if (backupMode == BackupMode.NORMAL) {
            return NORMAL;
        }

        if (backupMode == BackupMode.NORMAL_PLUS_MODS) {
            return NORMAL_PLUS_MODS;
        }

        return FULL;
    }

    /**
     * Returns a human-readable description of what the backup mode includes.
     */
    public static String getBackupModeDescription(BackupMode backupMode) {
        switch (backupMode) {
            case NORMAL:
                return "Configuration only (server.properties, config/, whitelist, bans)";
            case NORMAL_PLUS_MODS:
                return "Configuration + Mods (adds mods/, plugins/)";
            case FULL:
                return "Full backup (everything including world data)";
            default:
                return backupMode.name();
        }
    }

    /**
     * Checks if a file path would be included in the given backup mode.
     */
    public static boolean isIncluded(String path, BackupMode backupMode) {
        return getMapperForBackupMode(backupMode).map(path) != null;
    }
}
