import React, { useState, useEffect } from 'react';
import { api } from '../../api/client';
import { useMeshEvents } from '../../api/events';
import { MeshStatus } from '../../types';

export const MeshScreen: React.FC = () => {
  const [mesh, setMesh] = useState<MeshStatus | null>(null);
  const [isPinging, setIsPinging] = useState(false);
  const [pingResult, setPingResult] = useState<string | null>(null);
  const [simActionMsg, setSimActionMsg] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(true);

  const fetchMesh = async () => {
    try {
      const data = await api.getMesh();
      setMesh(data);
    } catch (err) {
      console.error('Failed to fetch mesh data:', err);
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => {
    fetchMesh();
    const interval = setInterval(fetchMesh, 3000);
    return () => clearInterval(interval);
  }, []);

  useMeshEvents((ev) => {
    if (
      ev.type === 'neighbor_up' ||
      ev.type === 'neighbor_down' ||
      ev.type === 'partition_healed' ||
      ev.type === 'drop_counter'
    ) {
      fetchMesh();
    }
  });

  const handlePingMesh = async () => {
    setIsPinging(true);
    setPingResult(null);
    const start = performance.now();
    try {
      const data = await api.getMesh();
      const latency = Math.round(performance.now() - start);
      setMesh(data);
      setPingResult(
        `Round-trip verified: ${data.neighbors.length} active radio neighbors (${latency}ms probe). Ingress filter active.`
      );
    } catch (err: any) {
      setPingResult(`Probe error: ${err.message}`);
    } finally {
      setIsPinging(false);
    }
  };

  const handlePartition = async () => {
    try {
      await api.simPartition();
      setSimActionMsg('Partition applied: node-resp-1 unlinked. Mesh severed into two partitions.');
      fetchMesh();
    } catch (err: any) {
      setSimActionMsg(`Partition error: ${err.message}`);
    }
  };

  const handleHeal = async () => {
    try {
      await api.simHeal();
      setSimActionMsg('Partitions healed! Anti-entropy logs reconciled.');
      fetchMesh();
    } catch (err: any) {
      setSimActionMsg(`Heal error: ${err.message}`);
    }
  };

  const handleAttack = async (type: 'blackhole' | 'greyhole' | 'replay') => {
    try {
      await api.simAttack(type);
      setSimActionMsg(`Attacker node 'atk0' injected (${type} attack). Watchdog monitoring active.`);
      fetchMesh();
    } catch (err: any) {
      setSimActionMsg(`Attack inject error: ${err.message}`);
    }
  };

  const handleKill = async (nodeId: string) => {
    try {
      await api.simKillNode(nodeId);
      setSimActionMsg(`Node '${nodeId}' powered down / radio isolated.`);
      fetchMesh();
    } catch (err: any) {
      setSimActionMsg(`Kill error: ${err.message}`);
    }
  };

  const neighbors = mesh?.neighbors || [];

  return (
    <div className="flex flex-col w-full pb-8 space-y-4 max-w-screen-sm mx-auto">
      {/* Header */}
      <div className="space-y-1 pt-1">
        <div className="flex items-center justify-between">
          <h2 className="text-[28px] leading-[36px] font-bold text-[#1a1c1e] tracking-tight">
            Mesh Network
          </h2>
          <span className="text-[12px] font-bold px-2 py-0.5 rounded-full bg-[#a6eff0] text-[#002020] font-mono">
            {mesh ? `${mesh.node_id} (Cell ${mesh.cell})` : 'Scanning...'}
          </span>
        </div>
        <p className="text-[16px] text-[#3f4948]">
          Zero-cell ad-hoc mesh over Bluetooth Low Energy & Wi-Fi Direct.
        </p>
      </div>

      {/* Cluster Health Summary */}
      <div className="grid grid-cols-3 gap-2">
        <div className="bg-white p-3 rounded-2xl border border-[#e8e8ea] text-center shadow-sm">
          <span className="text-[11px] font-bold text-[#3f4948] block">NEIGHBORS</span>
          <span className="text-[24px] font-bold text-[#004546]">
            {neighbors.length} Nodes
          </span>
          <span className="text-[10px] text-[#1e6b47] block font-semibold">1-Hop Radio</span>
        </div>
        <div className="bg-white p-3 rounded-2xl border border-[#e8e8ea] text-center shadow-sm">
          <span className="text-[11px] font-bold text-[#3f4948] block">AIRTIME TX</span>
          <span className="text-[22px] font-bold text-[#004546] font-mono">
            {mesh?.airtime.total_tx_bytes || 0} B
          </span>
          <span className="text-[10px] text-[#3f4948] block font-mono">
            {mesh ? `${(mesh.airtime.duty_cycle_used * 100).toFixed(2)}% duty` : '0%'}
          </span>
        </div>
        <div className="bg-white p-3 rounded-2xl border border-[#e8e8ea] text-center shadow-sm">
          <span className="text-[11px] font-bold text-[#3f4948] block">INGRESS DROPS</span>
          <span className="text-[24px] font-bold text-[#004546]">
            {mesh ? Object.values(mesh.dropped_counters).reduce((a, b) => a + b, 0) : 0}
          </span>
          <span className="text-[10px] text-[#1e6b47] block font-semibold">Guard Active</span>
        </div>
      </div>

      {/* Ping Button */}
      <div className="bg-white p-4 rounded-2xl border border-[#e8e8ea] shadow-sm space-y-3">
        <button
          type="button"
          onClick={handlePingMesh}
          disabled={isPinging}
          className="w-full min-h-[56px] rounded-xl bg-[#004546] text-white font-bold text-[16px] flex items-center justify-center gap-2 hover:bg-[#005f60] active:scale-[0.99] transition-all disabled:opacity-75"
        >
          {isPinging ? (
            <>
              <span className="material-symbols-outlined animate-spin text-[20px]">
                sync
              </span>
              <span>Probing Neighbor Links...</span>
            </>
          ) : (
            <>
              <span className="material-symbols-outlined text-[20px]">radar</span>
              <span>Ping Mesh Neighbors</span>
            </>
          )}
        </button>

        {pingResult && (
          <div className="p-3 bg-[#e8f5e9] text-[#1b5e20] text-xs font-semibold rounded-xl border border-[#a5d6a7]">
            {pingResult}
          </div>
        )}
      </div>

      {/* Peer list */}
      <div className="space-y-2">
        <h3 className="text-[18px] font-bold text-[#1a1c1e] pt-1">
          Direct Radio Peers ({neighbors.length})
        </h3>

        {neighbors.length === 0 && !isLoading && (
          <div className="p-6 text-center text-[#3f4948] bg-white rounded-2xl border border-[#e8e8ea]">
            No radio neighbors detected. Searching beacons on Wi-Fi Direct & BLE...
          </div>
        )}

        {neighbors.map((peer) => (
          <div
            key={peer.id}
            className="p-4 rounded-2xl bg-white border border-[#e8e8ea] shadow-sm flex items-center justify-between gap-3"
          >
            <div className="flex items-center gap-3">
              <div className="w-10 h-10 rounded-xl bg-[#e8e8ea] flex items-center justify-center text-[#004546]">
                <span className="material-symbols-outlined text-[22px]">
                  smartphone
                </span>
              </div>
              <div>
                <div className="text-[16px] font-bold text-[#1a1c1e] flex items-center gap-2">
                  <span>{peer.id}</span>
                  <span className="text-[11px] bg-[#a6eff0] text-[#002020] px-1.5 py-0.2 rounded font-mono">
                    Cell {peer.cell}
                  </span>
                </div>
                <div className="text-[12px] text-[#3f4948]">
                  {peer.connection_type} | Seen {peer.last_seen_s_ago}s ago
                </div>
              </div>
            </div>

            <div className="text-right text-xs">
              <div className="font-bold text-[#1e6b47]">
                {Math.round(peer.battery * 100)}% bat
              </div>
              <div className="text-[#3f4948] font-mono">
                Trust: {peer.trust.toFixed(2)}
              </div>
            </div>
          </div>
        ))}
      </div>

      {/* Dev Simulation & Chaos Controls */}
      <div className="bg-white p-4 rounded-2xl border border-amber-200 shadow-sm space-y-3">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-1.5 text-amber-800 font-bold text-sm">
            <span className="material-symbols-outlined text-[18px]">build</span>
            <span>Mesh Simulation & Chaos Controls</span>
          </div>
          <span className="text-[10px] bg-amber-100 text-amber-900 px-2 py-0.5 rounded font-mono font-bold">
            DEV CONTROLS
          </span>
        </div>

        {simActionMsg && (
          <div className="p-2.5 bg-amber-50 text-amber-900 text-xs rounded-lg border border-amber-200 font-medium">
            {simActionMsg}
          </div>
        )}

        <div className="grid grid-cols-2 gap-2">
          <button
            type="button"
            onClick={handlePartition}
            className="p-2.5 rounded-xl bg-orange-100 hover:bg-orange-200 text-orange-950 font-bold text-xs flex items-center justify-center gap-1"
          >
            <span className="material-symbols-outlined text-[16px]">call_split</span>
            <span>Partition Mesh</span>
          </button>

          <button
            type="button"
            onClick={handleHeal}
            className="p-2.5 rounded-xl bg-green-100 hover:bg-green-200 text-green-950 font-bold text-xs flex items-center justify-center gap-1"
          >
            <span className="material-symbols-outlined text-[16px]">healing</span>
            <span>Heal Partition</span>
          </button>

          <button
            type="button"
            onClick={() => handleAttack('blackhole')}
            className="p-2.5 rounded-xl bg-red-100 hover:bg-red-200 text-red-950 font-bold text-xs flex items-center justify-center gap-1"
          >
            <span className="material-symbols-outlined text-[16px]">security</span>
            <span>Blackhole Attack</span>
          </button>

          <button
            type="button"
            onClick={() => handleKill('node-relay-1')}
            className="p-2.5 rounded-xl bg-gray-100 hover:bg-gray-200 text-gray-900 font-bold text-xs flex items-center justify-center gap-1"
          >
            <span className="material-symbols-outlined text-[16px]">power_off</span>
            <span>Kill Relay-1</span>
          </button>
        </div>
      </div>
    </div>
  );
};
