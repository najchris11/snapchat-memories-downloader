'use client';

import React from 'react';
import {
  LayoutDashboard,
  ImageIcon,
  MonitorCog,
  Settings,
  Play,
  Square,
} from 'lucide-react';

export type PageId = 'dashboard' | 'library' | 'environment' | 'settings';

interface AppShellProps {
  activePage: PageId;
  onNavigate: (page: PageId) => void;
  isRunning?: boolean;
  canStart?: boolean;
  onStartSync?: () => void;
  onStopSync?: () => void;
  children: React.ReactNode;
}

const NAV_ITEMS: { id: PageId; label: string; icon: React.ReactNode }[] = [
  { id: 'dashboard', label: 'Dashboard', icon: <LayoutDashboard className="w-4 h-4" /> },
  { id: 'library', label: 'Library', icon: <ImageIcon className="w-4 h-4" /> },
  { id: 'environment', label: 'Environment', icon: <MonitorCog className="w-4 h-4" /> },
  { id: 'settings', label: 'Settings', icon: <Settings className="w-4 h-4" /> },
];

export function AppShell({
  activePage,
  onNavigate,
  isRunning = false,
  canStart = false,
  onStartSync,
  onStopSync,
  children,
}: AppShellProps) {
  return (
    <div className="h-screen flex flex-col overflow-hidden bg-background text-foreground selection:bg-primary/30">
      {/* ── Top Header Bar ─────────────────────────────── */}
      <header className="drag-region flex justify-between items-center px-8 w-full z-10 bg-surface-dim/30 backdrop-blur-md h-14 border-b border-white/10 shrink-0">
        <div className="flex items-center gap-4 pl-16">
          <span className="font-semibold text-xl tracking-tight text-on-surface">SnapVault</span>
          <div className="h-4 w-px bg-white/10" />
          <span className="text-xs text-on-surface-variant/70 uppercase tracking-widest font-medium">Downloader</span>
        </div>
        <div className="no-drag flex items-center gap-3">
          {/* Page badge */}
          {activePage !== 'dashboard' && (
            <span className="px-2.5 py-0.5 rounded-full bg-primary/10 text-primary text-[10px] font-bold tracking-widest uppercase">
              {activePage}
            </span>
          )}
        </div>
      </header>

      {/* ── Main Layout: Sidebar + Content ─────────────── */}
      <div className="flex-1 flex overflow-hidden">
        {/* ── Left Sidebar ───────────────────────────────── */}
        <nav className="hidden md:flex flex-col h-full p-4 overflow-y-auto w-56 border-r border-white/10 bg-surface/40 backdrop-blur-xl shrink-0">
          {/* Logo */}
          <div className="flex items-center gap-3 mb-6 px-1">
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src="/icon.png" alt="SnapVault" className="w-8 h-8 rounded-lg" />
            <div className="flex flex-col">
              <span className="text-sm font-bold text-on-surface tracking-tight leading-none">SnapVault</span>
              <span className="text-[10px] text-primary/80 font-medium">Pro Utility</span>
            </div>
          </div>
          <div className="space-y-1">
            {NAV_ITEMS.map((item) => (
              <button
                key={item.id}
                onClick={() => onNavigate(item.id)}
                className={`w-full flex items-center gap-3 px-3 py-2 rounded-lg text-sm font-medium transition-all duration-200 cursor-pointer ${
                  activePage === item.id
                    ? 'bg-white/10 text-primary font-bold'
                    : 'text-on-surface-variant/70 hover:text-on-surface hover:bg-white/5'
                }`}
              >
                {item.icon}
                {item.label}
              </button>
            ))}
          </div>

          <div className="mt-auto pt-8">
            <button
              onClick={isRunning ? onStopSync : onStartSync}
              disabled={!isRunning && !canStart}
              className={`w-full py-2.5 rounded-lg font-bold flex items-center justify-center gap-2 text-sm transition-all active:scale-95 disabled:opacity-40 disabled:cursor-not-allowed ${
                isRunning
                  ? 'bg-red-500/20 border border-red-500/30 text-red-400 hover:bg-red-500/30'
                  : 'bg-primary text-primary-foreground shadow-[0px_0px_20px_rgba(208,188,255,0.2)] hover:brightness-110'
              }`}
            >
              {isRunning ? (
                <>
                  <Square className="w-4 h-4" />
                  Stop
                </>
              ) : (
                <>
                  <Play className="w-4 h-4" />
                  Start Sync
                </>
              )}
            </button>
          </div>
        </nav>

        {/* ── Main Content ───────────────────────────────── */}
        <main className="flex-1 flex flex-col overflow-hidden">
          {children}
        </main>
      </div>

      {/* Decorative bottom gradient line */}
      <div className="fixed bottom-0 left-0 w-full h-px bg-gradient-to-r from-transparent via-primary/50 to-transparent pointer-events-none" />
    </div>
  );
}
