import React, { useState, useEffect } from 'react';
import { api } from '../../api/client';
import { useMeshEvents } from '../../api/events';
import { DistressRequest } from '../../types';

export const RespondScreen: React.FC = () => {
  const [requests, setRequests] = useState<DistressRequest[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [filter, setFilter] = useState<'all' | 'critical' | 'high' | 'moderate'>('all');
  const [toastMessage, setToastMessage] = useState<string | null>(null);

  const showToast = (msg: string) => {
    setToastMessage(msg);
    setTimeout(() => setToastMessage(null), 2500);
  };

  const fetchRequests = async () => {
    try {
      setError(null);
      const data = await api.getRequests();
      setRequests(data.requests);
    } catch (err: any) {
      console.error('Failed to fetch requests:', err);
      setError(err.message || 'Failed to load requests from local replica');
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => {
    fetchRequests();
    const interval = setInterval(fetchRequests, 5000);
    return () => clearInterval(interval);
  }, []);

  // Real-time update on mesh events
  useMeshEvents((ev) => {
    if (ev.type === 'claim_opened' || ev.type === 'dispatch_state' || ev.type === 'partition_healed') {
      fetchRequests();
    }
  });

  const handleRespond = async (claimId: string, action: 'accept' | 'decline' | 'refer') => {
    try {
      const res = await api.respondRequest(claimId, action);
      if (action === 'accept' && res.sealed_released) {
        showToast('Accepted! M9 stage-2 private details unlocked.');
      } else {
        showToast(`Request ${action}ed`);
      }
      fetchRequests();
    } catch (err: any) {
      showToast(`Action failed: ${err.message}`);
    }
  };

  const filteredRequests = requests.filter((req) => {
    if (filter === 'all') return true;
    if (filter === 'critical') return req.urgency >= 0.8;
    if (filter === 'high') return req.urgency >= 0.5 && req.urgency < 0.8;
    return req.urgency < 0.5;
  });

  return (
    <div className="flex flex-col w-full pb-8 space-y-4 max-w-screen-sm mx-auto">
      {/* Toast notification */}
      {toastMessage && (
        <div className="fixed top-24 left-1/2 -translate-x-1/2 z-50 bg-[#1a1c1e] text-white px-4 py-2 rounded-full text-xs font-semibold shadow-lg flex items-center gap-2 animate-bounce">
          <span className="material-symbols-outlined text-[16px] text-[#8ed6d7]">
            verified
          </span>
          {toastMessage}
        </div>
      )}

      {/* Header */}
      <div className="space-y-1 pt-1">
        <div className="flex items-center justify-between">
          <h2 className="text-[28px] leading-[36px] font-bold text-[#1a1c1e] tracking-tight">
            Incoming Distress Signals
          </h2>
          <span className="text-[12px] font-bold px-2 py-0.5 rounded-full bg-[#eeeef0] text-[#004546] font-mono">
            {requests.length} Active
          </span>
        </div>
        <p className="text-[16px] text-[#3f4948]">
          Verified emergency requests broadcast across the peer-to-peer mesh.
        </p>
      </div>

      {/* Filter pills */}
      <div className="flex items-center gap-1.5 overflow-x-auto no-scrollbar">
        {[
          { key: 'all', label: 'All Urgencies' },
          { key: 'critical', label: 'Critical (>80%)' },
          { key: 'high', label: 'High Priority' },
          { key: 'moderate', label: 'Standard' },
        ].map((item) => {
          const isActive = filter === item.key;
          return (
            <button
              key={item.key}
              type="button"
              onClick={() => setFilter(item.key as any)}
              className={`px-3 py-1.5 rounded-full text-[14px] font-semibold whitespace-nowrap transition-colors min-h-[40px] ${
                isActive
                  ? 'bg-[#004546] text-white shadow-sm'
                  : 'bg-[#e8e8ea] text-[#3f4948] hover:bg-[#eeeef0]'
              }`}
            >
              {item.label}
            </button>
          );
        })}
      </div>

      {/* Error Banner */}
      {error && (
        <div className="p-3 bg-red-50 border border-red-200 text-red-700 text-sm rounded-xl">
          {error}
        </div>
      )}

      {/* Loading state */}
      {isLoading && requests.length === 0 && (
        <div className="p-8 text-center text-[#3f4948] bg-white rounded-2xl border border-[#e8e8ea] space-y-2">
          <span className="material-symbols-outlined text-[32px] animate-spin text-[#004546]">
            sync
          </span>
          <p className="text-[16px] font-semibold">Scanning local mesh replica book...</p>
        </div>
      )}

      {/* Empty State */}
      {!isLoading && filteredRequests.length === 0 && (
        <div className="p-8 text-center text-[#3f4948] bg-white rounded-2xl border border-[#e8e8ea] space-y-2">
          <span className="material-symbols-outlined text-[40px] text-[#bec9c8]">
            check_circle
          </span>
          <p className="text-[18px] font-bold text-[#1a1c1e]">No pending requests</p>
          <p className="text-[14px] text-[#3f4948]">
            No unhandled distress beacons in this mesh partition.
          </p>
        </div>
      )}

      {/* Request Cards */}
      <div className="space-y-3">
        {filteredRequests.map((req) => {
          const isCritical = req.urgency >= 0.8;
          const isAccepted = req.status === 'accepted' || req.sealed != null;

          return (
            <div
              key={req.id}
              className={`p-4 rounded-2xl bg-white border shadow-sm space-y-3 transition-all ${
                isAccepted ? 'border-[#1e6b47] ring-1 ring-[#1e6b47]/20' : 'border-[#e8e8ea]'
              }`}
            >
              {/* Top metadata */}
              <div className="flex items-start justify-between gap-2">
                <div className="space-y-0.5">
                  <div className="flex items-center gap-1.5">
                    <span className="text-[18px] font-bold text-[#1a1c1e]">
                      Cell {req.cell}
                    </span>
                    <span className="text-xs font-mono text-[#3f4948] bg-[#f3f3f6] px-1.5 py-0.5 rounded">
                      ID: {req.id}
                    </span>
                  </div>
                  <div className="text-[12px] text-[#3f4948]">
                    Hops: {req.hops} | Age: {req.age_s}s ago | Status: {req.status.toUpperCase()}
                  </div>
                </div>

                <span
                  className={`text-[12px] font-bold px-2 py-0.5 rounded-full ${
                    isCritical
                      ? 'bg-[#ffdad6] text-[#ba1a1a]'
                      : 'bg-[#a6eff0] text-[#002020]'
                  }`}
                >
                  Urgency: {Math.round(req.urgency * 100)}%
                </span>
              </div>

              {/* Description & M10 Rationale */}
              <div className="space-y-1">
                <p className="text-[16px] text-[#1a1c1e] font-medium leading-snug">
                  {req.raw_text}
                </p>
                {req.justification?.text && (
                  <div className="p-2.5 bg-[#f3f3f6] rounded-xl text-[13px] text-[#3f4948] space-y-1">
                    <div className="font-bold text-[#004546] flex items-center justify-between text-xs">
                      <span>M10 JUSTIFICATION</span>
                      <span className="uppercase">Risk: {req.justification.risk}</span>
                    </div>
                    <p>{req.justification.text}</p>
                    {req.justification.abort && (
                      <p className="text-red-700 font-semibold text-xs">
                        ⚠️ {req.justification.abort}
                      </p>
                    )}
                  </div>
                )}
              </div>

              {/* Stage-2 Sealed Details (M9 privacy release) */}
              {req.sealed ? (
                <div className="p-3 bg-[#e8f5e9] border border-[#a5d6a7] rounded-xl space-y-1">
                  <div className="flex items-center gap-1 text-xs font-bold text-[#2e7d32]">
                    <span className="material-symbols-outlined text-[16px]">lock_open</span>
                    <span>STAGE-2 SEALED DETAILS UNLOCKED</span>
                  </div>
                  <div className="text-[14px] text-[#1b5e20] space-y-0.5">
                    <div><strong>Victim:</strong> {req.sealed.victim_name}</div>
                    <div><strong>Contact:</strong> {req.sealed.contact_phone}</div>
                    <div><strong>Exact Coordinates:</strong> [{req.sealed.exact_pos.join(', ')}]</div>
                    <div><strong>Medical:</strong> {req.sealed.medical_details}</div>
                  </div>
                </div>
              ) : (
                <div className="flex items-center gap-1 text-xs text-[#3f4948]">
                  <span className="material-symbols-outlined text-[14px]">lock</span>
                  <span>Exact GPS location sealed until assignment is accepted</span>
                </div>
              )}

              {/* Action Buttons */}
              <div className="flex items-center gap-2 pt-1">
                {!isAccepted ? (
                  <>
                    <button
                      type="button"
                      onClick={() => handleRespond(req.id, 'accept')}
                      className="flex-1 min-h-[48px] bg-[#004546] text-white rounded-xl font-bold text-[16px] flex items-center justify-center gap-1 hover:bg-[#005f60] active:scale-[0.99] transition-all"
                    >
                      <span className="material-symbols-outlined text-[20px]">check</span>
                      <span>Accept Task</span>
                    </button>
                    <button
                      type="button"
                      onClick={() => handleRespond(req.id, 'refer')}
                      className="px-3 min-h-[48px] bg-[#e8e8ea] text-[#1a1c1e] rounded-xl font-semibold text-[14px] hover:bg-[#eeeef0]"
                    >
                      Refer
                    </button>
                    <button
                      type="button"
                      onClick={() => handleRespond(req.id, 'decline')}
                      className="px-3 min-h-[48px] bg-[#e8e8ea] text-[#ba1a1a] rounded-xl font-semibold text-[14px] hover:bg-[#ffdad6]"
                    >
                      Decline
                    </button>
                  </>
                ) : (
                  <div className="w-full flex items-center justify-between text-[#1e6b47] font-bold text-[15px] px-2">
                    <span className="flex items-center gap-1">
                      <span className="material-symbols-outlined">directions_run</span>
                      Assigned to you
                    </span>
                    <span className="text-xs bg-[#e8f5e9] px-2 py-1 rounded">En Route</span>
                  </div>
                )}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
};
