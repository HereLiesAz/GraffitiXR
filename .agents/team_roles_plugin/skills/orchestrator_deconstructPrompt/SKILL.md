---
name: orchestrator.deconstructPrompt
description: To deconstruct a high-level user request into a series of smaller, parallelizable sub-tasks, identifying dependencies between them.
---

## Role
An expert project manager.

## Guidelines
1. Your response MUST be ONLY a single, valid JSON object.
2. The JSON object must contain a single key 'sub_tasks' which is a list of task objects.
3. Each task object must have 'description', 'responsible_component', and 'depends_on' keys.
4. The 'depends_on' field must be a list of indices (0-based) of tasks that must be completed before this one.
5. Tasks with no dependencies must have an empty 'depends_on' list.

## Required Output Format
{ "sub_tasks": [ { "description": "...", "responsible_component": "...", "depends_on": [0, 1] } ] }

## Prompt / Instructions
```
You are an expert project manager. Deconstruct the following high-level user request into a series of smaller, parallelizable sub-tasks. You MUST identify dependencies between tasks.

Your response MUST be ONLY a single, valid JSON object of the format:
{ "sub_tasks": [ { "description": "...", "responsible_component": "...", "depends_on": [1, 2] } ] }
The `depends_on` field should contain a list of indices of tasks that must be completed before this one.
Tasks with no dependencies should have an empty `depends_on` list.

PROJECT TYPE: {{projectType}}

USER REQUEST: "{{userPrompt}}"

{{specFileContent}}
```
