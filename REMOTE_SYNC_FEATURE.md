# Remote Server Sync Feature - Design Document

## Overview
Add the ability to push Minecraft server configurations from ATLauncher to a remote server automatically.

---

## Findings

### ATLauncher Architecture
- **Language**: Java 8+ (compiled with JDK 17)
- **Build System**: Gradle with shadow plugin for fat JAR
- **UI Framework**: Java Swing with FlatLaf theming
- **Networking**: OkHttp3 for HTTP, Apollo GraphQL client
- **Reactive**: RxJava3 for event handling

### Current Server Handling
- Server configs stored as JSON (`server.json` per server folder)
- `Server.java` (1,338 lines) - full server data model with launch(), backup(), mod management
- `ServerManager.java` - manages server list with RxJava BehaviorSubject
- Local backup exists: creates timestamped ZIPs to configurable `backupsPath`
- **No remote sync currently exists**

### Existing Infrastructure We Can Leverage
- `NetworkClient.java` - pre-configured OkHttp3 client
- `Settings.java` - global settings with JSON persistence
- Analytics system shows how to batch/send data remotely
- Paste service integration shows file upload pattern

---

## Recommended Approach: Remote Commands via SSH

### Why Remote Commands (not File Transfer)
| Capability | Remote Commands | File Transfer Only |
|------------|-----------------|-------------------|
| Start/stop server | Yes | No |
| Real-time logs | Yes | No |
| Immediate feedback | Yes | No |
| Atomic operations | Yes | No |
| Works in restricted envs | No | Yes |

**Recommendation**: Implement **Remote Commands** as primary mode with File Transfer as fallback.

### Proposed Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     ATLauncher Client                        │
├─────────────────────────────────────────────────────────────┤
│  RemoteSyncSettings        │  RemoteSyncManager             │
│  - host, port, user        │  - connect()                   │
│  - authMethod (SSH/API)    │  - pushConfig()                │
│  - remotePath              │  - executeCommand()            │
│  - syncOptions             │  - streamLogs()                │
│  - autoSync triggers       │  - getServerStatus()           │
├─────────────────────────────────────────────────────────────┤
│  SSHClient (JSch)          │  SFTPClient (for file transfer)│
│  - exec commands           │  - upload files                │
│  - port forwarding         │  - download backups            │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                     Remote Server                            │
├─────────────────────────────────────────────────────────────┤
│  /opt/minecraft/server/                                      │
│  ├── server.properties                                       │
│  ├── mods/                                                   │
│  ├── config/                                                 │
│  └── world/                                                  │
└─────────────────────────────────────────────────────────────┘
```

### Implementation Components

1. **RemoteSyncSettings.java** - New settings model
   - Remote host, port, username
   - Auth method: SSH key, password, or API token
   - Remote path to Minecraft server
   - What to sync: server.properties, mods, configs, world
   - Auto-sync triggers: on launch, on shutdown

2. **RemoteSyncManager.java** - Core sync logic
   - SSH connection management
   - Config file serialization/upload
   - Remote command execution
   - Log streaming
   - Error handling and retry logic

3. **RemoteSyncDialog.java** - UI (demo already created)
   - Connection settings tab
   - Sync mode selection
   - Sync options configuration
   - Test connection / Sync now buttons
   - Progress and log display

4. **Integration Points**
   - Add to `Server.java`: `syncToRemote()` method
   - Add to `ServerManager`: observe sync events
   - Add to Settings UI: remote sync configuration
   - Add to Server context menu: "Sync to Remote" action

### Dependencies to Add
```gradle
// SSH/SFTP support
implementation 'com.jcraft:jsch:0.1.55'
// Or newer: implementation 'com.github.mwiede:jsch:0.2.17'
```

---

## Decisions Needed

### 1. Authentication Method Priority
**Options:**
- A) SSH Key only (most secure, recommended)
- B) SSH Key + Password fallback
- C) SSH Key + Password + API Token (for custom server management APIs)

**Recommendation**: Option B - SSH Key primary with password fallback for ease of setup.

### 2. Sync Trigger Behavior
**Options:**
- A) Manual only - user clicks "Sync Now"
- B) Auto on server stop - sync after local server shutdown
- C) Auto on launch - sync before starting remote server
- D) All of the above (configurable)

**Recommendation**: Option D - let user choose their workflow.

### 3. Remote Command Set
**What commands should we support?**
- `start-server` - Start Minecraft server
- `stop-server` - Graceful stop (save-all, stop)
- `restart-server` - Stop then start
- `server-status` - Check if running (process check or query port)
- `backup-world` - Create backup on remote
- `tail-log` - Stream latest.log
- `send-command <cmd>` - Send to server console (via screen/tmux/RCON)
- `list-players` - Query connected players

**Decision needed**: Which commands are MVP vs nice-to-have?

### 4. File Transfer Scope
**What to sync by default?**
- `server.properties` - Always
- `mods/` folder - Yes (can be large)
- `config/` folder - Yes
- `world/` - Optional (very large, risky)
- `plugins/` - For Paper/Spigot servers

**Decision needed**: Default selections and size warnings.

### 5. Conflict Resolution
**When remote has newer files:**
- A) Always overwrite remote (ATLauncher is source of truth)
- B) Always keep remote (remote is source of truth)
- C) Ask user each time
- D) Configurable per sync

**Recommendation**: Option A for MVP - ATLauncher pushes to remote.

### 6. Server Management Wrapper
**How to manage server process on remote?**
- A) Direct java command (requires process management)
- B) systemd service (Linux standard)
- C) screen/tmux session (common for game servers)
- D) Docker container
- E) Custom wrapper script (user provides)

**Recommendation**: Option E with templates for B, C, D - let user configure.

---

## GUI Demo

A working GUI demo exists at:
```
src/main/java/com/atlauncher/gui/dialogs/RemoteSyncDemo.java
```

Run it with:
```bash
export JAVA_HOME="/c/Program Files/JetBrains/JetBrains Rider 2024.3.6/jbr"
"$JAVA_HOME/bin/java" -cp "build/libs/ATLauncher-3.4.40.3.jar" com.atlauncher.gui.dialogs.RemoteSyncDemo
```

The demo shows:
- 3-tab configuration dialog (Connection, Sync Mode, Sync Options)
- Test connection flow simulation
- Sync progress with logging
- Remote commands vs file transfer mode selection

---

## Next Steps

1. **Make architecture decisions** (see Decisions Needed above)
2. **Add JSch dependency** to build.gradle
3. **Implement RemoteSyncSettings** - settings model with persistence
4. **Implement RemoteSyncManager** - SSH/SFTP core logic
5. **Convert RemoteSyncDemo to RemoteSyncDialog** - integrate with real logic
6. **Add to Server context menu** - "Configure Remote Sync" and "Sync Now"
7. **Add to Settings panel** - global remote sync defaults
8. **Test with real remote server**

---

## Git Setup

Fork is configured:
- `origin` → `https://github.com/wiwalsh/ATLauncher.git` (your fork)
- `upstream` → `https://github.com/ATLauncher/ATLauncher.git` (original)

Create feature branch:
```bash
git checkout -b feature/remote-server-sync
```
