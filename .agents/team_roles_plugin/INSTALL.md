# How to Install the General Developer Team Plugin

This plugin provides a high-robustness, multi-session agent team for any Antigravity project. It is standardized for the **Agentic Collaboration Standard (ACS)**.

## Installation Steps (Manual)

1.  **Download and Extract**: Unzip the `team_roles_plugin.zip` file.
2.  **Locate your Project**: Open your project in the Antigravity IDE.
3.  **Place the Plugin**: Copy the entire `team_roles_plugin` folder into your project's `/.agents/` directory.
    -   *If the `/.agents/` folder doesn't exist, create it.*
    -   Final path should be `your-project/.agents/team_roles_plugin/`.

## Usage

Once installed, your Antigravity session will have access to the decentralized team. You can initiate a mission by talking to the **Orchestrator**:

> *"Ask the Orchestrator to generate a plan for [Your Task]."*

The Orchestrator will automatically invoke the other 14 specialists (Native Guru, UX Visionary, Dreamer, etc.) as independent sessions whenever needed.

## Updating Personas
You can customize the agents by editing the `prompt.md` files located in:
`/.agents/team_roles_plugin/agents/[agent_name]/prompt.md`
