const { app, BrowserWindow, ipcMain, dialog } = require('electron');
const path = require('path');
const fs = require('fs');
const { spawn } = require('child_process');

let mainWindow;
let isQuitting = false;
const activeProcesses = new Set();
let stopRequested = false;

function trackProcess(proc) {
  activeProcesses.add(proc);
  proc.on('close', () => {
    activeProcesses.delete(proc);
  });
  proc.on('exit', () => {
    activeProcesses.delete(proc);
  });
}

function terminateActiveProcesses() {
  for (const proc of Array.from(activeProcesses)) {
    try {
      if (process.platform === 'win32') {
        // On Windows, send a hard kill to the entire tree to ensure Python + children exit
        const pid = proc.pid;
        if (pid) {
          try {
            spawn('taskkill', ['/PID', String(pid), '/T', '/F']);
          } catch (e) {
            // Fallback to direct kill if taskkill fails
            try { proc.kill('SIGTERM'); } catch (_) {}
          }
        }
      } else {
        // Try graceful stop first on POSIX
        proc.kill('SIGINT');
        setTimeout(() => {
          try { proc.kill('SIGTERM'); } catch (e) {}
        }, 1500);
      }
    } catch (e) {
      // Ignore errors during shutdown
    }
  }
}

function confirmQuitSync(browserWindow) {
  const res = dialog.showMessageBoxSync(browserWindow || null, {
    type: 'warning',
    buttons: ['Cancel', 'Quit'],
    defaultId: 1,
    cancelId: 0,
    title: 'Quit Application?',
    message: 'Do you want to quit?',
    detail:
      'Exiting will stop the current run. The downloader will resume progress once restarted.'
  });
  return res === 1; // 1 = "Quit"
}

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1200,
    height: 900,
    webPreferences: {
      nodeIntegration: true,
      contextIsolation: false, // For easier prototyping; secure apps should use preload
      webSecurity: false // Allow loading local resources
    },
    backgroundColor: '#000000',
  });

  const startUrl = process.env.ELECTRON_START_URL || 
    `file://${path.join(__dirname, 'out/index.html')}`;

  if (process.env.ELECTRON_START_URL) {
      mainWindow.loadURL('http://localhost:3000');
      mainWindow.webContents.openDevTools();
  } else {
      mainWindow.loadFile(path.join(__dirname, 'out/index.html'));
  }

  // Intercept window close to confirm quit and stop scripts
  mainWindow.on('close', (event) => {
    if (!isQuitting) {
      const confirmed = confirmQuitSync(mainWindow);
      if (!confirmed) {
        event.preventDefault();
        return;
      }
      isQuitting = true;
      terminateActiveProcesses();
    }
  });

  mainWindow.on('closed', function () {
    mainWindow = null;
  });
}

app.on('ready', createWindow);

// Always quit the app when all windows are closed (and after confirmation above)
app.on('window-all-closed', function () {
  app.quit();
});

app.on('activate', function () {
  if (mainWindow === null) {
    createWindow();
  }
});

// Confirm on explicit app quit (e.g., Cmd+Q)
app.on('before-quit', (event) => {
  if (!isQuitting) {
    const confirmed = confirmQuitSync(mainWindow);
    if (!confirmed) {
      event.preventDefault();
      return;
    }
    isQuitting = true;
    terminateActiveProcesses();
  }
});

// IPC Handlers

// 1. Select File Dialog
ipcMain.handle('select-file', async () => {
  const result = await dialog.showOpenDialog(mainWindow, {
    properties: ['openFile'],
    filters: [{ name: 'HTML Files', extensions: ['html'] }]
  });
  return result.filePaths[0];
});

// 1b. Select Folder Dialog
ipcMain.handle('select-folder', async () => {
  const result = await dialog.showOpenDialog(mainWindow, {
    properties: ['openDirectory', 'createDirectory']
  });
  return result.filePaths[0];
});

// 2. Run Python Scripts (single or workflow)
ipcMain.on('run-script', async (event, payload) => {
  stopRequested = false;
  // Determine api.py path
  let scriptPath;
  let pythonCommand;
  let baseDir;

  if (app.isPackaged) {
    scriptPath = path.join(process.resourcesPath, 'api.py');
    baseDir = process.resourcesPath;
  } else {
    scriptPath = path.join(__dirname, '..', 'api.py');
    baseDir = path.join(__dirname, '..');
  }

  // Check for venv in user's home directory first (where installer creates it)
  const homeVenvPath = path.join(process.env.USERPROFILE || process.env.HOME, 'snapchat-memories-downloader', '.venv');
  const projectVenvPath = path.join(baseDir, '.venv');

  if (process.platform === 'win32') {
    const homeVenvPython = path.join(homeVenvPath, 'Scripts', 'python.exe');
    const projectVenvPython = path.join(projectVenvPath, 'Scripts', 'python.exe');

    if (fs.existsSync(homeVenvPython)) {
      pythonCommand = homeVenvPython;
    } else if (fs.existsSync(projectVenvPython)) {
      pythonCommand = projectVenvPython;
    } else {
      pythonCommand = 'python';
    }
  } else {
    const homeVenvPython = path.join(homeVenvPath, 'bin', 'python3');
    const projectVenvPython = path.join(projectVenvPath, 'bin', 'python3');

    if (fs.existsSync(homeVenvPython)) {
      pythonCommand = homeVenvPython;
    } else if (fs.existsSync(projectVenvPython)) {
      pythonCommand = projectVenvPython;
    } else {
      pythonCommand = 'python3';
    }
  }

  const runApi = (command, args = []) => {
    const scriptArgs = [scriptPath, command, ...args];
    const pythonProcess = spawn(pythonCommand, scriptArgs);
    trackProcess(pythonProcess);

    pythonProcess.stdout.on('data', (data) => {
      const lines = data.toString().split('\n');
      lines.forEach(line => {
        if (line.trim()) {
          try {
            const jsonLog = JSON.parse(line);
            event.reply('script-log', jsonLog);
          } catch (e) {
            event.reply('script-log', { type: 'raw', message: line });
          }
        }
      });
    });

    pythonProcess.stderr.on('data', (data) => {
      event.reply('script-log', { type: 'error', message: data.toString() });
    });

    return new Promise((resolve) => {
      pythonProcess.on('close', (code) => {
        resolve(code);
      });
      pythonProcess.on('exit', (code) => {
        resolve(code);
      });
    });
  };

  // Workflow mode: run selected steps in sequence
  if (payload.workflow) {
    const { htmlFile, downloadFolder, runDownload, runMetadata, runCombine, runDedupe, dryRun, workerCount } = payload;

    const dryRunArgs = dryRun ? ['--dry-run'] : ['--no-dry-run'];
    const workerArgs = workerCount ? ['--workers', String(workerCount)] : [];
    const withOutput = (args = []) => (downloadFolder ? [...args, '--output', downloadFolder] : args);

    const steps = [];
    if (runDownload) {
      if (!htmlFile) {
        event.reply('script-log', { type: 'error', message: 'HTML file required to download memories.' });
        event.reply('script-exit', 1);
        return;
      }
      steps.push({ label: 'Download Memories', command: 'download', args: withOutput([htmlFile, ...workerArgs]) });
    }
    if (runMetadata) {
      if (!htmlFile) {
        event.reply('script-log', { type: 'error', message: 'HTML file required to add metadata.' });
        event.reply('script-exit', 1);
        return;
      }
      steps.push({ label: 'Add GPS Metadata', command: 'metadata', args: withOutput([htmlFile, ...workerArgs]) });
    }
    if (runCombine) {
      steps.push({ label: 'Combine Overlays', command: 'combine', args: withOutput([...dryRunArgs, ...workerArgs]) });
    }
    if (runDedupe) {
      steps.push({ label: 'Delete Duplicates', command: 'dedupe', args: withOutput([...dryRunArgs, ...workerArgs]) });
    }

    for (const step of steps) {
      if (stopRequested) {
        event.reply('script-log', { type: 'info', message: 'Stop requested; aborting remaining steps.' });
        return;
      }

      event.reply('script-log', { type: 'info', message: `=== ${step.label} ===` });
      const code = await runApi(step.command, step.args);

      if (stopRequested) {
        event.reply('script-log', { type: 'info', message: 'Stop requested; remaining steps skipped.' });
        return;
      }

      if (code !== 0) {
        event.reply('script-exit', code);
        return;
      }
    }

    event.reply('script-exit', 0);
    return;
  }

  // Single command mode (legacy)
  const { command, args = [], downloadFolder } = payload;
  const scriptArgs = downloadFolder ? [...args, '--output', downloadFolder] : args;
  const code = await runApi(command, scriptArgs);
  if (!stopRequested) {
    event.reply('script-exit', code);
  }
});

// 2b. Stop Running Script
ipcMain.on('stop-script', (event) => {
    event.reply('script-log', { type: 'info', message: 'Stopping process...' });
    stopRequested = true;
    terminateActiveProcesses();
    event.reply('script-exit', -1); // Signal stopped
});

// 3. Install Dependencies
ipcMain.on('install-dependencies', (event) => {
  const projectRoot = path.join(__dirname, '..');

  if (process.platform === 'darwin') {
    // Resolve script path based on environment
    let scriptPath;
    if (app.isPackaged) {
      scriptPath = path.join(process.resourcesPath, 'installer.sh');
    } else {
      scriptPath = path.join(projectRoot, 'installer.sh');
    }

    // Ensure executable
    const chmod = spawn('chmod', ['+x', scriptPath]);
    
    chmod.on('close', (code) => {
      if (code !== 0) {
        event.reply('script-log', { type: 'error', message: 'Failed to make installer executable. Please try running manually.' });
        event.reply('script-exit', code);
        return;
      }

      event.reply('script-log', { type: 'info', message: 'Launching installer in Terminal...' });
      event.reply('script-log', { type: 'info', message: 'Please follow the instructions in the new Terminal window.' });

      // Open a new Terminal window to run the script
      // This allows for user interaction (sudo password, homebrew prompt)
      spawn('open', ['-a', 'Terminal', scriptPath]);
      
      event.reply('script-exit', 0);
    });
    return;
  }

  if (process.platform === 'win32') {
    // Resolve script path based on environment
    let psScript;
    if (app.isPackaged) {
      psScript = path.join(process.resourcesPath, 'installer.ps1');
    } else {
      psScript = path.join(projectRoot, 'installer.ps1');
    }
    
    const powershell = 'powershell.exe';
    const args = ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', psScript];
    const installProcess = spawn(powershell, args, { cwd: app.isPackaged ? process.resourcesPath : projectRoot });
    trackProcess(installProcess);

    installProcess.stdout.on('data', (data) => {
      const lines = data.toString().split('\n');
      lines.forEach(line => {
        if(line.trim()) event.reply('script-log', { type: 'log', message: line });
      });
    });

    installProcess.stderr.on('data', (data) => {
       event.reply('script-log', { type: 'log', message: data.toString() });
    });

    installProcess.on('close', (code) => {
      if (code === 0) {
         event.reply('script-log', { type: 'success', message: 'Installation completed successfully!' });
      } else {
         event.reply('script-log', { type: 'error', message: `Installer exited with code ${code}` });
      }
      event.reply('script-exit', code);
    });
    return;
  }

  // Linux and others: show instructions modal (required deps)
  const message = 'Manual setup required on Linux (GUI will not proceed until installed).';
  const detail = [
    'REQUIRED:',
    ' - Python 3.10+ and pip',
    '   Ubuntu/Debian: sudo apt update && sudo apt install python3 python3-pip python3-venv',
    ' - exiftool (write GPS metadata to files)',
    '   Ubuntu/Debian: sudo apt install exiftool',
    ' - ffmpeg (combine video overlays)',
    '   Ubuntu/Debian: sudo apt install ffmpeg',
    '',
    'Setup steps:',
    ' 1) Create and activate a virtual environment:',
    '    python3 -m venv .venv',
    '    source .venv/bin/activate',
    ' 2) Install Python requirements:',
    '    pip install -r requirements.txt',
    ' 3) Run the orchestrator:',
    '    python3 run_all.py'
  ].join('\n');

  dialog.showMessageBox(mainWindow, {
    type: 'info',
    buttons: ['OK'],
    title: 'Linux Setup',
    message,
    detail
  });
  event.reply('script-log', { type: 'info', message });
  detail.split('\n').forEach(line => event.reply('script-log', { type: 'log', message: line }));
  event.reply('script-exit', 0);
});
