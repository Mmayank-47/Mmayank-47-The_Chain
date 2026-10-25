import React, { useState } from 'react';
import { MeshPeer } from '../../types';

export const MeshScreen: React.FC = () => {
  const [isPinging, setIsPinging] = useState(false);
  const [pingResult, setPingResult] = useState<string | null>(null);

  const [peers] = useState<MeshPeer[]>([
    {
      id: 'node-104',
      deviceName: 'Pixel 8 Pro (Volunteer)',
      deviceModel: 'Google Pixel 8 Pro',
      connectionType: 'Direct Wi-Fi',
      signalDbm: -48,
      batteryPercentage: 94,
      packetsRelayed: 31,
      lastPingMs: 14,
      isDirectNeighbor: true,
    },
    {
      id: 'node-218',
      deviceName: 'Galaxy M32 (Resident)',
      deviceModel: 'Samsung SM-M325F',
      connectionType: 'BLE Mesh',
      signalDbm: -64,
      batteryPercentage: 68,
      packetsRelayed: 17,
      lastPingMs: 28,
      isDirectNeighbor: true,
    },
    {
      id: 'node-309',
      deviceName: 'Redmi Note 12 (Bridge)',
      deviceModel: 'Xiaomi 22111317I',
      connectionType: 'Hop 2 (BLE)',
      signalDbm: -78,
      batteryPercentage: 42,
      packetsRelayed: 9,
      lastPingMs: 52,
      isDirectNeighbor: false,
    },
    {
      id: 'node-411',
      deviceName: 'OnePlus Nord CE (Aid Camp)',
      deviceModel: 'OnePlus EB2101',
      connectionType: 'Hop 3 (Relay)',
      signalDbm: -82,
      batteryPercentage: 81,
      packetsRelayed: 46,
      lastPingMs: 84,
      isDirectNeighbor: false,
    },
  ]);

  const handlePingMesh = () => {
    setIsPinging(true);
    setPingResult(null);
    setTimeout(() => {
      setIsPinging(false);
      setPingResult('Mesh heartbeats validated: 4/4 nodes active. 0% packet loss.');
    }, 1200);
  };

  return (
    <div className="flex flex-col w-full pb-8 space-y-4 max-w-screen-sm mx-auto">
      {/* Header */}
      <div className="space-y-1 pt-1">
        <div className="flex items-center justify-between">
          <h2 className="text-[28px] leading-[36px] font-bold text-[#1a1c1e] tracking-tight">
            Mesh Network
          </h2>
          <span className="text-[12px] font-bold px-2 py-0.5 rounded-full bg-[#a6eff0] text-[#002020]">
            ACTIVE PEER-TO-PEER
          </span>
        </div>
        <p className="text-[16px] text-[#3f4948]">
          Zero-cell ad-hoc mesh over Bluetooth Low Energy & Wi-Fi Direct.
        </p>
      </div>

      {/* Cluster Health Summary */}
      <div className="grid grid-cols-3 gap-2">
        <div className="bg-white p-3 rounded-2xl border border-[#e8e8ea] text-center shadow-sm">
          <span className="text-[11px] font-bold text-[#3f4948] block">CONNECTED</span>
          <span className="text-[24px] font-bold text-[#004546]">4 Nodes</span>
          <span className="text-[10px] text-[#1e6b47] block font-semibold">100% In Range</span>
        </div>
        <div className="bg-white p-3 rounded-2xl border border-[#e8e8ea] text-center shadow-sm">
          <span className="text-[11px] font-bold text-[#3f4948] block">RELAY CAPACITY</span>
          <span className="text-[24px] font-bold text-[#004546]">103 pkts</span>
          <span className="text-[10px] text-[#3f4948] block font-mono">Zero drops</span>
        </div>
        <div className="bg-white p-3 rounded-2xl border border-[#e8e8ea] text-center shadow-sm">
          <span className="text-[11px] font-bold text-[#3f4948] block">RADIUS</span>
          <span className="text-[24px] font-bold text-[#004546]">~450m</span>
          <span className="text-[10px] text-[#895100] block font-semibold">3 hops span</span>
        </div>
      </div>

      {/* Ping button & feedback */}
      <div className="space-y-2">
        <button
          type="button"
          onClick={handlePingMesh}
          disabled={isPinging}
          className="w-full min-h-[56px] bg-[#004546] text-white rounded-2xl flex items-center justify-center gap-2 text-[17px] font-bold hover:bg-[#005f60] active:scale-[0.99] transition-all"
        >
          <span
            className={`material-symbols-outlined text-[22px] ${
              isPinging ? 'animate-spin' : ''
            }`}
          >
            {isPinging ? 'sync' : 'network_check'}
          </span>
          <span>{isPinging ? 'Pinging cluster nodes...' : 'Send Cluster Diagnostic Ping'}</span>
        </button>

        {pingResult && (
          <div className="bg-[#f0fdf4] border border-[#bbf7d0] text-[#166534] p-3 rounded-xl text-[13px] font-semibold flex items-center gap-2">
            <span className="material-symbols-outlined text-[18px]">check_circle</span>
            <span>{pingResult}</span>
          </div>
        )}
      </div>

      {/* Peer Roster (Each row min-height 72px as per Image_27_8d76987a.markdown) */}
      <div className="bg-white rounded-2xl border border-[#e8e8ea] shadow-sm overflow-hidden">
        <div className="px-4 py-3 bg-[#f3f3f6] border-b border-[#e8e8ea] flex items-center justify-between">
          <h3 className="text-[15px] font-bold text-[#1a1c1e]">
            Discovered Neighboring Devices
          </h3>
          <span className="text-[12px] font-mono text-[#3f4948]">2.4GHz + BLE 5.2</span>
        </div>

        <div className="divide-y divide-[#dde2df]">
          {peers.map((peer) => (
            <div
              key={peer.id}
              className="min-h-[72px] p-4 flex items-center justify-between hover:bg-[#f9f9fc] transition-colors"
            >
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 rounded-xl bg-[#e8e8ea] flex items-center justify-center text-[#004546] shrink-0">
                  <span className="material-symbols-outlined text-[22px]">
                    {peer.connectionType.includes('Wi-Fi') ? 'wifi_tethering' : 'bluetooth'}
                  </span>
                </div>
                <div>
                  <div className="flex items-center gap-2">
                    <h4 className="text-[16px] font-bold text-[#1a1c1e]">
                      {peer.deviceName}
                    </h4>
                    {peer.isDirectNeighbor && (
                      <span className="text-[10px] bg-[#a6eff0] text-[#002020] font-bold px-1.5 py-0.2 rounded">
                        Direct
                      </span>
                    )}
                  </div>
                  <div className="flex items-center gap-2 text-[12px] text-[#3f4948] mt-0.5 font-medium">
                    <span>{peer.connectionType}</span>
                    <span>·</span>
                    <span className="font-mono">{peer.signalDbm} dBm</span>
                    <span>·</span>
                    <span>{peer.packetsRelayed} relayed</span>
                  </div>
                </div>
              </div>

              {/* Battery & Ping Latency */}
              <div className="text-right shrink-0">
                <div className="flex items-center gap-1 justify-end text-[13px] font-bold text-[#1a1c1e]">
                  <span className="material-symbols-outlined text-[16px] text-[#004546]">
                    battery_std
                  </span>
                  <span>{peer.batteryPercentage}%</span>
                </div>
                <span className="text-[11px] font-mono text-[#3f4948]">
                  {peer.lastPingMs}ms ping
                </span>
              </div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
};
