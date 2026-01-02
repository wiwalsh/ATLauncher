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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Index of all saved profiles for a specific server.
 * Stored as profiles.json in the server's profile directory.
 */
public class ServerProfileIndex {
    /**
     * Safe name of the server this index belongs to.
     * Used for validation when loading.
     */
    public String serverSafeName;

    /**
     * List of all saved profiles for this server.
     */
    public List<ServerProfile> profiles;

    /**
     * ID of the currently active (last restored) profile.
     * Can be null if no profile has been restored.
     */
    public String activeProfileId;

    /**
     * Version of the index format for future migrations.
     */
    public int version = 1;

    public ServerProfileIndex() {
        this.profiles = new ArrayList<>();
    }

    /**
     * Creates a new index for the given server.
     */
    public static ServerProfileIndex create(String serverSafeName) {
        ServerProfileIndex index = new ServerProfileIndex();
        index.serverSafeName = serverSafeName;
        return index;
    }

    /**
     * Finds a profile by its ID.
     */
    public Optional<ServerProfile> getProfileById(String id) {
        if (id == null || profiles == null) {
            return Optional.empty();
        }
        return profiles.stream()
                .filter(p -> id.equals(p.id))
                .findFirst();
    }

    /**
     * Finds a profile by its name (case-insensitive).
     */
    public Optional<ServerProfile> getProfileByName(String name) {
        if (name == null || profiles == null) {
            return Optional.empty();
        }
        return profiles.stream()
                .filter(p -> name.equalsIgnoreCase(p.name))
                .findFirst();
    }

    /**
     * Adds a profile to the index.
     */
    public void addProfile(ServerProfile profile) {
        if (profiles == null) {
            profiles = new ArrayList<>();
        }
        profiles.add(profile);
    }

    /**
     * Removes a profile by ID.
     * @return true if a profile was removed
     */
    public boolean removeProfile(String id) {
        if (id == null || profiles == null) {
            return false;
        }
        boolean removed = profiles.removeIf(p -> id.equals(p.id));

        // Clear active profile if it was removed
        if (removed && id.equals(activeProfileId)) {
            activeProfileId = null;
        }

        return removed;
    }

    /**
     * Gets the currently active profile, if any.
     */
    public Optional<ServerProfile> getActiveProfile() {
        return getProfileById(activeProfileId);
    }

    /**
     * Sets a profile as active by ID.
     */
    public void setActiveProfile(String profileId) {
        this.activeProfileId = profileId;
    }

    /**
     * Returns the number of profiles.
     */
    public int getProfileCount() {
        return profiles == null ? 0 : profiles.size();
    }
}
