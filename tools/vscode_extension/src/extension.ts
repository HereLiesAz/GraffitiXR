
import * as vscode from 'vscode';
import * as cp from 'child_process';
import * as path from 'path';
import * as fs from 'fs';

let outputChannel: vscode.OutputChannel;
let logcatProcess: cp.ChildProcess | undefined;

// --- UTILS TO EXTRACT PACKAGE NAME ---
// --- UTILS TO EXTRACT PACKAGE NAME ---
async function getPackageName(rootPath: string): Promise<string | undefined> {
    // 1. Try build.gradle.kts (Kotlin DSL)
    const gradleKts = path.join(rootPath, 'app', 'build.gradle.kts');
    if (fs.existsSync(gradleKts)) {
        const content = fs.readFileSync(gradleKts, 'utf-8');
        // Match applicationId = "com.foo"
        const match = content.match(/applicationId\s*=\s*["']([^"']+)["']/);
        if (match) return match[1];
    }

    // 2. Try build.gradle (Groovy DSL)
    const gradleGroovy = path.join(rootPath, 'app', 'build.gradle');
    if (fs.existsSync(gradleGroovy)) {
        const content = fs.readFileSync(gradleGroovy, 'utf-8');
        // Groovy can be applicationId "com.foo" or applicationId = "com.foo"
        const match = content.match(/applicationId\s*=?\s*["']([^"']+)["']/);
        if (match) return match[1];
    }

    // 3. Fallback: AndroidManifest.xml (older structure or base package)
    const manifest = path.join(rootPath, 'app', 'src', 'main', 'AndroidManifest.xml');
    if (fs.existsSync(manifest)) {
        const content = fs.readFileSync(manifest, 'utf-8');
        const match = content.match(/package=["']([^"']+)["']/);
        if (match) return match[1];
    }

    return undefined;
}

// --- DEVICE & ENV UTILS ---
async function getAdbPath(): Promise<string> {
    // Check if valid in PATH
    try {
        await runCommand('adb', ['version'], '.');
        return 'adb';
    } catch {
        // Not in path, try env vars
        const sdkRoot = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT;
        if (sdkRoot) {
            const platformTools = path.join(sdkRoot, 'platform-tools', process.platform === 'win32' ? 'adb.exe' : 'adb');
            if (fs.existsSync(platformTools)) return platformTools;
        }
        return 'adb'; // Fail downstream if still not found
    }
}

async function selectDevice(): Promise<string | undefined> {
    try {
        const output = await execShell(`${await getAdbPath()} devices -l`);
        const lines = output.split('\n').filter(l => l.trim() !== '' && !l.startsWith('List of devices'));
        const devices = lines.map(line => {
            const parts = line.split(/\s+/);
            const serial = parts[0];
            const modelMatch = line.match(/model:(\S+)/);
            const model = modelMatch ? modelMatch[1] : 'Unknown';
            return { serial, model, label: `${model} (${serial})`, description: 'Connected Device' };
        }).filter(d => !d.serial.includes('offline') && !d.serial.includes('unauthorized'));

        if (devices.length === 0) {
            vscode.window.showErrorMessage("No Android devices found. Please connect a device or start an emulator.");
            return undefined;
        }
        if (devices.length === 1) {
            return devices[0].serial;
        }

        const picked = await vscode.window.showQuickPick(devices, { placeHolder: "Select Target Device" });
        return picked?.serial;

    } catch (e) {
        outputChannel.appendLine(`[Error] Failed to list devices: ${e}`);
        return undefined;
    }
}

function execShell(cmd: string): Promise<string> {
    return new Promise((resolve, reject) => {
        cp.exec(cmd, (err, stdout) => {
            if (err) reject(err);
            else resolve(stdout);
        });
    });
}

export function activate(context: vscode.ExtensionContext) {
    outputChannel = vscode.window.createOutputChannel("Android AI Debugger");
    
    const treeDataProvider = new DebugRunnerProvider();
    vscode.window.registerTreeDataProvider('android-ai-runner-view', treeDataProvider);

    context.subscriptions.push(vscode.commands.registerCommand('androidai.buildAndRun', () => buildAndRunAndMonitor(context)));
    context.subscriptions.push(vscode.commands.registerCommand('androidai.toggleLoop', () => treeDataProvider.toggleLoop()));
    context.subscriptions.push(vscode.commands.registerCommand('androidai.clearHistory', () => clearHistory(context)));
    
    // Watcher: Auto-rebuild on Save if waiting for fix
    context.subscriptions.push(vscode.workspace.onDidSaveTextDocument(() => {
        if (treeDataProvider.isLoopEnabled && treeDataProvider.isWaitingForFix) {
            console.log("File saved, restarting build loop...");
            outputChannel.appendLine("\n[Loop] File saved! Restarting Build & Run sequence...");
            buildAndRunAndMonitor(context, treeDataProvider);
        }
    }));
}

class DebugRunnerProvider implements vscode.TreeDataProvider<vscode.TreeItem> {
    private _onDidChangeTreeData: vscode.EventEmitter<vscode.TreeItem | undefined | null | void> = new vscode.EventEmitter<vscode.TreeItem | undefined | null | void>();
    readonly onDidChangeTreeData: vscode.Event<vscode.TreeItem | undefined | null | void> = this._onDidChangeTreeData.event;

    public isLoopEnabled: boolean = false;
    public isWaitingForFix: boolean = false;

    toggleLoop() {
        this.isLoopEnabled = !this.isLoopEnabled;
        this._onDidChangeTreeData.fire();
    }

    setWaitingForFix(waiting: boolean) {
        this.isWaitingForFix = waiting;
    }

    getTreeItem(element: vscode.TreeItem): vscode.TreeItem {
        return element;
    }

    getChildren(element?: vscode.TreeItem): Thenable<vscode.TreeItem[]> {
        if (!element) {
            // Button 1: Start
            const startItem = new vscode.TreeItem("Start Debug Session", vscode.TreeItemCollapsibleState.None);
            startItem.command = { command: 'androidai.buildAndRun', title: "Start" };
            startItem.iconPath = new vscode.ThemeIcon("play");

            // Button 2: Loop Toggle
            const loopLabel = this.isLoopEnabled ? "Continuous Mode: ON" : "Continuous Mode: OFF";
            const loopItem = new vscode.TreeItem(loopLabel, vscode.TreeItemCollapsibleState.None);
            loopItem.command = { command: 'androidai.toggleLoop', title: "Toggle Loop" };
            loopItem.iconPath = new vscode.ThemeIcon(this.isLoopEnabled ? "sync" : "circle-slash");
            loopItem.description = this.isLoopEnabled ? "(Rebuilds on Save after error)" : "(Manual restart only)";

            // Button 3: Clear History
            const cleanItem = new vscode.TreeItem("Clear AI History", vscode.TreeItemCollapsibleState.None);
            cleanItem.command = { command: 'androidai.clearHistory', title: "Clear History" };
            cleanItem.iconPath = new vscode.ThemeIcon("trash");

            return Promise.resolve([startItem, loopItem, cleanItem]);
        }
        return Promise.resolve([]);
    }
}

async function buildAndRunAndMonitor(context: vscode.ExtensionContext, provider?: DebugRunnerProvider) {
    if (provider) provider.setWaitingForFix(false);
    
    outputChannel.show(true);
    outputChannel.clear();
    
    const workspaceFolders = vscode.workspace.workspaceFolders;
    if (!workspaceFolders) {
        vscode.window.showErrorMessage("No workspace open.");
        return;
    }
    const rootPath = workspaceFolders[0].uri.fsPath;
    
    // Path Resolution
    const gradlewName = process.platform === 'win32' ? 'gradlew.bat' : 'gradlew';
    const gradlewPath = path.join(rootPath, gradlewName);
    const gradlewCmd = fs.existsSync(gradlewPath) ? (process.platform === 'win32' ? gradlewPath : `./${gradlewName}`) : 'gradle'; // Fallback to global gradle
    const adbPath = await getAdbPath();

    // Device Selection
    const deviceSerial = await selectDevice();
    if (!deviceSerial) return; // User cancelled or no device
    
    outputChannel.appendLine(`[Runner] Target Device: ${deviceSerial}`);
    
    // Guess APK path - standard is usually this, but can vary by flavor.
    // For a generic extension, we might strictly look for 'app/build/outputs/apk/debug/app-debug.apk'
    // or we could parse JSON output from gradle. For now, strict default is okay for MVP.
    const apkPath = path.join(rootPath, 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk');
    const logsPath = path.join(rootPath, 'ai_debug_crash.log');
    const buildErrorPath = path.join(rootPath, 'ai_debug_error.log');
    const historyPath = path.join(rootPath, 'ai_debug_session.md');

    // 0. STOP PREVIOUS
    if (logcatProcess) {
        outputChannel.appendLine("[Runner] Stopping previous logcat monitor...");
        logcatProcess.kill();
        logcatProcess = undefined;
    }

    // 1. BUILD
    outputChannel.appendLine("[Runner] Building Debug APK...");
    try {
        await runCommand(gradlewCmd, ['assembleDebug'], rootPath);
        outputChannel.appendLine("[Runner] Build Success.");
    } catch (e: any) {
        outputChannel.appendLine(`[Runner] BUILD FAILED: ${e.message}`);
        const errorContent = e.output || e.message;
        fs.writeFileSync(buildErrorPath, errorContent);
        addToHistory(historyPath, "BUILD_ERROR", errorContent);
        
        if (provider && provider.isLoopEnabled) {
            outputChannel.appendLine("[Loop] Build Failed. Waiting for file save to retry...");
            provider.setWaitingForFix(true);
        }

        const config = vscode.workspace.getConfiguration('androidai');
        const autoDebug = config.get<boolean>('autoDebug', false);
        const aiProvider = config.get<string>('aiProvider', 'vscode-chat');
        
        const aiPrompt = `Antigravity, please review the latest BUILD_ERROR in ${path.basename(historyPath)}. Fix the code while checking previous mistakes in the history.`;

        if (autoDebug) {
             outputChannel.appendLine(`[Runner] Auto-prompting AI (${aiProvider}) for build error...`);
             triggerAI(aiProvider, aiPrompt, historyPath);
        } else {
            const selection = await vscode.window.showErrorMessage(
                "Build Failed. What would you like to do?",
                "Ask Antigravity",
                "Always Ask Antigravity"
            );
            if (selection === "Ask Antigravity") {
                triggerAI(aiProvider, aiPrompt, historyPath);
            } else if (selection === "Always Ask Antigravity") {
                await config.update('autoDebug', true, vscode.ConfigurationTarget.Global);
                triggerAI(aiProvider, aiPrompt, historyPath);
            }
        }
        return;
    }

    // 2. DETECT PACKAGE NAME
    const packageName = await getPackageName(rootPath);
    if (!packageName) {
        outputChannel.appendLine("[Runner] WARNING: Could not detect package name (applicationId). Launching might fail.");
    } else {
        outputChannel.appendLine(`[Runner] Detected Package: ${packageName}`);
    }

    // 3. INSTALL
    outputChannel.appendLine("[Runner] Installing APK...");
    try {
        await runCommand(adbPath, ['-s', deviceSerial, 'install', '-r', apkPath], rootPath);
        outputChannel.appendLine("[Runner] Install Success.");
    } catch (e: any) {
        outputChannel.appendLine(`[Runner] INSTALL FAILED: ${e.message}`);
        vscode.window.showErrorMessage("Install Failed. Check output for details.");
        return;
    }

    // 4. LAUNCH
    outputChannel.appendLine("[Runner] Launching Activity...");
    try {
        await runCommand(adbPath, ['-s', deviceSerial, 'logcat', '-c'], rootPath); // Clear logs
        
        if (packageName) {
            // New way: Launch via Monkey (generic launcher) or try to start Main
            // Simplest generic way: "adb shell monkey -p <package> -c android.intent.category.LAUNCHER 1"
            await runCommand(adbPath, ['-s', deviceSerial, 'shell', 'monkey', '-p', packageName, '-c', 'android.intent.category.LAUNCHER', '1'], rootPath);
        } else {
            // Fallback to previous hardcoded if parsing failed? Or fail.
            // Let's fallback to the user's specific case for safety if parse failed, 
            // BUT since this is generic, we should probably just error or try a 'cmd package resolve-activity'
            throw new Error("Could not determine Package Name to launch.");
        }
        
        outputChannel.appendLine("[Runner] App Launched!");
    } catch (e: any) {
         outputChannel.appendLine(`[Runner] LAUNCH FAILED: ${e.message}`);
         return;
    }

    // 5. MONITOR
    outputChannel.appendLine("[Runner] Monitoring Logcat for Crashes...");
    if (packageName) {
        monitorLogcat(rootPath, logsPath, historyPath, context, packageName, adbPath, deviceSerial, provider);
    }
}

function monitorLogcat(cwd: string, logPath: string, historyPath: string, context: vscode.ExtensionContext, packageName: string, adbPath: string, deviceSerial: string, provider?: DebugRunnerProvider) {
    // Monitor everything, filter in code
    logcatProcess = cp.spawn(adbPath, ['-s', deviceSerial, 'logcat', '-v', 'threadtime', '*:E'], { cwd });
    
    logcatProcess.stdout?.on('data', async (data) => {
        const str = data.toString();
        // Heuristic: FATAL EXCEPTION or "Process <pkg> died"
        if (str.includes("FATAL EXCEPTION") || str.includes(`Process ${packageName} died`)) {
            outputChannel.appendLine("\n\n!! CRASH DETECTED !!\n");
            outputChannel.appendLine(str);
            fs.writeFileSync(logPath, str); 
            addToHistory(historyPath, "CRASH", str);

            if (logcatProcess) {
                logcatProcess.kill();
                logcatProcess = undefined;
            }

            if (provider && provider.isLoopEnabled) {
                outputChannel.appendLine("[Loop] App Crashed. Waiting for file save to retry...");
                provider.setWaitingForFix(true);
            }

            const config = vscode.workspace.getConfiguration('androidai');
            const autoDebug = config.get<boolean>('autoDebug', false);
            const aiProvider = config.get<string>('aiProvider', 'vscode-chat');

            const aiPrompt = `Antigravity, please review the latest CRASH in ${path.basename(historyPath)}. Analyze the stack trace and fix the issue, avoiding previous mistakes.`;

            if (autoDebug) {
                 triggerAI(aiProvider, aiPrompt, historyPath);
            } else {
                const selection = await vscode.window.showErrorMessage(
                    "App Crashed!",
                    "Ask Antigravity",
                    "Always Ask Antigravity"
                );
                
                if (selection === "Ask Antigravity") {
                    triggerAI(aiProvider, aiPrompt, historyPath);
                } else if (selection === "Always Ask Antigravity") {
                    await config.update('autoDebug', true, vscode.ConfigurationTarget.Global);
                    triggerAI(aiProvider, aiPrompt, historyPath);
                }
            }
        }
    });

    logcatProcess.on('close', (code) => {
        outputChannel.appendLine(`[Runner] Logcat monitor exited with code ${code}`);
    });
}

function addToHistory(historyPath: string, type: string, content: string) {
    const timestamp = new Date().toISOString();
    const entry = `\n\n## [${timestamp}] ${type}\n\`\`\`\n${content}\n\`\`\`\n`;
    fs.appendFileSync(historyPath, entry);
}

function clearHistory(context: vscode.ExtensionContext) {
    const workspaceFolders = vscode.workspace.workspaceFolders;
    if (workspaceFolders) {
        const historyPath = path.join(workspaceFolders[0].uri.fsPath, 'ai_debug_session.md');
        if (fs.existsSync(historyPath)) {
            fs.unlinkSync(historyPath);
            vscode.window.showInformationMessage("AI Debug History Cleared.");
        }
    }
}

function triggerAI(provider: string, prompt: string, contextFile: string) {
    if (provider === 'gemini-cli') {
        // Run gemini in a terminal
        const term = vscode.window.createTerminal("Antigravity Gemini");
        term.show();
        // Assuming 'gemini' CLI is installed and contextFile is relative or absolute
        // Adjust syntax based on actual gemini-cli capabilities. 
        // Assuming: gemini query -f <file> "<prompt>"
        term.sendText(`gemini query -f "${contextFile}" "${prompt}"`);
    } else {
        // Default: VS Code Chat (Proactive Agent)
        vscode.commands.executeCommand('workbench.action.chat.open', prompt);
    }
}

function runCommand(command: string, args: string[], cwd: string): Promise<void> {
    return new Promise((resolve, reject) => {
        const child = cp.spawn(command, args, { cwd, shell: true });
        let output = "";
        child.stdout?.on('data', d => { const s = d.toString(); output += s; outputChannel.append(s); });
        child.stderr?.on('data', d => { const s = d.toString(); output += s; outputChannel.append(s); });
        child.on('error', (err) => reject({ message: err.message, output }));
        child.on('close', (code) => {
            if (code === 0) resolve();
            else reject({ message: `Exited with code ${code}`, output });
        });
    });
}

export function deactivate() {
    if (logcatProcess) logcatProcess.kill();
}
