import { HttpErrorResponse, HttpEvent, HttpEventType, HttpHeaders, HttpResponse, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Observable, Subject, throwError } from 'rxjs';
import { CsvExportButtonComponent } from './csv-export-button.component';
import { CsvExportService, attachmentName } from './csv-export.service';

@Component({
  imports: [CsvExportButtonComponent],
  template: `<app-csv-export-button label="Exportar CSV" [request]="request" fallbackName="despesas.csv" />`,
})
class HostComponent {
  readonly calls = signal(0);
  source: () => Observable<HttpEvent<Blob>> = () => new Subject<HttpEvent<Blob>>();
  readonly request = () => { this.calls.update(v => v + 1); return this.source(); };
}

describe('CsvExportButtonComponent', () => {
  let fixture: ComponentFixture<HostComponent>;
  let host: HostComponent;
  const element = () => fixture.nativeElement as HTMLElement;
  const button = () => element().querySelector('button') as HTMLButtonElement;
  const byTestId = (id: string) => element().querySelector(`[data-testid="${id}"]`)?.textContent?.trim();
  const response = (body: Blob | null, rows: string, name = 'despesas_vencimento_2026-10-01_a_2026-10-31.csv') =>
    new HttpResponse<Blob>({ body, status: 200, headers: new HttpHeaders({
      'X-Export-Rows': rows, 'Content-Disposition': `attachment; filename="${name}"` }) });

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [HostComponent] }).compileComponents();
    fixture = TestBed.createComponent(HostComponent);
    host = fixture.componentInstance;
    fixture.detectChanges();
  });

  afterEach(() => vi.restoreAllMocks());

  it('shows progress, blocks a second click and saves the file with the server name', () => {
    const events = new Subject<HttpEvent<Blob>>();
    host.source = () => events;
    const created = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:x');
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);

    button().click();
    fixture.detectChanges();
    expect(button().disabled).toBe(true);
    expect(button().textContent).toContain('Gerando arquivo…');
    expect(element().textContent).toContain('Gerando arquivo no servidor…');
    button().click();
    expect(host.calls()).toBe(1);

    events.next({ type: HttpEventType.DownloadProgress, loaded: 50, total: 200 });
    fixture.detectChanges();
    expect(element().textContent).toContain('Baixando… 25%');

    events.next(response(new Blob(['x']), '2'));
    fixture.detectChanges();
    expect(created).toHaveBeenCalledTimes(1);
    expect(click).toHaveBeenCalledTimes(1);
    expect(click.mock.contexts[0]).toMatchObject({ download: 'despesas_vencimento_2026-10-01_a_2026-10-31.csv' });
    expect(byTestId('csv-export-message')).toBe('Arquivo despesas_vencimento_2026-10-01_a_2026-10-31.csv baixado com 2 registros.');
    expect(button().disabled).toBe(false);
  });

  it('does not save an empty selection and says so', () => {
    const events = new Subject<HttpEvent<Blob>>();
    host.source = () => events;
    const created = vi.spyOn(URL, 'createObjectURL');
    button().click();
    events.next(response(new Blob(['header']), '0'));
    fixture.detectChanges();
    expect(created).not.toHaveBeenCalled();
    expect(byTestId('csv-export-message')).toBe('Nenhum registro com os filtros atuais; nenhum arquivo foi baixado.');
  });

  it('shows the server message of a refused export and a clear message for other failures', async () => {
    const limit = JSON.stringify({ code: 'EXPORT_LIMIT_EXCEEDED',
      message: 'A seleção tem 10.001 registros e a exportação aceita até 10.000. Reduza o período ou aplique filtros e exporte em partes.' });
    host.source = () => throwError(() => new HttpErrorResponse({ status: 422,
      error: new Blob([limit], { type: 'application/json' }) }));
    button().click();
    await vi.waitFor(() => { fixture.detectChanges(); expect(byTestId('csv-export-error')).toContain('10.001 registros'); });
    expect(button().disabled).toBe(false);

    host.source = () => throwError(() => new HttpErrorResponse({ status: 0 }));
    button().click();
    await vi.waitFor(() => { fixture.detectChanges(); expect(byTestId('csv-export-error')).toBe(
      'Não foi possível gerar o arquivo. Verifique a conexão e tente novamente.'); });

    host.source = () => throwError(() => new HttpErrorResponse({ status: 401, error: new Blob(['{}']) }));
    button().click();
    await vi.waitFor(() => { fixture.detectChanges(); expect(byTestId('csv-export-error')).toBe(
      'Sua sessão terminou. Entre novamente para exportar.'); });

    host.source = () => throwError(() => new HttpErrorResponse({ status: 500, error: new Blob(['<html>']) }));
    button().click();
    await vi.waitFor(() => { fixture.detectChanges(); expect(byTestId('csv-export-error')).toBe(
      'Não foi possível gerar o arquivo. Verifique a conexão e tente novamente.'); });
    expect(byTestId('csv-export-message')).toBeUndefined();
  });

  it('never takes a path from the server file name', () => {
    expect(attachmentName('attachment; filename="../../x/despesas.csv"', 'f.csv')).toBe('despesas.csv');
    expect(attachmentName(null, 'f.csv')).toBe('f.csv');
    expect(attachmentName('attachment', 'f.csv')).toBe('f.csv');
  });
});

describe('CsvExportService', () => {
  it('sends the filters and order of the list, without empty values, as a blob download', () => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    const service = TestBed.inject(CsvExportService);
    const http = TestBed.inject(HttpTestingController);

    service.expenses({ search: 'luz', dateFrom: '2026-10-01', dateTo: undefined, dateBasis: 'PAYMENT_DATE', withoutCategory: false,
      status: 'ALL' }, 'AMOUNT', 'DESC').subscribe();
    const expenses = http.expectOne(r => r.url === '/api/v1/reports/expenses/export');
    expect(expenses.request.responseType).toBe('blob');
    expect(expenses.request.params.toString())
      .toBe('search=luz&dateFrom=2026-10-01&dateBasis=PAYMENT_DATE&status=ALL&sort=AMOUNT&direction=DESC');

    service.forecasts({ categoryId: 'c', withoutResponsible: true }).subscribe();
    const forecasts = http.expectOne(r => r.url === '/api/v1/reports/planning/export');
    expect(forecasts.request.params.toString()).toBe('categoryId=c&withoutResponsible=true');
    http.verify();
  });
});
