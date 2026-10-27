import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { map } from 'rxjs/operators';
import { environment } from '../../environment/environment';

@Injectable({ providedIn: 'root' })
export class ReviewCyclesService {
  private base = environment.apiUrl + '/cycles';

  constructor(private http: HttpClient) {}

  private toIsoDate(d: string): string {
    if (!d) return d;
    if (/^\d{4}-\d{2}-\d{2}$/.test(d)) return d;
    const parts = d.split('/');
    if (parts.length === 3) return `${parts[2]}-${parts[1]}-${parts[0]}`;
    return d;
  }

  private rolesToTrigger(roles: string[]): string {
    if (!roles || roles.length === 0) return 'NONE';
    if (roles.includes('EMPLOYEE')) return 'EMPLOYEE_SELF_REVIEW';
    if (roles.includes('MANAGER')) return 'MANAGER_REVIEW';
    if (roles.includes('ADMIN')) return 'ADMIN_REVIEW';
    return 'NONE';
  }

  private normalizeCycle(c: any): any {
    return {
      ...c,
      startDate: this.toIsoDate(c.startDate),
      dueDate:   this.toIsoDate(c.dueDate),
      milestones: (c.milestones ?? []).map((m: any) => ({
        ...m,
        startDate: this.toIsoDate(m.startDate),
        endDate:   this.toIsoDate(m.endDate),
        trigger:   this.rolesToTrigger(m.assignedRoles ?? []),
      })),
    };
  }

  getCycles(): Observable<any> {
    return this.http.get<any[]>(this.base).pipe(
      map((data: any) => {
        const arr = Array.isArray(data) ? data : (data.cycles ?? []);
        return arr.map((c: any) => this.normalizeCycle(c));
      })
    );
  }

  getCycle(cycleId: string): Observable<any> {
    return this.http.get(`${this.base}/${cycleId}`).pipe(
      map((c: any) => this.normalizeCycle(c))
    );
  }

  createCycle(payload: any): Observable<any> {
    return this.http.post(this.base, payload);
  }

  updateCycle(cycleId: string, payload: any): Observable<any> {
    return this.http.put(`${this.base}/${cycleId}`, payload);
  }

  publishCycle(cycleId: string): Observable<any> {
    return this.http.post(`${this.base}/${cycleId}/publish`, {});
  }

  /** SCHEDULED → ACTIVE. Admin-driven manual override of the daily auto-sync. */
  activateCycle(cycleId: string): Observable<any> {
    return this.http.post(`${this.base}/${cycleId}/activate`, {});
  }

  /** ACTIVE → COMPLETED. Force-finish the cycle ahead of its due date (admin only). */
  completeCycle(cycleId: string): Observable<any> {
    return this.http.post(`${this.base}/${cycleId}/complete`, {});
  }

  deleteCycle(cycleId: string): Observable<any> {
    return this.http.delete(`${this.base}/${cycleId}`);
  }
}
