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

@Injectable({ providedIn: 'root' })
export class ReportService {
  private readonly http = inject(HttpClient);
  private readonly endpoint = '/api/v1/reports';

  dueDashboard(month: string, filters: ReportFilters = {}) {
    return this.http.get<DueDashboard>(`${this.endpoint}/due-dashboard`, { params: params(month, filters) });
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
