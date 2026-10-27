import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../environment/environment';
import {
  ManualReminderRequest,
  ManualReminderResponse,
  NotificationJobStatusResponse,
} from '../models/notification.model';

@Injectable({ providedIn: 'root' })
export class NotificationService {
  private readonly base = environment.apiUrl + '/notifications';

  constructor(private http: HttpClient) {}

  sendManualReminder(body: ManualReminderRequest): Observable<ManualReminderResponse> {
    return this.http.post<ManualReminderResponse>(`${this.base}/reminders/manual`, body);
  }

  getJobStatus(jobId: string): Observable<NotificationJobStatusResponse> {
    return this.http.get<NotificationJobStatusResponse>(
      `${this.base}/jobs/${encodeURIComponent(jobId)}/status`,
    );
  }
}
