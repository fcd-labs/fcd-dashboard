import { Injectable } from '@angular/core';
import { LiveState } from './model';

/** The live view as Server-Sent Events (GET /api/v1/live). EventSource connects again by itself. */
@Injectable({ providedIn: 'root' })
export class LiveStream {
  /** Returns a function that closes the connection. */
  connect(
    experiment: string | null,
    onState: (state: LiveState) => void,
    onConnected: (connected: boolean) => void,
  ): () => void {
    const source = globalThis.EventSource;
    if (!source) {
      onConnected(false);
      return () => undefined;
    }
    const url =
      '/api/v1/live' + (experiment ? `?experiment=${encodeURIComponent(experiment)}` : '');
    const events = new source(url);
    events.addEventListener('live-state', (e) => {
      onState(JSON.parse((e as MessageEvent<string>).data) as LiveState);
      onConnected(true);
    });
    events.onopen = () => onConnected(true);
    events.onerror = () => onConnected(false);
    return () => events.close();
  }
}
