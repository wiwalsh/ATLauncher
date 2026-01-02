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

/**
 * Metadata about what is contained in a server profile archive.
 */
public class ProfileContents {
    public boolean hasServerProperties;
    public boolean hasMods;
    public boolean hasConfigs;
    public boolean hasPlugins;
    public boolean hasWorld;

    public int modCount;
    public int configFileCount;
    public int pluginCount;
    public int totalFileCount;

    public ProfileContents() {
    }

    /**
     * Returns a human-readable summary of the contents.
     */
    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        if (hasServerProperties) sb.append("server.properties, ");
        if (hasMods) sb.append(modCount).append(" mods, ");
        if (hasConfigs) sb.append(configFileCount).append(" configs, ");
        if (hasPlugins) sb.append(pluginCount).append(" plugins, ");
        if (hasWorld) sb.append("world data, ");

        if (sb.length() > 0) {
            sb.setLength(sb.length() - 2); // Remove trailing ", "
        } else {
            sb.append("empty");
        }

        return sb.toString();
    }
}
