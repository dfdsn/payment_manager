import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { switchMap } from 'rxjs';

/** H05.1: data approved for a purchase; the first due date anchors the monthly calendar. */
export interface InstallmentPurchaseData {
  description: string;
  totalAmount: string;
  installmentCount: number;
  firstDueDate: string;
  categoryId: string | null;
  responsibleUserId: string | null;
}
export interface InstallmentItem { number: number; count: number; amount: string; dueDate: string;
  expenseId: string | null; status: 'PENDING' | 'PAID' | 'CANCELLED' | null; version?: number | null;
  overdue?: boolean; description?: string | null; categoryId?: string | null; categoryName?: string | null;
  responsibleUserId?: string | null; responsibleDisplayName?: string | null; paymentDate?: string | null;
  paidAmount?: string | null; }
/** H05.2: progress derived only from the installments' situations; never a bank or card balance. */
export interface InstallmentProgress { installmentCount: number; paidCount: number; pendingCount: number;
  overdueCount: number; cancelledCount: number; paidAmount: string; pendingAmount: string; overdueAmount: string;
  cancelledAmount: string; nextDueDate: string | null; }
export interface InstallmentPurchaseSummary { id: string; description: string; totalAmount: string;
  installmentCount: number; firstDueDate: string; lastDueDate: string | null; categoryName: string | null;
  responsibleDisplayName: string | null; createdAt: string; progress: InstallmentProgress; }
export interface InstallmentPurchasePage { items: InstallmentPurchaseSummary[]; page: number; size: number;
  totalItems: number; }
export interface InstallmentPreview { description: string; totalAmount: string; installmentCount: number;
  firstDueDate: string; lastDueDate: string; regularAmount: string; lastAmount: string;
  lastInstallmentAdjustment: string; installmentsSum: string; installments: InstallmentItem[]; }
export interface InstallmentPurchase { id: string; description: string; totalAmount: string; installmentCount: number;
  firstDueDate: string; lastDueDate: string; categoryId: string | null; categoryName: string | null;
  responsibleUserId: string | null; responsibleDisplayName: string | null; createdByUserId: string;
  createdByDisplayName: string; createdAt: string; installmentsSum: string; progress?: InstallmentProgress;
  replacesPurchaseId?: string | null; installments: InstallmentItem[]; }
/** H05.3: what a change or cancellation would do; the token must come back unchanged with the confirmation. */
export interface InstallmentFieldChange { field: string; from: string | null; to: string | null; }
export interface AffectedInstallment { number: number; expenseId: string; version: number; amount: string;
  dueDate: string; changes: InstallmentFieldChange[]; }
export interface PreservedInstallment { number: number; status: 'PENDING' | 'PAID' | 'CANCELLED'; reason: string; }
export interface InstallmentImpact { changeType: 'CHANGE' | 'CANCELLATION'; impactToken: string;
  affected: AffectedInstallment[]; preserved: PreservedInstallment[]; affectedAmount: string;
  replacement: InstallmentPreview | null; }
export type InstallmentChangeField = 'description' | 'categoryId' | 'responsibleUserId' | 'dueDate';
export interface InstallmentChangeData { fromNumber: number; scope: 'THIS' | 'THIS_AND_FOLLOWING';
  changedFields: InstallmentChangeField[]; description: string | null; categoryId: string | null;
  responsibleUserId: string | null; dueDate: string | null; }
export interface InstallmentCancellationData { installmentNumbers: number[]; reason: string;
  replacement: InstallmentPurchaseData | null; }
export interface InstallmentChangeResult { changeId: string; changeType: 'CHANGE' | 'CANCELLATION';
  affectedCount: number; preservedCount: number; purchase: InstallmentPurchase;
  replacement: InstallmentPurchase | null; replayed: boolean; }

@Injectable({ providedIn: 'root' })
export class InstallmentPurchaseService {
  private readonly http = inject(HttpClient);
  private readonly endpoint = '/api/v1/installment-purchases';
  newIdempotencyKey() { return crypto.randomUUID(); }
  /** Amounts and dates always come from the backend, which revalidates everything again on creation. */
  preview(data: InstallmentPurchaseData) {
    return this.http.get('/api/v1/auth/csrf').pipe(switchMap(() =>
      this.http.post<InstallmentPreview>(`${this.endpoint}/preview`, data)));
  }
  list(page = 0, size = 20) {
    return this.http.get<InstallmentPurchasePage>(this.endpoint, { params: { page, size } });
  }
  get(id: string) {
    return this.http.get<InstallmentPurchase>(`${this.endpoint}/${id}`);
  }
  previewChange(id: string, data: InstallmentChangeData) {
    return this.http.get('/api/v1/auth/csrf').pipe(switchMap(() =>
      this.http.post<InstallmentImpact>(`${this.endpoint}/${id}/changes/preview`, data)));
  }
  applyChange(id: string, data: InstallmentChangeData, impactToken: string, key: string) {
    return this.http.get('/api/v1/auth/csrf').pipe(switchMap(() =>
      this.http.post<InstallmentChangeResult>(`${this.endpoint}/${id}/changes`, { ...data, impactToken },
        { headers: new HttpHeaders({ 'Idempotency-Key': key }) })));
  }
  previewCancellation(id: string, data: InstallmentCancellationData) {
    return this.http.get('/api/v1/auth/csrf').pipe(switchMap(() =>
      this.http.post<InstallmentImpact>(`${this.endpoint}/${id}/cancellation/preview`, data)));
  }
  applyCancellation(id: string, data: InstallmentCancellationData, impactToken: string, key: string) {
    return this.http.get('/api/v1/auth/csrf').pipe(switchMap(() =>
      this.http.post<InstallmentChangeResult>(`${this.endpoint}/${id}/cancellation`, { ...data, impactToken },
        { headers: new HttpHeaders({ 'Idempotency-Key': key }) })));
  }
  create(data: InstallmentPurchaseData, key: string) {
    return this.http.get('/api/v1/auth/csrf').pipe(switchMap(() =>
      this.http.post<InstallmentPurchase>(this.endpoint, data, { headers: new HttpHeaders({ 'Idempotency-Key': key }) })));
  }
}
