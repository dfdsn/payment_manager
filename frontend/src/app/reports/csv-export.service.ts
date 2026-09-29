import { HttpClient, HttpEvent, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ExpenseFilters, ExpenseSort, SortDirection } from '../expenses/expense.service';
import { PlanningFilters } from './report.service';

/**
 * H06.4 CSV downloads. The backend builds the whole file before answering, so the response is either a complete
 * file or an error; progress events cover the transfer of that file.
 */
@Injectable({ providedIn: 'root' })
export class CsvExportService {
  private readonly http = inject(HttpClient);

  expenses(filters: ExpenseFilters, sort: ExpenseSort, direction: SortDirection): Observable<HttpEvent<Blob>> {
    return this.download('/api/v1/reports/expenses/export', { ...filters, sort, direction });
  }

  forecasts(filters: PlanningFilters): Observable<HttpEvent<Blob>> {
    return this.download('/api/v1/reports/planning/export', filters);
  }

  private download(url: string, filters: object): Observable<HttpEvent<Blob>> {
    let params = new HttpParams();
    for (const [key, value] of Object.entries(filters)) {
      if (value !== undefined && value !== null && value !== '' && value !== false) params = params.set(key, String(value));
    }
    return this.http.get(url, { params, responseType: 'blob', observe: 'events', reportProgress: true });
  }
}

/** The file name the server chose, or a safe fallback; never a path. */
export function attachmentName(contentDisposition: string | null, fallback: string): string {
  const match = contentDisposition?.match(/filename="?([^";]+)"?/i);
  const name = match?.[1]?.split(/[\\/]/).pop()?.trim();
  return name ? name : fallback;
}
