'use client';

import { useState, useRef, useEffect } from 'react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '@/components/ui/card';
import { Checkbox } from '@/components/ui/checkbox';
import { ScrollArea } from '@/components/ui/scroll-area';
import { FolderOpen, Download, Terminal, Settings, MapPin, Layers, Copy, Trash2 } from 'lucide-react';

interface LogMessage {
  type: 'info' | 'error' | 'success' | 'log' | 'raw';
  message: string;
  progress?: number;
}

export default function Home() {
  const [htmlFile, setHtmlFile] = useState<string | null>(null);
  const [downloadFolder, setDownloadFolder] = useState<string | null>(null);
  const [isRunning, setIsRunning] = useState(false);
  const [logs, setLogs] = useState<LogMessage[]>([]);
  const scrollRef = useRef<HTMLDivElement>(null);
  const scrollAreaRef = useRef<HTMLDivElement>(null);

  // Steps state
  const [runDownload, setRunDownload] = useState(true);
  const [runMetadata, setRunMetadata] = useState(true);
  const [runCombine, setRunCombine] = useState(true);
  const [runDedupe, setRunDedupe] = useState(true);
    const [dryRun, setDryRun] = useState(false);

  // Auto-scroll logs
  useEffect(() => {
    if (scrollRef.current) {
        scrollRef.current.scrollIntoView({ behavior: 'smooth' });
    }
  }, [logs]);

  // IPC Listener
  useEffect(() => {
    if (typeof window !== 'undefined' && window.require) {
      const { ipcRenderer } = window.require('electron');
      
      const handleLog = (event: any, log: LogMessage) => {
        setLogs(prev => [...prev, log]);
      };
      
      const handleExit = (event: any, code: number) => {
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
  }

    const startProcess = () => {
        const nothingSelected = !runDownload && !runMetadata && !runCombine && !runDedupe;
        if (nothingSelected) {
            setLogs(prev => [...prev, { type: 'error', message: 'Select at least one step to run.' }]);
            return;
        }
        if (!htmlFile && (runDownload || runMetadata)) {
            setLogs(prev => [...prev, { type: 'error', message: 'Please choose memories_history.html before running download/metadata.' }]);
            return;
        }

        setIsRunning(true);
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
            });
        }
    };

  const stopProcess = () => {
    if (typeof window !== 'undefined' && window.require) {
      const { ipcRenderer } = window.require('electron');
      ipcRenderer.send('stop-script');
    }
  };

  return (
    <div 
      className="min-h-screen p-8 font-sans"
      style={{ backgroundColor: '#FFFC00', color: '#000000' }}
    >
      <div className="max-w-4xl mx-auto space-y-8">
        
        {/* Header */}
        <div className="flex items-center justify-between">
            <div className="flex items-center space-x-4">
                <div className="w-12 h-12 bg-foreground rounded-2xl flex items-center justify-center shadow-xl">
                    <Download className="text-background w-6 h-6" />
                </div>
                <div>
                    <h1 className="text-2xl font-black tracking-tight text-foreground">Snapchat Memories</h1>
                    <p className="font-medium opacity-60 text-foreground">Downloader & Organizer</p>
                </div>
            </div>
            
            <Button 
                variant="outline" 
                onClick={runInstaller}
                disabled={isRunning}
            >
                <Settings className="w-4 h-4 mr-2" />
                Setup Environment
            </Button>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
            
            {/* Left Column: Controls */}
            <div className="md:col-span-1 space-y-6">
                
                {/* File Selection */}
                <Card className="border-2 border-border shadow-[4px_4px_0px_0px_hsl(var(--foreground))] rounded-xl overflow-hidden">
                    <CardHeader className="bg-muted border-b-2 border-dashed border-muted-foreground/20">
                        <CardTitle className="text-lg font-bold">1. Select Files</CardTitle>
                    </CardHeader>
                    <CardContent className="pt-6 space-y-4">
                        <div>
                            <Button 
                                variant="default"
                                className="w-full flex items-center gap-2 font-bold py-6 text-md"
                                onClick={selectFile}
                            >
                                <FolderOpen className="w-5 h-5" />
                                {htmlFile ? "Change HTML" : "Select HTML"}
                            </Button>
                            
                            <p className="mt-3 text-xs font-medium text-center opacity-60">
                                Select <span className="font-mono bg-secondary px-1 rounded">memories_history.html</span>
                            </p>

                            {htmlFile && (
                                <div className="mt-4 p-3 bg-muted rounded border border-border">
                                    <p className="text-xs text-foreground break-all font-mono">
                                        {htmlFile.split('/').pop() || htmlFile.split('\\').pop()}
                                    </p>
                                </div>
                            )}
                        </div>

                        <div>
                            <Button 
                                variant="outline"
                                className="w-full flex items-center gap-2 font-bold py-6 text-md"
                                onClick={selectFolder}
                            >
                                <Download className="w-5 h-5" />
                                {downloadFolder ? "Change Folder" : "Download Folder"}
                            </Button>
                            
                            <p className="mt-3 text-xs font-medium text-center opacity-60">
                                Optional: Choose where to save memories
                            </p>

                            {downloadFolder && (
                                <div className="mt-4 p-3 bg-muted rounded border border-border">
                                    <p className="text-xs text-foreground break-all font-mono">
                                        {downloadFolder}
                                    </p>
                                </div>
                            )}
                        </div>
                    </CardContent>
                </Card>

                {/* Options */}
                <Card className="border-2 border-border shadow-[4px_4px_0px_0px_hsl(var(--foreground))] rounded-xl">
                    <CardHeader className="bg-muted border-b-2 border-dashed border-muted-foreground/20">
                        <CardTitle className="text-lg font-bold">2. Configure</CardTitle>
                    </CardHeader>
                    <CardContent className="space-y-4 pt-6">
                        <div className="flex items-center space-x-2">
                            <Checkbox id="dl" checked={runDownload} onCheckedChange={(c) => setRunDownload(!!c)} />
                            <label htmlFor="dl" className="text-sm font-bold cursor-pointer">
                                Download Memories
                            </label>
                        </div>
                        <div className="flex items-center space-x-2">
                            <Checkbox id="meta" checked={runMetadata} onCheckedChange={(c) => setRunMetadata(!!c)} />
                            <label htmlFor="meta" className="text-sm font-bold cursor-pointer">
                                Add GPS Metadata
                            </label>
                        </div>
                        <div className="flex items-center space-x-2">
                            <Checkbox id="combine" checked={runCombine} onCheckedChange={(c) => setRunCombine(!!c)} />
                            <label htmlFor="combine" className="text-sm font-bold cursor-pointer">
                                Combine Overlays
                            </label>
                        </div>
                        <div className="flex items-center space-x-2">
                            <Checkbox id="dedupe" checked={runDedupe} onCheckedChange={(c) => setRunDedupe(!!c)} />
                            <label htmlFor="dedupe" className="text-sm font-bold cursor-pointer">
                                Delete Duplicates
                            </label>
                        </div>
                        <div className="flex items-center space-x-2">
                            <Checkbox id="dryRun" checked={dryRun} onCheckedChange={(c) => setDryRun(!!c)} />
                            <label htmlFor="dryRun" className="text-sm font-bold cursor-pointer">
                                Dry run (preview only)
                            </label>
                        </div>
                    </CardContent>
                </Card>

                <Button 
                    size="lg" 
                    className="w-full font-black text-lg py-8 shadow-[4px_4px_0px_0px_hsl(var(--foreground))] hover:translate-x-[2px] hover:translate-y-[2px] hover:shadow-[2px_2px_0px_0px_hsl(var(--foreground))] transition-all bg-foreground text-background border-2 border-border rounded-xl"
                    disabled={!htmlFile || isRunning}
                    onClick={startProcess}
                >
                    {isRunning ? "PROCESSING..." : "START DOWNLOAD"}
                </Button>
                
                {isRunning && (
                    <Button 
                        size="lg"
                        variant="destructive"
                        className="w-full font-black text-lg py-8 shadow-[4px_4px_0px_0px_hsl(var(--foreground))] hover:translate-x-[2px] hover:translate-y-[2px] hover:shadow-[2px_2px_0px_0px_hsl(var(--foreground))] transition-all border-2 border-border rounded-xl"
                        onClick={stopProcess}
                    >
                        STOP PROCESS
                    </Button>
                )}
            </div>

            {/* Right Column: Logs */}
            <div className="md:col-span-2">
                <Card className="h-full border-2 border-border shadow-[4px_4px_0px_0px_hsl(var(--foreground))] bg-foreground text-background flex flex-col rounded-xl overflow-hidden">
                    <CardHeader className="pb-3 border-b border-background/10 bg-foreground/90">
                        <CardTitle className="text-sm font-mono text-background/60 flex items-center gap-2">
                            <Terminal className="w-4 h-4" /> Terminal Output
                        </CardTitle>
                    </CardHeader>
                    <CardContent className="flex-1 p-0 min-h-[500px] relative">
                        <ScrollArea className="h-[500px] w-full p-4 font-mono text-xs">
                            <div className="space-y-1">
                                {logs.length === 0 && (
                                    <div className="text-background/40 italic"> waiting for command...</div>
                                )}
                                {logs.map((log, i) => (
                                    <div key={i} className={`
                                        ${log.type === 'error' ? 'text-red-400 font-bold' : ''}
                                        ${log.type === 'success' ? 'text-green-400 font-bold' : ''}
                                        ${log.type === 'info' ? 'text-blue-300' : ''}
                                        ${log.type === 'log' ? 'text-zinc-300' : ''}
                                        break-words
                                    `}>
                                        <span className="opacity-30 mr-2 select-none">[{new Date().toLocaleTimeString()}]</span>
                                        {log.message}
                                    </div>
                                ))}
                                <div ref={scrollRef} />
                            </div>
                        </ScrollArea>
                    </CardContent>
                </Card>
            </div>

        </div>
      </div>
    </div>
  );
}
