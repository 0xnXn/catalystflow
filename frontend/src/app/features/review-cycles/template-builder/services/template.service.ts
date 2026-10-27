import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { map } from 'rxjs/operators';
import { environment } from '../../../../../environment/environment';
import {
  ReviewTemplate,
  ReviewTemplateListResponse,
  CreateReviewTemplateRequest,
} from '../models/template.model';

@Injectable({ providedIn: 'root' })
export class TemplateService {
  private readonly base = `${environment.apiUrl}/templates`;

  constructor(private http: HttpClient) {}

  getAll(reviewType?: string): Observable<ReviewTemplate[]> {
    let params = new HttpParams();
    if (reviewType) params = params.set('reviewType', reviewType);
    return this.http
      .get<ReviewTemplateListResponse>(this.base, { params })
      .pipe(map(res => res.templates ?? []));
  }

  getById(templateId: string): Observable<ReviewTemplate> {
    return this.http.get<ReviewTemplate>(`${this.base}/${templateId}`);
  }

  create(request: CreateReviewTemplateRequest): Observable<ReviewTemplate> {
    return this.http.post<ReviewTemplate>(this.base, request);
  }

  update(templateId: string, request: Partial<CreateReviewTemplateRequest>): Observable<ReviewTemplate> {
    return this.http.put<ReviewTemplate>(`${this.base}/${templateId}`, request);
  }

  delete(templateId: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/${templateId}`);
  }

  checkNameAvailable(name: string, excludeId?: string): Observable<boolean> {
    let params = new HttpParams().set('name', name);
    if (excludeId) params = params.set('excludeId', excludeId);
    return this.http
      .get<{ available: boolean }>(`${this.base}/check-name`, { params })
      .pipe(map(res => res.available));
  }
}
