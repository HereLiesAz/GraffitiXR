# GraffitiXR Agent Guide

**CRITICAL INSTRUCTION: The AI absolutely MUST get a PERFECT code review AND a passing build with tests, and MUST keep all documents and documentation up to date, BEFORE committing--WITHOUT exception.**

This guide outlines the operational procedures, behavioral expectations, and tool usage guidelines for AI agents working on the GraffitiXR repository.

## **1. Core Directives**

### **Strict Commit Rules**
You are **strictly forbidden** from committing any code that has not passed the following checks:
1.  **Build Verification:** You must run `./gradlew assembleDebug` (or the appropriate build command) and ensure it completes successfully.
2.  **Test Verification:** You must run relevant unit tests (e.g., `./gradlew testDebugUnitTest`) and ensure they pass.
3.  **Code Review:** You must request a code review using the available tool and receive a "passing" or "perfect" score.
4.  **Documentation Update:** You must verify that `AGENTS.md`, `TODO.md`, and all files in `docs/` are up-to-date with your changes.

**Violation of these rules allows the user to reject your work immediately.**

### **"Verify Your Work"**
Never assume a tool action succeeded just because it didn't return an error. Always use a read-only tool (like `read_file`, `list_files`, `grep`) to verify the state of the codebase after every modification.
*   *Example:* After creating a file, `ls` the directory to confirm it's there.
*   *Example:* After editing a file, `read_file` to confirm the content is correct.

### **"Edit Source, Not Artifacts"**
Never edit files in `build/`, `dist/`, or other generated directories. Trace the artifact back to its source (e.g., `.kt`, `.xml`, `.java` files) and edit that instead.

### **"Practice Proactive Testing"**
Write tests *before* or *concurrently* with your code. Use `MainViewModelTest` as a template. If a full suite is too slow, run specific test classes.

### **"Diagnose Before Changing Environment"**
If the build fails, do not blindly install/uninstall dependencies. Read the error log. Most issues are code-related, not environment-related.

### **"Solve Problems Autonomously"**
You are a skilled engineer. Use `grep`, `find`, and other bash tools to locate files and code. Do not ask the user for trivial information like "where is MainViewModel?". Find it.

---

## **2. Tool Usage Guidelines**

-   **`run_in_bash_session`**: Use this for all shell commands (`./gradlew`, `ls`, `grep`, `git`).
-   **`create_file_with_block` / `overwrite_file_with_block`**: Use these for creating or fully replacing files. Preferred over search/replace for small to medium files to avoid diff errors.
-   **`replace_with_git_merge_diff`**: Use this for targeted edits in large files. Ensure context is unique.
-   **`view_image`**: Use this to inspect UI screenshots or assets if provided via URL.

---

## **3. Handling `AGENTS.md` and `TODO.md`**

-   **`AGENTS.md`**: This is your primary instruction manual. Its rules supersede standard behavior.
-   **`TODO.md`**: This is the source of truth for your tasks.
    -   **Working Down the List:** Address tasks in order.
    -   **Marking Complete:** When a task is done, change `[ ]` to `[x]`.
    -   **Updating:** If you identify new work, add it to the list.

---

## **4. Common Pitfalls**

-   **Deleting OpenCV:** Never remove the OpenCV dependency. It is required for `Fingerprint` serialization.
-   **Ignoring Lint Errors:** Run `./gradlew lintDebug` to catch potential crashes.
-   **Partial Commits:** Do not commit broken code. Squash your work into atomic, working commits.
