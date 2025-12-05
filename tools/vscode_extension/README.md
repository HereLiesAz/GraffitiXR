# Antigravity Android Debugger

**Antigravity Android Debugger** is an intelligent assistant integration for VS Code, specifically designed to connect your Android Development workflow with the Antigravity (or Gemini) AI Agent.

## How It Works
It creates a continuous feedback loop between your Android App and the Agent:
1.  **Builds & Installs** your app.
2.  **Monitors** for crashes or build failures.
3.  **Captures Context**: Saves exact error logs to `ai_debug_session.md`.
4.  **Triggers Antigravity**: Automatically asks the Agent to fix the specific error using valueable context.

## Requirements
- **VS Code Chat Provider**: Copilot, Cursor, or any extension providing `workbench.action.chat.open`.
- **(Optional) Gemini CLI**: If you prefer terminal-based agents, install the `gemini` CLI tool.

## Features
- **Auto-Detect**: Finds your `applicationId` automatically.
- **Continuous Mode**: Fix a bug, Save the file, and the Agent immediately rebuilds and tests again.
- **Session Memory**: The Agent sees the history of what broke before, avoiding repetitive mistakes.

## Configuration
- `androidai.autoDebug`: (Boolean) Skip the popup and asking Antigravity immediately on error.
- `androidai.aiProvider`:
    - `vscode-chat`: Opens the sidebar Chat (default).
    - `gemini-cli`: Runs `gemini query` in the terminal.

## License
MIT
