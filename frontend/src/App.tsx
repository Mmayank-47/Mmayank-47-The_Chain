import React, { useState } from 'react';
import { Header } from './components/Header';
import { BottomNav, TabKey } from './components/BottomNav';
import { HelpScreenStep1 } from './components/screens/HelpScreenStep1';
import { HelpScreenStep2 } from './components/screens/HelpScreenStep2';
import { HelpScreenStep3 } from './components/screens/HelpScreenStep3';
import { RespondScreen } from './components/screens/RespondScreen';
import { MapScreen } from './components/screens/MapScreen';
import { MeshScreen } from './components/screens/MeshScreen';
import { MeScreen } from './components/screens/MeScreen';
import { AndroidPhoneFrame } from './components/AndroidPhoneFrame';
import { ComposeInspector } from './components/ComposeInspector';
import { TopStudioBar, ViewMode } from './components/TopStudioBar';
import { Language, ParsedSituation } from './types';

export default function App() {
  const [activeTab, setActiveTab] = useState<TabKey>('help');
  const [helpStep, setHelpStep] = useState<1 | 2 | 3>(2); // Step 2 is the exact screenshot screen
  const [viewMode, setViewMode] = useState<ViewMode>('phone');
  const [language, setLanguage] = useState<Language>('EN');
  const [batteryPercent] = useState<number>(62);
  const [peerCount, setPeerCount] = useState<number>(4);

  const [currentSituation, setCurrentSituation] = useState({
    rawText: 'Mother needs bp medicine, Shivaji Nagar Lane 4',
    parsed: {
      need: 'medicine',
      quantity: '2 strips',
      person: 'elderly',
      near: 'Shivaji Nagar',
      language: 'Hindi',
    } as ParsedSituation,
  });

  const [notification, setNotification] = useState<string | null>(null);

  const showNotification = (msg: string) => {
    setNotification(msg);
    setTimeout(() => setNotification(null), 3000);
  };

  const handleSimulateMeshHop = () => {
    setPeerCount((prev) => (prev >= 6 ? 4 : prev + 1));
    showNotification('Discovered new peer: Redmi 12 (+1 mesh node connected via BLE)');
  };

  const handleSelectNeedCategory = (_cat: string, defaultPrompt: string) => {
    setCurrentSituation((prev) => ({
      ...prev,
      rawText: defaultPrompt,
    }));
  };

  const handleSendHelpRequest = (rawText: string, parsed: ParsedSituation) => {
    setCurrentSituation({ rawText, parsed });
    setHelpStep(3); // Advance to Step 3 active broadcast
    showNotification('Distress beacon relayed to 4 neighboring phones');
  };

  // Section title mapping for header
  const getSectionTitle = () => {
    switch (activeTab) {
      case 'help':
        return 'Help';
      case 'respond':
        return 'Respond';
      case 'map':
        return 'Map';
      case 'mesh':
        return 'Mesh';
      case 'me':
        return 'Me';
      default:
        return 'The Chain';
    }
  };

  // Render active screen inside the phone shell
  const renderScreenContent = () => {
    if (activeTab === 'help') {
      if (helpStep === 1) {
        return (
          <HelpScreenStep1
            onSelectCategory={handleSelectNeedCategory}
            onProceedToStep2={() => setHelpStep(2)}
          />
        );
      }
      if (helpStep === 2) {
        return (
          <HelpScreenStep2
            onBack={() => setHelpStep(1)}
            onSendHelpRequest={handleSendHelpRequest}
          />
        );
      }
      return (
        <HelpScreenStep3
          rawText={currentSituation.rawText}
          parsed={currentSituation.parsed}
          onCancelBroadcast={() => setHelpStep(2)}
          onNewRequest={() => setHelpStep(1)}
        />
      );
    }

    if (activeTab === 'respond') {
      return <RespondScreen />;
    }

    if (activeTab === 'map') {
      return <MapScreen />;
    }

    if (activeTab === 'mesh') {
      return <MeshScreen />;
    }

    if (activeTab === 'me') {
      return (
        <MeScreen
          currentLanguage={language}
          onLanguageChange={(lang) => setLanguage(lang)}
        />
      );
    }

    return null;
  };

  // The mobile app experience
  const AppletInterface = (
    <div className="flex flex-col min-h-full bg-[#f9f9fc] text-[#1a1c1e] relative">
      <Header
        batteryPercent={batteryPercent}
        peerCount={peerCount}
        currentLanguage={language}
        onLanguageChange={setLanguage}
        onOpenProfile={() => setActiveTab('me')}
        sectionTitle={getSectionTitle()}
      />

      <main className="flex-1 w-full px-3 pt-3 pb-24 overflow-y-auto max-w-screen-sm mx-auto">
        {renderScreenContent()}
      </main>

      <BottomNav
        activeTab={activeTab}
        onTabChange={(tab) => setActiveTab(tab)}
        pendingResponseCount={3}
        isBroadcasting={helpStep === 3}
        peerCount={peerCount}
        batteryPercent={batteryPercent}
        onIslandClick={() => {
          setActiveTab('help');
          setHelpStep(3);
        }}
      />
    </div>
  );

  return (
    <div className="min-h-screen bg-[#0e1313] text-[#f0f0f3] flex flex-col font-sans">
      {/* Top Studio Controls */}
      <TopStudioBar
        viewMode={viewMode}
        onViewModeChange={setViewMode}
        currentLanguage={language}
        onLanguageChange={setLanguage}
        onSimulateHop={handleSimulateMeshHop}
      />

      {/* Global Notification Banner */}
      {notification && (
        <div className="bg-[#004546] text-[#a6eff0] px-4 py-2 text-center text-xs font-semibold flex items-center justify-center gap-2 border-b border-[#005f60]">
          <span className="material-symbols-outlined text-[16px]">sensors</span>
          <span>{notification}</span>
        </div>
      )}

      {/* Main Workspace based on ViewMode */}
      <div className="flex-1 flex overflow-hidden p-2 sm:p-4">
        {/* Mode 1: Pure Phone Frame */}
        {viewMode === 'phone' && (
          <div className="w-full flex items-center justify-center overflow-y-auto">
            <AndroidPhoneFrame isFramed={true}>
              {AppletInterface}
            </AndroidPhoneFrame>
          </div>
        )}

        {/* Mode 2: Split View (Live Phone + Jetpack Compose Kotlin Code) */}
        {viewMode === 'split' && (
          <div className="w-full h-full flex flex-col lg:flex-row gap-4 items-stretch overflow-hidden">
            {/* Left: Interactive Phone */}
            <div className="w-full lg:w-[440px] shrink-0 flex items-center justify-center overflow-y-auto">
              <AndroidPhoneFrame isFramed={true}>
                {AppletInterface}
              </AndroidPhoneFrame>
            </div>

            {/* Right: Jetpack Compose Kotlin Code Inspector */}
            <div className="flex-1 min-h-[500px] h-full overflow-hidden flex flex-col">
              <ComposeInspector />
            </div>
          </div>
        )}

        {/* Mode 3: Pure Kotlin Jetpack Compose Code Inspector */}
        {viewMode === 'code' && (
          <div className="w-full h-full max-w-6xl mx-auto flex flex-col overflow-hidden">
            <ComposeInspector />
          </div>
        )}

        {/* Mode 4: Fullscreen / Tablet responsive Web App */}
        {viewMode === 'fullscreen' && (
          <div className="w-full h-full max-w-2xl mx-auto bg-[#f9f9fc] rounded-2xl overflow-y-auto shadow-2xl relative text-[#1a1c1e]">
            {AppletInterface}
          </div>
        )}
      </div>
    </div>
  );
}
