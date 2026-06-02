'use client';

import {
  ImageIcon,
  Film,
  Calendar,
  ChevronDown,
  Grid3X3,
  List,
  ArrowUpDown,
  MapPin,
  Layers,
  Heart,
  Info,
  PlayCircle,
  Download,
  Sparkles,
  Shield,
  Share2,
} from 'lucide-react';

// TODO: Wire to actual downloaded files from the output folder
// This page will eventually scan the download directory and display real media

export function LibraryPage() {
  // Placeholder data — will be replaced with fs-based scan of download folder
  const placeholderItems = [
    { id: 1, type: 'photo', date: 'OCT 12, 2023', title: 'Memory #001', hasGps: true, hasOverlay: true },
    { id: 2, type: 'video', date: 'SEPT 28, 2023', title: 'Memory #002', duration: '00:15', hasGps: false, hasOverlay: false },
    { id: 3, type: 'photo', date: 'AUG 15, 2023', title: 'Memory #003', hasGps: false, hasOverlay: false, favorited: true },
    { id: 4, type: 'photo', date: 'JUL 22, 2023', title: 'Memory #004', hasGps: true, hasOverlay: false },
    { id: 5, type: 'video', date: 'JUN 08, 2023', title: 'Memory #005', duration: '00:42', hasGps: false, hasOverlay: true },
    { id: 6, type: 'photo', date: 'MAY 19, 2023', title: 'Memory #006', hasGps: true, hasOverlay: false },
    { id: 7, type: 'photo', date: 'APR 30, 2023', title: 'Memory #007', hasGps: false, hasOverlay: false },
    { id: 8, type: 'video', date: 'MAR 12, 2023', title: 'Memory #008', duration: '01:05', hasGps: true, hasOverlay: false },
  ];

  return (
    <div className="flex-1 flex min-w-0 overflow-hidden">
      {/* Main Library Content */}
      <div className="flex-1 flex flex-col p-6 gap-5 overflow-y-auto">

        {/* Filters Bar */}
        <section className="flex flex-wrap items-center justify-between gap-4">
          <div className="flex items-center gap-2">
            {/* Media Type Filter */}
            <div className="flex p-1 bg-surface-container-lowest rounded-lg border border-white/5">
              <button className="px-3 py-1.5 bg-white/10 text-primary font-bold rounded-md text-xs shadow-sm">All</button>
              <button className="px-3 py-1.5 text-on-surface-variant hover:text-on-surface transition-colors text-xs">Photos</button>
              <button className="px-3 py-1.5 text-on-surface-variant hover:text-on-surface transition-colors text-xs">Videos</button>
            </div>
            <div className="h-6 w-px bg-white/10 mx-1" />
            {/* Date Filter */}
            <button className="flex items-center gap-1.5 px-3 py-1.5 bg-surface-container-low border border-white/10 rounded-lg text-on-surface-variant hover:border-primary/50 transition-all text-xs">
              <Calendar className="w-3.5 h-3.5" />
              <span>Filter by Date</span>
              <ChevronDown className="w-3.5 h-3.5" />
            </button>
          </div>
          <div className="flex items-center gap-3">
            <span className="text-on-surface-variant text-xs">View:</span>
            <div className="flex items-center bg-surface-container-lowest rounded-md border border-white/5">
              <button className="p-1.5 text-primary"><Grid3X3 className="w-4 h-4" /></button>
              <button className="p-1.5 text-on-surface-variant/50"><List className="w-4 h-4" /></button>
            </div>
            <button className="text-on-surface-variant text-xs flex items-center gap-1 hover:text-primary transition-colors">
              <span>Sort: Newest First</span>
              <ArrowUpDown className="w-3.5 h-3.5" />
            </button>
          </div>
        </section>

        {/* Media Grid */}
        <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-4">
          {placeholderItems.map((item) => (
            <MediaCard key={item.id} item={item} />
          ))}
        </div>

        {/* Load More */}
        <div className="flex justify-center py-6">
          <button className="px-6 py-3 glass-panel rounded-xl text-on-surface-variant hover:text-primary hover:border-primary/50 transition-all text-xs flex items-center gap-3 active:scale-95">
            <ChevronDown className="w-4 h-4" />
            Load More Memories
          </button>
        </div>
      </div>

      {/* Right Inspector Panel */}
      <aside className="hidden lg:flex flex-col w-72 border-l border-white/10 bg-surface-container-low/50 backdrop-blur-xl p-4 overflow-y-auto shrink-0">
        <h3 className="text-lg font-semibold text-on-surface mb-4">Inspector</h3>
        <div className="space-y-5">
          {/* Storage Usage */}
          <div className="p-4 glass-panel rounded-xl">
            <div className="flex items-center justify-between mb-2">
              <span className="text-[11px] text-on-surface-variant font-bold uppercase tracking-wider">Storage Usage</span>
              <span className="text-[11px] text-primary font-bold">—</span>
            </div>
            <div className="h-1 w-full bg-white/5 rounded-full overflow-hidden mb-2">
              <div className="h-full bg-gradient-to-r from-primary to-tertiary w-0" />
            </div>
            <div className="flex justify-between text-[10px] text-on-surface-variant/60">
              <span>No data yet</span>
              <span>Run a sync first</span>
            </div>
          </div>

          {/* Metadata Status */}
          <div>
            <h4 className="text-xs text-on-surface-variant font-bold mb-3">Metadata Status</h4>
            <div className="space-y-2">
              <div className="flex items-start gap-2 p-2 hover:bg-white/5 rounded-lg transition-colors">
                <MapPin className="w-4 h-4 text-primary shrink-0 mt-0.5" />
                <div className="flex flex-col">
                  <span className="text-xs text-on-surface font-medium">GPS Data</span>
                  <span className="text-[10px] text-on-surface-variant/70">Not yet processed</span>
                </div>
              </div>
              <div className="flex items-start gap-2 p-2 hover:bg-white/5 rounded-lg transition-colors">
                <Layers className="w-4 h-4 text-tertiary shrink-0 mt-0.5" />
                <div className="flex flex-col">
                  <span className="text-xs text-on-surface font-medium">Overlay Detection</span>
                  <span className="text-[10px] text-on-surface-variant/70">Pending sync</span>
                </div>
              </div>
            </div>
          </div>

          {/* Vault Tools */}
          <div className="pt-3 border-t border-white/10">
            <h4 className="text-xs text-on-surface-variant font-bold mb-3">Vault Tools</h4>
            <div className="grid grid-cols-2 gap-2">
              {[
                { icon: <Download className="w-5 h-5" />, label: 'Export All' },
                { icon: <Sparkles className="w-5 h-5" />, label: 'Optimize' },
                { icon: <Shield className="w-5 h-5" />, label: 'Privacy' },
                { icon: <Share2 className="w-5 h-5" />, label: 'Share' },
              ].map((tool) => (
                <button
                  key={tool.label}
                  className="flex flex-col items-center gap-1.5 p-3 glass-panel rounded-xl hover:bg-white/5 transition-all text-on-surface-variant hover:text-primary"
                >
                  {tool.icon}
                  <span className="text-[10px] font-bold">{tool.label}</span>
                </button>
              ))}
            </div>
          </div>
        </div>
      </aside>
    </div>
  );
}

function MediaCard({ item }: { item: any }) {
  const isVideo = item.type === 'video';

  return (
    <div className="glass-panel rounded-xl overflow-hidden group hover:shadow-[0px_10px_30px_rgba(139,92,246,0.15)] transition-all duration-300 cursor-pointer">
      {/* Thumbnail placeholder */}
      <div className="relative aspect-[3/4] overflow-hidden bg-surface-container-high">
        <div className="w-full h-full flex items-center justify-center bg-gradient-to-br from-surface-container to-surface-container-highest">
          {isVideo ? (
            <Film className="w-8 h-8 text-on-surface-variant/20" />
          ) : (
            <ImageIcon className="w-8 h-8 text-on-surface-variant/20" />
          )}
        </div>
        {/* Hover overlay */}
        <div className="absolute inset-0 bg-gradient-to-t from-surface-container-highest/80 via-transparent to-transparent opacity-0 group-hover:opacity-100 transition-opacity" />
        {/* Type badge */}
        <div className="absolute top-2 right-2">
          <span className={`text-[10px] px-2 py-0.5 rounded-full border uppercase font-bold backdrop-blur-md ${
            isVideo
              ? 'bg-tertiary/20 text-tertiary border-tertiary/20'
              : 'bg-primary/20 text-primary border-primary/20'
          }`}>
            {item.type}
          </span>
        </div>
        {/* Video play icon */}
        {isVideo && (
          <div className="absolute top-2 left-2 bg-black/40 backdrop-blur-md p-1 rounded-lg border border-white/10">
            <PlayCircle className="w-4 h-4 text-white" />
          </div>
        )}
        {/* Duration badge */}
        {item.duration && (
          <div className="absolute bottom-2 left-2">
            <span className="text-[10px] bg-black/50 backdrop-blur-sm px-2 py-0.5 rounded-md font-mono text-white">{item.duration}</span>
          </div>
        )}
      </div>
      {/* Info */}
      <div className="p-3 flex flex-col gap-1">
        <div className="flex justify-between items-center">
          <span className="font-mono text-[11px] text-on-surface-variant">{item.date}</span>
          <div className="flex gap-1">
            {item.hasGps && <MapPin className="w-3.5 h-3.5 text-primary" />}
            {item.hasOverlay && <Layers className="w-3.5 h-3.5 text-tertiary" />}
            {item.favorited && <Heart className="w-3.5 h-3.5 text-primary fill-primary" />}
            {!item.hasGps && !item.hasOverlay && !item.favorited && <Info className="w-3.5 h-3.5 text-on-surface-variant/30" />}
          </div>
        </div>
        <p className="text-xs text-on-surface font-medium truncate">{item.title}</p>
      </div>
    </div>
  );
}
