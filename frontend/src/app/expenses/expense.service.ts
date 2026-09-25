import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { switchMap } from 'rxjs';

export type ExpenseStatus = 'PENDING' | 'PAID';
export type ExpenseSort = 'REFERENCE_DATE' | 'AMOUNT' | 'DESCRIPTION';
export type SortDirection = 'ASC' | 'DESC';

export interface CreateExpenseData {
  description: string;
  amount: string;
  status: ExpenseStatus;
  dueDate: string | null;
  paymentDate: string | null;
  notes: string | null;
  paidAmount?: string;
  paidByUserId?: string;
  paymentNotes?: string | null;
}

export interface Expense {
  id: string;
  origin: 'ONE_OFF';
  description: string;
  amount: string;
  currency: 'BRL';
  status: ExpenseStatus;
  dueDate: string | null;
  paymentDate: string | null;
  paidAmount: string | null;
  referenceDate: string;
  overdue: boolean;
  categoryName: string | null;
  responsibleUserId: string | null;
  notes: string | null;
  createdByDisplayName: string;
  paidByDisplayName: string | null;
  createdAt: string;
  version: number;
  paymentAudit?: { recordedByDisplayName: string; recordedByUserId: string; recordedAt: string; notes: string | null } | null;
}

export interface ExpensePage {
  content: Expense[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  sort: ExpenseSort;
  direction: SortDirection;
}

@Injectable({ providedIn: 'root' })
export class ExpenseService {
  private readonly http = inject(HttpClient);
  private readonly endpoint = '/api/v1/expenses';

  newIdempotencyKey(): string {
    return crypto.randomUUID();
  }

  create(data: CreateExpenseData, idempotencyKey: string) {
    return this.http.get<{ headerName: string }>('/api/v1/auth/csrf').pipe(
      switchMap(() => this.http.post<Expense>(this.endpoint, data, {
        headers: new HttpHeaders({ 'Idempotency-Key': idempotencyKey }),
      })),
    );
  }

  list(page = 0, size = 20, sort: ExpenseSort = 'REFERENCE_DATE', direction: SortDirection = 'ASC') {
    const params = new HttpParams()
      .set('page', page)
      .set('size', size)
      .set('sort', sort)
      .set('direction', direction);
    return this.http.get<ExpensePage>(this.endpoint, { params });
  }

  settle(id: string, data: { version: number; paidAmount: string; paymentDate: string; paidByUserId: string; paymentNotes: string | null }, key: string) {
    return this.http.get('/api/v1/auth/csrf').pipe(switchMap(() =>
      this.http.post<Expense>(`${this.endpoint}/${id}/payment`, data, { headers: { 'Idempotency-Key': key } })));
  }
}
