import React from 'react';

interface AndroidPhoneFrameProps {
  children: React.ReactNode;
  isFramed?: boolean;
}

export const AndroidPhoneFrame: React.FC<AndroidPhoneFrameProps> = ({
  children,
  isFramed = true,
}) => {
  if (!isFramed) {
    return <div className="w-full min-h-screen bg-[#f9f9fc]">{children}</div>;
  }

  return (
    <div className="relative mx-auto w-full sm:my-3 md:my-6 sm:max-w-[420px] sm:rounded-[48px] sm:p-3.5 sm:bg-[#1a1c1e] sm:shadow-[0_25px_60px_-15px_rgba(0,0,0,0.35)] sm:ring-1 sm:ring-white/10">
      {/* Outer Phone Shell */}
      <div className="relative w-full overflow-hidden sm:rounded-[38px] bg-[#f9f9fc] min-h-screen sm:min-h-[850px] sm:max-h-[890px] flex flex-col shadow-inner">
        {/* Android Status Bar (desktop simulator frame only) */}
        <div className="hidden sm:flex h-9 px-6 bg-[#f9f9fc] text-[#1a1c1e] items-center justify-between text-[13px] font-semibold select-none z-50 shrink-0">
          <span>12:51</span>
          {/* Camera Punch Hole */}
          <div className="w-4 h-4 rounded-full bg-[#1a1c1e] shadow-sm -ml-4" />
          <div className="flex items-center gap-1.5 text-[14px]">
            <span className="material-symbols-outlined text-[16px]">wifi</span>
            <span className="material-symbols-outlined text-[16px]">signal_cellular_alt</span>
            <span className="text-[12px] font-bold">95%</span>
          </div>
        </div>

        {/* Scrollable Screen Content */}
        <div className="flex-1 overflow-y-auto no-scrollbar relative flex flex-col">
          {children}
        </div>

        {/* Android Gesture Bar (desktop simulator frame only) */}
        <div className="hidden sm:flex h-5 bg-[#f9f9fc] items-center justify-center shrink-0 z-50 pb-1">
          <div className="w-32 h-1 bg-[#1a1c1e]/40 rounded-full" />
        </div>
      </div>
    </div>
  );
};
