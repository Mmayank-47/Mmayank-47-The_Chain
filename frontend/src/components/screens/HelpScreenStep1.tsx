import React from 'react';

interface HelpScreenStep1Props {
  onSelectCategory: (category: string, defaultPrompt: string) => void;
  onProceedToStep2: () => void;
}

export const HelpScreenStep1: React.FC<HelpScreenStep1Props> = ({
  onSelectCategory,
  onProceedToStep2,
}) => {
  const categories = [
    {
      id: 'medicine',
      title: 'Medicine & First Aid',
      desc: 'Blood pressure, insulin, pain relief, bandages',
      icon: 'prescriptions',
      badge: 'URGENT',
      defaultPrompt: 'Mother needs bp medicine, Shivaji Nagar Lane 4',
    },
    {
      id: 'water',
      title: 'Drinking Water',
      desc: 'Clean potable drinking water for family/infant',
      icon: 'water_drop',
      badge: 'CRITICAL',
      defaultPrompt: 'Need clean drinking water for 4 people, Lane 2 Shivaji Nagar',
    },
    {
      id: 'rescue',
      title: 'Evacuation & Rescue',
      desc: 'Flooding water rising, trapped on upper floor',
      icon: 'flood',
      badge: 'IMMEDIATE',
      defaultPrompt: 'Water rising rapidly, 3 people trapped on 1st floor, Lane 6',
    },
    {
      id: 'elderly',
      title: 'Elderly / Special Care',
      desc: 'Non-ambulatory senior or oxygen assistance',
      icon: 'elderly',
      badge: 'HIGH PRIORITY',
      defaultPrompt: 'Elderly grandfather unable to walk, needs stretcher transport',
    },
    {
      id: 'food',
      title: 'Emergency Food / Baby Milk',
      desc: 'Ready-to-eat rations, infant baby formula',
      icon: 'lunch_dining',
      badge: 'SUPPLY',
      defaultPrompt: 'Baby milk formula and dry rations needed, Sector 3',
    },
    {
      id: 'power',
      title: 'Power / Charging Medical Device',
      desc: 'Nebulizer, CPAP battery depleted',
      icon: 'power',
      badge: 'SUPPORT',
      defaultPrompt: 'Need 12V emergency power pack for nebulizer, Shivaji Nagar',
    },
  ];

  return (
    <div className="flex flex-col w-full pb-8 space-y-4 max-w-screen-sm mx-auto">
      {/* Progress Header */}
      <div className="flex items-center justify-between pt-1">
        <span className="text-[14px] font-bold text-[#004546] uppercase tracking-wide">
          Offline Mesh Emergency Beacon
        </span>
        <div className="flex items-center gap-1.5 bg-[#eeeef0] px-3 py-1 rounded-full">
          <span className="w-2 h-2 rounded-full bg-[#004546]" />
          <span className="text-[14px] font-semibold text-[#3f4948]">Step 1 of 3</span>
        </div>
      </div>

      {/* Headline */}
      <div className="space-y-1">
        <h2 className="text-[28px] leading-[36px] font-bold text-[#1a1c1e] tracking-tight">
          What do you need right now?
        </h2>
        <p className="text-[16px] text-[#3f4948]">
          Select the urgent category to prioritize mesh relay hops across local phones.
        </p>
      </div>

      {/* Category selection grid */}
      <div className="grid grid-cols-1 gap-2.5">
        {categories.map((cat) => (
          <button
            key={cat.id}
            type="button"
            onClick={() => {
              onSelectCategory(cat.id, cat.defaultPrompt);
              onProceedToStep2();
            }}
            className="flex items-center justify-between p-4 rounded-2xl bg-white border border-[#e8e8ea] hover:border-[#004546] hover:bg-[#f3f3f6] active:scale-[0.99] transition-all text-left shadow-sm min-h-[72px]"
          >
            <div className="flex items-center gap-3">
              <div className="w-12 h-12 rounded-xl bg-[#e8e8ea] flex items-center justify-center shrink-0">
                <span className="material-symbols-outlined text-[#004546] text-[28px]">
                  {cat.icon}
                </span>
              </div>
              <div>
                <div className="flex items-center gap-2">
                  <h3 className="text-[18px] font-bold text-[#1a1c1e]">{cat.title}</h3>
                  <span className="text-[11px] font-bold px-1.5 py-0.5 rounded bg-[#eeeef0] text-[#3f4948]">
                    {cat.badge}
                  </span>
                </div>
                <p className="text-[14px] text-[#3f4948] line-clamp-1">{cat.desc}</p>
              </div>
            </div>

            <span className="material-symbols-outlined text-[#004546] text-[24px]">
              chevron_right
            </span>
          </button>
        ))}
      </div>

      {/* Skip directly to freeform typing */}
      <div className="pt-2">
        <button
          type="button"
          onClick={onProceedToStep2}
          className="w-full min-h-[56px] border-2 border-[#004546] text-[#004546] bg-white rounded-2xl flex items-center justify-center gap-2 text-[18px] font-bold hover:bg-[#f3f3f6] active:bg-[#e8e8ea] transition-colors"
        >
          <span>Describe other emergency custom details</span>
          <span className="material-symbols-outlined text-[20px]">edit_note</span>
        </button>
      </div>
    </div>
  );
};
