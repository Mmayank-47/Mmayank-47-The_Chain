export type Language = 'EN' | 'HI' | 'MR';

export interface ParsedSituation {
  need: string;
  quantity: string;
  person: string;
  near: string;
  language: string;
}

export interface DistressRequest {
  id: string;
  senderName: string;
  needCategory: 'medicine' | 'water' | 'food' | 'rescue' | 'elderly' | 'power';
  rawText: string;
  parsed: ParsedSituation;
  timestamp: string;
  status: 'relaying' | 'acknowledged' | 'responder_assigned' | 'resolved';
  hopCount: number;
  relayedNodesCount: number;
  distanceMeters: number;
  urgency: 'critical' | 'high' | 'moderate';
  approxLocation: string;
}

export interface MeshPeer {
  id: string;
  deviceName: string;
  deviceModel: string;
  connectionType: 'Direct Wi-Fi' | 'BLE Mesh' | 'Hop 2 (BLE)' | 'Hop 3 (Relay)';
  signalDbm: number; // e.g. -54 dBm
  batteryPercentage: number;
  packetsRelayed: number;
  lastPingMs: number;
  isDirectNeighbor: boolean;
}

export interface MapFeature {
  id: string;
  type: 'danger_water' | 'safe_shelter' | 'medical_post' | 'distress_call' | 'mesh_phone';
  title: string;
  subtitle: string;
  coords: { x: number; y: number }; // Percentage coords on tactical vector map
  urgency?: 'critical' | 'high' | 'normal';
}

export interface EmergencyProfile {
  fullName: string;
  phoneMeshId: string;
  bloodGroup: string;
  chronicConditions: string[];
  emergencyContact: string;
  lowBatterySaver: boolean;
}
