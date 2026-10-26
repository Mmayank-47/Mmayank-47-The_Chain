/**
 * Server-Sent Events (SSE) hook for real-time mesh protocol updates.
 * Implements automatic reconnect with exponential backoff and typed event dispatching.
 */
import { useEffect, useRef, useState } from 'react';

export type MeshEventType =
  | 'connected'
  | 'claim_opened'
  | 'dispatch_state'
  | 'neighbor_up'
  | 'neighbor_down'
  | 'partition_healed'
  | 'drop_counter'
  | 'message_delivered';

export interface MeshEvent<T = any> {
  type: MeshEventType;
  data: T;
  timestamp: number;
}

export type ConnectionStatus = 'connecting' | 'connected' | 'reconnecting' | 'error';

export type EventHandler = (event: MeshEvent) => void;

class MeshEventBus {
  private listeners = new Set<EventHandler>();
  private eventSource: EventSource | null = null;
  private retryTimeout: any = null;
  private backoffMs = 1000;
  private maxBackoffMs = 15000;
  private statusListeners = new Set<(status: ConnectionStatus) => void>();
  private currentStatus: ConnectionStatus = 'connecting';

  constructor() {
    this.connect();
  }

  private setStatus(status: ConnectionStatus) {
    this.currentStatus = status;
    this.statusListeners.forEach((fn) => fn(status));
  }

  public getStatus(): ConnectionStatus {
    return this.currentStatus;
  }

  public subscribeStatus(fn: (status: ConnectionStatus) => void): () => void {
    this.statusListeners.add(fn);
    fn(this.currentStatus);
    return () => this.statusListeners.delete(fn);
  }

  public subscribe(handler: EventHandler): () => void {
    this.listeners.add(handler);
    return () => this.listeners.delete(handler);
  }

  private connect() {
    if (this.eventSource) {
      this.eventSource.close();
      this.eventSource = null;
    }

    try {
      this.setStatus(this.currentStatus === 'connected' ? 'reconnecting' : 'connecting');
      const es = new EventSource('/api/events');
      this.eventSource = es;

      es.onopen = () => {
        this.backoffMs = 1000;
        this.setStatus('connected');
      };

      const eventTypes: MeshEventType[] = [
        'connected',
        'claim_opened',
        'dispatch_state',
        'neighbor_up',
        'neighbor_down',
        'partition_healed',
        'drop_counter',
        'message_delivered',
      ];

      eventTypes.forEach((type) => {
        es.addEventListener(type, (e: MessageEvent) => {
          try {
            const data = JSON.parse(e.data);
            const meshEvent: MeshEvent = {
              type,
              data,
              timestamp: Date.now(),
            };
            this.listeners.forEach((fn) => fn(meshEvent));
          } catch (err) {
            console.error(`[SSE] Failed to parse ${type} event:`, err);
          }
        });
      });

      es.onerror = () => {
        es.close();
        this.eventSource = null;
        this.setStatus('reconnecting');
        this.scheduleReconnect();
      };
    } catch (err) {
      this.setStatus('error');
      this.scheduleReconnect();
    }
  }

  private scheduleReconnect() {
    if (this.retryTimeout) clearTimeout(this.retryTimeout);
    this.retryTimeout = setTimeout(() => {
      this.backoffMs = Math.min(this.backoffMs * 1.5, this.maxBackoffMs);
      this.connect();
    }, this.backoffMs);
  }
}

// Global singleton event bus
export const meshEventBus = new MeshEventBus();

/**
 * React hook to subscribe to live mesh SSE events.
 */
export function useMeshEvents(handler?: EventHandler) {
  const [status, setStatus] = useState<ConnectionStatus>(meshEventBus.getStatus());
  const handlerRef = useRef(handler);
  handlerRef.current = handler;

  useEffect(() => {
    const unsubStatus = meshEventBus.subscribeStatus(setStatus);
    const unsubEvents = meshEventBus.subscribe((ev) => {
      if (handlerRef.current) {
        handlerRef.current(ev);
      }
    });

    return () => {
      unsubStatus();
      unsubEvents();
    };
  }, []);

  return { status };
}
