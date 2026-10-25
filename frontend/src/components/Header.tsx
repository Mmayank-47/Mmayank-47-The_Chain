import React from 'react';
import { Language } from '../types';

interface HeaderProps {
  batteryPercent: number;
  peerCount: number;
  currentLanguage: Language;
  onLanguageChange: (lang: Language) => void;
  onOpenProfile: () => void;
  sectionTitle?: string;
}

export const Header: React.FC<HeaderProps> = ({
  batteryPercent,
  peerCount,
  currentLanguage,
  onLanguageChange,
  onOpenProfile,
  sectionTitle = 'Help',
}) => {
  return (
    <header className="sticky top-0 w-full z-40 bg-[#f9f9fc]/95 backdrop-blur-md pt-safe shadow-[0_1px_8px_rgba(0,0,0,0.04)] border-b border-[#eeeef0]">
      <div className="h-28 px-3 flex flex-col justify-between py-1">
        {/* Top row: Telemetry pills & controls */}
        <div className="flex items-center justify-between gap-1">
          {/* Left metrics */}
          <div className="flex items-center gap-1.5 overflow-x-auto no-scrollbar">
            {/* Battery pill */}
            <div className="flex items-center gap-1 bg-[#e8e8ea] px-2.5 py-0.5 rounded-full min-h-[32px]">
              <span className="material-symbols-outlined text-[#004546] text-[18px]">
                battery_5_bar
              </span>
              <span className="text-[14px] font-semibold text-[#1a1c1e] whitespace-nowrap">
                {batteryPercent}% battery
              </span>
            </div>

            {/* Mesh phones pill */}
            <div className="flex items-center gap-1.5 bg-[#e8e8ea] px-2.5 py-0.5 rounded-full min-h-[32px]">
              <span className="w-2 h-2 rounded-full bg-[#004546] animate-pulse" />
              <span className="material-symbols-outlined text-[#004546] text-[18px]">
                hub
              </span>
              <span className="text-[14px] font-semibold text-[#1a1c1e] whitespace-nowrap">
                {peerCount} phones
              </span>
            </div>
          </div>

          {/* Right controls */}
          <div className="flex items-center gap-2 shrink-0">
            {/* Language switcher */}
            <div className="flex items-center bg-[#e8e8ea] px-2.5 py-1 rounded-full text-[#3f4948] text-[14px] min-h-[32px]">
              <button
                type="button"
                onClick={() => onLanguageChange('EN')}
                className={`transition-colors font-semibold ${
                  currentLanguage === 'EN' ? 'font-bold text-[#004546]' : 'hover:text-[#1a1c1e]'
                }`}
              >
                EN
              </button>
              <span className="mx-1 text-[#bec9c8]">|</span>
              <button
                type="button"
                onClick={() => onLanguageChange('HI')}
                className={`transition-colors font-semibold ${
                  currentLanguage === 'HI' ? 'font-bold text-[#004546]' : 'hover:text-[#1a1c1e]'
                }`}
              >
                हिं
              </button>
              <span className="mx-1 text-[#bec9c8]">|</span>
              <button
                type="button"
                onClick={() => onLanguageChange('MR')}
                className={`transition-colors font-semibold ${
                  currentLanguage === 'MR' ? 'font-bold text-[#004546]' : 'hover:text-[#1a1c1e]'
                }`}
              >
                मर
              </button>
            </div>

            {/* Profile Avatar */}
            <button
              type="button"
              onClick={onOpenProfile}
              aria-label="User Profile and Emergency Card"
              className="w-8 h-8 rounded-full bg-[#004546] flex items-center justify-center shrink-0 active:scale-95 transition-transform"
            >
              <span className="material-symbols-outlined text-white text-[18px]">
                person
              </span>
            </button>
          </div>
        </div>

        {/* Bottom row: Brand & Section */}
        <div className="flex items-center justify-between pb-1">
          <div className="flex items-center gap-1.5">
            <span className="material-symbols-outlined text-[#004546] text-[26px]">
              link
            </span>
            <h1 className="text-[20px] font-bold text-[#1a1c1e] tracking-tight">
              The Chain
            </h1>
          </div>
          <span className="text-[14px] font-bold text-[#3f4948] uppercase tracking-wider">
            {sectionTitle}
          </span>
        </div>
      </div>
    </header>
  );
};
