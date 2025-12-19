const { app, BrowserWindow, ipcMain, dialog } = require('electron');
const path = require('path');
const { spawn } = require('child_process');

let mainWindow;
let isQuitting = false;
const activeProcesses = new Set();

function trackProcess(proc) {
  activeProcesses.add(proc);
  proc.on('close', () => {
    activeProcesses.delete(proc);
  });
}

function terminateActiveProcesses() {
  for (const proc of Array.from(activeProcesses)) {
    try {
      // Try graceful stop first
      proc.kill('SIGINT');
      setTimeout(() => {
        // Ensure termination if still alive
        try { proc.kill('SIGTERM'); } catch (e) {}
      }, 1500);
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
    width: 1000,
    height: 800,
    webPreferences: {
      nodeIntegration: true,
      contextIsolation: false, // For easier prototyping; secure apps should use preload
      webSecurity: false // Allow loading local resources
    },
    titleBarStyle: 'hidden',
    trafficLightPosition: { x: 15, y: 15 }, // MacOS traffic lights
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

// 2. Run Python Scripts
ipcMain.on('run-script', (event, { command, args }) => {
    // Determine api.py path
    // In dev: ../api.py. In prod: resources/app.asar.unpacked/api.py or similar
    // For now, let's assume we are running relative to the project root in dev
    // and we'll need to handle prod path logic later.
    
    let scriptPath;
    if (app.isPackaged) {
        scriptPath = path.join(process.resourcesPath, 'api.py');
    } else {
        scriptPath = path.join(__dirname, '..', 'api.py');
    }

    const pythonProcess = spawn('python3', [scriptPath, command, ...args]);
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

    pythonProcess.on('close', (code) => {
        event.reply('script-exit', code);
    });
});

// 3. Install Dependencies
ipcMain.on('install-dependencies', (event) => {
  const projectRoot = path.join(__dirname, '..');

  if (process.platform === 'darwin') {
    const scriptPath = path.join(projectRoot, 'installer.sh');
    // Ensure executable
    const chmod = spawn('chmod', ['+x', scriptPath]);
    trackProcess(chmod);

    chmod.on('close', (code) => {
      if (code !== 0) {
        event.reply('script-log', { type: 'error', message: 'Failed to make installer executable.' });
        event.reply('script-exit', code);
        return;
      }

      const installProcess = spawn(scriptPath, [], { cwd: projectRoot });
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
    });
    return;
  }

  if (process.platform === 'win32') {
    const psScript = path.join(projectRoot, 'installer.ps1');
    const powershell = 'powershell.exe';
    const args = ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', psScript];
    const installProcess = spawn(powershell, args, { cwd: projectRoot });
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
