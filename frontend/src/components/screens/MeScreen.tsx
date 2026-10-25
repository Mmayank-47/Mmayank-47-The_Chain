import React, { useState } from 'react';
import { Language } from '../../types';

interface MeScreenProps {
  currentLanguage: Language;
  onLanguageChange: (lang: Language) => void;
}

export const MeScreen: React.FC<MeScreenProps> = ({
  currentLanguage,
  onLanguageChange,
}) => {
  const [bloodGroup, setBloodGroup] = useState('O+ (Positive)');
  const [allergies, setAllergies] = useState('Penicillin, Sulfa drugs');
  const [conditions, setConditions] = useState('Hypertension (BP), Diabetic');
  const [emergencyPhone, setEmergencyPhone] = useState('+91 98201 54321 (Daughter Anita)');
  const [lowPowerMode, setLowPowerMode] = useState(false);
  const [savedToast, setSavedToast] = useState(false);

  const handleSave = () => {
    setSavedToast(true);
    setTimeout(() => setSavedToast(false), 2000);
  };

  return (
    <div className="flex flex-col w-full pb-8 space-y-4 max-w-screen-sm mx-auto">
      {/* Header */}
      <div className="space-y-1 pt-1">
        <div className="flex items-center justify-between">
          <h2 className="text-[28px] leading-[36px] font-bold text-[#1a1c1e] tracking-tight">
            Emergency Identity
          </h2>
          <span className="text-[12px] font-bold px-2 py-0.5 rounded-full bg-[#eeeef0] text-[#004546]">
            STORED ON-DEVICE
          </span>
        </div>
        <p className="text-[16px] text-[#3f4948]">
          Critical medical profile accessible to verified first-responders offline.
        </p>
      </div>

      {savedToast && (
        <div className="bg-[#1e6b47] text-white p-3 rounded-xl text-center font-bold text-[14px] flex items-center justify-center gap-2">
          <span className="material-symbols-outlined text-[18px]">check</span>
          <span>Emergency Profile Updated Locally</span>
        </div>
      )}

      {/* Identity Card */}
      <div className="bg-white rounded-2xl p-4 border border-[#e8e8ea] shadow-sm space-y-3">
        <div className="flex items-center gap-3">
          <div className="w-14 h-14 rounded-full bg-[#004546] text-white flex items-center justify-center text-[22px] font-bold">
            SK
          </div>
          <div>
            <h3 className="text-[18px] font-bold text-[#1a1c1e]">
              Suman Kulkarni
            </h3>
            <span className="text-[12px] font-mono text-[#3f4948] block">
              Node ID: MESH-IN-MH-7092A
            </span>
          </div>
        </div>

        {/* Offline Cryptographic Signature */}
        <div className="p-3 bg-[#f3f3f6] rounded-xl text-[12px] text-[#3f4948] font-mono flex items-center justify-between">
          <span>ECDSA Mesh Key: 0x4B9F...8E21</span>
          <span className="text-[#1e6b47] font-bold flex items-center gap-1">
            <span className="material-symbols-outlined text-[16px]">verified</span>
            Verified
          </span>
        </div>
      </div>

      {/* Medical Profile Section */}
      <div className="bg-white rounded-2xl p-4 border border-[#e8e8ea] shadow-sm space-y-3">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <span className="material-symbols-outlined text-[#004546] text-[22px]">
              medical_information
            </span>
            <h3 className="text-[18px] font-bold text-[#1a1c1e]">Medical SOS Card</h3>
          </div>
          <span className="text-[12px] text-[#3f4948]">Editable</span>
        </div>

        <div className="space-y-2.5">
          <div>
            <label className="text-[12px] font-bold text-[#3f4948] uppercase">
              Blood Group
            </label>
            <input
              type="text"
              value={bloodGroup}
              onChange={(e) => setBloodGroup(e.target.value)}
              className="w-full bg-[#f3f3f6] border border-[#bec9c8] rounded-xl p-3 text-[15px] font-bold text-[#1a1c1e] mt-1"
            />
          </div>

          <div>
            <label className="text-[12px] font-bold text-[#3f4948] uppercase">
              Allergies
            </label>
            <input
              type="text"
              value={allergies}
              onChange={(e) => setAllergies(e.target.value)}
              className="w-full bg-[#f3f3f6] border border-[#bec9c8] rounded-xl p-3 text-[15px] text-[#1a1c1e] mt-1"
            />
          </div>

          <div>
            <label className="text-[12px] font-bold text-[#3f4948] uppercase">
              Chronic Conditions & Routine Medications
            </label>
            <input
              type="text"
              value={conditions}
              onChange={(e) => setConditions(e.target.value)}
              className="w-full bg-[#f3f3f6] border border-[#bec9c8] rounded-xl p-3 text-[15px] text-[#1a1c1e] mt-1"
            />
          </div>

          <div>
            <label className="text-[12px] font-bold text-[#3f4948] uppercase">
              Emergency Contact (ICE)
            </label>
            <input
              type="text"
              value={emergencyPhone}
              onChange={(e) => setEmergencyPhone(e.target.value)}
              className="w-full bg-[#f3f3f6] border border-[#bec9c8] rounded-xl p-3 text-[15px] text-[#1a1c1e] mt-1"
            />
          </div>
        </div>

        <button
          type="button"
          onClick={handleSave}
          className="w-full py-3 bg-[#004546] text-white rounded-xl text-[16px] font-bold hover:bg-[#005f60] transition-colors"
        >
          Save Emergency Card
        </button>
      </div>

      {/* Device & Radio Power Settings */}
      <div className="bg-white rounded-2xl p-4 border border-[#e8e8ea] shadow-sm space-y-3">
        <h3 className="text-[16px] font-bold text-[#1a1c1e]">
          Emergency Radio & Power Mode
        </h3>

        <div className="flex items-center justify-between py-1">
          <div>
            <h4 className="text-[15px] font-bold text-[#1a1c1e]">
              Ultra Low-Battery Mesh Throttling
            </h4>
            <p className="text-[13px] text-[#3f4948]">
              Limits background relay checks to save phone battery during floods.
            </p>
          </div>
          <button
            type="button"
            onClick={() => setLowPowerMode(!lowPowerMode)}
            className={`w-14 h-8 rounded-full transition-colors relative p-1 ${
              lowPowerMode ? 'bg-[#004546]' : 'bg-[#e2e2e5]'
            }`}
          >
            <div
              className={`w-6 h-6 rounded-full bg-white transition-transform ${
                lowPowerMode ? 'translate-x-6' : 'translate-x-0'
              }`}
            />
          </button>
        </div>

        <div className="pt-2 border-t border-[#eeeef0]">
          <label className="text-[13px] font-bold text-[#3f4948] block mb-2">
            Language / भाषा / भाषा
          </label>
          <div className="grid grid-cols-3 gap-2">
            {[
              { code: 'EN', name: 'English' },
              { code: 'HI', name: 'हिन्दी' },
              { code: 'MR', name: 'मराठी' },
            ].map((lang) => (
              <button
                key={lang.code}
                type="button"
                onClick={() => onLanguageChange(lang.code as Language)}
                className={`py-2 rounded-xl text-[14px] font-bold border transition-colors ${
                  currentLanguage === lang.code
                    ? 'border-[#004546] bg-[#a6eff0] text-[#002020]'
                    : 'border-[#bec9c8] bg-white text-[#3f4948]'
                }`}
              >
                {lang.name}
              </button>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
};
