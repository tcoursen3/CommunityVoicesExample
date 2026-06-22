import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

@Injectable({
  providedIn: 'root'
})
export class ReportService {
  private readonly http = inject(HttpClient);

  getReportHtml(): Observable<string> {
    return this.http.get('/generate-report', {
      responseType: 'text'
    });
  }

  getNonRagReportHtml(): Observable<string> {
    return this.http.get('/generate-non-rag-report', {
      responseType: 'text'
    });
  }
}
