import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { switchMap } from 'rxjs';

export type ExpenseStatus = 'PENDING' | 'PAID' | 'CANCELLED';
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
  categoryId?: string | null;
  responsibleUserId?: string | null;
}

export interface CorrectExpenseData {
  version: number;
  status: ExpenseStatus;
  description: string;
  amount: string;
  dueDate: string | null;
  notes: string | null;
  paidAmount?: string;
  paymentDate?: string;
  paidByUserId?: string;
  paymentNotes?: string | null;
  categoryId?: string | null;
  responsibleUserId?: string | null;
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
  paidByUserId: string | null;
  referenceDate: string;
  overdue: boolean;
  categoryName: string | null;
  categoryId: string | null;
  responsibleUserId: string | null;
  responsibleDisplayName: string | null;
  notes: string | null;
  createdByDisplayName: string;
  paidByDisplayName: string | null;
  createdAt: string;
  version: number;
  paymentAudit?: { recordedByDisplayName: string; recordedByUserId: string; recordedAt: string; notes: string | null } | null;
  history: ExpenseHistoryEvent[];
}

export interface ExpenseHistoryEvent {
  type: 'EXPENSE_CREATED' | 'EXPENSE_PAID' | 'PAYMENT_REVERSED' | 'EXPENSE_CORRECTED' | 'EXPENSE_CANCELLED';
  actorUserId: string;
  actorDisplayName: string;
  occurredAt: string;
  reason: string | null;
  notes: string | null;
  version: number;
  paidAmount: string | null;
  paymentDate: string | null;
  payerUserId: string | null;
  payerDisplayName: string | null;
  changedFields: string | null;
  batchOperationId: string | null;
  changes: { field: string; previousValue: string | null; currentValue: string | null }[];
}

export interface ExpenseHistoryPage {
  content: ExpenseHistoryEvent[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface ExpenseAttachment {
  id: string;
  name: string;
  mediaType: 'application/pdf' | 'image/jpeg' | 'image/png';
  size: number;
  uploadedByDisplayName: string;
  uploadedAt: string;
}

export interface BatchSettlementResult {
  operationId: string;
  replayed: boolean;
  items: { expenseId: string; fromVersion: number; toVersion: number; paidAmount: string }[];
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

  get(id: string) {
    return this.http.get<Expense>(`${this.endpoint}/${id}`);
  }

  history(id: string, page = 0, size = 10) {
    return this.http.get<ExpenseHistoryPage>(`${this.endpoint}/${id}/history`, {
      params: new HttpParams().set('page', page).set('size', size),
    });
  }

  attachments(id: string) {
    return this.http.get<ExpenseAttachment[]>(`${this.endpoint}/${id}/attachments`);
  }

  uploadAttachment(id: string, file: File, key: string) {
    const body = new FormData(); body.append('file', file, file.name);
    return this.http.get('/api/v1/auth/csrf').pipe(switchMap(() =>
      this.http.post<ExpenseAttachment>(`${this.endpoint}/${id}/attachments`, body,
        { headers: { 'Idempotency-Key': key } })));
  }

  downloadAttachment(expenseId: string, attachmentId: string) {
    return this.http.get(`${this.endpoint}/${expenseId}/attachments/${attachmentId}`, { responseType: 'blob' });
  }

  removeAttachment(expenseId: string, attachmentId: string) {
    return this.http.get('/api/v1/auth/csrf').pipe(switchMap(() =>
      this.http.delete<void>(`${this.endpoint}/${expenseId}/attachments/${attachmentId}`)));
  }

  correct(id: string, data: CorrectExpenseData, idempotencyKey: string) {
    return this.http.get<{ headerName: string }>('/api/v1/auth/csrf').pipe(
      switchMap(() => this.http.put<Expense>(`${this.endpoint}/${id}`, data, {
        headers: new HttpHeaders({ 'Idempotency-Key': idempotencyKey }),
      })),
    );
  }

  settle(id: string, data: { version: number; paidAmount: string; paymentDate: string; paidByUserId: string; paymentNotes: string | null }, key: string) {
    return this.http.get('/api/v1/auth/csrf').pipe(switchMap(() =>
      this.http.post<Expense>(`${this.endpoint}/${id}/payment`, data, { headers: { 'Idempotency-Key': key } })));
  }

  settleBatch(data: {
    items: { expenseId: string; version: number }[];
    paymentDate: string;
    paidByUserId: string;
    confirmed: boolean;
  }, key: string) {
    return this.http.get('/api/v1/auth/csrf').pipe(switchMap(() =>
      this.http.post<BatchSettlementResult>(`${this.endpoint}/batch-payment`, data,
        { headers: { 'Idempotency-Key': key } })));
  }

  reversePayment(id: string, version: number, reason: string, key: string) {
    return this.action(id, 'payment-reversal', version, reason, key);
  }

  cancel(id: string, version: number, reason: string, key: string) {
    return this.action(id, 'cancellation', version, reason, key);
  }

  private action(id: string, path: string, version: number, reason: string, key: string) {
    return this.http.get('/api/v1/auth/csrf').pipe(switchMap(() =>
      this.http.post<Expense>(`${this.endpoint}/${id}/${path}`, { version, reason },
        { headers: { 'Idempotency-Key': key } })));
  }
}
