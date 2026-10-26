import React, { useState, useEffect } from 'react';
import { api } from '../../api/client';
import { useMeshEvents } from '../../api/events';
import { ParsedSituation } from '../../types';

interface HelpScreenStep3Props {
  rawText: string;
  parsed: ParsedSituation;
  claimId?: string;
  onCancelBroadcast: () => void;
  onNewRequest: () => void;
}

export const HelpScreenStep3: React.FC<HelpScreenStep3Props> = ({
  rawText,
  parsed,
  claimId,
  onCancelBroadcast,
  onNewRequest,
}) => {
  const [hops, setHops] = useState(1);
  const [nodesRelayed, setNodesRelayed] = useState(4);
  const [dispatchStatus, setDispatchStatus] = useState<string>('PROPOSED');
  const [elapsedSeconds, setElapsedSeconds] = useState(0);
  const [isAborting, setIsAborting] = useState(false);
  const [toastMessage, setToastMessage] = useState<string | null>(null);

  const showToast = (msg: string) => {
    setToastMessage(msg);
    setTimeout(() => setToastMessage(null), 2500);
  };

  // Live timer
  useEffect(() => {
    const timer = setInterval(() => {
      setElapsedSeconds((s) => s + 1);
    }, 1000);
    return () => clearInterval(timer);
  }, []);

  // Poll mesh status to get real connected neighbor count
  useEffect(() => {
    let mounted = true;
    const fetchMesh = async () => {
      try {
        const mesh = await api.getMesh();
        if (mounted) {
          setNodesRelayed(mesh.neighbors.length || 1);
        }
      } catch (err) {
        console.error('Failed to query mesh status:', err);
      }
    };
    fetchMesh();
    const interval = setInterval(fetchMesh, 4000);
    return () => {
      mounted = false;
      clearInterval(interval);
    };
  }, []);

  // Subscribe to real mesh events via SSE
  useMeshEvents((ev) => {
    if (ev.type === 'dispatch_state' && ev.data) {
      if (!claimId || ev.data.claim_id === claimId || ev.data.did?.includes(claimId)) {
        setDispatchStatus(ev.data.state || 'ACCEPTED');
        showToast(`Mesh Dispatch: ${ev.data.state}`);
      }
    } else if (ev.type === 'message_delivered') {
      setHops((h) => Math.max(h, 2));
    } else if (ev.type === 'neighbor_up') {
      setNodesRelayed((n) => n + 1);
    }
  });

  const handleAbort = async () => {
    setIsAborting(true);
    try {
      if (claimId) {
        await api.abortClaim(claimId);
      }
      showToast('Broadcast cancelled. CRDT lease released.');
      setTimeout(() => {
        onCancelBroadcast();
      }, 500);
    } catch (err: any) {
      showToast(`Failed to abort: ${err.message}`);
      setIsAborting(false);
    }
  };

  const formatTime = (secs: number) => {
    const m = Math.floor(secs / 60);
    const s = secs % 60;
    return `${m}:${s < 10 ? '0' : ''}${s}`;
  };

  const isAccepted = dispatchStatus === 'ACCEPTED' || dispatchStatus === 'EN_ROUTE' || dispatchStatus === 'ARRIVED';
  const isResolved = dispatchStatus === 'RESOLVED';

  return (
    <div className="flex flex-col w-full pb-8 space-y-4 max-w-screen-sm mx-auto">
      {/* Toast notification */}
      {toastMessage && (
        <div className="fixed top-24 left-1/2 -translate-x-1/2 z-50 bg-[#1a1c1e] text-white px-4 py-2 rounded-full text-xs font-semibold shadow-lg flex items-center gap-2 animate-bounce">
          <span className="material-symbols-outlined text-[16px] text-[#8ed6d7]">
            info
          </span>
          {toastMessage}
        </div>
      )}

      {/* Progress & Context Header */}
      <div className="flex items-center justify-between pt-1">
        <div className="flex items-center gap-2">
          <span className={`w-3 h-3 rounded-full ${isAccepted ? 'bg-[#1e6b47]' : 'bg-[#ba1a1a] animate-ping'}`} />
          <span className="text-[14px] font-bold text-[#1e6b47] uppercase tracking-wider">
            {isResolved ? 'Request Resolved' : isAccepted ? 'Responder Assigned' : 'Beacon Active'}
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
          {isAccepted ? 'Responder en route' : 'Help request is broadcasting'}
        </h2>
        <p className="text-[16px] text-[#3f4948]">
          Hopping phone-to-phone via BLE & Wi-Fi Direct gossip. No internet or cellular required.
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

        {/* Dispatch State Pipeline Card */}
        <div className="p-3 bg-[#f3f3f6] rounded-xl space-y-2">
          <div className="flex items-center justify-between text-xs font-bold text-[#3f4948] uppercase">
            <span>M8 CRDT Auction State</span>
            <span className="px-2 py-0.5 rounded bg-white text-[#004546] font-mono">
              {dispatchStatus}
            </span>
          </div>

          <div className="grid grid-cols-4 gap-1 text-center text-xs font-semibold">
            <div className={`p-1.5 rounded ${dispatchStatus === 'PROPOSED' ? 'bg-[#004546] text-white' : 'bg-[#e8e8ea] text-[#3f4948]'}`}>
              1. Proposed
            </div>
            <div className={`p-1.5 rounded ${dispatchStatus === 'ACCEPTED' ? 'bg-[#1e6b47] text-white' : 'bg-[#e8e8ea] text-[#3f4948]'}`}>
              2. Accepted
            </div>
            <div className={`p-1.5 rounded ${dispatchStatus === 'EN_ROUTE' ? 'bg-[#1e6b47] text-white' : 'bg-[#e8e8ea] text-[#3f4948]'}`}>
              3. En Route
            </div>
            <div className={`p-1.5 rounded ${dispatchStatus === 'RESOLVED' ? 'bg-[#1e6b47] text-white' : 'bg-[#e8e8ea] text-[#3f4948]'}`}>
              4. Resolved
            </div>
          </div>
        </div>

        {/* Claim summary */}
        <div className="p-3 bg-white border border-[#e8e8ea] rounded-xl space-y-1">
          <div className="text-xs font-bold text-[#3f4948] uppercase">Broadcast Content</div>
          <p className="text-[15px] font-medium text-[#1a1c1e] italic">"{rawText}"</p>
          {claimId && (
            <div className="text-xs font-mono text-[#3f4948] pt-1">
              Signed Wire ID: {claimId}
            </div>
          )}
        </div>
      </div>

      {/* Action Buttons */}
      <div className="space-y-2 pt-2">
        <button
          type="button"
          onClick={handleAbort}
          disabled={isAborting}
          className="w-full min-h-[64px] bg-[#ba1a1a] text-white rounded-2xl flex items-center justify-center gap-2 px-4 shadow-md hover:bg-[#93000a] active:scale-[0.99] transition-all disabled:opacity-70"
        >
          {isAborting ? (
            <>
              <span className="material-symbols-outlined animate-spin text-[24px]">
                progress_activity
              </span>
              <span className="text-[18px] font-bold">Cancelling lease...</span>
            </>
          ) : (
            <>
              <span className="material-symbols-outlined text-[24px]">cancel</span>
              <span className="text-[18px] font-bold">Abort Broadcast</span>
            </>
          )}
        </button>

        <button
          type="button"
          onClick={onNewRequest}
          className="w-full min-h-[56px] bg-[#e8e8ea] text-[#1a1c1e] rounded-2xl flex items-center justify-center gap-2 px-4 hover:bg-[#eeeef0] active:scale-[0.99] transition-all"
        >
          <span className="material-symbols-outlined text-[20px]">add</span>
          <span className="text-[16px] font-bold">Submit Another Request</span>
        </button>
      </div>
    </div>
  );
};
