import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { ExpenseStatusFilter } from '../expenses/expense.service';

/** Filters shared with the expense list (H03.4), except the period: reports always cover one calendar month. */
export interface ReportFilters {
  search?: string;
  categoryId?: string;
  withoutCategory?: boolean;
  responsibleUserId?: string;
  withoutResponsible?: boolean;
  payerUserId?: string;
  status?: ExpenseStatusFilter;
}

export interface DueIndicators {
  plannedCount: number; plannedTotal: string; plannedEstimated: string;
  paidCount: number; paidTotal: string;
  pendingCount: number; pendingTotal: string; pendingEstimated: string;
  overdueCount: number; overdueTotal: string; overdueEstimated: string;
  adjustmentIncrease: string; adjustmentDiscount: string; adjustmentNet: string;
}

export interface PreviousPending {
  dueBefore: string; count: number; total: string; estimated: string; overdueCount: number; overdueTotal: string;
}

export interface DueDashboard {
  month: string;
  periodStart: string;
  periodEnd: string;
  dateBasis: 'DUE_DATE';
  today: string;
  timeZone: string;
  indicators: DueIndicators;
  previousPending: PreviousPending;
}

export interface PaymentIndicators {
  count: number; paidTotal: string; chargeTotal: string;
  adjustmentIncrease: string; adjustmentDiscount: string; adjustmentNet: string;
}

export interface PaymentCorrection {
  actorUserId: string; actorDisplayName: string; correctedAt: string; changedFields: string[];
}

export interface PaymentRow {
  expenseId: string; description: string; origin: 'ONE_OFF' | 'RECURRENCE' | 'INSTALLMENT';
  installment: { purchaseId: string; number: number; count: number } | null;
  dueDate: string | null; chargeAmount: string; chargeConfirmed: boolean; paidAmount: string; adjustment: string;
  paymentDate: string; payerUserId: string; payerDisplayName: string; recordedByUserId: string;
  recordedByDisplayName: string; recordedAt: string; batchPayment: boolean; categoryName: string | null;
  responsibleDisplayName: string | null; correctionCount: number; lastCorrection: PaymentCorrection | null;
}

export type PaymentSort = 'PAYMENT_DATE' | 'PAID_AMOUNT' | 'DESCRIPTION';

export interface PaymentReport {
  month: string; periodStart: string; periodEnd: string; dateBasis: 'PAYMENT_DATE'; timeZone: string;
  indicators: PaymentIndicators; content: PaymentRow[]; page: number; size: number; totalElements: number;
  totalPages: number; sort: PaymentSort; direction: 'ASC' | 'DESC';
}

/** H06.3 indicators: every value is a sum of lines, never a difference between totals. */
export interface PlanningTotals {
  count: number; plannedTotal: string; confirmedTotal: string; estimatedTotal: string;
  materializedCount: number; materializedTotal: string; forecastCount: number; forecastTotal: string;
  paidCount: number; paidTotal: string; openCount: number; openTotal: string;
  oneOffTotal: string; installmentTotal: string; recurrenceTotal: string;
}

export interface PlanningItem {
  kind: 'EXPENSE' | 'FORECAST'; expenseId: string | null; recurrenceId: string | null;
  origin: 'ONE_OFF' | 'RECURRENCE' | 'INSTALLMENT';
  installment: { purchaseId: string; number: number; count: number } | null;
  description: string; date: string; dueDate: string | null; amount: string; estimated: boolean;
  status: 'PENDING' | 'PAID' | 'FORECAST'; overdue: boolean; paidAmount: string | null; paymentDate: string | null;
  categoryName: string | null; responsibleDisplayName: string | null;
}

export interface Planning {
  horizonStart: string; horizonEnd: string; periodStart: string; periodEnd: string; dateBasis: 'DUE_DATE';
  today: string; timeZone: string; totals: PlanningTotals; months: { month: string; totals: PlanningTotals }[];
  month: string; monthTotals: PlanningTotals; content: PlanningItem[]; page: number; size: number;
  totalElements: number; totalPages: number;
}

export type PlanningFilters = Pick<ReportFilters, 'search' | 'categoryId' | 'withoutCategory' | 'responsibleUserId'
  | 'withoutResponsible'>;

@Injectable({ providedIn: 'root' })
export class ReportService {
  private readonly http = inject(HttpClient);
  private readonly endpoint = '/api/v1/reports';

  dueDashboard(month: string, filters: ReportFilters = {}) {
    return this.http.get<DueDashboard>(`${this.endpoint}/due-dashboard`, { params: params(month, filters) });
  }

  /** H06.2: active payments by effective payment date; the situation filter does not apply. */
  payments(month: string, filters: Omit<ReportFilters, 'status'>, page: number, size: number, sort: PaymentSort,
    direction: 'ASC' | 'DESC') {
    const query = params(month, { ...filters, page, size, sort, direction });
    return this.http.get<PaymentReport>(`${this.endpoint}/payments`, { params: query });
  }

  /** H06.3: current month plus 12; {@code month} picks which month of the horizon is listed. */
  planning(month: string | null, filters: PlanningFilters, page: number, size: number) {
    let query = params(month ?? '', { ...filters, page, size });
    if (!month) query = query.delete('month');
    return this.http.get<Planning>(`${this.endpoint}/planning`, { params: query });
  }
}

export function params(month: string, filters: object): HttpParams {
  let result = new HttpParams().set('month', month);
  for (const [key, value] of Object.entries(filters))
    if (value !== undefined && value !== null && value !== '' && value !== false) result = result.set(key, String(value));
  return result;
}

/** Current month (AAAA-MM) in the space time zone, not in the browser's. */
export function currentMonth(timeZone: string, now = new Date()): string {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone, year: 'numeric', month: '2-digit' }).formatToParts(now);
  const part = (type: string) => parts.find(value => value.type === type)!.value;
  return `${part('year')}-${part('month')}`;
}

export function shiftMonth(month: string, delta: number): string {
  const [year, value] = month.split('-').map(Number);
  const index = year * 12 + (value - 1) + delta;
  return `${Math.floor(index / 12)}-${String((index % 12) + 1).padStart(2, '0')}`;
}

export function monthBounds(month: string): { from: string; to: string } {
  const [year, value] = month.split('-').map(Number);
  const last = new Date(Date.UTC(year, value, 0)).getUTCDate();
  return { from: `${month}-01`, to: `${month}-${String(last).padStart(2, '0')}` };
}

export function monthLabel(month: string): string {
  const [year, value] = month.split('-').map(Number);
  const label = new Intl.DateTimeFormat('pt-BR', { month: 'long', year: 'numeric', timeZone: 'UTC' })
    .format(new Date(Date.UTC(year, value - 1, 1)));
  return label.charAt(0).toUpperCase() + label.slice(1);
}

/**
 * Formats the API decimal string exactly, digit by digit: totals may exceed what a JavaScript number keeps without
 * losing cents, so the value is never converted to a number.
 */
export function formatCurrency(value: string): string {
  const negative = value.startsWith('-');
  const [integer, decimals = ''] = (negative ? value.slice(1) : value).split('.');
  const grouped = integer.replace(/^0+(?=\d)/, '').replace(/\B(?=(\d{3})+(?!\d))/g, '.');
  return `${negative ? '-' : ''}R$\u00a0${grouped},${decimals.padEnd(2, '0').slice(0, 2)}`;
}

/** Signed amount for adjustments: “+R$ 5,00”, “-R$ 10,00” or “R$ 0,00”. */
export function formatSigned(value: string): string {
  if (/^-?0+(\.0+)?$/.test(value)) return formatCurrency('0.00');
  return value.startsWith('-') ? formatCurrency(value) : `+${formatCurrency(value)}`;
}

export function formatDate(value: string | null): string {
  if (!value) return 'Não informado';
  const [year, month, day] = value.split('-');
  return `${day}/${month}/${year}`;
}

/** Technical instants are UTC; members read them in the space time zone. */
export function formatInstant(value: string, timeZone: string): string {
  return new Intl.DateTimeFormat('pt-BR', { timeZone, day: '2-digit', month: '2-digit', year: 'numeric',
    hour: '2-digit', minute: '2-digit' }).format(new Date(value));
}

const PAYMENT_FIELD_LABELS: Record<string, string> = {
  amount: 'valor da cobrança', paidAmount: 'valor pago', paymentDate: 'data do pagamento', paidByUserId: 'pagador',
};

export function paymentFields(fields: string[]): string {
  return fields.map(field => PAYMENT_FIELD_LABELS[field] ?? field).join(', ');
}
