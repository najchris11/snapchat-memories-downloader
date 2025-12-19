const { app, BrowserWindow, ipcMain, dialog } = require('electron');
const path = require('path');
const { spawn } = require('child_process');

let mainWindow;

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

  mainWindow.on('closed', function () {
    mainWindow = null;
  });
}

app.on('ready', createWindow);

app.on('window-all-closed', function () {
  if (process.platform !== 'darwin') {
    app.quit();
  }
});

app.on('activate', function () {
  if (mainWindow === null) {
    createWindow();
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
    if (process.platform !== 'darwin') {
        event.reply('script-log', { type: 'error', message: 'The automatic installer is only supported on macOS.' });
        event.reply('script-log', { type: 'info', message: 'For Windows/Linux, please install Python 3.10+ and pip manually.' });
        event.reply('script-log', { type: 'info', message: 'Then run: pip install -r requirements.txt' });
        event.reply('script-exit', 1);
        return;
    }

    const scriptPath = path.join(__dirname, '..', 'installer.sh');
    // Ensure executable
    const chmod = spawn('chmod', ['+x', scriptPath]);
    
    chmod.on('close', (code) => {
        if (code !== 0) {
            event.reply('script-log', { type: 'error', message: 'Failed to make installer executable.' });
            return;
        }

        const installProcess = spawn(scriptPath, [], {
            cwd: path.join(__dirname, '..') // Run in project root
        });

        installProcess.stdout.on('data', (data) => {
            const lines = data.toString().split('\n');
            lines.forEach(line => {
                if(line.trim()) event.reply('script-log', { type: 'log', message: line });
            });
        });

        installProcess.stderr.on('data', (data) => {
             // installer.sh might output to stderr for info, treat as log unless it fails
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
});
