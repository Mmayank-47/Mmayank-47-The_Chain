import React, { useState, useEffect, useRef, useCallback } from 'react';
import { api } from '../../api/client';
import { ClaimAnalysisResponse, Language, ParsedSituation } from '../../types';

interface HelpScreenStep2Props {
  currentLanguage?: Language;
  onBack: () => void;
  onSendHelpRequest: (
    rawText: string,
    parsed: ParsedSituation,
    claimId?: string,
    analysis?: ClaimAnalysisResponse
  ) => void;
}

export const HelpScreenStep2: React.FC<HelpScreenStep2Props> = ({
  currentLanguage = 'EN',
  onBack,
  onSendHelpRequest,
}) => {
  const [situationText, setSituationText] = useState(
    'Mother needs bp medicine, Shivaji Nagar Lane 4'
  );
  const [isListening, setIsListening] = useState(false);
  const [isAnalyzing, setIsAnalyzing] = useState(false);
  const [isBroadcasting, setIsBroadcasting] = useState(false);
  const [broadcastSuccess, setBroadcastSuccess] = useState(false);

  // Analysis result from real backend
  const [analysis, setAnalysis] = useState<ClaimAnalysisResponse | null>(null);

  // Parsed situation fields (synced with backend analysis)
  const [parsed, setParsed] = useState<ParsedSituation>({
    kind: 'claim',
    capability_tags: ['medic'],
    features: ['severe_bleeding'],
    quantity: '2 strips',
    location_text: 'Shivaji Nagar Lane 4',
    language: 'Hindi',
    confidence: 0.85,
    urgency: 0.72,
    need: 'medicine',
    person: 'elderly',
    near: 'Shivaji Nagar Lane 4',
  });

  // Modal for editing parsed chip
  const [editingField, setEditingField] = useState<{
    key: keyof ParsedSituation;
    label: string;
    value: string;
  } | null>(null);

  const [toastMessage, setToastMessage] = useState<string | null>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  const showToast = (msg: string) => {
    setToastMessage(msg);
    setTimeout(() => setToastMessage(null), 2500);
  };

  // Debounced backend claim analysis via M2..M10 chain
  const analyzeText = useCallback(
    async (text: string) => {
      if (!text.trim() || text.length < 3) return;
      setIsAnalyzing(true);
      try {
        const langCode = currentLanguage.toLowerCase();
        const res = await api.analyzeClaim(text, langCode);
        setAnalysis(res);
        setParsed({
          ...res.parsed,
          need: res.parsed.capability_tags?.[0] || 'general aid',
          person: res.parsed.features?.[0] || 'individual',
          near: res.parsed.location_text || 'Nearby cell',
        });
      } catch (err: any) {
        console.error('Failed to analyze claim:', err);
      } finally {
        setIsAnalyzing(false);
      }
    },
    [currentLanguage]
  );

  // Trigger analysis when text or language changes (debounced by 400ms)
  useEffect(() => {
    const timer = setTimeout(() => {
      analyzeText(situationText);
    }, 400);
    return () => clearTimeout(timer);
  }, [situationText, analyzeText]);

  // Real Web Speech API
  useEffect(() => {
    let recognition: any = null;
    const SpeechRecognition =
      (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition;

    if (isListening && SpeechRecognition) {
      try {
        recognition = new SpeechRecognition();
        recognition.continuous = false;
        recognition.interimResults = true;
        recognition.lang =
          currentLanguage === 'HI' ? 'hi-IN' : currentLanguage === 'MR' ? 'mr-IN' : 'en-IN';

        recognition.onresult = (event: any) => {
          const transcript = Array.from(event.results)
            .map((result: any) => result[0].transcript)
            .join('');
          if (transcript) {
            setSituationText(transcript);
          }
        };

        recognition.onerror = (e: any) => {
          console.warn('Speech recognition error:', e);
          setIsListening(false);
          showToast('Speech recognition unavailable. Please type your situation.');
        };

        recognition.onend = () => {
          setIsListening(false);
        };

        recognition.start();
      } catch (err) {
        setIsListening(false);
        showToast('Speech recognition not supported in this browser.');
      }
    } else if (isListening && !SpeechRecognition) {
      setIsListening(false);
      showToast('Speech recognition is not supported in this browser.');
    }

    return () => {
      if (recognition) {
        try {
          recognition.abort();
        } catch {}
      }
    };
  }, [isListening, currentLanguage]);

  const handleClear = () => {
    setSituationText('');
    setAnalysis(null);
    if (textareaRef.current) {
      textareaRef.current.focus();
    }
  };

  const handleEditSave = () => {
    if (!editingField) return;
    setParsed((prev) => ({
      ...prev,
      [editingField.key]: editingField.value,
    }));
    showToast(`Updated ${editingField.label} to "${editingField.value}"`);
    setEditingField(null);
  };

  const handleSend = async () => {
    if (!situationText.trim()) {
      showToast('Please enter your situation details');
      return;
    }

    setIsBroadcasting(true);
    try {
      let claimId = analysis?.claim_id;
      if (!claimId) {
        const langCode = currentLanguage.toLowerCase();
        const res = await api.analyzeClaim(situationText, langCode);
        claimId = res.claim_id;
        setAnalysis(res);
      }

      // Broadcast onto local CRDT log & gossip transport
      await api.broadcastClaim(claimId);

      setIsBroadcasting(false);
      setBroadcastSuccess(true);

      setTimeout(() => {
        onSendHelpRequest(situationText, parsed, claimId, analysis || undefined);
      }, 500);
    } catch (err: any) {
      setIsBroadcasting(false);
      showToast(`Broadcast failed: ${err.message || 'Network error'}`);
    }
  };

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
        <button
          type="button"
          onClick={onBack}
          aria-label="Go back to previous screen"
          className="flex items-center gap-1 text-[#1a1c1e] min-h-[48px] px-2 -ml-1 rounded-full active:bg-[#eeeef0] transition-colors"
        >
          <span className="material-symbols-outlined text-[24px]">arrow_back</span>
          <span className="text-[18px] font-semibold">Back</span>
        </button>

        <div className="flex items-center gap-1.5 bg-[#eeeef0] px-3 py-1 rounded-full">
          <span className="w-2 h-2 rounded-full bg-[#004546]" />
          <span className="text-[14px] font-semibold text-[#3f4948]">Step 2 of 3</span>
        </div>
      </div>

      {/* Screen Heading */}
      <div className="space-y-1">
        <h2 className="text-[28px] leading-[36px] font-bold text-[#1a1c1e] tracking-tight">
          Describe your situation
        </h2>
        <p className="text-[16px] leading-[24px] text-[#3f4948]">
          Type or speak plainly. Evaluated by on-device M2 parser and M3 verifier.
        </p>
      </div>

      {/* Large Input & Voice Section */}
      <div className="flex flex-col bg-white rounded-2xl p-4 shadow-sm border border-[#e8e8ea] space-y-4">
        <div className="relative w-full">
          <label className="sr-only" htmlFor="situation-input">
            Describe what you need
          </label>
          <textarea
            ref={textareaRef}
            id="situation-input"
            rows={3}
            value={situationText}
            onChange={(e) => setSituationText(e.target.value)}
            placeholder="Tell us what you need, in English, Hindi, or Marathi"
            className="w-full bg-[#f3f3f6] rounded-xl p-4 text-[18px] leading-[26px] text-[#1a1c1e] placeholder:text-[#3f4948]/70 focus:outline-none focus:bg-[#eeeef0] resize-none border border-transparent focus:border-[#004546] transition-colors"
          />
          {isAnalyzing && (
            <div className="absolute right-3 bottom-3 flex items-center gap-1 text-xs text-[#004546] bg-white/90 px-2 py-0.5 rounded-full border border-[#e8e8ea]">
              <span className="material-symbols-outlined text-[14px] animate-spin">
                sync
              </span>
              <span>Evaluating...</span>
            </div>
          )}
        </div>

        {/* Voice Input Trigger & Clear button */}
        <div className="flex items-center justify-between gap-2 pt-0.5">
          <button
            type="button"
            id="voice-trigger-btn"
            onClick={() => setIsListening(!isListening)}
            className={`flex-1 flex items-center justify-center gap-2 min-h-[64px] px-4 rounded-xl transition-all ${
              isListening
                ? 'bg-[#ffdcbd] text-[#2c1600] border-2 border-[#895100]'
                : 'bg-[#e8e8ea] text-[#1a1c1e] hover:bg-[#eeeef0] active:bg-[#dadadc]'
            }`}
          >
            <span
              className="material-symbols-outlined text-[#004546] text-[28px]"
              style={{ fontVariationSettings: "'FILL' 1" }}
            >
              {isListening ? 'graphic_eq' : 'mic'}
            </span>
            <span className="text-[18px] font-semibold">
              {isListening ? 'Listening... speak now' : 'Speak details'}
            </span>
            {isListening && (
              <span className="w-2 h-2 rounded-full bg-[#ba1a1a] animate-ping ml-1" />
            )}
          </button>

          <button
            type="button"
            id="clear-text-btn"
            onClick={handleClear}
            aria-label="Clear typed text"
            className="min-w-[64px] min-h-[64px] flex items-center justify-center rounded-xl bg-[#e8e8ea] text-[#3f4948] hover:text-[#1a1c1e] active:bg-[#dadadc] transition-colors"
          >
            <span className="material-symbols-outlined text-[24px]">backspace</span>
          </button>
        </div>
      </div>

      {/* Extracted Interpretation Card ("We understood") */}
      <div className="flex flex-col bg-white rounded-2xl p-4 shadow-sm border border-[#e8e8ea] space-y-4">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-1.5">
            <span
              className="material-symbols-outlined text-[#004546] text-[24px]"
              style={{ fontVariationSettings: "'FILL' 1" }}
            >
              verified
            </span>
            <h3 className="text-[20px] font-bold text-[#1a1c1e]">Parsed Claim</h3>
          </div>
          <div className="flex items-center gap-1.5">
            {analysis && (
              <span className="text-[12px] font-bold bg-[#a6eff0] text-[#002020] px-2 py-0.5 rounded-full font-mono">
                p_valid: {Math.round(analysis.p_valid * 100)}%
              </span>
            )}
            <span className="text-[13px] font-semibold bg-[#eeeef0] px-2 py-0.5 rounded text-[#3f4948]">
              M2 Verified
            </span>
          </div>
        </div>

        {/* Editable Chips List */}
        <div className="flex flex-col gap-2.5">
          {/* Chip 1: Need / Capability */}
          <button
            type="button"
            onClick={() =>
              setEditingField({
                key: 'need',
                label: 'Need',
                value: parsed.need || parsed.capability_tags?.[0] || '',
              })
            }
            className="flex items-center justify-between min-h-[56px] w-full px-4 py-2 rounded-xl bg-[#f3f3f6] text-left hover:bg-[#eeeef0] active:bg-[#e8e8ea] transition-colors border border-transparent hover:border-[#bec9c8]"
          >
            <div className="flex items-center gap-2 truncate">
              <span className="text-[14px] font-semibold text-[#3f4948] min-w-[70px]">
                Need:
              </span>
              <span className="text-[18px] font-bold text-[#1a1c1e] truncate">
                {parsed.need || parsed.capability_tags?.join(', ') || 'Assistance'}
              </span>
            </div>
            <div className="flex items-center gap-1 text-[#004546] shrink-0 ml-2">
              <span className="material-symbols-outlined text-[20px]">edit</span>
            </div>
          </button>

          {/* Chip 2: Quantity */}
          <button
            type="button"
            onClick={() =>
              setEditingField({
                key: 'quantity',
                label: 'Quantity',
                value: parsed.quantity || '',
              })
            }
            className="flex items-center justify-between min-h-[56px] w-full px-4 py-2 rounded-xl bg-[#f3f3f6] text-left hover:bg-[#eeeef0] active:bg-[#e8e8ea] transition-colors border border-transparent hover:border-[#bec9c8]"
          >
            <div className="flex items-center gap-2 truncate">
              <span className="text-[14px] font-semibold text-[#3f4948] min-w-[70px]">
                Quantity:
              </span>
              <span className="text-[18px] font-bold text-[#1a1c1e] truncate">
                {parsed.quantity || '1 unit'}
              </span>
            </div>
            <div className="flex items-center gap-1 text-[#004546] shrink-0 ml-2">
              <span className="material-symbols-outlined text-[20px]">edit</span>
            </div>
          </button>

          {/* Chip 3: Location / Near */}
          <button
            type="button"
            onClick={() =>
              setEditingField({
                key: 'location_text',
                label: 'Location',
                value: parsed.location_text || parsed.near || '',
              })
            }
            className="flex items-center justify-between min-h-[56px] w-full px-4 py-2 rounded-xl bg-[#f3f3f6] text-left hover:bg-[#eeeef0] active:bg-[#e8e8ea] transition-colors border border-transparent hover:border-[#bec9c8]"
          >
            <div className="flex items-center gap-2 truncate">
              <span className="text-[14px] font-semibold text-[#3f4948] min-w-[70px]">
                Location:
              </span>
              <span className="text-[18px] font-bold text-[#1a1c1e] truncate">
                {parsed.location_text || parsed.near || 'Current mesh cell'}
              </span>
            </div>
            <div className="flex items-center gap-1 text-[#004546] shrink-0 ml-2">
              <span className="material-symbols-outlined text-[20px]">edit</span>
            </div>
          </button>
        </div>

        {/* Real Justification Preview from M10 */}
        {analysis?.justification && (
          <div className="p-3 bg-[#e8f5e9] border border-[#a5d6a7] rounded-xl space-y-1">
            <div className="flex items-center justify-between text-xs font-bold text-[#2e7d32]">
              <span className="uppercase">M10 Dispatch Rationale</span>
              <span className="px-2 py-0.5 rounded bg-white font-mono">
                Risk: {analysis.justification.risk.toUpperCase()}
              </span>
            </div>
            <p className="text-[14px] text-[#1b5e20] leading-snug">
              {analysis.justification.text}
            </p>
            {analysis.justification.abort && (
              <p className="text-[12px] text-[#b71c1c] font-semibold">
                ⚠️ Abort Criterion: {analysis.justification.abort}
              </p>
            )}
          </div>
        )}

        {/* Gentle reassurance line */}
        <div className="flex items-center gap-2 pt-1 text-[#3f4948]">
          <span className="material-symbols-outlined text-[18px]">info</span>
          <p className="text-[15px]">Tap any chip to edit before broadcasting.</p>
        </div>
      </div>

      {/* Location Privacy Protection Notice */}
      <div className="flex items-start gap-3 p-4 rounded-2xl bg-[#f3f3f6] border border-[#e8e8ea]">
        <div className="w-10 h-10 rounded-full bg-[#e2e2e5] flex items-center justify-center shrink-0 mt-0.5">
          <span className="material-symbols-outlined text-[#004546] text-[22px]">
            location_off
          </span>
        </div>
        <div className="space-y-0.5">
          <h4 className="text-[18px] font-bold text-[#1a1c1e]">
            M9 Two-Stage Privacy Active
          </h4>
          <p className="text-[15px] text-[#3f4948] leading-snug">
            Only a coarse cell is visible to the network. Exact coordinates remain sealed until a responder accepts.
          </p>
        </div>
      </div>

      {/* Primary Action Area */}
      <div className="space-y-2 pt-1">
        <button
          type="button"
          id="send-btn"
          disabled={isBroadcasting}
          onClick={handleSend}
          className="w-full min-h-[64px] bg-[#004546] text-white rounded-2xl flex items-center justify-center gap-2 px-4 shadow-md hover:bg-[#005f60] active:bg-[#003839] active:scale-[0.99] transition-all disabled:opacity-80"
        >
          {isBroadcasting ? (
            <>
              <span className="material-symbols-outlined animate-spin text-[26px]">
                progress_activity
              </span>
              <span className="text-[20px] font-bold">Broadcasting to mesh...</span>
            </>
          ) : broadcastSuccess ? (
            <>
              <span className="material-symbols-outlined text-[26px]">check_circle</span>
              <span className="text-[20px] font-bold">Beacon broadcasted!</span>
            </>
          ) : (
            <>
              <span className="text-[20px] font-bold">Broadcast Distress Beacon</span>
              <span className="material-symbols-outlined text-[26px]">cell_tower</span>
            </>
          )}
        </button>

        <div className="flex items-center justify-center gap-1.5 py-1 text-[#3f4948]">
          <span className="material-symbols-outlined text-[#004546] text-[18px]">
            hub
          </span>
          <p className="text-[14px] font-semibold text-center">
            Signed with hardware key & broadcasted peer-to-peer via Bluetooth & Wi-Fi
          </p>
        </div>
      </div>

      {/* Edit Modal Dialog */}
      {editingField && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4 backdrop-blur-sm">
          <div className="bg-white rounded-2xl w-full max-w-sm p-5 space-y-4 shadow-2xl border border-[#bec9c8]">
            <div className="flex items-center justify-between">
              <h3 className="text-[18px] font-bold text-[#1a1c1e]">
                Edit {editingField.label}
              </h3>
              <button
                type="button"
                onClick={() => setEditingField(null)}
                className="w-8 h-8 rounded-full flex items-center justify-center text-[#3f4948] hover:bg-[#eeeef0]"
              >
                <span className="material-symbols-outlined text-[20px]">close</span>
              </button>
            </div>

            <div className="space-y-1">
              <label className="text-[12px] font-semibold text-[#3f4948] uppercase tracking-wide">
                Parsed {editingField.label}
              </label>
              <input
                type="text"
                value={editingField.value}
                onChange={(e) =>
                  setEditingField({ ...editingField, value: e.target.value })
                }
                className="w-full bg-[#f3f3f6] border border-[#bec9c8] rounded-xl px-3 py-3 text-[16px] font-medium text-[#1a1c1e] focus:outline-none focus:border-[#004546]"
                autoFocus
              />
            </div>

            <div className="flex items-center justify-end gap-2 pt-2">
              <button
                type="button"
                onClick={() => setEditingField(null)}
                className="px-4 py-2.5 rounded-xl text-[15px] font-semibold text-[#3f4948] hover:bg-[#eeeef0]"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={handleEditSave}
                className="px-5 py-2.5 rounded-xl text-[15px] font-bold bg-[#004546] text-white hover:bg-[#005f60]"
              >
                Save
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
