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
    <div className="relative mx-auto my-3 md:my-6 w-full max-w-[420px] rounded-[48px] p-3.5 bg-[#1a1c1e] shadow-[0_25px_60px_-15px_rgba(0,0,0,0.35)] ring-1 ring-white/10">
      {/* Outer Phone Shell */}
      <div className="relative w-full overflow-hidden rounded-[38px] bg-[#f9f9fc] min-h-[850px] max-h-[890px] flex flex-col shadow-inner">
        {/* Android Status Bar */}
        <div className="h-9 px-6 bg-[#f9f9fc] text-[#1a1c1e] flex items-center justify-between text-[13px] font-semibold select-none z-50 shrink-0">
          <span>12:51</span>
          {/* Camera Punch Hole */}
          <div className="w-4 h-4 rounded-full bg-[#1a1c1e] shadow-sm -ml-4" />
          <div className="flex items-center gap-1.5 text-[14px]">
            <span className="material-symbols-outlined text-[16px]">wifi</span>
            <span className="material-symbols-outlined text-[16px]">signal_cellular_alt</span>
            <span className="text-[12px] font-bold">62%</span>
          </div>
        </div>

        {/* Scrollable Screen Content */}
        <div className="flex-1 overflow-y-auto no-scrollbar relative flex flex-col">
          {children}
        </div>

        {/* Android Gesture Bar */}
        <div className="h-5 bg-[#f9f9fc] flex items-center justify-center shrink-0 z-50 pb-1">
          <div className="w-32 h-1 bg-[#1a1c1e]/40 rounded-full" />
        </div>
      </div>
    </div>
  );
};
