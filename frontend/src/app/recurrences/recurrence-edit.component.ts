import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, input, output, signal } from '@angular/core';
import { AbstractControl, FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { finalize } from 'rxjs';
import { Category } from '../expenses/category.service';
import { SpaceMember } from '../identity/account-access.service';
import { ChangeRequest, ClosureRequest, ImpactAction, ImpactOccurrence, Recurrence, RecurrenceChangeResult, RecurrenceFrequency,
  RecurrenceImpact, RecurrenceSegment, RecurrenceService } from './recurrence.service';

type Mode = 'change' | 'closure' | null;
const AMOUNT = /^\d{1,8}([.,]\d{1,2})?$/;

/**
 * H04.5: "este e os próximos" and closure of one recurrence. The impact is always reviewed before applying; the
 * server recomputes it when saving and refuses a different set of changes, so conflicts keep the typed data.
 */
@Component({
  selector: 'app-recurrence-edit',
  imports: [ReactiveFormsModule, MatButtonModule, MatFormFieldModule, MatInputModule],
  templateUrl: './recurrence-edit.component.html',
  styleUrl: './recurrence-edit.component.scss'
})
export class RecurrenceEditComponent {
  private readonly fb = inject(FormBuilder);
  private readonly api = inject(RecurrenceService);
  readonly recurrence = input.required<Recurrence>();
  readonly categories = input<Category[]>([]);
  readonly members = input<SpaceMember[]>([]);
  readonly applied = output<RecurrenceChangeResult>();
  readonly conflicted = output<void>();

  readonly mode = signal<Mode>(null);
  readonly impact = signal<RecurrenceImpact | null>(null);
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  readonly message = signal<string | null>(null);
  private key = '';
  private reviewed: { version: number; request: ChangeRequest | ClosureRequest } | null = null;

  readonly dates = computed(() => this.recurrence().upcomingDates ?? []);
  readonly variable = computed(() => this.recurrence().valueType === 'VARIABLE_ESTIMATE');
  readonly closureDates = computed(() => {
    const r = this.recurrence();
    return this.dates().filter(date => !r.lastDueDate || date < r.lastDueDate);
  });

  readonly changeForm = this.fb.nonNullable.group({
    effectiveDueDate: ['', Validators.required],
    description: ['', [Validators.required, Validators.maxLength(200)]],
    amount: ['', [Validators.required, Validators.pattern(AMOUNT), (c: AbstractControl) => c.value && Number(String(c.value).replace(',', '.')) <= 0 ? { positive: true } : null]],
    frequency: ['MONTHLY' as RecurrenceFrequency, Validators.required],
    dueDay: [1, [Validators.required, Validators.min(1), Validators.max(31)]],
    categoryId: [''],
    responsibleUserId: ['']
  });
  readonly closureForm = this.fb.nonNullable.group({
    lastDueDate: ['', Validators.required],
    reason: ['', [Validators.required, Validators.maxLength(2000), (c: AbstractControl) => String(c.value ?? '').trim() ? null : { required: true }]]
  });

  openChange() {
    const first = this.dates()[0] ?? '';
    this.mode.set('change'); this.reset();
    this.changeForm.reset({ effectiveDueDate: first, ...this.configurationFor(first) });
  }

  openClosure() {
    this.mode.set('closure'); this.reset();
    this.closureForm.reset({ lastDueDate: this.closureDates()[0] ?? '', reason: '' });
  }

  close() { this.mode.set(null); this.reset(); }

  /** Choosing another start fills the fields with the configuration in force for that period. */
  startChanged() {
    const date = this.changeForm.controls.effectiveDueDate.value;
    this.changeForm.patchValue(this.configurationFor(date));
    this.impact.set(null);
  }

  editAgain() { this.impact.set(null); this.error.set(null); }

  review() {
    const form = this.mode() === 'change' ? this.changeForm : this.closureForm;
    if (form.invalid || this.busy()) { form.markAllAsTouched(); return; }
    const r = this.recurrence();
    const request = this.mode() === 'change' ? this.changeRequest() : this.closureRequest();
    this.busy.set(true); this.error.set(null); this.message.set(null);
    const call = this.mode() === 'change' ? this.api.previewChange(r.id, request as ChangeRequest)
      : this.api.previewClosure(r.id, request as ClosureRequest);
    call.pipe(finalize(() => this.busy.set(false))).subscribe({
      next: impact => { this.impact.set(impact); this.key = this.api.newIdempotencyKey(); this.reviewed = { version: r.version, request }; },
      error: (e: HttpErrorResponse) => this.fail(e, 'Não foi possível calcular o impacto.')
    });
  }

  confirm() {
    const impact = this.impact(); const reviewed = this.reviewed;
    if (!impact || !reviewed || this.busy()) return;
    const r = this.recurrence();
    this.busy.set(true); this.error.set(null);
    // Exactly the reviewed request is sent, with the token of the impact the user saw.
    const call = impact.operation === 'CHANGE'
      ? this.api.applyChange(r.id, { ...(reviewed.request as ChangeRequest), version: reviewed.version, impactToken: impact.impactToken }, this.key)
      : this.api.applyClosure(r.id, { ...(reviewed.request as ClosureRequest), version: reviewed.version, impactToken: impact.impactToken }, this.key);
    call.pipe(finalize(() => this.busy.set(false))).subscribe({
      next: result => {
        this.message.set(impact.operation === 'CHANGE'
          ? `Alteração aplicada a partir de ${impact.effectiveDueDate}: ${result.change.updatedCount} lançamento(s) atualizado(s), ${result.change.removedCount} retirado(s), ${result.change.reviewCount} para revisão.`
          : `Recorrência encerrada em ${impact.effectiveDueDate}. Nenhuma nova ocorrência será gerada depois dessa data.`);
        this.mode.set(null); this.impact.set(null); this.reviewed = null;
        this.applied.emit(result);
      },
      error: (e: HttpErrorResponse) => {
        this.fail(e, 'Não foi possível salvar. Os dados digitados foram preservados.');
        if (e.status === 409) { this.impact.set(null); this.reviewed = null; this.conflicted.emit(); }
      }
    });
  }

  actionLabel(action: ImpactAction) {
    return ({ UPDATE: 'Será atualizado', REMOVE: 'Sai da programação (cancelado com motivo)', REVIEW: 'Mantido para revisão', PRESERVE: 'Preservado' })[action];
  }

  reasonLabel(o: ImpactOccurrence) {
    return ({ PAID: 'pago', CANCELLED: 'cancelado', AFTER_END: 'depois do término', OUTSIDE_SCHEDULE: 'fora da nova programação',
      CHANGED: 'pendente', UNCHANGED: 'sem diferença' } as Record<string, string>)[o.reason] ?? o.reason;
  }

  fieldLabel(field: string) {
    return ({ description: 'descrição', amount: this.variable() ? 'estimativa' : 'valor', dueDate: 'vencimento', dueDay: 'dia de vencimento',
      frequency: 'frequência', categoryId: 'categoria', responsibleUserId: 'responsável', lastDueDate: 'término' } as Record<string, string>)[field] ?? field;
  }

  fieldList(fields: string[]) { return fields.map(f => this.fieldLabel(f)).join(', '); }

  valueLabel(field: string, value: string | null) {
    if (value === null) return field === 'categoryId' ? 'sem categoria' : 'sem responsável';
    if (field === 'categoryId') return this.categories().find(c => c.id === value)?.name ?? 'categoria indisponível';
    if (field === 'responsibleUserId') return this.members().find(m => m.userId === value)?.displayName ?? 'membro indisponível';
    return value;
  }

  frequencyLabel(v: RecurrenceFrequency) {
    return { MONTHLY: 'Mensal', BIMONTHLY: 'Bimestral', QUARTERLY: 'Trimestral', SEMIANNUAL: 'Semestral', ANNUAL: 'Anual' }[v];
  }

  private configurationFor(date: string) {
    const segment = this.segmentFor(date);
    return { description: segment.description, amount: segment.amount, frequency: segment.frequency, dueDay: segment.dueDay,
      categoryId: segment.categoryId ?? '', responsibleUserId: segment.responsibleUserId ?? '' };
  }

  private segmentFor(date: string): RecurrenceSegment {
    const r = this.recurrence();
    const month = date.slice(0, 7);
    const applicable = (r.segments ?? []).filter(s => s.effectiveMonth.slice(0, 7) <= month);
    return applicable.at(-1) ?? { effectiveMonth: r.firstDueDate, description: r.description, amount: r.amount, frequency: r.frequency,
      dueDay: r.baseDay, categoryId: r.categoryId, categoryName: r.categoryName, responsibleUserId: r.responsibleUserId,
      responsibleDisplayName: r.responsibleDisplayName };
  }

  private changeRequest(): ChangeRequest {
    const v = this.changeForm.getRawValue();
    return { version: this.recurrence().version, effectiveDueDate: v.effectiveDueDate, description: v.description.trim(),
      amount: v.amount.replace(',', '.'), frequency: v.frequency, dueDay: Number(v.dueDay), categoryId: v.categoryId || null,
      responsibleUserId: v.responsibleUserId || null };
  }

  private closureRequest(): ClosureRequest {
    const v = this.closureForm.getRawValue();
    return { version: this.recurrence().version, lastDueDate: v.lastDueDate, reason: v.reason.trim() };
  }

  private reset() { this.impact.set(null); this.error.set(null); this.message.set(null); this.reviewed = null; }

  private fail(e: HttpErrorResponse, fallback: string) {
    const body = e.error as { message?: string; code?: string } | null;
    const conflict = body?.code === 'RECURRENCE_VERSION_CONFLICT' || body?.code === 'RECURRENCE_IMPACT_CHANGED';
    this.error.set((body?.message ?? fallback) + (conflict ? ' Os dados digitados foram mantidos; revise o impacto novamente.' : ''));
  }
}
