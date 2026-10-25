import React, { useState } from 'react';

export type TabKey = 'help' | 'respond' | 'map' | 'mesh' | 'me';

interface BottomNavProps {
  activeTab: TabKey;
  onTabChange: (tab: TabKey) => void;
  pendingResponseCount?: number;
  isBroadcasting?: boolean;
  peerCount?: number;
  batteryPercent?: number;
  onIslandClick?: () => void;
}

export const BottomNav: React.FC<BottomNavProps> = ({
  activeTab,
  onTabChange,
  pendingResponseCount = 3,
  isBroadcasting = false,
  peerCount = 4,
  batteryPercent = 62,
  onIslandClick,
}) => {
  const [isExpanded, setIsExpanded] = useState(false);

  const tabs = [
    { key: 'help' as TabKey, label: 'Help', icon: 'emergency' },
    { key: 'respond' as TabKey, label: 'Respond', icon: 'volunteer_activism', badge: pendingResponseCount },
    { key: 'map' as TabKey, label: 'Map', icon: 'map' },
    { key: 'mesh' as TabKey, label: 'Mesh', icon: 'sensors' },
    { key: 'me' as TabKey, label: 'Me', icon: 'badge' },
  ];

  return (
    <div className="fixed bottom-3 left-0 right-0 z-50 pointer-events-none flex flex-col items-center px-3 max-w-screen-sm mx-auto">
      {/* Dynamic Island Container */}
      <div
        className={`pointer-events-auto transition-all duration-300 ease-[cubic-bezier(0.16,1,0.3,1)] flex flex-col items-center bg-[#141a1a]/95 text-white backdrop-blur-2xl border border-white/15 shadow-[0_16px_40px_rgba(0,0,0,0.4)] ${
          isExpanded
            ? 'w-[96%] max-w-[420px] rounded-[32px] p-2'
            : isBroadcasting
            ? 'w-[94%] max-w-[390px] rounded-[30px] p-1.5'
            : 'w-auto rounded-full p-1.5'
        }`}
      >
        {/* Dynamic Island Live Activity Pill (When broadcasting or expanded) */}
        {(isBroadcasting || isExpanded) && (
          <div
            onClick={onIslandClick}
            className="w-full flex items-center justify-between px-3.5 py-1.5 mb-1 bg-white/10 rounded-full cursor-pointer hover:bg-white/15 transition-all text-xs"
          >
            <div className="flex items-center gap-2">
              <span className="relative flex h-2.5 w-2.5">
                <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-[#8ad3d3] opacity-75" />
                <span className="relative inline-flex rounded-full h-2.5 w-2.5 bg-[#a6eff0]" />
              </span>
              <span className="font-bold text-[#a6eff0] tracking-tight">
                {isBroadcasting ? 'SOS Beacon Active' : 'Cluster Mesh Relay'}
              </span>
              <span className="text-white/60">·</span>
              <span className="text-white/80 font-mono text-[11px]">
                {peerCount} phones nearby
              </span>
            </div>

            <div className="flex items-center gap-2">
              <span className="text-[11px] font-mono text-white/70">
                {batteryPercent}% bat
              </span>
              <button
                type="button"
                onClick={(e) => {
                  e.stopPropagation();
                  setIsExpanded(!isExpanded);
                }}
                className="w-5 h-5 rounded-full bg-white/20 flex items-center justify-center text-white/90 hover:bg-white/30"
              >
                <span className="material-symbols-outlined text-[14px]">
                  {isExpanded ? 'expand_more' : 'expand_less'}
                </span>
              </button>
            </div>
          </div>
        )}

        {/* Expanded Telemetry Drawer inside Island */}
        {isExpanded && (
          <div className="w-full px-3 py-2 bg-black/30 rounded-2xl mb-1.5 border border-white/5 text-[12px] flex items-center justify-between gap-3 animate-fadeIn">
            <div className="flex items-center gap-2">
              <div className="w-7 h-7 rounded-lg bg-[#005f60] flex items-center justify-center text-white">
                <span className="material-symbols-outlined text-[16px]">wifi_tethering</span>
              </div>
              <div>
                <div className="font-bold text-white text-[11px]">Direct Wi-Fi + BLE</div>
                <div className="text-white/60 text-[10px]">Zero cell data / 0 byte cloud</div>
              </div>
            </div>

            <div className="flex items-center gap-2">
              <span className="px-2 py-0.5 rounded-full bg-[#1e6b47] text-white font-mono text-[10px] font-bold">
                ENCRYPTED
              </span>
              <span className="px-2 py-0.5 rounded-full bg-[#895100] text-white font-mono text-[10px] font-bold">
                HOP 1
              </span>
            </div>
          </div>
        )}

        {/* Primary Island Navigation Pills */}
        <div className="flex items-center justify-center gap-1 sm:gap-1.5 px-1">
          {tabs.map((tab) => {
            const isActive = activeTab === tab.key;
            return (
              <button
                key={tab.key}
                type="button"
                onClick={() => onTabChange(tab.key)}
                className={`relative flex items-center gap-1.5 py-2 px-3 sm:px-3.5 rounded-full transition-all duration-200 select-none ${
                  isActive
                    ? 'bg-[#005f60] text-[#a6eff0] shadow-md shadow-black/30 font-bold scale-[1.03]'
                    : 'text-white/70 hover:text-white hover:bg-white/10 active:scale-95'
                }`}
              >
                <div className="relative flex items-center justify-center">
                  <span
                    className="material-symbols-outlined text-[22px]"
                    style={{
                      fontVariationSettings: isActive
                        ? "'FILL' 1, 'wght' 600"
                        : "'FILL' 0, 'wght' 400",
                    }}
                  >
                    {tab.icon}
                  </span>

                  {tab.badge && tab.badge > 0 && !isActive && (
                    <span className="absolute -top-1.5 -right-2 bg-[#fea946] text-[#2c1600] text-[10px] font-bold w-4 h-4 rounded-full flex items-center justify-center ring-2 ring-[#141a1a]">
                      {tab.badge}
                    </span>
                  )}
                </div>

                {/* Dynamic Island label expansion: always show active tab label, or compact on non-active */}
                <span
                  className={`text-[13px] tracking-tight transition-all duration-200 ${
                    isActive
                      ? 'inline-block font-bold text-white'
                      : 'hidden sm:inline-block font-medium text-white/70'
                  }`}
                >
                  {tab.label}
                </span>

                {/* Subtle active glow pill dot */}
                {isActive && (
                  <span className="w-1.5 h-1.5 rounded-full bg-[#a6eff0] shrink-0" />
                )}
              </button>
            );
          })}

          {/* Quick Island Morph Toggle (Dot button) */}
          <button
            type="button"
            onClick={() => setIsExpanded(!isExpanded)}
            title="Toggle Dynamic Island telemetry status"
            className="w-8 h-8 rounded-full bg-white/10 hover:bg-white/20 active:scale-95 flex items-center justify-center text-white/80 transition-colors ml-0.5"
          >
            <span
              className={`material-symbols-outlined text-[16px] transition-transform duration-300 ${
                isExpanded ? 'rotate-180 text-[#a6eff0]' : ''
              }`}
            >
              {isExpanded ? 'close' : 'tune'}
            </span>
          </button>
        </div>
      </div>
    </div>
  );
};
