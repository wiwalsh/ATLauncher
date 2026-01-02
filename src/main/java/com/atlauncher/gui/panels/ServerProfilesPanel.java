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
package com.atlauncher.gui.panels;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Optional;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableModel;

import org.mini2Dx.gettext.GetText;

import com.atlauncher.builders.HTMLBuilder;
import com.atlauncher.data.ChangeDetectionResult;
import com.atlauncher.data.Server;
import com.atlauncher.data.ServerProfile;
import com.atlauncher.gui.dialogs.SaveServerProfileDialog;
import com.atlauncher.managers.DialogManager;
import com.atlauncher.managers.LogManager;
import com.atlauncher.managers.ServerProfileManager;
import com.atlauncher.utils.ServerProfileNameMapper;
import com.formdev.flatlaf.ui.FlatScrollPaneBorder;

/**
 * Panel for managing server profiles (save/restore configurations).
 */
public class ServerProfilesPanel extends JPanel {

    private final Server server;
    private JTable profileTable;
    private DefaultTableModel tableModel;
    private JButton saveNewButton, restoreButton, deleteButton, compareButton;
    private JPanel warningPanel;
    private JLabel warningLabel;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter
            .ofLocalizedDateTime(FormatStyle.MEDIUM)
            .withZone(ZoneId.systemDefault());

    public ServerProfilesPanel(Server server) {
        this.server = server;
        setLayout(new BorderLayout(10, 10));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        if (server != null) {
            setupComponents();
        } else {
            setupNoServerPlaceholder();
        }
    }

    private void setupNoServerPlaceholder() {
        JLabel placeholder = new JLabel(new HTMLBuilder()
                .center()
                .text(GetText.tr("Please select a server to manage profiles."))
                .build());
        placeholder.setHorizontalAlignment(SwingConstants.CENTER);
        add(placeholder, BorderLayout.CENTER);
    }

    private void setupComponents() {
        // Info label at top
        JLabel infoLabel = new JLabel(new HTMLBuilder()
                .center()
                .text(GetText.tr(
                        "Profiles allow you to save and restore server configurations."
                                + "<br/>Save a profile before making changes to preserve your current setup."))
                .build());
        infoLabel.setHorizontalAlignment(SwingConstants.CENTER);
        infoLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 0));

        // Warning panel for unsaved changes
        warningPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        warningPanel.setBackground(new Color(255, 240, 200));
        warningPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(255, 200, 100)),
                BorderFactory.createEmptyBorder(5, 10, 5, 10)));
        warningLabel = new JLabel();
        warningLabel.setIcon(null);
        warningPanel.add(warningLabel);
        warningPanel.setVisible(false);

        // Top panel combining info and warning
        JPanel topPanel = new JPanel();
        topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.Y_AXIS));
        topPanel.add(infoLabel);
        topPanel.add(warningPanel);

        // Table setup
        String[] columnNames = {
                GetText.tr("Name"),
                GetText.tr("Date"),
                GetText.tr("Mode"),
                GetText.tr("Size"),
                GetText.tr("Active")
        };

        tableModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        profileTable = new JTable(tableModel);
        profileTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        profileTable.setRowHeight(24);
        profileTable.getTableHeader().setReorderingAllowed(false);

        // Set column widths
        profileTable.getColumnModel().getColumn(0).setPreferredWidth(150); // Name
        profileTable.getColumnModel().getColumn(1).setPreferredWidth(150); // Date
        profileTable.getColumnModel().getColumn(2).setPreferredWidth(120); // Mode
        profileTable.getColumnModel().getColumn(3).setPreferredWidth(80);  // Size
        profileTable.getColumnModel().getColumn(4).setPreferredWidth(50);  // Active

        // Right-click menu
        profileTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    int row = profileTable.rowAtPoint(e.getPoint());
                    if (row != -1) {
                        profileTable.setRowSelectionInterval(row, row);
                        showContextMenu(e.getX(), e.getY());
                    }
                }
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && profileTable.getSelectedRow() != -1) {
                    restoreSelectedProfile();
                }
            }
        });

        // Enable/disable buttons based on selection
        profileTable.getSelectionModel().addListSelectionListener(e -> {
            boolean hasSelection = profileTable.getSelectedRow() != -1;
            restoreButton.setEnabled(hasSelection);
            deleteButton.setEnabled(hasSelection);
            compareButton.setEnabled(hasSelection);
        });

        JScrollPane scrollPane = new JScrollPane(profileTable);
        scrollPane.setPreferredSize(new Dimension(600, 250));
        scrollPane.setBorder(new FlatScrollPaneBorder());

        // Button panel
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 5));

        saveNewButton = new JButton(GetText.tr("Save New Profile"));
        saveNewButton.addActionListener(e -> saveNewProfile());

        restoreButton = new JButton(GetText.tr("Restore"));
        restoreButton.setEnabled(false);
        restoreButton.addActionListener(e -> restoreSelectedProfile());

        deleteButton = new JButton(GetText.tr("Delete"));
        deleteButton.setEnabled(false);
        deleteButton.addActionListener(e -> deleteSelectedProfile());

        compareButton = new JButton(GetText.tr("View Changes"));
        compareButton.setEnabled(false);
        compareButton.addActionListener(e -> viewChanges());

        JButton refreshButton = new JButton(GetText.tr("Refresh"));
        refreshButton.addActionListener(e -> refreshProfiles());

        buttonPanel.add(saveNewButton);
        buttonPanel.add(restoreButton);
        buttonPanel.add(deleteButton);
        buttonPanel.add(compareButton);
        buttonPanel.add(refreshButton);

        add(topPanel, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
        add(buttonPanel, BorderLayout.SOUTH);

        // Initial load
        refreshProfiles();
    }

    private void showContextMenu(int x, int y) {
        JPopupMenu menu = new JPopupMenu();

        JMenuItem restoreItem = new JMenuItem(GetText.tr("Restore"));
        restoreItem.addActionListener(e -> restoreSelectedProfile());
        menu.add(restoreItem);

        JMenuItem viewChangesItem = new JMenuItem(GetText.tr("View Changes"));
        viewChangesItem.addActionListener(e -> viewChanges());
        menu.add(viewChangesItem);

        menu.addSeparator();

        JMenuItem deleteItem = new JMenuItem(GetText.tr("Delete"));
        deleteItem.addActionListener(e -> deleteSelectedProfile());
        menu.add(deleteItem);

        menu.show(profileTable, x, y);
    }

    public void refreshProfiles() {
        new SwingWorker<Void, Void>() {
            private List<ServerProfile> profiles;
            private Optional<ServerProfile> activeProfile;
            private boolean hasChanges;
            private ChangeDetectionResult changes;

            @Override
            protected Void doInBackground() {
                profiles = ServerProfileManager.getProfilesForServer(server);
                activeProfile = ServerProfileManager.getActiveProfile(server);
                hasChanges = ServerProfileManager.hasUnsavedChanges(server);
                if (hasChanges) {
                    changes = ServerProfileManager.detectChanges(server);
                }
                return null;
            }

            @Override
            protected void done() {
                try {
                    // Update table
                    tableModel.setRowCount(0);
                    String activeId = activeProfile.map(p -> p.id).orElse(null);

                    for (ServerProfile profile : profiles) {
                        String dateStr = DATE_FORMATTER.format(profile.createdAt);
                        String modeStr = getModeDisplayName(profile.backupMode.name());
                        String sizeStr = profile.getFormattedSize();
                        String activeStr = profile.id.equals(activeId) ? "✓" : "";

                        tableModel.addRow(new Object[] {
                                profile.name,
                                dateStr,
                                modeStr,
                                sizeStr,
                                activeStr
                        });
                    }

                    // Update warning panel
                    if (hasChanges && changes != null) {
                        warningLabel.setText(new HTMLBuilder()
                                .text("⚠ " + GetText.tr("Unsaved changes detected: {0}", changes.getSummary()))
                                .build());
                        warningPanel.setVisible(true);
                    } else if (profiles.isEmpty()) {
                        warningLabel.setText(new HTMLBuilder()
                                .text("ℹ " + GetText.tr("No profiles saved yet. Create one to preserve your current configuration."))
                                .build());
                        warningPanel.setBackground(new Color(220, 235, 255));
                        ((javax.swing.border.CompoundBorder) warningPanel.getBorder())
                                .getOutsideBorder();
                        warningPanel.setVisible(true);
                    } else {
                        warningPanel.setVisible(false);
                    }

                } catch (Exception ex) {
                    LogManager.logStackTrace("Error refreshing profiles", ex);
                }
            }
        }.execute();
    }

    private String getModeDisplayName(String modeName) {
        switch (modeName) {
            case "NORMAL":
                return GetText.tr("Config Only");
            case "NORMAL_PLUS_MODS":
                return GetText.tr("Config + Mods");
            case "FULL":
                return GetText.tr("Full");
            default:
                return modeName;
        }
    }

    private void saveNewProfile() {
        SaveServerProfileDialog dialog = new SaveServerProfileDialog(server, SwingUtilities.getWindowAncestor(this));
        dialog.setVisible(true);

        // Refresh after dialog closes
        if (dialog.getCreatedProfile() != null) {
            refreshProfiles();
        }
    }

    private void restoreSelectedProfile() {
        int row = profileTable.getSelectedRow();
        if (row == -1) return;

        String profileName = (String) tableModel.getValueAt(row, 0);
        List<ServerProfile> profiles = ServerProfileManager.getProfilesForServer(server);
        Optional<ServerProfile> profileOpt = profiles.stream()
                .filter(p -> p.name.equals(profileName))
                .findFirst();

        if (!profileOpt.isPresent()) {
            DialogManager.okDialog()
                    .setTitle(GetText.tr("Error"))
                    .setContent(GetText.tr("Profile not found."))
                    .setType(DialogManager.ERROR)
                    .show();
            return;
        }

        ServerProfile profile = profileOpt.get();

        // Confirm restore
        int confirm = DialogManager.yesNoDialog()
                .setTitle(GetText.tr("Restore Profile"))
                .setContent(new HTMLBuilder().center()
                        .text(GetText.tr("Are you sure you want to restore profile \"{0}\"?", profile.name))
                        .text("<br/><br/>")
                        .text(GetText.tr("This will overwrite files in your server directory."))
                        .text("<br/>")
                        .text("<i>" + ServerProfileNameMapper.getBackupModeDescription(profile.backupMode) + "</i>")
                        .build())
                .setType(DialogManager.WARNING)
                .show();

        if (confirm != DialogManager.YES_OPTION) return;

        // Restore in background
        saveNewButton.setEnabled(false);
        restoreButton.setEnabled(false);
        deleteButton.setEnabled(false);

        new SwingWorker<Boolean, Void>() {
            @Override
            protected Boolean doInBackground() {
                return ServerProfileManager.restoreProfile(server, profile.id);
            }

            @Override
            protected void done() {
                try {
                    boolean success = get();
                    if (success) {
                        DialogManager.okDialog()
                                .setTitle(GetText.tr("Success"))
                                .setContent(GetText.tr("Profile \"{0}\" restored successfully!", profile.name))
                                .setType(DialogManager.INFO)
                                .show();
                    } else {
                        DialogManager.okDialog()
                                .setTitle(GetText.tr("Error"))
                                .setContent(GetText.tr("Failed to restore profile. Check the console for details."))
                                .setType(DialogManager.ERROR)
                                .show();
                    }
                } catch (Exception ex) {
                    LogManager.logStackTrace("Error restoring profile", ex);
                    DialogManager.okDialog()
                            .setTitle(GetText.tr("Error"))
                            .setContent(GetText.tr("An error occurred while restoring the profile."))
                            .setType(DialogManager.ERROR)
                            .show();
                } finally {
                    saveNewButton.setEnabled(true);
                    refreshProfiles();
                }
            }
        }.execute();
    }

    private void deleteSelectedProfile() {
        int row = profileTable.getSelectedRow();
        if (row == -1) return;

        String profileName = (String) tableModel.getValueAt(row, 0);
        List<ServerProfile> profiles = ServerProfileManager.getProfilesForServer(server);
        Optional<ServerProfile> profileOpt = profiles.stream()
                .filter(p -> p.name.equals(profileName))
                .findFirst();

        if (!profileOpt.isPresent()) {
            return;
        }

        ServerProfile profile = profileOpt.get();

        // Confirm delete
        int confirm = DialogManager.yesNoDialog()
                .setTitle(GetText.tr("Delete Profile"))
                .setContent(GetText.tr("Are you sure you want to delete profile \"{0}\"?", profile.name))
                .setType(DialogManager.WARNING)
                .show();

        if (confirm != DialogManager.YES_OPTION) return;

        boolean success = ServerProfileManager.deleteProfile(server, profile.id);
        if (success) {
            refreshProfiles();
        } else {
            DialogManager.okDialog()
                    .setTitle(GetText.tr("Error"))
                    .setContent(GetText.tr("Failed to delete profile."))
                    .setType(DialogManager.ERROR)
                    .show();
        }
    }

    private void viewChanges() {
        new SwingWorker<ChangeDetectionResult, Void>() {
            @Override
            protected ChangeDetectionResult doInBackground() {
                return ServerProfileManager.detectChanges(server);
            }

            @Override
            protected void done() {
                try {
                    ChangeDetectionResult result = get();

                    if (!result.hasChanges) {
                        DialogManager.okDialog()
                                .setTitle(GetText.tr("No Changes"))
                                .setContent(GetText.tr("No changes detected compared to the active profile."))
                                .setType(DialogManager.INFO)
                                .show();
                        return;
                    }

                    // Build change report
                    HTMLBuilder builder = new HTMLBuilder().center();
                    builder.text("<b>" + result.getSummary() + "</b>");
                    builder.text("<br/><br/>");

                    if (!result.addedFiles.isEmpty()) {
                        builder.text("<b>" + GetText.tr("Added Files:") + "</b><br/>");
                        int shown = 0;
                        for (String file : result.addedFiles) {
                            if (shown >= 5) {
                                builder.text("... " + GetText.tr("and {0} more", result.addedFiles.size() - 5) + "<br/>");
                                break;
                            }
                            builder.text("+ " + file + "<br/>");
                            shown++;
                        }
                        builder.text("<br/>");
                    }

                    if (!result.modifiedFiles.isEmpty()) {
                        builder.text("<b>" + GetText.tr("Modified Files:") + "</b><br/>");
                        int shown = 0;
                        for (String file : result.modifiedFiles) {
                            if (shown >= 5) {
                                builder.text("... " + GetText.tr("and {0} more", result.modifiedFiles.size() - 5) + "<br/>");
                                break;
                            }
                            builder.text("~ " + file + "<br/>");
                            shown++;
                        }
                        builder.text("<br/>");
                    }

                    if (!result.deletedFiles.isEmpty()) {
                        builder.text("<b>" + GetText.tr("Deleted Files:") + "</b><br/>");
                        int shown = 0;
                        for (String file : result.deletedFiles) {
                            if (shown >= 5) {
                                builder.text("... " + GetText.tr("and {0} more", result.deletedFiles.size() - 5) + "<br/>");
                                break;
                            }
                            builder.text("- " + file + "<br/>");
                            shown++;
                        }
                    }

                    DialogManager.okDialog()
                            .setTitle(GetText.tr("Changes Detected"))
                            .setContent(builder.build())
                            .setType(DialogManager.INFO)
                            .show();

                } catch (Exception ex) {
                    LogManager.logStackTrace("Error detecting changes", ex);
                    DialogManager.okDialog()
                            .setTitle(GetText.tr("Error"))
                            .setContent(GetText.tr("An error occurred while detecting changes."))
                            .setType(DialogManager.ERROR)
                            .show();
                }
            }
        }.execute();
    }

    /**
     * Checks if there are unsaved changes and prompts user if so.
     * Returns true if it's safe to proceed (no changes or user confirmed).
     */
    public boolean checkUnsavedChangesBeforeSync() {
        if (server == null) {
            return true;
        }

        if (!ServerProfileManager.hasUnsavedChanges(server)) {
            return true;
        }

        int choice = DialogManager.optionDialog()
                .setTitle(GetText.tr("Unsaved Changes"))
                .setContent(new HTMLBuilder().center()
                        .text(GetText.tr("You have unsaved changes to this server configuration."))
                        .text("<br/><br/>")
                        .text(GetText.tr("Would you like to save a profile before syncing?"))
                        .build())
                .addOption(GetText.tr("Save Profile First"))
                .addOption(GetText.tr("Continue Without Saving"))
                .addOption(GetText.tr("Cancel"))
                .setType(DialogManager.WARNING)
                .show();

        if (choice == 0) {
            // Save profile first
            saveNewProfile();
            return false; // Let user complete save, then try sync again
        } else if (choice == 1) {
            // Continue without saving
            return true;
        } else {
            // Cancel
            return false;
        }
    }
}
