# ATLauncher Remote Server Sync Feature

## Project Context
This is a fork of ATLauncher (wiwalsh/ATLauncher) with a new feature: **Remote Server Configuration Push**.

The goal is to add the ability to push Minecraft server configurations to a remote server as an automated step.

## Quick Start
```bash
# Set Java (JetBrains bundled JDK works)
export JAVA_HOME="/c/Program Files/JetBrains/JetBrains Rider 2024.3.6/jbr"

# Build
./gradlew shadowJar

# Run the demo UI
"$JAVA_HOME/bin/java" -cp "build/libs/ATLauncher-3.4.40.3.jar" com.atlauncher.gui.dialogs.RemoteSyncDemo
```

## Key Files
- `src/main/java/com/atlauncher/gui/dialogs/RemoteSyncDemo.java` - GUI demo (created)
- `src/main/java/com/atlauncher/data/Server.java` - Server data model
- `src/main/java/com/atlauncher/data/Settings.java` - Global settings
- `src/main/java/com/atlauncher/managers/ServerManager.java` - Server list management
- `src/main/java/com/atlauncher/network/NetworkClient.java` - HTTP client

## See Also
- `REMOTE_SYNC_FEATURE.md` - Detailed findings, architecture options, and decisions needed
