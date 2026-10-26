export type Language = 'EN' | 'HI' | 'MR';

export interface ParsedSituation {
  kind?: string;
  capability_tags?: string[];
  features?: string[];
  quantity?: string;
  location_text?: string;
  language?: string;
  confidence?: number;
  urgency?: number;
  // UI helper fields
  need?: string;
  person?: string;
  near?: string;
}

export interface ClaimAnalysisResponse {
  claim_id: string;
  parsed: ParsedSituation;
  p_valid: number;
  top_reasons: [string, number][];
  trust: number;
  battery: {
    rate: number;
    tts_s: number;
    urgency: number;
  };
  gate: string;
  disclosure: {
    case: {
      id: string;
      cell: string;
      tags: string[];
    };
    public: {
      c: string;
      cell: string;
      cat?: string;
      need?: string;
      tag?: string;
    };
  };
  justification: {
    text: string;
    risk: 'low' | 'medium' | 'high';
    abort: string;
    lines: string[];
  };
}

export interface SealedDetails {
  exact_pos: [number, number];
  contact_phone: string;
  victim_name: string;
  medical_details: string;
}

export interface DistressRequest {
  id: string;
  cell: string;
  urgency: number;
  age_s: number;
  hops: number;
  winner: string | null;
  status: string;
  raw_text: string;
  justification: {
    text: string;
    risk: string;
    abort: string;
  };
  sealed?: SealedDetails | null;
  // UI mapped helpers
  senderName?: string;
  needCategory?: 'medicine' | 'water' | 'food' | 'rescue' | 'elderly' | 'power';
  distanceMeters?: number;
  approxLocation?: string;
  parsed?: ParsedSituation;
}

export interface MeshPeer {
  id: string;
  battery: number;
  cell: string;
  last_seen_s_ago: number;
  trust: number;
  hop_estimate: number;
  connection_type: string;
  deviceName?: string;
  deviceModel?: string;
  signalDbm?: number;
  packetsRelayed?: number;
  lastPingMs?: number;
  isDirectNeighbor?: boolean;
}

export interface MeshStatus {
  node_id: string;
  battery: number;
  cell: string;
  neighbors: MeshPeer[];
  dropped_counters: {
    frame_unauthenticated: number;
    frame_size: number;
    rate_limited: number;
    [key: string]: number;
  };
  airtime: {
    duty_cycle_used: number;
    duty_cycle_limit: number;
    total_airtime_s: number;
    total_tx_bytes: number;
  };
  watchdog_penalties: any[];
  forks: string[];
  suspects: string[];
}

export interface HazardCell {
  cell: string;
  risk: number;
  is_water: boolean;
  depth_cm: number;
}

export interface MapEntity {
  id: string;
  type: 'danger_water' | 'safe_shelter' | 'medical_post' | 'distress_call' | 'mesh_phone';
  title: string;
  subtitle?: string;
  cell?: string;
  coarse_pos?: [number, number];
  coords?: { x: number; y: number };
  urgency?: 'critical' | 'high' | 'normal';
}

export interface MapData {
  flood_level_m: number;
  flood_x: number;
  hazard_cells: HazardCell[];
  entities: MapEntity[];
}

export interface MapFeature extends MapEntity {}

export interface EmergencyProfile {
  fullName: string;
  phoneMeshId: string;
  bloodGroup: string;
  chronicConditions: string[];
  emergencyContact: string;
  lowBatterySaver: boolean;
}
