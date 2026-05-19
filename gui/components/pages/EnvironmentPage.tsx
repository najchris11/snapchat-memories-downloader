'use client';

import {
  MonitorCog,
  Terminal,
  Image,
  Film,
  CheckCircle,
  AlertCircle,
  Clock,
  ArrowRight,
  Shield,
  Zap,
  Database,
  Loader2,
} from 'lucide-react';

// Types for dependency status
type DepStatus = 'ready' | 'missing' | 'pending' | 'checking';

interface Dependency {
  name: string;
  description: string;
  icon: React.ReactNode;
  status: DepStatus;
  version?: string;
  statusMessage: string;
}

interface EnvironmentPageProps {
  onRunInstaller: () => void;
  isInstalling: boolean;
}

export function EnvironmentPage({ onRunInstaller, isInstalling }: EnvironmentPageProps) {
  // TODO: Wire these to real dependency detection via IPC
  const dependencies: Dependency[] = [
    {
      name: 'Python Core',
      description: 'Required for local automation scripts and media processing.',
      icon: <Terminal className="w-5 h-5" />,
      status: 'ready',
      version: 'v3.11.x detected',
      statusMessage: 'READY',
    },
    {
      name: 'ExifTool',
      description: 'Enables precise metadata extraction for GPS coordinates and timestamps.',
      icon: <Image className="w-5 h-5" />,
      status: 'pending',
      statusMessage: 'PENDING',
    },
    {
      name: 'FFmpeg',
      description: 'Handles video transcoding and overlay composition for media files.',
      icon: <Film className="w-5 h-5" />,
      status: 'pending',
      statusMessage: 'PENDING',
    },
  ];

  return (
    <div className="flex-1 overflow-y-auto p-8">
      <div className="max-w-4xl mx-auto space-y-8">

        {/* Header */}
        <section className="space-y-2">
          <h2 className="text-3xl font-bold tracking-tight text-on-surface">Environment Setup</h2>
          <p className="text-on-surface-variant max-w-2xl text-base">
            The downloader requires specific tools to process media and extract metadata.
            Ensure your workstation has the right dependencies installed.
          </p>
        </section>

        {/* Dependency Bento Grid */}
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
          {dependencies.map((dep) => (
            <DependencyCard key={dep.name} dep={dep} />
          ))}
        </div>

        {/* Main Action Area */}
        <div className="glass-panel rounded-2xl p-10 flex flex-col items-center justify-center text-center space-y-6 min-h-[350px] relative overflow-hidden">
          {/* Atmospheric glow */}
          <div className="absolute -top-[30%] -right-[15%] w-[400px] h-[400px] bg-primary/10 rounded-full blur-[120px] pointer-events-none" />
          <div className="absolute -bottom-[30%] -left-[15%] w-[350px] h-[350px] bg-tertiary/10 rounded-full blur-[100px] pointer-events-none" />

          {isInstalling ? (
            <div className="flex flex-col items-center space-y-6 z-10">
              <div className="w-20 h-20 rounded-2xl bg-gradient-to-br from-primary to-tertiary flex items-center justify-center shadow-2xl shadow-primary/20 animate-pulse">
                <Loader2 className="w-10 h-10 text-white animate-spin" />
              </div>
              <div className="space-y-2">
                <h3 className="text-2xl font-semibold text-on-surface">Installing Dependencies...</h3>
                <p className="text-on-surface-variant max-w-sm">
                  Running the installer script. Check the Dashboard terminal for live output.
                </p>
              </div>
            </div>
          ) : (
            <div className="flex flex-col items-center space-y-6 z-10">
              <div className="w-20 h-20 rounded-2xl bg-gradient-to-br from-primary to-tertiary flex items-center justify-center shadow-2xl shadow-primary/20">
                <MonitorCog className="w-10 h-10 text-white" />
              </div>
              <div className="space-y-2">
                <h3 className="text-2xl font-semibold text-on-surface">Automatic Configuration</h3>
                <p className="text-on-surface-variant max-w-sm">
                  Automatically fetch and install missing dependencies for your OS architecture.
                  Runs the installer script (bash/PowerShell) to set up Python venv, exiftool, and ffmpeg.
                </p>
              </div>
              <button
                onClick={onRunInstaller}
                className="pro-shadow group flex items-center gap-2 px-8 py-4 bg-primary text-primary-foreground font-bold rounded-xl hover:scale-105 active:scale-95 transition-all duration-300 shadow-[0px_10px_30px_rgba(139,92,246,0.15)]"
              >
                <span>Configure Environment</span>
                <ArrowRight className="w-5 h-5 group-hover:translate-x-1 transition-transform" />
              </button>
            </div>
          )}
        </div>

        {/* Footer badges */}
        <div className="flex items-center justify-center gap-8 py-4 opacity-50 text-xs font-medium">
          <div className="flex items-center gap-2">
            <Shield className="w-3.5 h-3.5" />
            <span>Local-Only Processing</span>
          </div>
          <div className="flex items-center gap-2">
            <Zap className="w-3.5 h-3.5" />
            <span>Direct Binary Execution</span>
          </div>
          <div className="flex items-center gap-2">
            <Database className="w-3.5 h-3.5" />
            <span>No Cloud Dependencies</span>
          </div>
        </div>
      </div>
    </div>
  );
}

function DependencyCard({ dep }: { dep: Dependency }) {
  const statusColors: Record<DepStatus, { dot: string; badge: string; badgeText: string; icon: string }> = {
    ready: { dot: 'bg-tertiary', badge: 'bg-surface-container-high', badgeText: 'text-tertiary', icon: 'text-primary' },
    missing: { dot: 'bg-red-400', badge: 'bg-red-500/20', badgeText: 'text-red-400', icon: 'text-red-400' },
    pending: { dot: 'bg-on-surface-variant/40', badge: 'bg-surface-container-high', badgeText: 'text-on-surface-variant', icon: 'text-tertiary' },
    checking: { dot: 'bg-amber-400', badge: 'bg-amber-500/20', badgeText: 'text-amber-400', icon: 'text-amber-400' },
  };

  const colors = statusColors[dep.status];

  return (
    <div className="glass-panel p-4 rounded-xl flex flex-col gap-2 group hover:border-primary/20 transition-all">
      <div className="flex justify-between items-start">
        <div className={`w-10 h-10 rounded-lg ${dep.status === 'missing' ? 'bg-red-500/10' : 'bg-primary/10'} flex items-center justify-center ${colors.icon}`}>
          {dep.icon}
        </div>
        <span className={`px-2 py-1 rounded text-[10px] font-bold ${colors.badge} ${colors.badgeText}`}>
          {dep.statusMessage}
        </span>
      </div>
      <h3 className="text-lg font-semibold text-on-surface mt-1">{dep.name}</h3>
      <p className="text-xs text-on-surface-variant">{dep.description}</p>
      <div className="mt-auto pt-2 flex items-center gap-2">
        <span className={`w-1.5 h-1.5 rounded-full ${colors.dot}`} />
        <span className="font-mono text-xs text-on-surface-variant">
          {dep.version || (dep.status === 'missing' ? 'Binary missing from path' : 'Click to verify binary')}
        </span>
      </div>
    </div>
  );
}
