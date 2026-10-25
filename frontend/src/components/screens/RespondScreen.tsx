import React, { useState } from 'react';
import { DistressRequest } from '../../types';

export const RespondScreen: React.FC = () => {
  const [filter, setFilter] = useState<'all' | 'medicine' | 'water' | 'rescue' | 'elderly'>('all');
  const [acceptedIds, setAcceptedIds] = useState<string[]>([]);

  const mockRequests: DistressRequest[] = [
    {
      id: 'req-101',
      senderName: 'Neighbor (Lane 4)',
      needCategory: 'medicine',
      rawText: 'Mother needs bp medicine (Amlodipine 5mg), Shivaji Nagar Lane 4',
      parsed: {
        need: 'medicine',
        quantity: '2 strips',
        person: 'elderly',
        near: 'Shivaji Nagar Lane 4',
        language: 'Hindi',
      },
      timestamp: '3m ago',
      status: 'relaying',
      hopCount: 1,
      relayedNodesCount: 4,
      distanceMeters: 65,
      urgency: 'critical',
      approxLocation: 'Shivaji Nagar Lane 4 (~60m radius)',
    },
    {
      id: 'req-102',
      senderName: 'Resident #41',
      needCategory: 'water',
      rawText: 'Potable drinking water for 3 children. Tap water contaminated by flood.',
      parsed: {
        need: 'drinking water',
        quantity: '10 litres',
        person: 'children (3)',
        near: 'Near Ganesh Temple, Shivaji Nagar',
        language: 'English',
      },
      timestamp: '8m ago',
      status: 'relaying',
      hopCount: 2,
      relayedNodesCount: 6,
      distanceMeters: 140,
      urgency: 'high',
      approxLocation: 'Ganesh Temple area (~120m radius)',
    },
    {
      id: 'req-103',
      senderName: 'Family Patil',
      needCategory: 'elderly',
      rawText: 'Grandfather oxygen concentrator battery dying, need backup cylinder or 220V inverter power.',
      parsed: {
        need: 'oxygen / backup power',
        quantity: '1 unit / battery',
        person: 'elderly patient',
        near: 'Behind Post Office, Lane 2',
        language: 'Marathi',
      },
      timestamp: '12m ago',
      status: 'relaying',
      hopCount: 2,
      relayedNodesCount: 8,
      distanceMeters: 210,
      urgency: 'critical',
      approxLocation: 'Old Post Office Lane (~150m radius)',
    },
    {
      id: 'req-104',
      senderName: 'Shopkeeper Ramesh',
      needCategory: 'food',
      rawText: 'Extra dry biscuits and baby milk formula available for stranded families.',
      parsed: {
        need: 'dry rations (available)',
        quantity: '15 packs',
        person: 'general community',
        near: 'Shree Provision Store, Main Bazaar',
        language: 'Hindi',
      },
      timestamp: '18m ago',
      status: 'relaying',
      hopCount: 3,
      relayedNodesCount: 12,
      distanceMeters: 380,
      urgency: 'moderate',
      approxLocation: 'Main Bazaar Road',
    },
  ];

  const filteredRequests = mockRequests.filter((req) => {
    if (filter === 'all') return true;
    return req.needCategory === filter;
  });

  const handleToggleAccept = (id: string) => {
    setAcceptedIds((prev) =>
      prev.includes(id) ? prev.filter((item) => item !== id) : [...prev, id]
    );
  };

  return (
    <div className="flex flex-col w-full pb-8 space-y-4 max-w-screen-sm mx-auto">
      {/* Screen Heading */}
      <div className="space-y-1 pt-1">
        <div className="flex items-center justify-between">
          <h2 className="text-[28px] leading-[36px] font-bold text-[#1a1c1e] tracking-tight">
            Nearby Help Requests
          </h2>
          <span className="text-[12px] font-bold px-2 py-0.5 rounded-full bg-[#ffdcbd] text-[#2c1600]">
            OFFLINE MESH FEED
          </span>
        </div>
        <p className="text-[16px] text-[#3f4948]">
          Distress signals relayed from nearby phones within your cluster.
        </p>
      </div>

      {/* Filter Tabs */}
      <div className="flex items-center gap-1.5 overflow-x-auto no-scrollbar py-1">
        {[
          { key: 'all', label: 'All (4)' },
          { key: 'medicine', label: 'Medicine' },
          { key: 'water', label: 'Water' },
          { key: 'elderly', label: 'Elderly' },
        ].map((tab) => {
          const isActive = filter === tab.key;
          return (
            <button
              key={tab.key}
              type="button"
              onClick={() => setFilter(tab.key as any)}
              className={`px-3.5 py-2 rounded-xl text-[14px] font-bold whitespace-nowrap transition-colors ${
                isActive
                  ? 'bg-[#004546] text-white shadow-sm'
                  : 'bg-[#e8e8ea] text-[#3f4948] hover:bg-[#eeeef0]'
              }`}
            >
              {tab.label}
            </button>
          );
        })}
      </div>

      {/* Distress Cards Feed */}
      <div className="flex flex-col gap-3">
        {filteredRequests.map((req) => {
          const isAccepted = acceptedIds.includes(req.id);
          const isCritical = req.urgency === 'critical';

          return (
            <div
              key={req.id}
              className={`bg-white rounded-2xl p-4 border transition-all shadow-sm space-y-3 ${
                isAccepted
                  ? 'border-[#1e6b47] bg-[#f0fdf4]'
                  : isCritical
                  ? 'border-[#9e2a2b]/30'
                  : 'border-[#e8e8ea]'
              }`}
            >
              {/* Card Header */}
              <div className="flex items-start justify-between">
                <div>
                  <div className="flex items-center gap-2">
                    <span
                      className={`text-[11px] font-bold px-2 py-0.5 rounded uppercase tracking-wider ${
                        isCritical
                          ? 'bg-[#ffdad6] text-[#93000a]'
                          : 'bg-[#ffdcbd] text-[#6e4000]'
                      }`}
                    >
                      {req.urgency}
                    </span>
                    <span className="text-[13px] font-mono text-[#3f4948]">
                      {req.distanceMeters}m away · {req.hopCount} {req.hopCount === 1 ? 'hop' : 'hops'}
                    </span>
                  </div>
                  <h3 className="text-[18px] font-bold text-[#1a1c1e] mt-1 capitalize">
                    {req.parsed.need} ({req.parsed.quantity})
                  </h3>
                </div>

                <span className="text-[12px] font-medium text-[#3f4948]">
                  {req.timestamp}
                </span>
              </div>

              {/* Raw distress text quote */}
              <div className="p-3 bg-[#f3f3f6] rounded-xl text-[15px] text-[#1a1c1e] italic border-l-3 border-[#004546]">
                "{req.rawText}"
              </div>

              {/* Parsed Chips Mini-Grid */}
              <div className="grid grid-cols-2 gap-1.5 text-[13px]">
                <div className="bg-[#eeeef0] px-2.5 py-1.5 rounded-lg flex items-center justify-between">
                  <span className="text-[#3f4948]">For:</span>
                  <span className="font-bold text-[#1a1c1e]">{req.parsed.person}</span>
                </div>
                <div className="bg-[#eeeef0] px-2.5 py-1.5 rounded-lg flex items-center justify-between">
                  <span className="text-[#3f4948]">Area:</span>
                  <span className="font-bold text-[#1a1c1e] truncate ml-1">{req.parsed.near}</span>
                </div>
              </div>

              {/* Privacy Location Tag */}
              <div className="flex items-center gap-1.5 text-[12px] text-[#3f4948]">
                <span className="material-symbols-outlined text-[16px] text-[#004546]">
                  location_on
                </span>
                <span>{req.approxLocation}</span>
              </div>

              {/* Action Buttons */}
              <div className="flex items-center gap-2 pt-1">
                <button
                  type="button"
                  onClick={() => handleToggleAccept(req.id)}
                  className={`flex-1 min-h-[52px] rounded-xl font-bold text-[16px] flex items-center justify-center gap-2 transition-colors ${
                    isAccepted
                      ? 'bg-[#1e6b47] text-white'
                      : 'bg-[#004546] text-white hover:bg-[#005f60]'
                  }`}
                >
                  <span className="material-symbols-outlined text-[20px]">
                    {isAccepted ? 'check_circle' : 'handshake'}
                  </span>
                  <span>{isAccepted ? 'Accepted by You' : 'Accept Request'}</span>
                </button>

                <button
                  type="button"
                  title="Forward over mesh"
                  className="min-w-[52px] min-h-[52px] rounded-xl bg-[#e8e8ea] text-[#004546] flex items-center justify-center hover:bg-[#eeeef0] transition-colors"
                >
                  <span className="material-symbols-outlined text-[22px]">
                    share
                  </span>
                </button>
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
};
