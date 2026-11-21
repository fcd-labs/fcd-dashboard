import { Component, inject, signal } from '@angular/core';
import { LiveStore } from './live-store';

/** The buttons of the product owner, in the header: start the pipeline, merge the patch, reset the demo. */
@Component({
  selector: 'app-demo-controls',
  template: `
    @if (store.state(); as s) {
      @if (s.controls.startButton || s.controls.demoReset) {
        <div class="demo-controls">
          @if (s.controls.startButton) {
            <button
              type="button"
              class="btn primary"
              data-command="start-run"
              [disabled]="store.running() || !!store.busy()"
              (click)="store.send('start-run')"
            >
              ▶ Pipeline starten{{ s.controls.approver ? ' (' + s.controls.approver + ')' : '' }}
            </button>
          }
          @if (s.controls.demoReset && s.demo?.phase === 'start') {
            <button
              type="button"
              class="btn"
              data-command="demo-merge"
              [disabled]="store.running() || !!store.busy()"
              [title]="'Merge ' + s.demo!.unmerged.join(', ')"
              (click)="store.send('demo-merge')"
            >
              ⇄ Patch mergen
            </button>
          } @else if (s.controls.demoReset) {
            @if (!confirmReset()) {
              <button
                type="button"
                class="btn"
                data-command="demo-reset"
                [disabled]="store.running() || !!store.busy()"
                (click)="confirmReset.set(true)"
              >
                ↺ Demo opnieuw
              </button>
            } @else {
              <span class="confirm" role="dialog" aria-label="Demo opnieuw bevestigen">
                <label
                  ><input
                    type="checkbox"
                    [checked]="saveFirst()"
                    (change)="saveFirst.set($any($event.target).checked)"
                  />
                  eerst bewaren</label
                >
                <button
                  type="button"
                  class="btn danger"
                  data-command="demo-reset-confirm"
                  (click)="reset()"
                >
                  Ja, opnieuw
                </button>
                <button type="button" class="btn" (click)="confirmReset.set(false)">Nee</button>
              </span>
            }
          }
        </div>
      }
    }
  `,
  styles: `
    .demo-controls,
    .confirm {
      display: flex;
      align-items: center;
      gap: 6px;
      white-space: nowrap;
    }
    /* Every button in the header has the same size and letter (also the choice of the demo, in app.css). */
    .btn {
      font: inherit;
      font-size: 0.85rem;
      font-weight: 600;
      height: 32px;
      width: 17.5em;
      padding: 0 10px;
      box-sizing: border-box;
      border-radius: 6px;
      border: 1px solid var(--line);
      background: var(--card);
      color: var(--text);
      cursor: pointer;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
    .confirm .btn {
      width: 7.5em;
    }
    .btn.primary {
      background: var(--green);
      border-color: var(--green);
      color: #fff;
    }
    .btn.danger {
      background: var(--red);
      border-color: var(--red);
      color: #fff;
    }
    .btn:disabled {
      opacity: 0.45;
      cursor: not-allowed;
    }
    .confirm label {
      font-size: 0.85rem;
    }
  `,
})
export class DemoControls {
  protected readonly store = inject(LiveStore);
  protected readonly confirmReset = signal(false);
  protected readonly saveFirst = signal(true);

  protected reset(): void {
    this.confirmReset.set(false);
    this.store.send('demo-reset', { save: this.saveFirst() });
  }
}
