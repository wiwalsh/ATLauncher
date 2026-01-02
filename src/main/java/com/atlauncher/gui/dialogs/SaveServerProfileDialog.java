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
package com.atlauncher.gui.dialogs;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingWorker;

import org.mini2Dx.gettext.GetText;

import com.atlauncher.App;
import com.atlauncher.builders.HTMLBuilder;
import com.atlauncher.constants.UIConstants;
import com.atlauncher.data.BackupMode;
import com.atlauncher.data.Server;
import com.atlauncher.data.ServerProfile;
import com.atlauncher.managers.DialogManager;
import com.atlauncher.managers.LogManager;
import com.atlauncher.managers.ServerProfileManager;
import com.atlauncher.network.Analytics;
import com.atlauncher.utils.ServerProfileNameMapper;
import com.atlauncher.utils.Utils;

/**
 * Dialog for saving a server configuration profile.
 */
public class SaveServerProfileDialog extends JDialog {

    private final Server server;
    private JTextField profileNameField;
    private JTextArea descriptionArea;
    private JComboBox<BackupModeItem> backupModeCombo;
    private JButton saveButton;
    private JButton cancelButton;
    private ServerProfile createdProfile;

    public SaveServerProfileDialog(Server server) {
        this(server, App.launcher.getParent());
    }

    public SaveServerProfileDialog(Server server, Window parent) {
        super(parent, GetText.tr("Save Server Profile"), ModalityType.DOCUMENT_MODAL);

        this.server = server;

        Analytics.sendScreenView("Save Server Profile Dialog");

        setSize(450, 350);
        setLocationRelativeTo(parent);
        setLayout(new BorderLayout());
        setIconImage(Utils.getImage("/assets/image/icon.png"));
        setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        setResizable(false);

        setupComponents();

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent arg0) {
                close();
            }
        });
    }

    private void setupComponents() {
        // Top Panel - Title
        JPanel top = new JPanel();
        top.add(new JLabel(GetText.tr("Save configuration for {0}", server.getName())));

        // Middle Panel - Form Fields
        JPanel middle = new JPanel();
        middle.setLayout(new GridBagLayout());
        middle.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        GridBagConstraints gbc = new GridBagConstraints();

        // Profile Name
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.insets = UIConstants.LABEL_INSETS;
        gbc.anchor = GridBagConstraints.BASELINE_TRAILING;
        middle.add(new JLabel(GetText.tr("Profile Name") + ": "), gbc);

        gbc.gridx++;
        gbc.insets = UIConstants.FIELD_INSETS;
        gbc.anchor = GridBagConstraints.BASELINE_LEADING;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        profileNameField = new JTextField(20);
        profileNameField.setToolTipText(GetText.tr("Enter a name for this profile"));
        middle.add(profileNameField, gbc);

        // Description
        gbc.gridx = 0;
        gbc.gridy++;
        gbc.insets = UIConstants.LABEL_INSETS;
        gbc.anchor = GridBagConstraints.FIRST_LINE_END;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        middle.add(new JLabel(GetText.tr("Description") + ": "), gbc);

        gbc.gridx++;
        gbc.insets = UIConstants.FIELD_INSETS;
        gbc.anchor = GridBagConstraints.BASELINE_LEADING;
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weightx = 1.0;
        gbc.weighty = 1.0;
        descriptionArea = new JTextArea(4, 20);
        descriptionArea.setLineWrap(true);
        descriptionArea.setWrapStyleWord(true);
        descriptionArea.setToolTipText(GetText.tr("Optional description for this profile"));
        JScrollPane descScroll = new JScrollPane(descriptionArea);
        descScroll.setPreferredSize(new Dimension(250, 80));
        middle.add(descScroll, gbc);

        // Backup Mode
        gbc.gridx = 0;
        gbc.gridy++;
        gbc.insets = UIConstants.LABEL_INSETS;
        gbc.anchor = GridBagConstraints.BASELINE_TRAILING;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        gbc.weighty = 0;
        middle.add(new JLabel(GetText.tr("Include") + ": "), gbc);

        gbc.gridx++;
        gbc.insets = UIConstants.FIELD_INSETS;
        gbc.anchor = GridBagConstraints.BASELINE_LEADING;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        backupModeCombo = new JComboBox<>();
        backupModeCombo.addItem(new BackupModeItem(BackupMode.NORMAL,
                GetText.tr("Configuration Only"),
                ServerProfileNameMapper.getBackupModeDescription(BackupMode.NORMAL)));
        backupModeCombo.addItem(new BackupModeItem(BackupMode.NORMAL_PLUS_MODS,
                GetText.tr("Configuration + Mods"),
                ServerProfileNameMapper.getBackupModeDescription(BackupMode.NORMAL_PLUS_MODS)));
        backupModeCombo.addItem(new BackupModeItem(BackupMode.FULL,
                GetText.tr("Full Backup"),
                ServerProfileNameMapper.getBackupModeDescription(BackupMode.FULL)));
        backupModeCombo.setSelectedIndex(1); // Default to NORMAL_PLUS_MODS
        middle.add(backupModeCombo, gbc);

        // Mode Description
        gbc.gridx = 0;
        gbc.gridy++;
        gbc.gridwidth = 2;
        gbc.insets = UIConstants.FIELD_INSETS;
        gbc.anchor = GridBagConstraints.CENTER;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        JLabel modeDescLabel = new JLabel();
        modeDescLabel.setFont(modeDescLabel.getFont().deriveFont(11f));
        updateModeDescription(modeDescLabel);
        backupModeCombo.addActionListener(e -> updateModeDescription(modeDescLabel));
        middle.add(modeDescLabel, gbc);

        // Bottom Panel - Buttons
        JPanel bottom = new JPanel();
        bottom.setLayout(new FlowLayout(FlowLayout.CENTER, 10, 10));

        saveButton = new JButton(GetText.tr("Save Profile"));
        saveButton.addActionListener(e -> saveProfile());
        bottom.add(saveButton);

        cancelButton = new JButton(GetText.tr("Cancel"));
        cancelButton.addActionListener(e -> close());
        bottom.add(cancelButton);

        add(top, BorderLayout.NORTH);
        add(middle, BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);
    }

    private void updateModeDescription(JLabel label) {
        BackupModeItem item = (BackupModeItem) backupModeCombo.getSelectedItem();
        if (item != null) {
            label.setText("<html><i>" + item.description + "</i></html>");
        }
    }

    private void saveProfile() {
        String name = profileNameField.getText().trim();
        String description = descriptionArea.getText().trim();
        BackupModeItem selectedItem = (BackupModeItem) backupModeCombo.getSelectedItem();

        // Validation
        if (name.isEmpty()) {
            DialogManager.okDialog()
                    .setParent(this)
                    .setTitle(GetText.tr("Error"))
                    .setContent(GetText.tr("Please enter a profile name."))
                    .setType(DialogManager.ERROR)
                    .show();
            profileNameField.requestFocus();
            return;
        }

        if (name.length() > 100) {
            DialogManager.okDialog()
                    .setParent(this)
                    .setTitle(GetText.tr("Error"))
                    .setContent(GetText.tr("Profile name must be less than 100 characters."))
                    .setType(DialogManager.ERROR)
                    .show();
            profileNameField.requestFocus();
            return;
        }

        if (ServerProfileManager.isProfileNameTaken(server, name)) {
            DialogManager.okDialog()
                    .setParent(this)
                    .setTitle(GetText.tr("Error"))
                    .setContent(GetText.tr("A profile with the name \"{0}\" already exists.", name))
                    .setType(DialogManager.ERROR)
                    .show();
            profileNameField.requestFocus();
            return;
        }

        // Disable buttons during save
        saveButton.setEnabled(false);
        cancelButton.setEnabled(false);
        saveButton.setText(GetText.tr("Saving..."));

        // Create profile in background
        BackupMode mode = selectedItem != null ? selectedItem.mode : BackupMode.NORMAL_PLUS_MODS;

        new SwingWorker<ServerProfile, Void>() {
            @Override
            protected ServerProfile doInBackground() {
                return ServerProfileManager.createProfile(server, name, description, mode);
            }

            @Override
            protected void done() {
                try {
                    createdProfile = get();
                    if (createdProfile != null) {
                        LogManager.info("Profile '" + name + "' saved successfully");
                        DialogManager.okDialog()
                                .setParent(SaveServerProfileDialog.this)
                                .setTitle(GetText.tr("Success"))
                                .setContent(new HTMLBuilder().center()
                                        .text(GetText.tr("Profile \"{0}\" saved successfully!", name))
                                        .text("<br/>")
                                        .text(GetText.tr("Size: {0}", createdProfile.getFormattedSize()))
                                        .build())
                                .setType(DialogManager.INFO)
                                .show();
                        close();
                    } else {
                        LogManager.error("Failed to create profile '" + name + "'");
                        DialogManager.okDialog()
                                .setParent(SaveServerProfileDialog.this)
                                .setTitle(GetText.tr("Error"))
                                .setContent(GetText.tr("Failed to create profile. Check the console for details."))
                                .setType(DialogManager.ERROR)
                                .show();
                        saveButton.setEnabled(true);
                        cancelButton.setEnabled(true);
                        saveButton.setText(GetText.tr("Save Profile"));
                    }
                } catch (Exception ex) {
                    LogManager.logStackTrace("Error saving profile", ex);
                    DialogManager.okDialog()
                            .setParent(SaveServerProfileDialog.this)
                            .setTitle(GetText.tr("Error"))
                            .setContent(GetText.tr("An error occurred while saving the profile."))
                            .setType(DialogManager.ERROR)
                            .show();
                    saveButton.setEnabled(true);
                    cancelButton.setEnabled(true);
                    saveButton.setText(GetText.tr("Save Profile"));
                }
            }
        }.execute();
    }

    private void close() {
        setVisible(false);
        dispose();
    }

    /**
     * Gets the created profile, or null if dialog was cancelled or save failed.
     */
    public ServerProfile getCreatedProfile() {
        return createdProfile;
    }

    /**
     * Helper class to display BackupMode with a friendly name in the combo box.
     */
    private static class BackupModeItem {
        final BackupMode mode;
        final String displayName;
        final String description;

        BackupModeItem(BackupMode mode, String displayName, String description) {
            this.mode = mode;
            this.displayName = displayName;
            this.description = description;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }
}
