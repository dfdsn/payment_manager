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
  expenseId: string | null; status: 'PENDING' | 'PAID' | 'CANCELLED' | null; }
export interface InstallmentPreview { description: string; totalAmount: string; installmentCount: number;
  firstDueDate: string; lastDueDate: string; regularAmount: string; lastAmount: string;
  lastInstallmentAdjustment: string; installmentsSum: string; installments: InstallmentItem[]; }
export interface InstallmentPurchase { id: string; description: string; totalAmount: string; installmentCount: number;
  firstDueDate: string; lastDueDate: string; categoryId: string | null; categoryName: string | null;
  responsibleUserId: string | null; responsibleDisplayName: string | null; createdByUserId: string;
  createdByDisplayName: string; createdAt: string; installmentsSum: string; installments: InstallmentItem[]; }

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
  create(data: InstallmentPurchaseData, key: string) {
    return this.http.get('/api/v1/auth/csrf').pipe(switchMap(() =>
      this.http.post<InstallmentPurchase>(this.endpoint, data, { headers: new HttpHeaders({ 'Idempotency-Key': key }) })));
  }
}
