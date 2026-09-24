import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';

export type InitialSetupStatus = 'AVAILABLE' | 'SECRET_NOT_CONFIGURED' | 'COMPLETED';

export interface InitialSetupData {
  administratorName: string;
  email: string;
  password: string;
  spaceName: string;
}

export interface InitialSetupResult {
  administratorId: string;
  administratorName: string;
  email: string;
  spaceId: string;
  spaceName: string;
  currency: 'BRL';
  locale: 'pt-BR';
  timeZone: 'America/Sao_Paulo';
}

export interface ApiError {
  code: string;
  message: string;
  fieldErrors: { field: string; message: string }[];
  operationId: string;
}

@Injectable({ providedIn: 'root' })
export class InitialSetupService {
  private readonly http = inject(HttpClient);
  private readonly endpoint = '/api/v1/setup';

  status() {
    return this.http.get<{ status: InitialSetupStatus }>(`${this.endpoint}/status`);
  }

  configure(data: InitialSetupData, setupSecret: string) {
    return this.http.post<InitialSetupResult>(this.endpoint, data, {
      headers: new HttpHeaders({ 'X-Setup-Secret': setupSecret }),
    });
  }
}
