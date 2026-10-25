import React from 'react';
import { Language } from '../types';

export type ViewMode = 'phone' | 'split' | 'code' | 'fullscreen';

interface TopStudioBarProps {
  viewMode: ViewMode;
  onViewModeChange: (mode: ViewMode) => void;
  currentLanguage: Language;
  onLanguageChange: (lang: Language) => void;
  onSimulateHop: () => void;
}

export const TopStudioBar: React.FC<TopStudioBarProps> = ({
  viewMode,
  onViewModeChange,
  currentLanguage,
  onLanguageChange,
  onSimulateHop,
}) => {
  return (
    <div className="w-full bg-[#141c1c] text-[#f0f0f3] border-b border-[#243535] px-3 py-2 z-50 flex items-center justify-between flex-wrap gap-2 text-xs">
      {/* Brand & Project Info */}
      <div className="flex items-center gap-2">
        <div className="flex items-center gap-1.5 px-2 py-1 rounded bg-[#004546] text-[#a6eff0] font-bold">
          <span className="material-symbols-outlined text-[16px]">link</span>
          <span className="tracking-tight">The Chain · Jetpack Compose Android</span>
        </div>
        <span className="hidden md:inline text-[#8ad3d3] font-mono text-[11px]">
          Target: Kotlin 2.0 / Material3 / P2P Mesh
        </span>
      </div>

      {/* Mode Switcher */}
      <div className="flex items-center bg-[#0d1414] p-0.5 rounded-lg border border-[#243535]">
        <button
          type="button"
          onClick={() => onViewModeChange('phone')}
          className={`flex items-center gap-1.5 px-3 py-1.5 rounded-md font-semibold transition-colors ${
            viewMode === 'phone'
              ? 'bg-[#004546] text-white shadow-sm'
              : 'text-[#8ad3d3] hover:text-white'
          }`}
        >
          <span className="material-symbols-outlined text-[15px]">smartphone</span>
          <span>Pixel Frame</span>
        </button>

        <button
          type="button"
          onClick={() => onViewModeChange('split')}
          className={`flex items-center gap-1.5 px-3 py-1.5 rounded-md font-semibold transition-colors ${
            viewMode === 'split'
              ? 'bg-[#004546] text-white shadow-sm'
              : 'text-[#8ad3d3] hover:text-white'
          }`}
        >
          <span className="material-symbols-outlined text-[15px]">view_column</span>
          <span className="hidden sm:inline">Split (App + Code)</span>
          <span className="sm:hidden">Split</span>
        </button>

        <button
          type="button"
          onClick={() => onViewModeChange('code')}
          className={`flex items-center gap-1.5 px-3 py-1.5 rounded-md font-semibold transition-colors ${
            viewMode === 'code'
              ? 'bg-[#004546] text-white shadow-sm'
              : 'text-[#8ad3d3] hover:text-white'
          }`}
        >
          <span className="material-symbols-outlined text-[15px]">code</span>
          <span>Kotlin Code</span>
        </button>

        <button
          type="button"
          onClick={() => onViewModeChange('fullscreen')}
          className={`flex items-center gap-1.5 px-3 py-1.5 rounded-md font-semibold transition-colors ${
            viewMode === 'fullscreen'
              ? 'bg-[#004546] text-white shadow-sm'
              : 'text-[#8ad3d3] hover:text-white'
          }`}
        >
          <span className="material-symbols-outlined text-[15px]">fullscreen</span>
          <span className="hidden sm:inline">Mobile Web</span>
        </button>
      </div>

      {/* Quick Test Actions */}
      <div className="flex items-center gap-2">
        <button
          type="button"
          onClick={onSimulateHop}
          className="flex items-center gap-1 px-2.5 py-1 rounded bg-[#243535] hover:bg-[#344b4b] text-[#a6eff0] font-mono text-[11px] transition-colors"
          title="Simulate peer heartbeat ping over Bluetooth"
        >
          <span className="material-symbols-outlined text-[14px]">sensors</span>
          <span>Mesh Ping</span>
        </button>
      </div>
    </div>
  );
};
