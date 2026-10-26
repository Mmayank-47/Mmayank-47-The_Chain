import React, { useState, useEffect } from 'react';
import { api } from '../../api/client';
import { useMeshEvents } from '../../api/events';
import { MapData, MapEntity } from '../../types';

export const MapScreen: React.FC = () => {
  const [mapData, setMapData] = useState<MapData | null>(null);
  const [selectedLayer, setSelectedLayer] = useState<'all' | 'shelters' | 'flood' | 'distress'>('all');
  const [selectedEntity, setSelectedEntity] = useState<MapEntity | null>(null);
  const [isLoading, setIsLoading] = useState(true);

  const fetchMap = async () => {
    try {
      const data = await api.getMap();
      setMapData(data);
    } catch (err) {
      console.error('Failed to load map data:', err);
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => {
    fetchMap();
    const interval = setInterval(fetchMap, 4000);
    return () => clearInterval(interval);
  }, []);

  useMeshEvents((ev) => {
    if (ev.type === 'claim_opened' || ev.type === 'dispatch_state') {
      fetchMap();
    }
  });

  const entities = mapData?.entities || [];
  const hazardCells = mapData?.hazard_cells || [];
  const floodLevelM = mapData?.flood_level_m || 0.35;
  const floodX = mapData?.flood_x || 125.0;

  const filteredEntities = entities.filter((ent) => {
    if (selectedLayer === 'all') return true;
    if (selectedLayer === 'shelters') return ent.type === 'safe_shelter' || ent.type === 'medical_post';
    if (selectedLayer === 'distress') return ent.type === 'distress_call';
    return true;
  });

  return (
    <div className="flex flex-col w-full pb-8 space-y-4 max-w-screen-sm mx-auto">
      {/* Header */}
      <div className="space-y-1 pt-1">
        <div className="flex items-center justify-between">
          <h2 className="text-[28px] leading-[36px] font-bold text-[#1a1c1e] tracking-tight">
            Tactical Map
          </h2>
          <span className="text-[12px] font-bold px-2 py-0.5 rounded-full bg-[#eeeef0] text-[#004546] font-mono">
            Water: {floodLevelM.toFixed(2)}m
          </span>
        </div>
        <p className="text-[16px] text-[#3f4948]">
          Vector map with flood front line and coarse cells. M9 location privacy active.
        </p>
      </div>

      {/* Layer Pills */}
      <div className="flex items-center gap-1.5 overflow-x-auto no-scrollbar">
        {[
          { key: 'all', label: 'All Hazards' },
          { key: 'flood', label: 'Flood Frontier' },
          { key: 'shelters', label: 'Relief Shelters' },
          { key: 'distress', label: 'Distress Cells' },
        ].map((layer) => {
          const isActive = selectedLayer === layer.key;
          return (
            <button
              key={layer.key}
              type="button"
              onClick={() => setSelectedLayer(layer.key as any)}
              className={`px-3 py-1.5 rounded-full text-[14px] font-semibold whitespace-nowrap transition-colors min-h-[40px] ${
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

      {/* Tactical Canvas / SVG Map */}
      <div className="relative w-full h-[340px] bg-[#1a2324] rounded-3xl overflow-hidden shadow-inner border border-[#3f4948]">
        {/* Flood line representation */}
        <div
          className="absolute top-0 bottom-0 left-0 bg-[#004546]/35 border-r-2 border-cyan-400 pointer-events-none transition-all duration-1000"
          style={{ width: `${Math.min(95, (floodX / 500) * 100)}%` }}
        >
          <div className="absolute right-2 top-2 bg-cyan-900/90 text-cyan-200 text-[10px] font-mono px-1.5 py-0.5 rounded border border-cyan-600">
            Flood Front ({Math.round(floodX)}m)
          </div>
        </div>

        {/* Coarse Grid Cells */}
        <div className="absolute inset-0 grid grid-cols-4 grid-rows-4 pointer-events-none opacity-20">
          {Array.from({ length: 16 }).map((_, i) => (
            <div key={i} className="border border-white/40 flex items-center justify-center text-[10px] text-white font-mono">
              CELL-{Math.floor(i / 4)}:{i % 4}
            </div>
          ))}
        </div>

        {/* Entities (Coarse Positions Only) */}
        {filteredEntities.map((ent) => {
          const pos = ent.coarse_pos || [200, 200];
          // Map 0..500 coords to percentage
          const leftPct = Math.min(92, Math.max(8, (pos[0] / 500) * 100));
          const topPct = Math.min(92, Math.max(8, (pos[1] / 500) * 100));
          const isDistress = ent.type === 'distress_call';
          const isShelter = ent.type === 'safe_shelter';

          return (
            <button
              key={ent.id}
              type="button"
              onClick={() => setSelectedEntity(ent)}
              style={{ left: `${leftPct}%`, top: `${topPct}%` }}
              className={`absolute -translate-x-1/2 -translate-y-1/2 p-2 rounded-full shadow-lg transition-transform hover:scale-125 focus:outline-none ${
                isDistress
                  ? 'bg-red-600 text-white animate-pulse'
                  : isShelter
                  ? 'bg-green-600 text-white'
                  : 'bg-teal-600 text-white'
              }`}
            >
              <span className="material-symbols-outlined text-[20px] block">
                {isDistress ? 'emergency' : isShelter ? 'night_shelter' : 'medical_services'}
              </span>
            </button>
          );
        })}

        {/* Selected Entity Popup Overlay */}
        {selectedEntity && (
          <div className="absolute bottom-3 left-3 right-3 bg-white/95 backdrop-blur-md rounded-2xl p-3 shadow-xl border border-white text-[#1a1c1e] text-xs space-y-1 z-30">
            <div className="flex items-center justify-between">
              <span className="font-bold text-sm text-[#004546]">
                {selectedEntity.title}
              </span>
              <button
                type="button"
                onClick={() => setSelectedEntity(null)}
                className="text-[#3f4948] hover:text-black"
              >
                <span className="material-symbols-outlined text-[18px]">close</span>
              </button>
            </div>
            <div className="font-mono text-[#3f4948]">
              Cell: {selectedEntity.cell || '0:0'} | Coarse: [{selectedEntity.coarse_pos?.join(', ')}]
            </div>
            {selectedEntity.type === 'distress_call' && (
              <div className="text-amber-800 bg-amber-50 p-1.5 rounded font-medium">
                🔒 M9 Protected: Exact victim location sealed until accepted.
              </div>
            )}
          </div>
        )}
      </div>

      {/* Flood & Safe Elevation Card */}
      <div className="bg-white p-4 rounded-2xl border border-[#e8e8ea] shadow-sm space-y-2">
        <div className="flex items-center justify-between text-xs font-bold text-[#3f4948] uppercase">
          <span>Terrain & Flood Line</span>
          <span className="text-[#004546]">M4 HAZARD ADAPTIVE</span>
        </div>
        <p className="text-[14px] text-[#1a1c1e] leading-snug">
          Flood waters currently advancing at 0.05 m/s. Low ground in Sector 0 inundated ({Math.round(floodLevelM * 100)} cm).
          Relief shelters in Sector 1 remain on elevated safe ground.
        </p>
      </div>

      {/* Coarse Cells List */}
      <div className="space-y-2">
        <h3 className="text-[18px] font-bold text-[#1a1c1e]">
          Hazard Risk by Cell
        </h3>
        <div className="grid grid-cols-2 gap-2">
          {hazardCells.slice(0, 6).map((c) => (
            <div
              key={c.cell}
              className={`p-3 rounded-xl border text-xs space-y-0.5 ${
                c.is_water
                  ? 'bg-cyan-50 border-cyan-200 text-cyan-900'
                  : 'bg-white border-[#e8e8ea] text-[#1a1c1e]'
              }`}
            >
              <div className="font-bold font-mono flex items-center justify-between">
                <span>{c.cell}</span>
                <span>Risk: {Math.round(c.risk * 100)}%</span>
              </div>
              <div className="text-[11px] text-[#3f4948]">
                {c.is_water ? `Water Depth: ${c.depth_cm} cm` : 'Dry Ground (Safe)'}
              </div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
};
