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

/**
 * Result of comparing current server state against a saved profile.
 */
public class ChangeDetectionResult {
    /**
     * Whether any changes were detected.
     */
    public boolean hasChanges;

    /**
     * ID of the profile we compared against.
     * Null if no profile was available for comparison.
     */
    public String comparedToProfileId;

    /**
     * Name of the profile we compared against.
     */
    public String comparedToProfileName;

    /**
     * Files that exist now but didn't exist in the profile.
     */
    public List<String> addedFiles;

    /**
     * Files that have been modified since the profile was saved.
     */
    public List<String> modifiedFiles;

    /**
     * Files that existed in the profile but no longer exist.
     */
    public List<String> deletedFiles;

    public ChangeDetectionResult() {
        this.addedFiles = new ArrayList<>();
        this.modifiedFiles = new ArrayList<>();
        this.deletedFiles = new ArrayList<>();
    }

    /**
     * Returns the total number of changes.
     */
    public int getTotalChanges() {
        return addedFiles.size() + modifiedFiles.size() + deletedFiles.size();
    }

    /**
     * Returns a human-readable summary of changes.
     */
    public String getSummary() {
        if (!hasChanges) {
            return "No changes detected";
        }

        StringBuilder sb = new StringBuilder();

        if (comparedToProfileName != null) {
            sb.append("Compared to '").append(comparedToProfileName).append("': ");
        }

        List<String> parts = new ArrayList<>();
        if (!addedFiles.isEmpty()) {
            parts.add(addedFiles.size() + " added");
        }
        if (!modifiedFiles.isEmpty()) {
            parts.add(modifiedFiles.size() + " modified");
        }
        if (!deletedFiles.isEmpty()) {
            parts.add(deletedFiles.size() + " deleted");
        }

        sb.append(String.join(", ", parts));
        return sb.toString();
    }

    /**
     * Creates a result indicating no profile was available for comparison.
     */
    public static ChangeDetectionResult noProfileAvailable() {
        ChangeDetectionResult result = new ChangeDetectionResult();
        result.hasChanges = true;
        result.comparedToProfileName = "(no profile selected)";
        return result;
    }

    /**
     * Creates a result indicating no changes were found.
     */
    public static ChangeDetectionResult noChanges(String profileId, String profileName) {
        ChangeDetectionResult result = new ChangeDetectionResult();
        result.hasChanges = false;
        result.comparedToProfileId = profileId;
        result.comparedToProfileName = profileName;
        return result;
    }
}
