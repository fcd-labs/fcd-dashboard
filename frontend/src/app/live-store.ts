import { Injectable, computed, inject, signal } from '@angular/core';
import { DashboardApi } from './dashboard-api';
import { Command, CommandKind, LiveState } from './model';

/**
 * The state of the live view, shared by the live view and the buttons in the header:
 * the last state pushed by the Dashboard, and what happened to the last button.
 */
@Injectable({ providedIn: 'root' })
export class LiveStore {
  private readonly api = inject(DashboardApi);

  readonly state = signal<LiveState | null>(null);
  /** Why the Dashboard refused the last button, shown with the messages. */
  readonly refused = signal<string | null>(null);

  /** A command that is still with the Pipeline (pending or claimed). */
  readonly busy = computed<Command | undefined>(() =>
    this.state()?.commands.find((c) => c.status === 'pending' || c.status === 'claimed'),
  );

  readonly running = computed(() =>
    (this.state()?.runs ?? []).some((r) => r.outcome === 'running' && r.environment === 'lokaal'),
  );

  /** Demo opnieuw always works (the Pipeline stops a running run), unless a reset is already on its way. */
  readonly resettable = computed(
    () =>
      !(this.state()?.commands ?? []).some(
        (c) => c.kind === 'demo-reset' && (c.status === 'pending' || c.status === 'claimed'),
      ),
  );

  /** A button of the product owner; the Pipeline agent carries it out. */
  send(kind: CommandKind, extra: { run?: string; save?: boolean } = {}): void {
    this.refused.set(null);
    this.api.command(kind, extra).subscribe({
      error: (e) => this.refused.set(e?.error?.detail ?? 'De knop werkt nu niet.'),
    });
  }
}
