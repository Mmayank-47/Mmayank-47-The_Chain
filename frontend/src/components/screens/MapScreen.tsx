import React, { useState } from 'react';

export const MapScreen: React.FC = () => {
  const [selectedLayer, setSelectedLayer] = useState<'all' | 'shelters' | 'flood' | 'mesh'>('all');
  const [selectedPin, setSelectedPin] = useState<{
    title: string;
    type: string;
    distance: string;
    description: string;
  } | null>(null);

  const pins = [
    {
      id: 'shelter-1',
      type: 'shelter',
      title: 'Shivaji High School (Safe High Ground)',
      x: 32,
      y: 28,
      icon: 'holiday_village',
      color: '#1e6b47',
      distance: '180m',
      description: 'Evacuation shelter with dry hall, drinking water, 200 person capacity.',
    },
    {
      id: 'medical-1',
      type: 'medical',
      title: 'Disaster Relief Medical Camp',
      x: 68,
      y: 35,
      icon: 'medical_services',
      color: '#004546',
      distance: '310m',
      description: 'Paramedic team, BP medication supplies, first-aid, insulin cooler box.',
    },
    {
      id: 'distress-1',
      type: 'distress',
      title: 'Distress Signal (Medicine Required)',
      x: 48,
      y: 62,
      icon: 'emergency',
      color: '#ba1a1a',
      distance: '65m',
      description: 'Mother needs bp medicine (2 strips), Shivaji Nagar Lane 4 (approx area).',
    },
    {
      id: 'mesh-node-1',
      type: 'mesh',
      title: 'Relay Phone #104 (Pixel 8)',
      x: 42,
      y: 48,
      icon: 'cell_tower',
      color: '#005f60',
      distance: '14m',
      description: 'Direct Wi-Fi Direct peer, 94% battery, active mesh packet bridge.',
    },
    {
      id: 'water-1',
      type: 'water',
      title: 'Municipal Tanker (Potable Water)',
      x: 22,
      y: 70,
      icon: 'water_drop',
      color: '#0284c7',
      distance: '240m',
      description: 'Operating solar water filtration system, 2000L available.',
    },
  ];

  return (
    <div className="flex flex-col w-full pb-8 space-y-4 max-w-screen-sm mx-auto">
      {/* Header */}
      <div className="space-y-1 pt-1">
        <div className="flex items-center justify-between">
          <h2 className="text-[28px] leading-[36px] font-bold text-[#1a1c1e] tracking-tight">
            Offline Tactical Map
          </h2>
          <span className="text-[12px] font-bold px-2 py-0.5 rounded-full bg-[#eeeef0] text-[#004546] border border-[#bec9c8]">
            STORED LOCALLY
          </span>
        </div>
        <p className="text-[16px] text-[#3f4948]">
          Pre-cached neighborhood vector map with mesh peers & flood hazards.
        </p>
      </div>

      {/* Map layer pills */}
      <div className="flex items-center gap-1.5 overflow-x-auto no-scrollbar">
        {[
          { key: 'all', label: 'All Layers' },
          { key: 'flood', label: 'Flood Zone' },
          { key: 'shelters', label: 'Safe Shelters' },
          { key: 'mesh', label: 'Mesh Links' },
        ].map((layer) => {
          const isActive = selectedLayer === layer.key;
          return (
            <button
              key={layer.key}
              type="button"
              onClick={() => setSelectedLayer(layer.key as any)}
              className={`px-3 py-1.5 rounded-xl text-[13px] font-bold whitespace-nowrap transition-colors ${
                isActive
                  ? 'bg-[#004546] text-white shadow-sm'
                  : 'bg-[#e8e8ea] text-[#3f4948] hover:bg-[#eeeef0]'
              }`}
            >
              {layer.label}
            </button>
          );
        })}
      </div>

      {/* Tactical Vector Map Canvas */}
      <div className="relative w-full h-[360px] bg-[#f0ede6] rounded-2xl border-2 border-[#bec9c8] overflow-hidden shadow-inner select-none">
        {/* Map Grid and Roads (SVG) */}
        <svg className="w-full h-full absolute inset-0 pointer-events-none">
          {/* Subtle terrain contours */}
          <path
            d="M 0 100 Q 150 70 300 120 T 600 90 L 600 400 L 0 400 Z"
            fill="#e9e5dc"
            opacity="0.6"
          />

          {/* Flooding water zone */}
          {(selectedLayer === 'all' || selectedLayer === 'flood') && (
            <g>
              <path
                d="M 180 400 Q 240 280 320 290 T 500 320 L 500 400 Z"
                fill="#93c5fd"
                opacity="0.45"
              />
              <path
                d="M 180 400 Q 240 280 320 290 T 500 320"
                stroke="#2563eb"
                strokeWidth="2"
                strokeDasharray="4 4"
                fill="none"
              />
              <text
                x="280"
                y="350"
                fill="#1e40af"
                fontSize="11"
                fontWeight="bold"
                fontFamily="sans-serif"
              >
                Rising Water Hazard (0.8m)
              </text>
            </g>
          )}

          {/* Road Network */}
          <path
            d="M 40 0 L 40 400 M 0 180 L 600 180 M 0 260 L 600 260 M 240 0 L 240 400 M 420 0 L 420 400"
            stroke="#ffffff"
            strokeWidth="10"
            strokeLinecap="round"
          />
          <path
            d="M 40 0 L 40 400 M 0 180 L 600 180 M 0 260 L 600 260 M 240 0 L 240 400 M 420 0 L 420 400"
            stroke="#d4cfc5"
            strokeWidth="1"
            fill="none"
          />

          {/* Mesh Wireless P2P Link Lines */}
          {(selectedLayer === 'all' || selectedLayer === 'mesh') && (
            <g>
              {/* You to Pixel 8 */}
              <line
                x1="50%"
                y1="50%"
                x2="42%"
                y2="48%"
                stroke="#004546"
                strokeWidth="2.5"
                strokeDasharray="5 3"
              />
              {/* Pixel 8 to Medical Camp */}
              <line
                x1="42%"
                y1="48%"
                x2="68%"
                y2="35%"
                stroke="#004546"
                strokeWidth="2"
                strokeDasharray="5 3"
                opacity="0.8"
              />
              {/* You to Shelter */}
              <line
                x1="50%"
                y1="50%"
                x2="32%"
                y2="28%"
                stroke="#004546"
                strokeWidth="2"
                strokeDasharray="5 3"
                opacity="0.7"
              />
            </g>
          )}
        </svg>

        {/* You Are Here Indicator (Center) */}
        <div
          className="absolute z-20 flex flex-col items-center pointer-events-none -translate-x-1/2 -translate-y-1/2"
          style={{ left: '50%', top: '50%' }}
        >
          <div className="w-8 h-8 rounded-full bg-[#004546] text-white flex items-center justify-center border-2 border-white shadow-lg animate-pulse">
            <span className="material-symbols-outlined text-[18px]">my_location</span>
          </div>
          <span className="text-[10px] font-bold px-1.5 py-0.5 rounded bg-[#004546] text-white mt-0.5">
            You
          </span>
        </div>

        {/* Tactical Pins */}
        {pins.map((pin) => {
          if (selectedLayer === 'shelters' && pin.type !== 'shelter') return null;
          if (selectedLayer === 'flood' && pin.type !== 'distress') return null;
          if (selectedLayer === 'mesh' && pin.type !== 'mesh') return null;

          return (
            <button
              key={pin.id}
              type="button"
              onClick={() => setSelectedPin(pin)}
              className="absolute z-10 flex flex-col items-center -translate-x-1/2 -translate-y-1/2 transition-transform hover:scale-110 active:scale-95"
              style={{ left: `${pin.x}%`, top: `${pin.y}%` }}
            >
              <div
                className="w-8 h-8 rounded-full flex items-center justify-center text-white shadow-md border-2 border-white"
                style={{ backgroundColor: pin.color }}
              >
                <span className="material-symbols-outlined text-[18px]">
                  {pin.icon}
                </span>
              </div>
              <span className="text-[10px] font-bold bg-white/95 text-[#1a1c1e] px-1.5 py-0.2 rounded shadow-sm border border-[#e8e8ea] mt-0.5 whitespace-nowrap">
                {pin.distance}
              </span>
            </button>
          );
        })}

        {/* Offline Compass Indicator */}
        <div className="absolute top-3 right-3 bg-white/90 backdrop-blur-sm px-2 py-1 rounded-lg border border-[#bec9c8] text-center shadow-sm">
          <span className="text-[10px] font-mono font-bold text-[#004546] block">N ↑</span>
          <span className="text-[9px] text-[#3f4948]">Offline GPS</span>
        </div>
      </div>

      {/* Selected Pin Details Box */}
      {selectedPin ? (
        <div className="bg-white rounded-2xl p-4 border border-[#004546] shadow-md space-y-2">
          <div className="flex items-start justify-between">
            <div>
              <span className="text-[11px] font-bold uppercase tracking-wider text-[#004546]">
                {selectedPin.type} · {selectedPin.distance} away
              </span>
              <h3 className="text-[18px] font-bold text-[#1a1c1e]">{selectedPin.title}</h3>
            </div>
            <button
              type="button"
              onClick={() => setSelectedPin(null)}
              className="w-7 h-7 rounded-full flex items-center justify-center text-[#3f4948] hover:bg-[#eeeef0]"
            >
              <span className="material-symbols-outlined text-[18px]">close</span>
            </button>
          </div>
          <p className="text-[14px] text-[#3f4948]">{selectedPin.description}</p>
        </div>
      ) : (
        <div className="bg-[#f3f3f6] rounded-2xl p-3 border border-[#e8e8ea] flex items-center gap-3">
          <span className="material-symbols-outlined text-[#004546] text-[22px]">
            touch_app
          </span>
          <p className="text-[14px] text-[#3f4948]">
            Tap any tactical marker on the map to inspect supplies, shelters, or mesh relays.
          </p>
        </div>
      )}
    </div>
  );
};
