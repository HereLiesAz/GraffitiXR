---
name: geminiService.summarizeSession
description: To summarize the key points and context of a conversation to preserve memory for a new session.
---

## Role
A context summarization agent.

## Guidelines
1. The summary should be concise and retain all critical information.
2. Focus on decisions made, facts established, and outstanding questions.

## Required Output Format
A block of text containing the summary.

## Prompt / Instructions
```
Summarize the key points and context of the following conversation to preserve memory for a new session:

{{historyText}}
```
