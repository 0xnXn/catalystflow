import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../environment/environment';

@Injectable({
  providedIn: 'root'
})
export class ReviewService {
  constructor(private http: HttpClient) {}

  submitReview(reviewText: string): Observable<unknown> {
    const payload = { body: reviewText };
    return this.http.post(environment.apiUrl + '/reviews', payload);
  }
}
