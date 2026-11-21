import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  Changelog,
  Command,
  CommandKind,
  KeyResultView,
  KpiView,
  LoglineRow,
  Overview,
  TestView,
} from './model';

/** The Dashboard API (contracts/dashboard-api.openapi.yaml), via nginx under /api/v1. */
@Injectable({ providedIn: 'root' })
export class DashboardApi {
  private readonly http = inject(HttpClient);

  private params(experiment: string | null): HttpParams {
    return experiment ? new HttpParams().set('experiment', experiment) : new HttpParams();
  }

  overview(experiment: string | null): Observable<Overview> {
    return this.http.get<Overview>('/api/v1/overview', { params: this.params(experiment) });
  }

  tests(experiment: string | null): Observable<TestView[]> {
    return this.http.get<TestView[]>('/api/v1/tests', { params: this.params(experiment) });
  }

  loglines(experiment: string | null): Observable<LoglineRow[]> {
    return this.http.get<LoglineRow[]>('/api/v1/loglines', {
      params: this.params(experiment).set('limit', 300),
    });
  }

  kpis(experiment: string | null): Observable<KpiView> {
    return this.http.get<KpiView>('/api/v1/kpis', { params: this.params(experiment) });
  }

  keyResult(experiment: string | null): Observable<KeyResultView> {
    return this.http.get<KeyResultView>('/api/v1/keyresult', { params: this.params(experiment) });
  }

  /** The status report as HTML for inside the Dashboard (the backend escapes every text). */
  report(experiment: string | null): Observable<string> {
    return this.http.get('/api/v1/report', {
      params: this.params(experiment).set('format', 'fragment'),
      responseType: 'text',
    });
  }

  changelog(experiment: string | null): Observable<Changelog[]> {
    return this.http.get<Changelog[]>('/api/v1/changelog', { params: this.params(experiment) });
  }

  /** A button of the product owner; the Pipeline agent carries it out. */
  command(kind: CommandKind, extra: { run?: string; save?: boolean } = {}): Observable<Command> {
    return this.http.post<Command>('/api/v1/commands', { kind, ...extra });
  }

  reportDownloadUrl(experiment: string | null, format: 'markdown' | 'html' = 'markdown'): string {
    let p = this.params(experiment).set('download', 'true');
    if (format !== 'markdown') p = p.set('format', format);
    return '/api/v1/report?' + p.toString();
  }
}
