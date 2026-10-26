/**
 * Typed client for The Chain Emergency Mesh Gateway REST API.
 * Uses relative paths ('/api/...') for seamless single-origin deployment.
 */
import {
  ClaimAnalysisResponse,
  DistressRequest,
  EmergencyProfile,
  MapData,
  MeshStatus,
  SealedDetails,
} from '../types';

export class ApiError extends Error {
  constructor(
    public code: string,
    message: string,
    public status?: number
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const url = path.startsWith('/') ? path : `/${path}`;
  const headers = new Headers(options.headers || {});

  if (!headers.has('Content-Type') && options.body && typeof options.body === 'string') {
    headers.set('Content-Type', 'application/json');
  }

  const response = await fetch(url, {
    ...options,
    headers,
  });

  let data: any;
  const contentType = response.headers.get('content-type') || '';
  if (contentType.includes('application/json')) {
    try {
      data = await response.json();
    } catch {
      data = null;
    }
  } else {
    data = await response.text();
  }

  if (!response.ok) {
    const code = data?.code || 'HTTP_ERROR';
    const message = data?.message || response.statusText || 'Request failed';
    throw new ApiError(code, message, response.status);
  }

  return data as T;
}

export const api = {
  /**
   * Analyze raw text claim through M2 parser, M3 verifier, M5 trust, M6 battery, M7 gate, M9 privacy, and M10 justification.
   */
  async analyzeClaim(
    text: string,
    lang: string = 'en',
    pos?: [number, number]
  ): Promise<ClaimAnalysisResponse> {
    return request<ClaimAnalysisResponse>('/api/claims', {
      method: 'POST',
      body: JSON.stringify({ text, lang, pos }),
    });
  },

  /**
   * Commit and broadcast an analyzed claim onto the local M8 CRDT event log and M0 gossip mesh.
   */
  async broadcastClaim(claimId: string): Promise<{
    ok: boolean;
    claim_id: string;
    event_hash: string;
    event: any;
  }> {
    return request(`/api/claims/${encodeURIComponent(claimId)}/broadcast`, {
      method: 'POST',
    });
  },

  /**
   * Abort a broadcasting claim. Cancels or resolves its lease in the CRDT book.
   */
  async abortClaim(claimId: string): Promise<{
    ok: boolean;
    claim_id: string;
    aborted: boolean;
  }> {
    return request(`/api/claims/${encodeURIComponent(claimId)}`, {
      method: 'DELETE',
    });
  },

  /**
   * Fetch incoming distress requests visible in the phone's replica book.
   */
  async getRequests(): Promise<{ requests: DistressRequest[] }> {
    return request<{ requests: DistressRequest[] }>('/api/requests');
  },

  /**
   * Act on an emergency request (accept, decline, refer).
   * Accepting releases M9 stage-2 sealed details (exact location & contact info).
   */
  async respondRequest(
    claimId: string,
    action: 'accept' | 'decline' | 'refer',
    reason?: string,
    referral?: string
  ): Promise<{
    ok: boolean;
    did: string;
    state: string;
    sealed_released?: SealedDetails;
  }> {
    return request(`/api/requests/${encodeURIComponent(claimId)}/respond`, {
      method: 'POST',
      body: JSON.stringify({ action, reason, referral }),
    });
  },

  /**
   * Inspect live local mesh status, neighbors, duty cycle, and ingress dropped counters.
   */
  async getMesh(): Promise<MeshStatus> {
    return request<MeshStatus>('/api/mesh');
  },

  /**
   * Get tactical map data: flood level, hazard grid cells, coarse positions of entities.
   */
  async getMap(): Promise<MapData> {
    return request<MapData>('/api/map');
  },

  /**
   * Get emergency identity profile.
   */
  async getProfile(): Promise<EmergencyProfile> {
    return request<EmergencyProfile>('/api/me');
  },

  /**
   * Update emergency identity profile on device.
   */
  async updateProfile(profile: Partial<EmergencyProfile>): Promise<EmergencyProfile> {
    return request<EmergencyProfile>('/api/me', {
      method: 'PUT',
      body: JSON.stringify(profile),
    });
  },

  /**
   * Simulation control: partition the mesh.
   */
  async simPartition(startX: number = 300.0): Promise<{ ok: boolean; partitioned: boolean }> {
    return request('/api/sim/partition', {
      method: 'POST',
      body: JSON.stringify({ start_x: startX }),
    });
  },

  /**
   * Simulation control: heal network partition and trigger anti-entropy reconciliation.
   */
  async simHeal(): Promise<{ ok: boolean; healed: boolean }> {
    return request('/api/sim/heal', {
      method: 'POST',
    });
  },

  /**
   * Simulation control: inject adversarial attacker node (blackhole, greyhole, replay).
   */
  async simAttack(type: 'blackhole' | 'greyhole' | 'replay' | 'eclipse'): Promise<{
    ok: boolean;
    attack: string;
  }> {
    return request('/api/sim/attack', {
      method: 'POST',
      body: JSON.stringify({ type }),
    });
  },

  /**
   * Simulation control: kill or isolate a node.
   */
  async simKillNode(nodeId: string): Promise<{ ok: boolean; killed: string }> {
    return request('/api/sim/kill_node', {
      method: 'POST',
      body: JSON.stringify({ node_id: nodeId }),
    });
  },
};
