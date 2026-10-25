import React, { useState, useEffect } from 'react';
import { ParsedSituation } from '../../types';

interface HelpScreenStep3Props {
  rawText: string;
  parsed: ParsedSituation;
  onCancelBroadcast: () => void;
  onNewRequest: () => void;
}

export const HelpScreenStep3: React.FC<HelpScreenStep3Props> = ({
  rawText,
  parsed,
  onCancelBroadcast,
  onNewRequest,
}) => {
  const [hops, setHops] = useState(1);
  const [nodesRelayed, setNodesRelayed] = useState(4);
  const [acknowledged, setAcknowledged] = useState(false);
  const [elapsedSeconds, setElapsedSeconds] = useState(0);

  useEffect(() => {
    const timer = setInterval(() => {
      setElapsedSeconds((s) => s + 1);
    }, 1000);

    const hopTimer = setTimeout(() => {
      setHops(2);
      setNodesRelayed(7);
    }, 3500);

    const ackTimer = setTimeout(() => {
      setHops(3);
      setNodesRelayed(11);
      setAcknowledged(true);
    }, 7000);

    return () => {
      clearInterval(timer);
      clearTimeout(hopTimer);
      clearTimeout(ackTimer);
    };
  }, []);

  const formatTime = (secs: number) => {
    const m = Math.floor(secs / 60);
    const s = secs % 60;
    return `${m}:${s < 10 ? '0' : ''}${s}`;
  };

  return (
    <div className="flex flex-col w-full pb-8 space-y-4 max-w-screen-sm mx-auto">
      {/* Progress & Context Header */}
      <div className="flex items-center justify-between pt-1">
        <div className="flex items-center gap-2">
          <span className="w-3 h-3 rounded-full bg-[#1e6b47] animate-ping" />
          <span className="text-[14px] font-bold text-[#1e6b47] uppercase tracking-wider">
            Beacon Transmitting
          </span>
        </div>
        <div className="flex items-center gap-1.5 bg-[#eeeef0] px-3 py-1 rounded-full">
          <span className="w-2 h-2 rounded-full bg-[#004546]" />
          <span className="text-[14px] font-semibold text-[#3f4948]">Step 3 of 3</span>
        </div>
      </div>

      {/* Screen Heading */}
      <div className="space-y-1">
        <h2 className="text-[28px] leading-[36px] font-bold text-[#1a1c1e] tracking-tight">
          Help request is broadcasting
        </h2>
        <p className="text-[16px] text-[#3f4948]">
          Hopping phone-to-phone via Bluetooth & Wi-Fi Direct. No cell service needed.
        </p>
      </div>

      {/* Active Relay Radar / Hop Visualizer */}
      <div className="bg-white rounded-2xl p-5 border border-[#e8e8ea] shadow-sm space-y-4">
        <div className="flex items-center justify-between border-b border-[#eeeef0] pb-3">
          <div>
            <span className="text-[13px] font-semibold text-[#3f4948] uppercase">
              Relay Telemetry
            </span>
            <div className="text-[20px] font-bold text-[#1a1c1e] flex items-center gap-2">
              <span>{nodesRelayed} nearby nodes engaged</span>
              <span className="text-xs bg-[#a6eff0] text-[#002020] px-2 py-0.5 rounded-full font-mono">
                {hops} hops
              </span>
            </div>
          </div>
          <div className="text-right">
            <span className="text-[12px] font-semibold text-[#3f4948]">ACTIVE FOR</span>
            <div className="text-[18px] font-mono font-bold text-[#004546]">
              {formatTime(elapsedSeconds)}
            </div>
          </div>
        </div>

        {/* Multi-Hop Network Nodes Illustration */}
        <div className="space-y-3 py-1">
          <div className="flex items-center justify-between">
            {/* Node 1: You */}
            <div className="flex flex-col items-center text-center">
              <div className="w-12 h-12 rounded-full bg-[#004546] text-white flex items-center justify-center shadow-md relative">
                <span className="material-symbols-outlined text-[24px]">person</span>
                <span className="absolute -bottom-1 bg-[#1e6b47] text-[10px] text-white font-bold px-1.5 rounded-full">
                  You
                </span>
              </div>
              <span className="text-[12px] font-bold text-[#1a1c1e] mt-1.5">Origin</span>
            </div>

            {/* Hop link 1 */}
            <div className="flex-1 flex flex-col items-center px-1">
              <div className="w-full h-1 bg-[#004546] rounded-full relative overflow-hidden">
                <div className="absolute inset-0 bg-[#a6eff0] animate-pulse" />
              </div>
              <span className="text-[11px] font-mono text-[#3f4948] mt-0.5">14m · BLE</span>
            </div>

            {/* Node 2: Neighbor 1 */}
            <div className="flex flex-col items-center text-center">
              <div className="w-12 h-12 rounded-full bg-[#e8e8ea] text-[#004546] border-2 border-[#004546] flex items-center justify-center relative">
                <span className="material-symbols-outlined text-[24px]">smartphone</span>
                <span className="absolute -top-1 -right-1 w-3.5 h-3.5 rounded-full bg-[#1e6b47] border-2 border-white" />
              </div>
              <span className="text-[12px] font-semibold text-[#1a1c1e] mt-1.5">
                Pixel 8
              </span>
            </div>

            {/* Hop link 2 */}
            <div className="flex-1 flex flex-col items-center px-1">
              <div
                className={`w-full h-1 rounded-full transition-all ${
                  hops >= 2 ? 'bg-[#004546]' : 'bg-[#e8e8ea]'
                }`}
              />
              <span className="text-[11px] font-mono text-[#3f4948] mt-0.5">38m · P2P</span>
            </div>

            {/* Node 3: Relief Post / Helper */}
            <div className="flex flex-col items-center text-center">
              <div
                className={`w-12 h-12 rounded-full flex items-center justify-center transition-all ${
                  acknowledged
                    ? 'bg-[#1e6b47] text-white shadow-lg'
                    : 'bg-[#e8e8ea] text-[#3f4948]'
                }`}
              >
                <span className="material-symbols-outlined text-[24px]">
                  {acknowledged ? 'medical_services' : 'share_location'}
                </span>
              </div>
              <span className="text-[12px] font-semibold text-[#1a1c1e] mt-1.5">
                {acknowledged ? 'Aid Team' : 'Relay Hub'}
              </span>
            </div>
          </div>
        </div>

        {/* Live Status Message Card */}
        {acknowledged ? (
          <div className="bg-[#f0fdf4] border border-[#bbf7d0] rounded-xl p-3 flex items-start gap-3">
            <span className="material-symbols-outlined text-[#1e6b47] text-[24px] mt-0.5 shrink-0">
              check_circle
            </span>
            <div>
              <h4 className="text-[16px] font-bold text-[#14532d]">
                Community Responder Acknowledged
              </h4>
              <p className="text-[14px] text-[#166534]">
                Dr. Sanjay Sharma & Shivaji Volunteer Group received your request for{' '}
                <strong>{parsed.need} ({parsed.quantity})</strong>. They are approximately
                320m away and preparing dispatch.
              </p>
            </div>
          </div>
        ) : (
          <div className="bg-[#fef3c7] border border-[#b36b00]/30 rounded-xl p-3 flex items-start gap-3">
            <span className="material-symbols-outlined text-[#b36b00] text-[22px] mt-0.5 shrink-0 animate-spin">
              sync
            </span>
            <div>
              <h4 className="text-[15px] font-bold text-[#b36b00]">
                Hopping to Community Network...
              </h4>
              <p className="text-[13px] text-[#78350f]">
                Relaying packet across Shivaji Nagar cluster. Keep device powered on.
              </p>
            </div>
          </div>
        )}
      </div>

      {/* Broadcast Summary Card */}
      <div className="bg-white rounded-2xl p-4 border border-[#e8e8ea] space-y-3">
        <h3 className="text-[16px] font-bold text-[#1a1c1e]">Your Broadcast Details</h3>
        <div className="bg-[#f3f3f6] p-3 rounded-xl text-[15px] text-[#1a1c1e] italic font-serif">
          "{rawText}"
        </div>

        <div className="grid grid-cols-2 gap-2 text-[14px]">
          <div className="bg-[#eeeef0] p-2.5 rounded-lg">
            <span className="text-[11px] font-bold text-[#3f4948] block">NEED</span>
            <span className="font-bold text-[#004546]">{parsed.need}</span>
          </div>
          <div className="bg-[#eeeef0] p-2.5 rounded-lg">
            <span className="text-[11px] font-bold text-[#3f4948] block">QUANTITY</span>
            <span className="font-bold text-[#004546]">{parsed.quantity}</span>
          </div>
          <div className="bg-[#eeeef0] p-2.5 rounded-lg">
            <span className="text-[11px] font-bold text-[#3f4948] block">FOR</span>
            <span className="font-bold text-[#004546]">{parsed.person}</span>
          </div>
          <div className="bg-[#eeeef0] p-2.5 rounded-lg">
            <span className="text-[11px] font-bold text-[#3f4948] block">LOCATION</span>
            <span className="font-bold text-[#004546]">{parsed.near}</span>
          </div>
        </div>
      </div>

      {/* Action Buttons: New or Abort Broadcast */}
      <div className="space-y-3 pt-2">
        <button
          type="button"
          onClick={onNewRequest}
          className="w-full min-h-[56px] bg-[#004546] text-white rounded-2xl flex items-center justify-center gap-2 text-[18px] font-bold hover:bg-[#005f60] transition-colors"
        >
          <span className="material-symbols-outlined text-[22px]">add</span>
          <span>Create another help request</span>
        </button>

        {/* Abort / Critical Action button as specified in Image_27_8d76987a.markdown */}
        <button
          type="button"
          onClick={onCancelBroadcast}
          className="w-full min-h-[64px] bg-[#9e2a2b] text-white rounded-2xl flex items-center justify-center gap-2 px-4 shadow-md hover:bg-[#7d1017] active:scale-[0.99] transition-all"
        >
          <span className="material-symbols-outlined text-[24px]">close</span>
          <span className="text-[18px] font-bold">Abort distress broadcast</span>
        </button>
      </div>
    </div>
  );
};
