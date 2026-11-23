import { Component, HostListener, inject, signal } from '@angular/core';
import { LiveStore } from './live-store';

/**
 * The buttons of the product owner, out of the way: one quiet "Bediening" menu in the header with
 * start the pipeline, merge the patch and reset the demo.
 */
@Component({
  selector: 'app-demo-controls',
  template: `
    @if (store.state(); as s) {
      @if (s.controls.startButton || s.controls.demoReset) {
        <div class="menu">
          <button
            type="button"
            class="header-control"
            data-command="menu"
            aria-haspopup="menu"
            [attr.aria-expanded]="open()"
            (click)="toggle($event)"
          >
            Bediening
          </button>
          @if (open()) {
            <div class="items" role="menu" (click)="$event.stopPropagation()">
              @if (s.controls.startButton) {
                <button
                  type="button"
                  role="menuitem"
                  class="item"
                  data-command="start-run"
                  [disabled]="store.running() || !!store.busy()"
                  (click)="run('start-run')"
                >
                  Pipeline starten{{ s.controls.approver ? ' (' + s.controls.approver + ')' : '' }}
                </button>
              }
              @if (s.controls.demoReset && s.demo?.phase === 'start') {
                <button
                  type="button"
                  role="menuitem"
                  class="item"
                  data-command="demo-merge"
                  [disabled]="store.running() || !!store.busy()"
                  [title]="'Merge ' + s.demo!.unmerged.join(', ')"
                  (click)="run('demo-merge')"
                >
                  Patch mergen
                </button>
              }
              <!-- Demo opnieuw always works, in every phase (besluit sim007). -->
              @if (s.controls.demoReset) {
                @if (!confirmReset()) {
                  <button
                    type="button"
                    role="menuitem"
                    class="item"
                    data-command="demo-reset"
                    [disabled]="!store.resettable()"
                    (click)="confirmReset.set(true)"
                  >
                    Demo opnieuw…
                  </button>
                } @else {
                  <div class="confirm" role="dialog" aria-label="Demo opnieuw bevestigen">
                    <span>Terug naar de start, met een lege log?</span>
                    <label
                      ><input
                        type="checkbox"
                        [checked]="saveFirst()"
                        (change)="saveFirst.set($any($event.target).checked)"
                      />
                      eerst de run en het rapport bewaren</label
                    >
                    <span class="row">
                      <button
                        type="button"
                        class="btn"
                        data-command="demo-reset-confirm"
                        (click)="reset()"
                      >
                        Ja, opnieuw
                      </button>
                      <button type="button" class="btn" (click)="confirmReset.set(false)">
                        Nee
                      </button>
                    </span>
                  </div>
                }
              }
            </div>
          }
        </div>
      }
    }
  `,
  styles: `
    .menu {
      position: relative;
    }
    /* Businesslike and quiet: the same neutral look as the choice of the demo round (app.css). */
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
      text-align: left;
    }
    .row .btn {
      width: auto;
      text-align: center;
    }
    /* The menu opens over the page: nothing moves. */
    .items {
      position: absolute;
      top: calc(100% + 4px);
      left: 0;
      z-index: 20;
      min-width: 100%;
      display: flex;
      flex-direction: column;
      padding: 4px;
      border: 1px solid var(--line);
      border-radius: 8px;
      background: var(--card);
      box-shadow: 0 6px 18px rgba(0, 0, 0, 0.18);
    }
    .item {
      font: inherit;
      font-size: 0.85rem;
      text-align: left;
      padding: 7px 10px;
      border: 0;
      border-radius: 5px;
      background: transparent;
      color: var(--text);
      cursor: pointer;
      white-space: nowrap;
    }
    .item:hover:not(:disabled) {
      background: color-mix(in srgb, var(--line) 60%, transparent);
    }
    .item:disabled,
    .btn:disabled {
      opacity: 0.45;
      cursor: not-allowed;
    }
    .confirm {
      display: flex;
      flex-direction: column;
      gap: 6px;
      padding: 6px 8px;
      font-size: 0.85rem;
      white-space: nowrap;
    }
    .confirm .row {
      display: flex;
      gap: 6px;
    }
  `,
})
export class DemoControls {
  protected readonly store = inject(LiveStore);
  protected readonly open = signal(false);
  protected readonly confirmReset = signal(false);
  protected readonly saveFirst = signal(true);

  protected toggle(e: Event): void {
    e.stopPropagation();
    this.open.update((v) => !v);
    this.confirmReset.set(false);
  }

  /** A click anywhere else closes the menu. */
  @HostListener('document:click')
  protected close(): void {
    this.open.set(false);
    this.confirmReset.set(false);
  }

  protected run(kind: 'start-run' | 'demo-merge'): void {
    this.close();
    this.store.send(kind);
  }

  protected reset(): void {
    this.close();
    this.store.send('demo-reset', { save: this.saveFirst() });
  }
}
