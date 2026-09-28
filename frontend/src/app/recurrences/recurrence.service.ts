import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { switchMap } from 'rxjs';
export type RecurrenceFrequency='MONTHLY'|'BIMONTHLY'|'QUARTERLY'|'SEMIANNUAL'|'ANNUAL';
export type RecurrenceValueType='FIXED'|'VARIABLE_ESTIMATE';
export interface RecurrenceData { description:string;amount:string;valueType:RecurrenceValueType;frequency:RecurrenceFrequency;firstDueDate:string;lastDueDate:string|null;categoryId:string|null;responsibleUserId:string|null; }
export interface Recurrence extends RecurrenceData { id:string;baseDay:number;categoryName:string|null;responsibleDisplayName:string|null;createdByDisplayName:string;createdAt:string;version:number;previewDates:string[]; }
export interface RecurrenceForecast { recurrenceId:string;description:string;amount:string;estimated:boolean;scheduledDueDate:string;state:'FORECAST'|'MATERIALIZED';expenseId:string|null;actualDueDate:string|null;expenseStatus:string|null;chargeConfirmed:boolean; }
export interface ForecastPeriod { from:string;to:string;occurrences:RecurrenceForecast[]; }
export interface AnticipationResult { occurrence:RecurrenceForecast;replayed:boolean; }
@Injectable({providedIn:'root'}) export class RecurrenceService {
  private readonly http=inject(HttpClient);private readonly endpoint='/api/v1/recurrences';
  newIdempotencyKey(){return crypto.randomUUID();} list(){return this.http.get<Recurrence[]>(this.endpoint);}
  preview(data:{firstDueDate:string;lastDueDate:string|null;frequency:RecurrenceFrequency}){return this.http.get('/api/v1/auth/csrf').pipe(switchMap(()=>this.http.post<string[]>(`${this.endpoint}/calendar-preview`,data)));}
  create(data:RecurrenceData,key:string){return this.http.get('/api/v1/auth/csrf').pipe(switchMap(()=>this.http.post<Recurrence>(this.endpoint,data,{headers:new HttpHeaders({'Idempotency-Key':key})})));}
  forecasts(){return this.http.get<ForecastPeriod>(`${this.endpoint}/forecasts`);}
  anticipate(item:RecurrenceForecast,key:string){return this.http.get('/api/v1/auth/csrf').pipe(switchMap(()=>this.http.post<AnticipationResult>(`${this.endpoint}/${item.recurrenceId}/occurrences/${item.scheduledDueDate}/anticipation`,{confirmed:true},{headers:new HttpHeaders({'Idempotency-Key':key})})));}
}
