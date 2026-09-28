import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { switchMap } from 'rxjs';
export type RecurrenceFrequency='MONTHLY'|'BIMONTHLY'|'QUARTERLY'|'SEMIANNUAL'|'ANNUAL';
export type RecurrenceValueType='FIXED'|'VARIABLE_ESTIMATE';
export interface RecurrenceData { description:string;amount:string;valueType:RecurrenceValueType;frequency:RecurrenceFrequency;firstDueDate:string;lastDueDate:string|null;categoryId:string|null;responsibleUserId:string|null; }
export interface RecurrenceSegment { effectiveMonth:string;description:string;amount:string;frequency:RecurrenceFrequency;dueDay:number;categoryId:string|null;categoryName:string|null;responsibleUserId:string|null;responsibleDisplayName:string|null; }
export interface RecurrenceChange { id:string;type:'CHANGE'|'CLOSURE';actorUserId:string;actorDisplayName:string;occurredAt:string;version:number;effectiveDueDate:string;changedFields:string[];reason:string|null;updatedCount:number;removedCount:number;reviewCount:number;preservedCount:number; }
export interface Recurrence extends RecurrenceData { id:string;baseDay:number;categoryName:string|null;responsibleDisplayName:string|null;createdByDisplayName:string;createdAt:string;version:number;previewDates:string[];upcomingDates?:string[];closedAt?:string|null;closedByDisplayName?:string|null;closureReason?:string|null;segments?:RecurrenceSegment[];changes?:RecurrenceChange[]; }
export type ReviewReason='AFTER_END'|'OUTSIDE_SCHEDULE';
export interface RecurrenceForecast { recurrenceId:string;description:string;amount:string;estimated:boolean;scheduledDueDate:string;state:'FORECAST'|'MATERIALIZED';expenseId:string|null;actualDueDate:string|null;expenseStatus:string|null;chargeConfirmed:boolean;reviewReason?:ReviewReason|null; }
/** H04.5: full configuration wanted from the period of effectiveDueDate on ("este e os próximos"). */
export interface ChangeRequest { version:number;effectiveDueDate:string;description:string;amount:string;frequency:RecurrenceFrequency;dueDay:number;categoryId:string|null;responsibleUserId:string|null;impactToken?:string; }
export interface ClosureRequest { version:number;lastDueDate:string;reason?:string;impactToken?:string; }
export type ImpactAction='UPDATE'|'REMOVE'|'REVIEW'|'PRESERVE';
export interface ImpactOccurrence { expenseId:string;scheduledDueDate:string;dueDate:string;description:string;amount:string;status:string;chargeConfirmed:boolean;action:ImpactAction;reason:string;changes:{field:string;previousValue:string|null;newValue:string|null}[];preservedFields:string[]; }
export interface ImpactForecast { month:string;action:'CHANGED'|'ADDED'|'REMOVED';previousDueDate:string|null;newDueDate:string|null;previousAmount:string|null;newAmount:string|null;previousDescription:string|null;newDescription:string|null; }
export interface RecurrenceImpact { recurrenceId:string;operation:'CHANGE'|'CLOSURE';version:number;effectiveDueDate:string;changedFields:string[];impactToken:string;occurrences:ImpactOccurrence[];forecasts:ImpactForecast[];updatedCount:number;removedCount:number;reviewCount:number;preservedCount:number; }
export interface RecurrenceChangeResult { recurrence:Recurrence;change:RecurrenceChange;replayed:boolean; }
export interface ForecastPeriod { from:string;to:string;occurrences:RecurrenceForecast[]; }
export interface AnticipationResult { occurrence:RecurrenceForecast;replayed:boolean; }
@Injectable({providedIn:'root'}) export class RecurrenceService {
  private readonly http=inject(HttpClient);private readonly endpoint='/api/v1/recurrences';
  newIdempotencyKey(){return crypto.randomUUID();} list(){return this.http.get<Recurrence[]>(this.endpoint);}
  preview(data:{firstDueDate:string;lastDueDate:string|null;frequency:RecurrenceFrequency}){return this.http.get('/api/v1/auth/csrf').pipe(switchMap(()=>this.http.post<string[]>(`${this.endpoint}/calendar-preview`,data)));}
  create(data:RecurrenceData,key:string){return this.http.get('/api/v1/auth/csrf').pipe(switchMap(()=>this.http.post<Recurrence>(this.endpoint,data,{headers:new HttpHeaders({'Idempotency-Key':key})})));}
  forecasts(){return this.http.get<ForecastPeriod>(`${this.endpoint}/forecasts`);}
  confirmForecastCharge(item:RecurrenceForecast,confirmedAmount:string,key:string){return this.http.get('/api/v1/auth/csrf').pipe(switchMap(()=>this.http.post<AnticipationResult>(`${this.endpoint}/${item.recurrenceId}/occurrences/${item.scheduledDueDate}/charge-confirmation`,{confirmedAmount},{headers:new HttpHeaders({'Idempotency-Key':key})})));}
  previewChange(id:string,data:ChangeRequest){return this.http.get('/api/v1/auth/csrf').pipe(switchMap(()=>this.http.post<RecurrenceImpact>(`${this.endpoint}/${id}/changes/preview`,data)));}
  applyChange(id:string,data:ChangeRequest,key:string){return this.http.get('/api/v1/auth/csrf').pipe(switchMap(()=>this.http.post<RecurrenceChangeResult>(`${this.endpoint}/${id}/changes`,data,{headers:new HttpHeaders({'Idempotency-Key':key})})));}
  previewClosure(id:string,data:ClosureRequest){return this.http.get('/api/v1/auth/csrf').pipe(switchMap(()=>this.http.post<RecurrenceImpact>(`${this.endpoint}/${id}/closure/preview`,data)));}
  applyClosure(id:string,data:ClosureRequest,key:string){return this.http.get('/api/v1/auth/csrf').pipe(switchMap(()=>this.http.post<RecurrenceChangeResult>(`${this.endpoint}/${id}/closure`,data,{headers:new HttpHeaders({'Idempotency-Key':key})})));}
  anticipate(item:RecurrenceForecast,key:string){return this.http.get('/api/v1/auth/csrf').pipe(switchMap(()=>this.http.post<AnticipationResult>(`${this.endpoint}/${item.recurrenceId}/occurrences/${item.scheduledDueDate}/anticipation`,{confirmed:true},{headers:new HttpHeaders({'Idempotency-Key':key})})));}
}
