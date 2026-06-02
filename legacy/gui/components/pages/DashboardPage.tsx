'use client';

import { useRef, useEffect } from 'react';
import { Switch } from '@/components/ui/switch';
import { Checkbox } from '@/components/ui/checkbox';
import { Slider } from '@/components/ui/slider';
import {
  FolderOpen,
  Download,
  Terminal,
  ChevronDown,
  ChevronUp,
  MapPin,
  Layers,
  Trash2,
  Play,
  Square,
  FileText,
  Check,
  Loader2,
  Flag,
  Copy,
  Eraser,
} from 'lucide-react';

interface LogMessage {
  type: 'info' | 'error' | 'success' | 'log' | 'raw';
  message: string;
  progress?: number;
}

interface DashboardPageProps {
  // File state
  htmlFile: string | null;
  downloadFolder: string | null;
  onSelectFile: () => void;
  onSelectFolder: () => void;
  // Pipeline state
  runDownload: boolean;
  setRunDownload: (v: boolean) => void;
  runMetadata: boolean;
  setRunMetadata: (v: boolean) => void;
  runCombine: boolean;
  setRunCombine: (v: boolean) => void;
  runDedupe: boolean;
  setRunDedupe: (v: boolean) => void;
  dryRun: boolean;
  setDryRun: (v: boolean) => void;
  // Advanced
  showAdvanced: boolean;
  setShowAdvanced: (v: boolean) => void;
  workerCount: number;
  setWorkerCount: (v: number) => void;
  // Process state
  isRunning: boolean;
  logs: LogMessage[];
  onStart: () => void;
  onStop: () => void;
  onClearLogs: () => void;
  onCopyLogs: () => void;
}

export function DashboardPage({
  htmlFile,
  downloadFolder,
  onSelectFile,
  onSelectFolder,
  runDownload,
  setRunDownload,
  runMetadata,
  setRunMetadata,
  runCombine,
  setRunCombine,
  runDedupe,
  setRunDedupe,
  dryRun,
  setDryRun,
  showAdvanced,
  setShowAdvanced,
  workerCount,
  setWorkerCount,
  isRunning,
  logs,
  onStart,
  onStop,
  onClearLogs,
  onCopyLogs,
}: DashboardPageProps) {
  const scrollRef = useRef<HTMLDivElement>(null);
  const htmlNeeded = runDownload || runMetadata;

  useEffect(() => {
    if (scrollRef.current) {
      scrollRef.current.scrollIntoView({ behavior: 'smooth' });
    }
  }, [logs]);

  return (
    <div className="flex-1 flex flex-col p-6 gap-6 overflow-y-auto bg-[radial-gradient(circle_at_top_right,_rgba(21,32,49,0.2),_#081425_70%)]">
      <div className="flex flex-col lg:flex-row gap-6 flex-1 min-h-0">

        {/* ── Left Column: Controls (40%) ──────────────── */}
        <div className="w-full lg:w-[40%] flex flex-col gap-4 overflow-y-auto pr-1">

          {/* Source & Destination */}
          <div className="glass-panel p-5 rounded-xl pro-shadow space-y-5">
            <h3 className="text-xs text-primary uppercase tracking-widest font-bold">Source & Destination</h3>
            <div className="space-y-3">
              <div>
                <label className="text-xs text-on-surface-variant block mb-1 font-medium">History Data</label>
                <div
                  onClick={onSelectFile}
                  className="flex items-center gap-2 bg-surface-container-lowest border border-white/5 p-2.5 rounded-lg cursor-pointer hover:border-primary/30 transition-colors group"
                >
                  <FileText className="w-4 h-4 text-on-surface-variant shrink-0" />
                  <span className="text-sm text-on-surface-variant/80 flex-1 truncate font-mono">
                    {htmlFile ? (htmlFile.split('/').pop() || htmlFile.split('\\').pop()) : 'memories_history.html'}
                  </span>
                  <span className="text-primary text-xs font-medium shrink-0">Browse</span>
                </div>
              </div>
              <div>
                <label className="text-xs text-on-surface-variant block mb-1 font-medium">Output Folder</label>
                <div
                  onClick={onSelectFolder}
                  className="flex items-center gap-2 bg-surface-container-lowest border border-white/5 p-2.5 rounded-lg cursor-pointer hover:border-primary/30 transition-colors group"
                >
                  <FolderOpen className="w-4 h-4 text-on-surface-variant shrink-0" />
                  <span className="text-sm text-on-surface-variant/80 flex-1 truncate font-mono">
                    {downloadFolder || '~/Downloads/snapchat_memories'}
                  </span>
                  <span className="text-primary text-xs font-medium shrink-0">Select</span>
                </div>
              </div>
            </div>
          </div>

          {/* Pipeline Options */}
          <div className="glass-panel p-5 rounded-xl pro-shadow space-y-4">
            <h3 className="text-xs text-primary uppercase tracking-widest font-bold">Pipeline Options</h3>
            <div className="space-y-1">
              <PipelineToggle icon={<Download className="w-4 h-4" />} label="Download Memories" checked={runDownload} onChange={setRunDownload} type="switch" />
              <PipelineToggle icon={<MapPin className="w-4 h-4" />} label="Add GPS Metadata" checked={runMetadata} onChange={setRunMetadata} type="checkbox" />
              <PipelineToggle icon={<Layers className="w-4 h-4" />} label="Combine Overlays" checked={runCombine} onChange={setRunCombine} type="switch" />
              <PipelineToggle icon={<Trash2 className="w-4 h-4" />} label="Remove Duplicates" checked={runDedupe} onChange={setRunDedupe} type="checkbox" />
              <PipelineToggle icon={<FileText className="w-4 h-4" />} label="Dry Run (preview only)" checked={dryRun} onChange={setDryRun} type="checkbox" />
            </div>
          </div>

          {/* Advanced Settings */}
          <div className="glass-panel rounded-xl pro-shadow overflow-hidden">
            <button onClick={() => setShowAdvanced(!showAdvanced)} className="w-full flex items-center justify-between p-4 hover:bg-white/5 transition-colors">
              <span className="text-xs text-on-surface-variant uppercase tracking-widest font-bold">Advanced Settings</span>
              {showAdvanced ? <ChevronUp className="w-4 h-4 text-on-surface-variant" /> : <ChevronDown className="w-4 h-4 text-on-surface-variant" />}
            </button>
            {showAdvanced && (
              <div className="px-5 pb-5 space-y-3">
                <div className="flex items-center justify-between">
                  <label className="text-sm text-on-surface font-medium">
                    Worker Threads: <span className="text-primary font-bold">{workerCount}</span>
                  </label>
                </div>
                <Slider
                  value={[workerCount]}
                  onValueChange={(vals) => setWorkerCount(vals[0])}
                  min={1}
                  max={typeof navigator !== 'undefined' ? (navigator.hardwareConcurrency || 8) : 8}
                  step={1}
                  className="w-full"
                />
                <p className="text-xs text-on-surface-variant/60 italic">
                  ⚠️ Higher values increase speed but use more CPU.
                </p>
              </div>
            )}
          </div>

          {/* Mobile Start/Stop */}
          <div className="flex gap-3 md:hidden">
            <button
              onClick={onStart}
              disabled={isRunning || (htmlNeeded && !htmlFile)}
              className="flex-1 bg-primary py-3 rounded-xl text-primary-foreground font-black text-lg flex items-center justify-center gap-3 transition-all hover:scale-[1.02] active:scale-95 shadow-[0px_0px_30px_rgba(208,188,255,0.3)] disabled:opacity-40"
            >
              <Play className="w-5 h-5" />
              {isRunning ? 'Processing...' : 'Start Sync'}
            </button>
            {isRunning && (
              <button onClick={onStop} className="px-6 bg-surface-container-highest/50 border border-white/10 text-on-surface rounded-xl hover:bg-red-500/10 hover:border-red-500/30 hover:text-red-400 transition-all active:scale-90">
                <Square className="w-5 h-5" />
              </button>
            )}
          </div>
        </div>

        {/* ── Right Column: Terminal + Progress (60%) ──── */}
        <div className="flex-1 flex flex-col gap-4 min-h-[400px]">

          {/* Terminal Panel */}
          <div className="glass-panel flex-1 rounded-xl pro-shadow flex flex-col overflow-hidden border border-white/5">
            <div className="bg-surface-container-high px-4 py-2.5 flex items-center justify-between border-b border-white/5 shrink-0">
              <div className="flex items-center gap-3">
                <div className="flex gap-1.5">
                  <div className="w-3 h-3 rounded-full bg-red-500/50" />
                  <div className="w-3 h-3 rounded-full bg-amber-500/50" />
                  <div className="w-3 h-3 rounded-full bg-green-500/50" />
                </div>
                <span className="ml-2 font-mono text-xs text-on-surface-variant/80">
                  <Terminal className="w-3 h-3 inline mr-1.5 -mt-px" />
                  snapvault — terminal
                </span>
              </div>
              <div className="flex gap-3 text-on-surface-variant/50">
                <button onClick={onCopyLogs} className="hover:text-on-surface transition-colors" title="Copy logs"><Copy className="w-4 h-4" /></button>
                <button onClick={onClearLogs} className="hover:text-on-surface transition-colors" title="Clear logs"><Eraser className="w-4 h-4" /></button>
              </div>
            </div>
            <div className="flex-1 bg-black/40 p-4 font-mono text-[13px] leading-5 overflow-y-auto">
              <div className="space-y-0.5">
                {logs.length === 0 && (
                  <div className="text-on-surface-variant/40 italic">$ waiting for command...<span className="terminal-cursor" /></div>
                )}
                {logs.map((log, i) => (
                  <div key={i} className="flex gap-3">
                    <span className={`shrink-0 ${log.type === 'error' ? 'text-red-400' : log.type === 'success' ? 'text-green-400' : log.type === 'info' ? 'text-tertiary' : 'text-on-surface-variant/60'}`}>
                      [{log.type === 'raw' ? 'LOG' : log.type.toUpperCase()}]
                    </span>
                    <span className={`text-on-surface/90 break-all ${log.type === 'error' || log.type === 'success' ? 'font-bold' : ''}`}>
                      {log.message}
                    </span>
                  </div>
                ))}
                {isRunning && logs.length > 0 && (
                  <div className="flex gap-3 mt-1">
                    <span className="text-on-surface-variant font-bold">$</span>
                    <span className="text-on-surface/90">syncing_in_progress<span className="terminal-cursor" /></span>
                  </div>
                )}
                <div ref={scrollRef} />
              </div>
            </div>
          </div>

          {/* Progress Stepper + Bar */}
          <div className="glass-panel p-5 rounded-xl pro-shadow space-y-4 shrink-0">
            <div className="flex items-center justify-between relative">
              <div className="absolute top-4 left-0 w-full h-[2px] bg-white/5 -z-10" />
              <StepItem label="Setup" status="done" />
              <StepItem label="Syncing" status={isRunning ? 'active' : 'pending'} />
              <StepItem label="Processing" status="pending" />
              <StepItem label="Complete" status="pending" isLast />
            </div>
            <div className="space-y-2">
              <div className="flex justify-between items-end">
                <span className="text-xs text-on-surface font-bold">{isRunning ? 'Processing...' : logs.length === 0 ? 'Ready' : 'Idle'}</span>
                <span className="text-xs text-tertiary font-medium">{isRunning ? '—' : ''}</span>
              </div>
              <div className="h-1.5 w-full bg-white/5 rounded-full overflow-hidden">
                <div className={`h-full progress-gradient transition-all duration-1000 ease-in-out ${isRunning ? 'active-glow' : ''}`} style={{ width: isRunning ? '35%' : '0%' }} />
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

/* ─── Sub-components ──────────────────────────────────── */

function PipelineToggle({ icon, label, checked, onChange, type }: {
  icon: React.ReactNode; label: string; checked: boolean; onChange: (v: boolean) => void; type: 'switch' | 'checkbox';
}) {
  return (
    <div className="flex items-center justify-between p-2 hover:bg-white/5 rounded-lg transition-colors group">
      <div className="flex items-center gap-3">
        <span className="text-on-surface-variant group-hover:text-tertiary transition-colors">{icon}</span>
        <span className="text-sm text-on-surface">{label}</span>
      </div>
      {type === 'switch' ? (
        <Switch checked={checked} onCheckedChange={onChange} />
      ) : (
        <Checkbox checked={checked} onCheckedChange={(c) => onChange(!!c)} />
      )}
    </div>
  );
}

function StepItem({ label, status, isLast }: { label: string; status: 'done' | 'active' | 'pending'; isLast?: boolean }) {
  return (
    <div className="flex flex-col items-center gap-1">
      <div className={`w-8 h-8 rounded-full flex items-center justify-center text-xs font-bold z-10 transition-all ${
        status === 'done' ? 'bg-primary text-primary-foreground shadow-[0_0_10px_rgba(208,188,255,0.4)]' :
        status === 'active' ? 'border-2 border-primary bg-primary/20 text-primary animate-pulse active-glow' :
        'border-2 border-white/10 bg-surface text-on-surface-variant/40'
      }`}>
        {status === 'done' ? <Check className="w-4 h-4" /> : status === 'active' ? <Loader2 className="w-4 h-4 animate-spin" /> : isLast ? <Flag className="w-3.5 h-3.5" /> : <span className="text-xs">•</span>}
      </div>
      <span className={`text-xs font-medium ${status === 'done' ? 'text-on-surface' : status === 'active' ? 'text-primary font-bold' : 'text-on-surface-variant/40'}`}>{label}</span>
    </div>
  );
}
