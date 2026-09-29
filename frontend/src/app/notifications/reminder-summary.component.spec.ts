import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { ReminderPreviewComponent } from './reminder-preview.component';
import { ReminderSummaryComponent } from './reminder-summary.component';
import { ReminderSummary, ReminderSummaryItem, ReminderSummaryService } from './reminder-summary.service';

const item = (position: number, overrides: Partial<ReminderSummaryItem> = {}): ReminderSummaryItem => ({
  position, expenseId: `e${position}`, recurrenceId: null, description: `Conta ${position}`, label: `Conta ${position}`,
  amount: '10.00', dueDate: '2026-10-07', estimated: false, overdue: false, forecast: false, origin: 'ONE_OFF',
  installmentNumber: null, installmentCount: null, ...overrides,
});

const seven = (id: string | null): ReminderSummary => ({
  id, date: '2026-10-05', slot: 'FIRST', scheduledTime: '09:00', timeZone: 'America/Sao_Paulo',
  generatedAt: id ? '2026-10-05T12:00:05Z' : null, count: 7, total: '1349.91', estimatedCount: 1, estimatedTotal: '99.99',
  overdueCount: 1, detailCount: 5, remaining: 2,
  items: [item(1, { label: 'Aluguel', amount: '1234.56', dueDate: '2026-10-01', overdue: true }),
    item(2, { label: 'Luz', amount: '99.99', estimated: true, origin: 'RECURRENCE' }),
    item(3, { label: 'Geladeira (3/10)', origin: 'INSTALLMENT', installmentNumber: 3, installmentCount: 10 }),
    item(4, { label: 'Internet', forecast: true, expenseId: null, recurrenceId: 'r1', origin: 'RECURRENCE_FORECAST' }),
    item(5), item(6, { label: 'Oculta A' }), item(7, { label: 'Oculta B' })],
  link: id ? `https://contas.malyah.tech/lembretes/resumos/${id}` : null, text: 'Contas a pagar — 05/10/2026, 09:00',
  channels: [{ channel: 'IN_APP', status: 'PLANNED', reason: null },
    { channel: 'WHATSAPP', status: 'SKIPPED', reason: 'PROVIDER_UNAVAILABLE' }],
});

describe('Reminder summaries (H08.2)', () => {
  const api = { preview: vi.fn(), summary: vi.fn() };
  const render = async (fixture: ComponentFixture<unknown>) => { fixture.detectChanges(); await fixture.whenStable(); fixture.detectChanges(); };
  const text = (fixture: ComponentFixture<unknown>) => (fixture.nativeElement as HTMLElement).textContent!.replace(/\s+/g, ' ');
  const byTestId = (fixture: ComponentFixture<unknown>, id: string) =>
    (fixture.nativeElement as HTMLElement).querySelector<HTMLElement>(`[data-testid="${id}"]`);

  beforeEach(async () => {
    Object.values(api).forEach(mock => mock.mockReset());
    await TestBed.configureTestingModule({
      imports: [ReminderPreviewComponent, ReminderSummaryComponent],
      providers: [provideRouter([]), { provide: ReminderSummaryService, useValue: api },
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ id: 's1' }) } } }],
    }).compileComponents();
  });

  it('shows the first slot of today, five details, the remainder and the full list', async () => {
    api.preview.mockReturnValue(of({ date: '2026-10-05', slot: 'FIRST', scheduledTime: '09:00', timeZone: 'America/Sao_Paulo',
      today: '2026-10-05', summary: seven(null) }));
    const fixture = TestBed.createComponent(ReminderPreviewComponent);
    await render(fixture);
    expect(api.preview).toHaveBeenCalledWith(null, 'FIRST');
    expect(byTestId(fixture, 'preview-heading')!.textContent).toContain('Primeiro horário de 05/10/2026, 09:00');
    expect(byTestId(fixture, 'summary-total')!.textContent).toContain('R$ 1.349,91');
    expect(byTestId(fixture, 'summary-estimates')!.textContent).toContain('1 estimada: R$ 99,99');
    const details = byTestId(fixture, 'summary-details')!.querySelectorAll('li');
    expect(details.length).toBe(5);
    expect(details[0].textContent).toContain('Atrasada');
    expect(details[0].textContent).toContain('01/10/2026 Aluguel — R$ 1.234,56');
    expect(details[1].textContent).toContain('Estimada');
    expect(details[2].textContent).toContain('Geladeira (3/10)');
    expect(details[3].textContent).toContain('Previsão');
    expect(byTestId(fixture, 'summary-remaining')!.textContent).toContain('e mais 2 contas');
    expect(byTestId(fixture, 'summary-items')!.querySelectorAll('li').length).toBe(7);
    expect(text(fixture)).toContain('Oculta B');
    expect(byTestId(fixture, 'summary-channels')!.textContent).toContain('não será enviado (envio real ainda indisponível)');
    expect(byTestId(fixture, 'summary-channels')!.textContent).toContain('No aplicativo (os dois membros): previsto');
    expect(text(fixture)).not.toContain('Abrir conta');
  });

  it('simulates another date and slot and explains an empty slot', async () => {
    api.preview.mockReturnValueOnce(of({ date: '2026-10-05', slot: 'FIRST', scheduledTime: '09:00', timeZone: 'America/Sao_Paulo',
      today: '2026-10-05', summary: seven(null) }));
    api.preview.mockReturnValueOnce(of({ date: '2026-10-12', slot: 'SECOND', scheduledTime: '18:00', timeZone: 'America/Sao_Paulo',
      today: '2026-10-05', summary: null }));
    const fixture = TestBed.createComponent(ReminderPreviewComponent);
    await render(fixture);
    const input = byTestId(fixture, 'preview-date') as HTMLInputElement;
    input.value = '2026-10-12';
    input.dispatchEvent(new Event('input'));
    byTestId(fixture, 'preview-second')!.click();
    await render(fixture);
    byTestId(fixture, 'preview-submit')!.click();
    await render(fixture);
    expect(api.preview).toHaveBeenLastCalledWith('2026-10-12', 'SECOND');
    expect(byTestId(fixture, 'preview-empty')!.textContent).toContain('nenhum resumo seria gerado');
    expect(byTestId(fixture, 'preview-heading')!.textContent).toContain('Segundo horário de 12/10/2026, 18:00');
  });

  it('shows the server message for an invalid date and asks to sign in without a session', async () => {
    api.preview.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 400,
      error: { code: 'REMINDER_QUERY_INVALID', message: 'Escolha uma data de hoje até 60 dias à frente.' } })));
    const fixture = TestBed.createComponent(ReminderPreviewComponent);
    await render(fixture);
    expect(byTestId(fixture, 'preview-error')!.textContent).toContain('60 dias');
    api.preview.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 401 })));
    byTestId(fixture, 'preview-submit')!.click();
    await render(fixture);
    expect(byTestId(fixture, 'preview-error')!.textContent).toContain('Entre para consultar');
  });

  it('opens a generated summary by its link', async () => {
    api.summary.mockReturnValue(of(seven('s1')));
    const fixture = TestBed.createComponent(ReminderSummaryComponent);
    await render(fixture);
    expect(api.summary).toHaveBeenCalledWith('s1');
    expect(byTestId(fixture, 'summary-heading')!.textContent).toContain('Primeiro horário de 05/10/2026, 09:00');
    expect(text(fixture)).toContain('Gerado em 05/10/2026');
    expect(byTestId(fixture, 'summary-items')!.querySelectorAll('li').length).toBe(7);
  });

  it('shows the current situation of each bill next to the historical content (H08.3)', async () => {
    const summary = seven('s1');
    summary.items[0] = { ...summary.items[0], currentStatus: 'PAID', currentDueDate: '2026-10-01' };
    summary.items[1] = { ...summary.items[1], currentStatus: 'PENDING', currentDueDate: '2026-10-20' };
    summary.items[2] = { ...summary.items[2], currentStatus: 'PENDING', currentDueDate: '2026-10-07' };
    api.summary.mockReturnValue(of(summary));
    const fixture = TestBed.createComponent(ReminderSummaryComponent);
    await render(fixture);
    const details = byTestId(fixture, 'summary-details')!.querySelectorAll('li');
    expect(details[0].textContent).toContain('1.234,56');
    expect(details[0].textContent).toContain('Agora: paga');
    expect(details[1].textContent).toContain('Agora vence em 20/10/2026');
    expect(details[2].textContent).not.toContain('Agora');
    expect(details[3].textContent).not.toContain('Abrir conta');
    expect(details[0].querySelector('a')!.getAttribute('href')).toBe('/despesas?despesa=e1');
  });

  it('distinguishes a missing session from a summary of another space', async () => {
    api.summary.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 401 })));
    const unauthenticated = TestBed.createComponent(ReminderSummaryComponent);
    await render(unauthenticated);
    expect(byTestId(unauthenticated, 'summary-error')!.textContent).toContain('Entre para ver este resumo');
    expect(byTestId(unauthenticated, 'summary-error')!.querySelector('a')!.getAttribute('href')).toBe('/entrar');
    api.summary.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 404,
      error: { code: 'REMINDER_SUMMARY_NOT_FOUND', message: 'Resumo não encontrado.' } })));
    const missing = TestBed.createComponent(ReminderSummaryComponent);
    await render(missing);
    expect(byTestId(missing, 'summary-error')!.textContent).toContain('Resumo não encontrado no seu espaço');
    expect(byTestId(missing, 'summary-error')!.querySelector('a')).toBeNull();
  });
});
