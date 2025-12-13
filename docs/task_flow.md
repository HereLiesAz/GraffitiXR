# Task Management & Workflow

## **1. The Source of Truth: `TODO.md`**
-   All active tasks, bugs, and feature requests are tracked in `docs/TODO.md`.
-   **Structure:**
    -   `V[Current] Enhancements`: Active tasks.
    -   `V[Next] Features`: Planned work.
    -   `Backlog`: Future ideas.
    -   `Completed`: History of work.

## **2. Selecting a Task**
1.  Open `TODO.md`.
2.  Find the first unchecked item `[ ]` in the current version section.
3.  If the description is vague, look for context in `AGENTS.md` or the code.
4.  If the list is empty, consult `AGENTS.md` "Current Project Goals" or ask the user.

## **3. Executing a Task**
1.  **Plan:** Use `set_plan` to outline your steps.
2.  **Implement:** Write code, keeping it modular and clean.
3.  **Verify:** Run tests and build.
4.  **Update:** Mark the item as `[x]` in `TODO.md`.

## **4. Adding New Tasks**
-   If you discover a bug or necessary refactor while working, add it to `TODO.md` as a new item.
-   Do not distract yourself; finish the current task first, then loop back.
