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
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Represents a saved server configuration profile/snapshot.
 * A profile contains a ZIP archive of server files and metadata for tracking changes.
 */
public class ServerProfile {
    /**
     * Unique identifier for this profile.
     */
    public String id;

    /**
     * Human-readable name for this profile.
     */
    public String name;

    /**
     * Optional description of this profile.
     */
    public String description;

    /**
     * Timestamp when this profile was created.
     */
    public Instant createdAt;

    /**
     * The backup mode used when creating this profile.
     * Determines what files were included.
     */
    public BackupMode backupMode;

    /**
     * Filename of the ZIP archive (relative to profile directory).
     */
    public String archiveFilename;

    /**
     * Size of the archive in bytes.
     */
    public long archiveSizeBytes;

    /**
     * SHA-256 hash of the archive for integrity verification.
     */
    public String archiveHash;

    /**
     * SHA-256 checksums of included files at save time.
     * Maps relative file path to its hash.
     * Used for change detection.
     */
    public Map<String, String> fileChecksums;

    /**
     * Metadata about what is contained in this profile.
     */
    public ProfileContents contents;

    public ServerProfile() {
        this.fileChecksums = new HashMap<>();
        this.contents = new ProfileContents();
    }

    /**
     * Creates a new profile with a generated UUID and current timestamp.
     */
    public static ServerProfile create(String name, String description, BackupMode backupMode) {
        ServerProfile profile = new ServerProfile();
        profile.id = UUID.randomUUID().toString();
        profile.name = name;
        profile.description = description;
        profile.createdAt = Instant.now();
        profile.backupMode = backupMode;
        return profile;
    }

    /**
     * Gets the archive path within the given profile directory.
     */
    public Path getArchivePath(Path profilesDir) {
        return profilesDir.resolve(archiveFilename);
    }

    /**
     * Returns a formatted size string (e.g., "45.2 MB").
     */
    public String getFormattedSize() {
        if (archiveSizeBytes < 1024) {
            return archiveSizeBytes + " B";
        } else if (archiveSizeBytes < 1024 * 1024) {
            return String.format("%.1f KB", archiveSizeBytes / 1024.0);
        } else if (archiveSizeBytes < 1024 * 1024 * 1024) {
            return String.format("%.1f MB", archiveSizeBytes / (1024.0 * 1024.0));
        } else {
            return String.format("%.1f GB", archiveSizeBytes / (1024.0 * 1024.0 * 1024.0));
        }
    }

    /**
     * Returns a human-readable backup mode description.
     */
    public String getBackupModeDescription() {
        switch (backupMode) {
            case NORMAL:
                return "Config Only";
            case NORMAL_PLUS_MODS:
                return "Config + Mods";
            case FULL:
                return "Full Backup";
            default:
                return backupMode.name();
        }
    }
}
