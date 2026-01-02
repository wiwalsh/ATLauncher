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
package com.atlauncher.managers;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.zeroturnaround.zip.NameMapper;

import com.atlauncher.FileSystem;
import com.atlauncher.Gsons;
import com.atlauncher.data.BackupMode;
import com.atlauncher.data.ChangeDetectionResult;
import com.atlauncher.data.ProfileContents;
import com.atlauncher.data.Server;
import com.atlauncher.data.ServerProfile;
import com.atlauncher.data.ServerProfileIndex;
import com.atlauncher.utils.ArchiveUtils;
import com.atlauncher.utils.FileUtils;
import com.atlauncher.utils.Hashing;
import com.atlauncher.utils.ServerProfileNameMapper;
import com.google.gson.JsonIOException;

/**
 * Manages server configuration profiles (snapshots).
 * Allows saving, restoring, and comparing server states.
 */
public class ServerProfileManager {

    /**
     * Gets the base directory for all server profiles.
     */
    public static Path getProfilesBaseDir() {
        return FileSystem.SERVER_PROFILES;
    }

    /**
     * Gets the profile directory for a specific server.
     */
    public static Path getServerProfileDir(Server server) {
        return getProfilesBaseDir().resolve(server.getSafeName());
    }

    /**
     * Gets the path to the profile index file for a server.
     */
    public static Path getProfileIndexPath(Server server) {
        return getServerProfileDir(server).resolve("profiles.json");
    }

    /**
     * Loads the profile index for a server.
     * Creates a new index if none exists.
     */
    public static ServerProfileIndex loadProfileIndex(Server server) {
        Path indexPath = getProfileIndexPath(server);

        if (!Files.exists(indexPath)) {
            return ServerProfileIndex.create(server.getSafeName());
        }

        try (InputStreamReader reader = new InputStreamReader(
                Files.newInputStream(indexPath), StandardCharsets.UTF_8)) {
            ServerProfileIndex index = Gsons.DEFAULT.fromJson(reader, ServerProfileIndex.class);
            if (index == null) {
                return ServerProfileIndex.create(server.getSafeName());
            }
            return index;
        } catch (Exception e) {
            LogManager.logStackTrace("Failed to load profile index for server " + server.getName(), e);
            return ServerProfileIndex.create(server.getSafeName());
        }
    }

    /**
     * Saves the profile index for a server.
     */
    public static void saveProfileIndex(Server server, ServerProfileIndex index) {
        Path profileDir = getServerProfileDir(server);
        Path indexPath = getProfileIndexPath(server);

        try {
            // Ensure directory exists
            if (!Files.exists(profileDir)) {
                Files.createDirectories(profileDir);
            }

            try (OutputStreamWriter writer = new OutputStreamWriter(
                    Files.newOutputStream(indexPath), StandardCharsets.UTF_8)) {
                Gsons.DEFAULT.toJson(index, writer);
            }
        } catch (JsonIOException | IOException e) {
            LogManager.logStackTrace("Failed to save profile index for server " + server.getName(), e);
        }
    }

    /**
     * Gets all profiles for a server.
     */
    public static List<ServerProfile> getProfilesForServer(Server server) {
        ServerProfileIndex index = loadProfileIndex(server);
        return index.profiles != null ? index.profiles : new ArrayList<>();
    }

    /**
     * Creates a new profile for a server.
     *
     * @param server      The server to create a profile for
     * @param name        The profile name
     * @param description Optional description
     * @param backupMode  What to include in the profile
     * @return The created profile, or null if creation failed
     */
    public static ServerProfile createProfile(Server server, String name, String description, BackupMode backupMode) {
        LogManager.info("Creating profile '" + name + "' for server " + server.getName());

        // Create the profile object
        ServerProfile profile = ServerProfile.create(name, description, backupMode);

        // Generate archive filename
        String safeProfileName = name.replaceAll("[^A-Za-z0-9_-]", "_");
        profile.archiveFilename = safeProfileName + "-" + System.currentTimeMillis() + ".zip";

        Path profileDir = getServerProfileDir(server);
        Path archivePath = profileDir.resolve(profile.archiveFilename);

        try {
            // Ensure directory exists
            if (!Files.exists(profileDir)) {
                Files.createDirectories(profileDir);
            }

            // Get the name mapper for filtering
            NameMapper nameMapper = ServerProfileNameMapper.getMapperForBackupMode(backupMode);

            // Compute checksums and collect contents info before creating archive
            Map<String, String> checksums = new HashMap<>();
            ProfileContents contents = new ProfileContents();

            Files.walkFileTree(server.getRoot(), new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String relativePath = server.getRoot().relativize(file).toString().replace('\\', '/');

                    // Check if this file would be included
                    if (nameMapper.map(relativePath) != null) {
                        // Compute checksum
                        String hash = Hashing.sha256(file).toString();
                        checksums.put(relativePath, hash);

                        // Update contents metadata
                        contents.totalFileCount++;
                        if (relativePath.equals("server.properties")) {
                            contents.hasServerProperties = true;
                        } else if (relativePath.startsWith("mods/")) {
                            contents.hasMods = true;
                            if (!attrs.isDirectory()) contents.modCount++;
                        } else if (relativePath.startsWith("config/")) {
                            contents.hasConfigs = true;
                            if (!attrs.isDirectory()) contents.configFileCount++;
                        } else if (relativePath.startsWith("plugins/")) {
                            contents.hasPlugins = true;
                            if (!attrs.isDirectory()) contents.pluginCount++;
                        } else if (relativePath.startsWith("world")) {
                            contents.hasWorld = true;
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    LogManager.warn("Failed to process file for profile: " + file);
                    return FileVisitResult.CONTINUE;
                }
            });

            profile.fileChecksums = checksums;
            profile.contents = contents;

            // Create the ZIP archive
            LogManager.debug("Creating archive at " + archivePath);
            boolean success = ArchiveUtils.createZip(server.getRoot(), archivePath, nameMapper);

            if (!success) {
                LogManager.error("Failed to create profile archive");
                return null;
            }

            // Set archive size and hash
            profile.archiveSizeBytes = Files.size(archivePath);
            profile.archiveHash = Hashing.sha256(archivePath).toString();

            // Add to index and save
            ServerProfileIndex index = loadProfileIndex(server);
            index.addProfile(profile);
            index.setActiveProfile(profile.id);
            saveProfileIndex(server, index);

            LogManager.info("Profile '" + name + "' created successfully (" + profile.getFormattedSize() + ")");
            return profile;

        } catch (IOException e) {
            LogManager.logStackTrace("Failed to create profile for server " + server.getName(), e);
            return null;
        }
    }

    /**
     * Restores a profile to a server.
     *
     * @param server    The server to restore to
     * @param profileId The profile ID to restore
     * @return true if restoration was successful
     */
    public static boolean restoreProfile(Server server, String profileId) {
        ServerProfileIndex index = loadProfileIndex(server);
        Optional<ServerProfile> profileOpt = index.getProfileById(profileId);

        if (!profileOpt.isPresent()) {
            LogManager.error("Profile not found: " + profileId);
            return false;
        }

        ServerProfile profile = profileOpt.get();
        Path archivePath = getServerProfileDir(server).resolve(profile.archiveFilename);

        if (!Files.exists(archivePath)) {
            LogManager.error("Profile archive not found: " + archivePath);
            return false;
        }

        LogManager.info("Restoring profile '" + profile.name + "' to server " + server.getName());

        try {
            // Extract the archive to server root
            NameMapper nameMapper = ServerProfileNameMapper.getMapperForBackupMode(profile.backupMode);
            boolean success = ArchiveUtils.extract(archivePath, server.getRoot(), nameMapper);

            if (!success) {
                LogManager.error("Failed to extract profile archive");
                return false;
            }

            // Update active profile
            index.setActiveProfile(profileId);
            saveProfileIndex(server, index);

            LogManager.info("Profile '" + profile.name + "' restored successfully");
            return true;

        } catch (Exception e) {
            LogManager.logStackTrace("Failed to restore profile", e);
            return false;
        }
    }

    /**
     * Deletes a profile.
     *
     * @param server    The server the profile belongs to
     * @param profileId The profile ID to delete
     * @return true if deletion was successful
     */
    public static boolean deleteProfile(Server server, String profileId) {
        ServerProfileIndex index = loadProfileIndex(server);
        Optional<ServerProfile> profileOpt = index.getProfileById(profileId);

        if (!profileOpt.isPresent()) {
            LogManager.error("Profile not found: " + profileId);
            return false;
        }

        ServerProfile profile = profileOpt.get();
        Path archivePath = getServerProfileDir(server).resolve(profile.archiveFilename);

        // Delete archive file
        if (Files.exists(archivePath)) {
            FileUtils.delete(archivePath);
        }

        // Remove from index
        index.removeProfile(profileId);
        saveProfileIndex(server, index);

        LogManager.info("Profile '" + profile.name + "' deleted");
        return true;
    }

    /**
     * Detects changes between current server state and the active profile.
     */
    public static ChangeDetectionResult detectChanges(Server server) {
        ServerProfileIndex index = loadProfileIndex(server);

        if (index.activeProfileId == null) {
            return ChangeDetectionResult.noProfileAvailable();
        }

        Optional<ServerProfile> profileOpt = index.getProfileById(index.activeProfileId);
        if (!profileOpt.isPresent()) {
            return ChangeDetectionResult.noProfileAvailable();
        }

        ServerProfile profile = profileOpt.get();
        ChangeDetectionResult result = new ChangeDetectionResult();
        result.comparedToProfileId = profile.id;
        result.comparedToProfileName = profile.name;

        Map<String, String> savedChecksums = profile.fileChecksums;
        if (savedChecksums == null) {
            savedChecksums = new HashMap<>();
        }

        NameMapper nameMapper = ServerProfileNameMapper.getMapperForBackupMode(profile.backupMode);
        Map<String, String> currentChecksums = new HashMap<>();

        // Compute current checksums
        try {
            final Map<String, String> finalSavedChecksums = savedChecksums;

            Files.walkFileTree(server.getRoot(), new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String relativePath = server.getRoot().relativize(file).toString().replace('\\', '/');

                    if (nameMapper.map(relativePath) != null) {
                        String hash = Hashing.sha256(file).toString();
                        currentChecksums.put(relativePath, hash);

                        String savedHash = finalSavedChecksums.get(relativePath);
                        if (savedHash == null) {
                            result.addedFiles.add(relativePath);
                        } else if (!savedHash.equals(hash)) {
                            result.modifiedFiles.add(relativePath);
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });

            // Find deleted files
            for (String path : savedChecksums.keySet()) {
                if (!currentChecksums.containsKey(path)) {
                    result.deletedFiles.add(path);
                }
            }

        } catch (IOException e) {
            LogManager.logStackTrace("Error detecting changes", e);
            result.hasChanges = true;
            return result;
        }

        result.hasChanges = result.getTotalChanges() > 0;
        return result;
    }

    /**
     * Quick check if there are unsaved changes.
     * Stops at first difference found for performance.
     */
    public static boolean hasUnsavedChanges(Server server) {
        ServerProfileIndex index = loadProfileIndex(server);

        if (index.activeProfileId == null) {
            // No active profile means we can't compare, treat as "unsaved"
            return true;
        }

        Optional<ServerProfile> profileOpt = index.getProfileById(index.activeProfileId);
        if (!profileOpt.isPresent()) {
            return true;
        }

        ServerProfile profile = profileOpt.get();
        Map<String, String> savedChecksums = profile.fileChecksums;
        if (savedChecksums == null || savedChecksums.isEmpty()) {
            return true;
        }

        NameMapper nameMapper = ServerProfileNameMapper.getMapperForBackupMode(profile.backupMode);
        final boolean[] hasChanges = {false};
        final int[] currentFileCount = {0};

        try {
            Files.walkFileTree(server.getRoot(), new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (hasChanges[0]) {
                        return FileVisitResult.TERMINATE;
                    }

                    String relativePath = server.getRoot().relativize(file).toString().replace('\\', '/');

                    if (nameMapper.map(relativePath) != null) {
                        currentFileCount[0]++;

                        String savedHash = savedChecksums.get(relativePath);
                        if (savedHash == null) {
                            // New file
                            hasChanges[0] = true;
                            return FileVisitResult.TERMINATE;
                        }

                        String currentHash = Hashing.sha256(file).toString();
                        if (!savedHash.equals(currentHash)) {
                            // Modified file
                            hasChanges[0] = true;
                            return FileVisitResult.TERMINATE;
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });

            // Check for deleted files (fewer files now than in saved state)
            if (!hasChanges[0] && currentFileCount[0] < savedChecksums.size()) {
                hasChanges[0] = true;
            }

        } catch (IOException e) {
            LogManager.logStackTrace("Error checking for unsaved changes", e);
            return true;
        }

        return hasChanges[0];
    }

    /**
     * Gets the active profile for a server, if any.
     */
    public static Optional<ServerProfile> getActiveProfile(Server server) {
        ServerProfileIndex index = loadProfileIndex(server);
        return index.getActiveProfile();
    }

    /**
     * Checks if a profile name is already used for a server.
     */
    public static boolean isProfileNameTaken(Server server, String name) {
        ServerProfileIndex index = loadProfileIndex(server);
        return index.getProfileByName(name).isPresent();
    }
}
