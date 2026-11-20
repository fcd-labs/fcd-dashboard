import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { LoglineRow, Overview, TestView } from './model';

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
}
