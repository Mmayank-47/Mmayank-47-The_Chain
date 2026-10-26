import React, { useState, useEffect } from 'react';
import { api } from '../../api/client';
import { EmergencyProfile, Language } from '../../types';

interface MeScreenProps {
  currentLanguage: Language;
  onLanguageChange: (lang: Language) => void;
}

export const MeScreen: React.FC<MeScreenProps> = ({
  currentLanguage,
  onLanguageChange,
}) => {
  const [profile, setProfile] = useState<EmergencyProfile>({
    fullName: 'Suman Kulkarni',
    phoneMeshId: 'node-me',
    bloodGroup: 'O+ (Positive)',
    chronicConditions: ['Hypertension (BP)', 'Diabetic'],
    emergencyContact: '+91 98201 54321',
    lowBatterySaver: false,
  });

  const [fullName, setFullName] = useState(profile.fullName);
  const [bloodGroup, setBloodGroup] = useState(profile.bloodGroup);
  const [conditions, setConditions] = useState(profile.chronicConditions.join(', '));
  const [emergencyPhone, setEmergencyPhone] = useState(profile.emergencyContact);
  const [lowPowerMode, setLowPowerMode] = useState(profile.lowBatterySaver);
  const [savedToast, setSavedToast] = useState(false);
  const [isSaving, setIsSaving] = useState(false);

  useEffect(() => {
    const fetchProfile = async () => {
      try {
        const data = await api.getProfile();
        setProfile(data);
        setFullName(data.fullName);
        setBloodGroup(data.bloodGroup);
        setConditions(data.chronicConditions.join(', '));
        setEmergencyPhone(data.emergencyContact);
        setLowPowerMode(data.lowBatterySaver);
      } catch (err) {
        console.error('Failed to load profile:', err);
      }
    };
    fetchProfile();
  }, []);

  const handleSave = async () => {
    setIsSaving(true);
    try {
      const updated = await api.updateProfile({
        fullName,
        bloodGroup,
        chronicConditions: conditions.split(',').map((s) => s.trim()).filter(Boolean),
        emergencyContact: emergencyPhone,
        lowBatterySaver: lowPowerMode,
      });
      setProfile(updated);
      setSavedToast(true);
      setTimeout(() => setSavedToast(false), 2500);
    } catch (err: any) {
      alert(`Save error: ${err.message}`);
    } finally {
      setIsSaving(false);
    }
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
          <span>Emergency Profile Persisted via NodePersistence</span>
        </div>
      )}

      {/* Identity Card */}
      <div className="bg-white rounded-2xl p-4 border border-[#e8e8ea] shadow-sm space-y-3">
        <div className="flex items-center gap-3">
          <div className="w-14 h-14 rounded-full bg-[#004546] text-white flex items-center justify-center text-[22px] font-bold">
            {fullName
              .split(' ')
              .map((n) => n[0])
              .join('')
              .slice(0, 2)
              .toUpperCase()}
          </div>
          <div>
            <h3 className="text-[18px] font-bold text-[#1a1c1e]">{fullName}</h3>
            <span className="text-[12px] font-mono text-[#3f4948] block">
              Node ID: {profile.phoneMeshId || 'node-me'}
            </span>
          </div>
        </div>

        {/* Cryptographic Key Security Badge */}
        <div className="p-3 bg-[#f3f3f6] rounded-xl text-[12px] text-[#3f4948] font-mono flex items-center justify-between">
          <span>Hardware Fleet Key: M1 DevProvider</span>
          <span className="text-[#1e6b47] font-bold flex items-center gap-1">
            <span className="material-symbols-outlined text-[16px]">verified</span>
            Enrolled
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

        <div className="space-y-3">
          <div>
            <label className="text-[12px] font-bold text-[#3f4948] uppercase">
              Full Name
            </label>
            <input
              type="text"
              value={fullName}
              onChange={(e) => setFullName(e.target.value)}
              className="w-full bg-[#f3f3f6] border border-[#bec9c8] rounded-xl p-3 text-[15px] font-bold text-[#1a1c1e] mt-1"
            />
          </div>

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
              Chronic Conditions & Allergies
            </label>
            <input
              type="text"
              value={conditions}
              onChange={(e) => setConditions(e.target.value)}
              className="w-full bg-[#f3f3f6] border border-[#bec9c8] rounded-xl p-3 text-[15px] text-[#1a1c1e] mt-1 font-medium"
            />
          </div>

          <div>
            <label className="text-[12px] font-bold text-[#3f4948] uppercase">
              Emergency Contact Phone
            </label>
            <input
              type="text"
              value={emergencyPhone}
              onChange={(e) => setEmergencyPhone(e.target.value)}
              className="w-full bg-[#f3f3f6] border border-[#bec9c8] rounded-xl p-3 text-[15px] text-[#1a1c1e] mt-1 font-medium"
            />
          </div>

          {/* Battery Saver Toggle */}
          <div className="flex items-center justify-between p-3 rounded-xl bg-[#f3f3f6] mt-2">
            <div className="space-y-0.5">
              <span className="text-[14px] font-bold text-[#1a1c1e] block">
                Aggressive Battery Conservation
              </span>
              <span className="text-[12px] text-[#3f4948] block">
                Adjust beacon interval dynamically based on M6 battery drain model.
              </span>
            </div>
            <input
              type="checkbox"
              checked={lowPowerMode}
              onChange={(e) => setLowPowerMode(e.target.checked)}
              className="w-5 h-5 accent-[#004546] rounded"
            />
          </div>
        </div>

        {/* Save Button */}
        <button
          type="button"
          onClick={handleSave}
          disabled={isSaving}
          className="w-full min-h-[56px] bg-[#004546] text-white rounded-xl font-bold text-[16px] hover:bg-[#005f60] active:scale-[0.99] transition-all flex items-center justify-center gap-2 mt-4"
        >
          {isSaving ? (
            <>
              <span className="material-symbols-outlined animate-spin text-[20px]">
                sync
              </span>
              <span>Saving to device...</span>
            </>
          ) : (
            <>
              <span className="material-symbols-outlined text-[20px]">save</span>
              <span>Save Emergency Profile</span>
            </>
          )}
        </button>
      </div>

      {/* Language Switch Section */}
      <div className="bg-white rounded-2xl p-4 border border-[#e8e8ea] shadow-sm space-y-2">
        <h3 className="text-[16px] font-bold text-[#1a1c1e]">Interface & Evaluation Language</h3>
        <p className="text-[13px] text-[#3f4948]">
          Drives backend M2 parser and M10 natural-language justification generation.
        </p>
        <div className="grid grid-cols-3 gap-2 pt-1">
          {(['EN', 'HI', 'MR'] as Language[]).map((lang) => (
            <button
              key={lang}
              type="button"
              onClick={() => onLanguageChange(lang)}
              className={`p-3 rounded-xl font-bold text-[14px] transition-colors ${
                currentLanguage === lang
                  ? 'bg-[#004546] text-white shadow-sm'
                  : 'bg-[#e8e8ea] text-[#3f4948] hover:bg-[#eeeef0]'
              }`}
            >
              {lang === 'EN' ? 'English' : lang === 'HI' ? 'हिंदी (Hindi)' : 'मराठी (Marathi)'}
            </button>
          ))}
        </div>
      </div>
    </div>
  );
};
