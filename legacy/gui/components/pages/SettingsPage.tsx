'use client';

import {
  FolderOpen,
  Cpu,
  Palette,
  Bell,
  Info,
  ExternalLink,
  Github,
} from 'lucide-react';

// TODO: Wire settings to persistent storage (electron-store or similar)
// For now this is a visual stub showing the settings categories

export function SettingsPage() {
  return (
    <div className="flex-1 overflow-y-auto p-8">
      <div className="max-w-3xl mx-auto space-y-8">

        {/* Header */}
        <section className="space-y-2">
          <h2 className="text-3xl font-bold tracking-tight text-on-surface">Settings</h2>
          <p className="text-on-surface-variant max-w-2xl text-base">
            Configure default behaviors, paths, and preferences for SnapVault.
          </p>
        </section>

        {/* Default Paths */}
        <SettingsSection title="Default Paths" icon={<FolderOpen className="w-4 h-4" />}>
          <SettingsRow
            label="Default Output Folder"
            description="Where downloaded memories are saved by default"
            action={
              <button className="text-xs text-primary hover:underline">
                ~/Downloads/snapchat_memories
              </button>
            }
          />
          <SettingsRow
            label="History File Location"
            description="Last used memories_history.html path"
            action={
              <span className="text-xs text-on-surface-variant/60 italic">Not set</span>
            }
          />
        </SettingsSection>

        {/* Performance */}
        <SettingsSection title="Performance" icon={<Cpu className="w-4 h-4" />}>
          <SettingsRow
            label="Default Worker Threads"
            description="Number of concurrent download threads"
            action={
              <span className="text-sm text-primary font-bold">Auto</span>
            }
          />
          <SettingsRow
            label="Retry Failed Downloads"
            description="Automatically retry failed media downloads"
            action={
              <div className="w-10 h-5 bg-primary rounded-full relative flex items-center px-[2px] cursor-not-allowed opacity-60">
                <div className="w-4 h-4 bg-white rounded-full translate-x-5" />
              </div>
            }
          />
        </SettingsSection>

        {/* Appearance */}
        <SettingsSection title="Appearance" icon={<Palette className="w-4 h-4" />}>
          <SettingsRow
            label="Theme"
            description="Application color scheme"
            action={
              <span className="text-xs text-on-surface-variant bg-surface-container-highest px-3 py-1 rounded-lg">
                Dark (System)
              </span>
            }
          />
          <SettingsRow
            label="Terminal Font Size"
            description="Log output font size in the dashboard terminal"
            action={
              <span className="text-sm text-on-surface font-mono">13px</span>
            }
          />
        </SettingsSection>

        {/* Notifications */}
        <SettingsSection title="Notifications" icon={<Bell className="w-4 h-4" />}>
          <SettingsRow
            label="Desktop Notifications"
            description="Show system notifications when sync completes"
            action={
              <div className="w-10 h-5 bg-surface-container-highest rounded-full relative flex items-center px-[2px] cursor-not-allowed opacity-60">
                <div className="w-4 h-4 bg-white/40 rounded-full" />
              </div>
            }
          />
        </SettingsSection>

        {/* About */}
        <SettingsSection title="About" icon={<Info className="w-4 h-4" />}>
          <SettingsRow
            label="Version"
            description="SnapVault Downloader"
            action={
              <span className="text-xs text-on-surface-variant font-mono">v0.2.0</span>
            }
          />
          <SettingsRow
            label="Source Code"
            description="View the project on GitHub"
            action={
              <button className="flex items-center gap-1.5 text-xs text-primary hover:underline">
                <Github className="w-3.5 h-3.5" />
                <span>Repository</span>
                <ExternalLink className="w-3 h-3" />
              </button>
            }
          />
        </SettingsSection>

        {/* Spacer */}
        <div className="pb-8" />
      </div>
    </div>
  );
}

function SettingsSection({
  title,
  icon,
  children,
}: {
  title: string;
  icon: React.ReactNode;
  children: React.ReactNode;
}) {
  return (
    <div className="glass-panel rounded-xl pro-shadow overflow-hidden">
      <div className="flex items-center gap-2 px-5 py-3 border-b border-white/5">
        <span className="text-primary">{icon}</span>
        <h3 className="text-xs text-primary uppercase tracking-widest font-bold">{title}</h3>
      </div>
      <div className="divide-y divide-white/5">
        {children}
      </div>
    </div>
  );
}

function SettingsRow({
  label,
  description,
  action,
}: {
  label: string;
  description: string;
  action: React.ReactNode;
}) {
  return (
    <div className="flex items-center justify-between px-5 py-4 hover:bg-white/[0.02] transition-colors">
      <div className="flex flex-col gap-0.5">
        <span className="text-sm text-on-surface font-medium">{label}</span>
        <span className="text-xs text-on-surface-variant/60">{description}</span>
      </div>
      <div className="shrink-0 ml-4">{action}</div>
    </div>
  );
}
