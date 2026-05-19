'use client';

import { useState, useEffect } from 'react';
import { AppShell, type PageId } from '@/components/AppShell';
import { DashboardPage } from '@/components/pages/DashboardPage';
import { LibraryPage } from '@/components/pages/LibraryPage';
import { EnvironmentPage } from '@/components/pages/EnvironmentPage';
import { SettingsPage } from '@/components/pages/SettingsPage';

interface LogMessage {
  type: 'info' | 'error' | 'success' | 'log' | 'raw';
  message: string;
  progress?: number;
}

export default function Home() {
  const [activePage, setActivePage] = useState<PageId>('dashboard');

  // ── Shared state (owned here, passed to pages) ──────
  const [htmlFile, setHtmlFile] = useState<string | null>(null);
  const [downloadFolder, setDownloadFolder] = useState<string | null>(null);
  const [isRunning, setIsRunning] = useState(false);
  const [logs, setLogs] = useState<LogMessage[]>([]);

  // Pipeline options
  const [runDownload, setRunDownload] = useState(true);
  const [runMetadata, setRunMetadata] = useState(true);
  const [runCombine, setRunCombine] = useState(true);
  const [runDedupe, setRunDedupe] = useState(true);
  const [dryRun, setDryRun] = useState(false);
  const [showAdvanced, setShowAdvanced] = useState(false);
  const [workerCount, setWorkerCount] = useState(
    typeof navigator !== 'undefined' ? Math.max(2, Math.floor((navigator.hardwareConcurrency || 4) / 2)) : 2
  );

  const htmlNeeded = runDownload || runMetadata;

  // ── IPC Listener ────────────────────────────────────
  useEffect(() => {
    if (typeof window !== 'undefined' && window.require) {
      const { ipcRenderer } = window.require('electron');

      const handleLog = (_event: any, log: LogMessage) => {
        setLogs(prev => [...prev, log]);
      };

      const handleExit = (_event: any, code: number) => {
        setLogs(prev => [...prev, { type: code === 0 ? 'success' : 'error', message: `Process exited with code ${code}` }]);
        setIsRunning(false);
      };

      ipcRenderer.on('script-log', handleLog);
      ipcRenderer.on('script-exit', handleExit);

      return () => {
        ipcRenderer.removeListener('script-log', handleLog);
        ipcRenderer.removeListener('script-exit', handleExit);
      };
    }
  }, []);

  // ── IPC Actions ─────────────────────────────────────
  const selectFile = async () => {
    if (typeof window !== 'undefined' && window.require) {
      const { ipcRenderer } = window.require('electron');
      const filePath = await ipcRenderer.invoke('select-file');
      if (filePath) {
        setHtmlFile(filePath);
        setLogs(prev => [...prev, { type: 'info', message: `Selected file: ${filePath}` }]);
      }
    } else {
      setHtmlFile('/mock/path/to/memories_history.html');
    }
  };

  const selectFolder = async () => {
    if (typeof window !== 'undefined' && window.require) {
      const { ipcRenderer } = window.require('electron');
      const folderPath = await ipcRenderer.invoke('select-folder');
      if (folderPath) {
        setDownloadFolder(folderPath);
        setLogs(prev => [...prev, { type: 'info', message: `Download folder: ${folderPath}` }]);
      }
    } else {
      setDownloadFolder('/mock/path/to/downloads');
    }
  };

  const runInstaller = () => {
    setIsRunning(true);
    setActivePage('dashboard'); // Switch to dashboard to show terminal output
    setLogs([{ type: 'info', message: 'Starting environment setup...' }]);
    if (typeof window !== 'undefined' && window.require) {
      const { ipcRenderer } = window.require('electron');
      ipcRenderer.send('install-dependencies');
    } else {
      setTimeout(() => {
        setLogs(prev => [...prev, { type: 'log', message: 'Simulated installer...' }]);
        setIsRunning(false);
      }, 1000);
    }
  };

  const startProcess = () => {
    const nothingSelected = !runDownload && !runMetadata && !runCombine && !runDedupe;
    if (nothingSelected) {
      setLogs(prev => [...prev, { type: 'error', message: 'Select at least one step to run.' }]);
      return;
    }
    if (!htmlFile && htmlNeeded) {
      setLogs(prev => [...prev, { type: 'error', message: 'Please choose memories_history.html before running download/metadata.' }]);
      return;
    }

    setIsRunning(true);
    setActivePage('dashboard'); // Ensure we're on dashboard to see output
    setLogs([{ type: 'info', message: 'Starting process...' }]);

    if (typeof window !== 'undefined' && window.require) {
      const { ipcRenderer } = window.require('electron');
      ipcRenderer.send('run-script', {
        workflow: true,
        htmlFile,
        downloadFolder,
        runDownload,
        runMetadata,
        runCombine,
        runDedupe,
        dryRun,
        workerCount,
      });
    }
  };

  const stopProcess = () => {
    if (typeof window !== 'undefined' && window.require) {
      const { ipcRenderer } = window.require('electron');
      ipcRenderer.send('stop-script');
    }
  };

  const clearLogs = () => {
    if (!isRunning) setLogs([]);
  };

  const copyLogs = () => {
    const text = logs.map(l => `[${l.type.toUpperCase()}] ${l.message}`).join('\n');
    navigator.clipboard?.writeText(text);
  };

  // ── Render ──────────────────────────────────────────
  return (
    <AppShell
      activePage={activePage}
      onNavigate={setActivePage}
      isRunning={isRunning}
      canStart={!htmlNeeded || !!htmlFile}
      onStartSync={startProcess}
      onStopSync={stopProcess}
    >
      {activePage === 'dashboard' && (
        <DashboardPage
          htmlFile={htmlFile}
          downloadFolder={downloadFolder}
          onSelectFile={selectFile}
          onSelectFolder={selectFolder}
          runDownload={runDownload}
          setRunDownload={setRunDownload}
          runMetadata={runMetadata}
          setRunMetadata={setRunMetadata}
          runCombine={runCombine}
          setRunCombine={setRunCombine}
          runDedupe={runDedupe}
          setRunDedupe={setRunDedupe}
          dryRun={dryRun}
          setDryRun={setDryRun}
          showAdvanced={showAdvanced}
          setShowAdvanced={setShowAdvanced}
          workerCount={workerCount}
          setWorkerCount={setWorkerCount}
          isRunning={isRunning}
          logs={logs}
          onStart={startProcess}
          onStop={stopProcess}
          onClearLogs={clearLogs}
          onCopyLogs={copyLogs}
        />
      )}
      {activePage === 'library' && <LibraryPage />}
      {activePage === 'environment' && (
        <EnvironmentPage onRunInstaller={runInstaller} isInstalling={isRunning} />
      )}
      {activePage === 'settings' && <SettingsPage />}
    </AppShell>
  );
}
