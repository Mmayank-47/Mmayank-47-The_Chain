import React, { useState } from 'react';
import { KOTLIN_FILES, KotlinFile } from '../data/composeCode';

export const ComposeInspector: React.FC = () => {
  const [selectedFile, setSelectedFile] = useState<KotlinFile>(KOTLIN_FILES[0]);
  const [copied, setCopied] = useState(false);

  const handleCopy = () => {
    navigator.clipboard.writeText(selectedFile.code);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  const handleDownloadFile = () => {
    const blob = new Blob([selectedFile.code], { type: 'text/plain;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = selectedFile.filename;
    a.click();
    URL.revokeObjectURL(url);
  };

  const handleDownloadAllZip = () => {
    // Download all files as individual scripts or combined bundle
    const combinedContent = KOTLIN_FILES.map(
      (f) => `// ==========================================\n// File: ${f.path}\n// ==========================================\n\n${f.code}\n\n`
    ).join('\n');
    const blob = new Blob([combinedContent], { type: 'text/plain;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'TheChain_Kotlin_Jetpack_Compose_Source.kt';
    a.click();
    URL.revokeObjectURL(url);
  };

  return (
    <div className="flex flex-col h-full bg-[#1e1e24] text-[#f0f0f3] rounded-2xl overflow-hidden border border-[#3f4948] shadow-2xl">
      {/* Top Inspector Header */}
      <div className="bg-[#141418] px-4 py-3 border-b border-[#2f3133] flex items-center justify-between flex-wrap gap-2">
        <div className="flex items-center gap-2">
          <div className="w-6 h-6 rounded bg-[#3ddc84] flex items-center justify-center text-black font-bold text-[12px]">
            K
          </div>
          <div>
            <h3 className="text-[15px] font-bold text-white flex items-center gap-2">
              <span>Android Studio · Jetpack Compose Project</span>
              <span className="text-[11px] font-mono bg-[#004546] text-[#8ed6d7] px-2 py-0.5 rounded">
                Kotlin 2.0 + Compose BOM 2024.10
              </span>
            </h3>
            <p className="text-[12px] text-[#bec9c8]">
              {selectedFile.path}
            </p>
          </div>
        </div>

        {/* Action Buttons */}
        <div className="flex items-center gap-2">
          <button
            type="button"
            onClick={handleCopy}
            className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-[#2f3133] hover:bg-[#3f4948] text-[13px] font-medium text-white transition-colors"
          >
            <span className="material-symbols-outlined text-[16px]">
              {copied ? 'check' : 'content_copy'}
            </span>
            <span>{copied ? 'Copied!' : 'Copy Code'}</span>
          </button>

          <button
            type="button"
            onClick={handleDownloadFile}
            className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-[#004546] hover:bg-[#005f60] text-[13px] font-bold text-white transition-colors"
          >
            <span className="material-symbols-outlined text-[16px]">download</span>
            <span>Download {selectedFile.filename}</span>
          </button>

          <button
            type="button"
            onClick={handleDownloadAllZip}
            title="Download full project sources"
            className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-[#895100] hover:bg-[#6e4000] text-[13px] font-bold text-white transition-colors"
          >
            <span className="material-symbols-outlined text-[16px]">folder_zip</span>
            <span className="hidden sm:inline">Export All</span>
          </button>
        </div>
      </div>

      {/* Main Split: File Tree Sidebar & Code Viewer */}
      <div className="flex flex-1 overflow-hidden">
        {/* Sidebar file list */}
        <div className="w-56 sm:w-64 bg-[#18181c] border-r border-[#2f3133] p-2 overflow-y-auto no-scrollbar shrink-0">
          <div className="text-[11px] font-mono font-bold text-[#8ad3d3] uppercase px-2 py-1 tracking-wider">
            Project Files
          </div>
          <div className="space-y-1 mt-1">
            {KOTLIN_FILES.map((file) => {
              const isSelected = selectedFile.filename === file.filename;
              return (
                <button
                  key={file.filename}
                  type="button"
                  onClick={() => setSelectedFile(file)}
                  className={`w-full text-left px-2.5 py-2 rounded-lg text-[13px] font-mono flex items-center gap-2 transition-colors ${
                    isSelected
                      ? 'bg-[#004546] text-white font-bold'
                      : 'text-[#bec9c8] hover:bg-[#25252b] hover:text-white'
                  }`}
                >
                  <span className="material-symbols-outlined text-[16px] text-[#8ed6d7]">
                    {file.category === 'screen'
                      ? 'widgets'
                      : file.category === 'theme'
                      ? 'palette'
                      : file.category === 'mesh'
                      ? 'hub'
                      : 'description'}
                  </span>
                  <span className="truncate">{file.filename}</span>
                </button>
              );
            })}
          </div>

          <div className="mt-6 p-2.5 rounded-xl bg-[#25252b] text-[12px] text-[#bec9c8] space-y-1.5">
            <span className="font-bold text-white block">Compose Architecture</span>
            <p className="leading-snug">
              Uses Material 3 <code className="text-[#8ad3d3]">Scaffold</code>, <code className="text-[#8ad3d3]">CenterAlignedTopAppBar</code>, and Google Nearby Connections for multi-hop BLE mesh.
            </p>
          </div>
        </div>

        {/* Code Content Area */}
        <div className="flex-1 flex flex-col overflow-hidden bg-[#121214]">
          <div className="bg-[#1a1a1e] px-4 py-2 border-b border-[#2f3133] text-[12px] text-[#bec9c8] flex items-center justify-between">
            <span>{selectedFile.description}</span>
            <span className="font-mono text-[11px] text-[#6f7979]">
              {selectedFile.code.split('\n').length} lines
            </span>
          </div>

          <pre className="flex-1 p-4 font-mono text-[13px] leading-[22px] overflow-auto select-text text-[#e2e2e5]">
            <code>
              {selectedFile.code.split('\n').map((line, idx) => (
                <div key={idx} className="table-row">
                  <span className="table-cell select-none pr-4 text-right text-[#49454f] w-8">
                    {idx + 1}
                  </span>
                  <span className="table-cell whitespace-pre">
                    {highlightKotlinSyntax(line)}
                  </span>
                </div>
              ))}
            </code>
          </pre>
        </div>
      </div>
    </div>
  );
};

// Simple syntax colorizer for Kotlin & Compose keywords
function highlightKotlinSyntax(line: string) {
  if (line.trim().startsWith('//') || line.trim().startsWith('/*') || line.trim().startsWith('*')) {
    return <span className="text-[#6f7979] italic">{line}</span>;
  }
  if (line.trim().startsWith('@')) {
    return <span className="text-[#fea946] font-semibold">{line}</span>;
  }
  if (line.trim().startsWith('package ') || line.trim().startsWith('import ')) {
    return <span className="text-[#ffb86d]">{line}</span>;
  }

  // Highlight keywords
  const parts = line.split(/(\b(?:fun|val|var|class|interface|object|enum|return|if|else|when|data|private|override|is|as|null|true|false)\b)/g);
  return (
    <span>
      {parts.map((part, i) => {
        if (
          [
            'fun',
            'val',
            'var',
            'class',
            'interface',
            'object',
            'enum',
            'return',
            'if',
            'else',
            'when',
            'data',
            'private',
            'override',
          ].includes(part)
        ) {
          return (
            <span key={i} className="text-[#a6eff0] font-bold">
              {part}
            </span>
          );
        }
        if (['true', 'false', 'null'].includes(part)) {
          return (
            <span key={i} className="text-[#ffb3ae]">
              {part}
            </span>
          );
        }
        return part;
      })}
    </span>
  );
}
