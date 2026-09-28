import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { finalize } from 'rxjs';
import { Category, CategoryService } from '../expenses/category.service';
import { AccountAccessService, SpaceMember } from '../identity/account-access.service';
import { InstallmentCancellationData, InstallmentChangeData, InstallmentChangeField, InstallmentChangeResult,
  InstallmentImpact, InstallmentItem, InstallmentPurchase, InstallmentPurchaseService } from './installment-purchase.service';

const FIELD_LABELS: Record<string, string> = { description: 'Descrição', categoryId: 'Categoria',
  responsibleUserId: 'Responsável', dueDate: 'Vencimento', status: 'Situação', reason: 'Motivo',
  installmentNumbers: 'Parcelas', changedFields: 'Campos', fromNumber: 'Parcela inicial', totalAmount: 'Valor total',
  installmentCount: 'Quantidade de parcelas', firstDueDate: 'Primeiro vencimento' };
const PRESERVED: Record<string, string> = { PAID: 'paga, não muda', CANCELLED: 'já cancelada',
  BEFORE_START: 'antes da parcela escolhida', OUTSIDE_SCOPE: 'fora do alcance “só esta”',
  UNCHANGED: 'já tem esses valores', NOT_SELECTED: 'não selecionada' };

/**
 * H05.3: changes pending installments of a purchase (metadata, due dates) and cancels the selected ones, always
 * reviewing the server-calculated impact first. Paid installments never change; nothing is refunded.
 */
@Component({ selector: 'app-installment-adjustments',
  imports: [ReactiveFormsModule, MatButtonModule, MatFormFieldModule, MatInputModule],
  templateUrl: './installment-adjustments.component.html', styleUrl: './installment-adjustments.component.scss' })
export class InstallmentAdjustmentsComponent implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly api = inject(InstallmentPurchaseService);
  private readonly categoriesApi = inject(CategoryService);
  private readonly identity = inject(AccountAccessService);
  readonly purchase = input.required<InstallmentPurchase>();
  /** Pending installments selected in the table; they are the ones a cancellation targets. */
  readonly selected = input<InstallmentItem[]>([]);
  readonly changed = output<InstallmentChangeResult>();
  readonly mode = signal<'change' | 'cancel' | null>(null);
  readonly impact = signal<InstallmentImpact | null>(null);
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  readonly categories = signal<Category[]>([]);
  readonly members = signal<SpaceMember[]>([]);
  readonly pending = computed(() => this.purchase().installments.filter(i => i.status === 'PENDING'));
  private reviewedChange: InstallmentChangeData | null = null;
  private reviewedCancellation: InstallmentCancellationData | null = null;
  private key = '';
  readonly changeForm = this.fb.nonNullable.group({
    fromNumber: [0, Validators.min(1)],
    scope: ['THIS_AND_FOLLOWING' as 'THIS' | 'THIS_AND_FOLLOWING'],
    changeDescription: [false], description: ['', Validators.maxLength(200)],
    changeCategory: [false], categoryId: [''],
    changeResponsible: [false], responsibleUserId: [''],
    changeDueDate: [false], dueDate: [''],
  });
  readonly cancelForm = this.fb.nonNullable.group({
    reason: ['', [Validators.required, Validators.maxLength(2000)]],
    withReplacement: [false],
    description: ['', Validators.maxLength(200)],
    totalAmount: ['', Validators.pattern(/^\d{1,8}([.,]\d{1,2})?$/)],
    installmentCount: [2, [Validators.min(2), Validators.max(360)]],
    firstDueDate: [''],
    categoryId: [''],
    responsibleUserId: [''],
  });

  constructor() {
    // A different selection is a different cancellation: the previous review no longer applies.
    effect(() => { this.selected(); untracked(() => this.discardReview()); });
  }

  ngOnInit() {
    this.categoriesApi.list(true).subscribe({ next: v => this.categories.set(v) });
    this.identity.members().subscribe({ next: v => this.members.set(v) });
    // Any edit after the review discards it: the server must recalculate the impact.
    this.changeForm.valueChanges.subscribe(() => this.discardReview());
    this.cancelForm.valueChanges.subscribe(() => this.discardReview());
  }

  activeCategories() { return this.categories().filter(c => !c.archived); }

  openChange() {
    const first = this.pending()[0];
    this.changeForm.reset({ fromNumber: first?.number ?? 0, scope: 'THIS_AND_FOLLOWING', changeDescription: false,
      description: first?.description ?? '', changeCategory: false, categoryId: first?.categoryId ?? '',
      changeResponsible: false, responsibleUserId: first?.responsibleUserId ?? '', changeDueDate: false,
      dueDate: first?.dueDate ?? '' });
    this.open('change');
  }

  openCancel() {
    const selected = [...this.selected()].sort((a, b) => a.number - b.number);
    if (selected.length === 0) return;
    const cents = selected.reduce((sum, i) => { const [w, f = ''] = i.amount.split('.');
      return sum + BigInt(w) * 100n + BigInt(f.padEnd(2, '0')); }, 0n);
    this.cancelForm.reset({ reason: '', withReplacement: false,
      description: `${this.purchase().description} (restante)`.slice(0, 200),
      totalAmount: `${cents / 100n}.${(cents % 100n).toString().padStart(2, '0')}`,
      installmentCount: Math.max(2, selected.length), firstDueDate: selected[0].dueDate,
      categoryId: selected[0].categoryId ?? '', responsibleUserId: selected[0].responsibleUserId ?? '' });
    this.open('cancel');
  }

  close() { this.mode.set(null); this.discardReview(true); this.error.set(null); }

  review() {
    if (this.busy()) return;
    this.error.set(null);
    if (this.mode() === 'change') {
      const data = this.changeData();
      if (!data) return;
      this.busy.set(true);
      this.api.previewChange(this.purchase().id, data).pipe(finalize(() => this.busy.set(false))).subscribe({
        next: impact => { this.impact.set(impact); this.reviewedChange = data; this.key = crypto.randomUUID(); },
        error: e => this.handle(e, 'Não foi possível calcular o impacto.'),
      });
    } else if (this.mode() === 'cancel') {
      const data = this.cancellationData();
      if (!data) return;
      this.busy.set(true);
      this.api.previewCancellation(this.purchase().id, data).pipe(finalize(() => this.busy.set(false))).subscribe({
        next: impact => { this.impact.set(impact); this.reviewedCancellation = data; this.key = crypto.randomUUID(); },
        error: e => this.handle(e, 'Não foi possível calcular o impacto.'),
      });
    }
  }

  confirm() {
    const impact = this.impact();
    if (!impact || this.busy()) return;
    const id = this.purchase().id;
    const request = impact.changeType === 'CHANGE'
      ? this.api.applyChange(id, this.reviewedChange!, impact.impactToken, this.key)
      : this.api.applyCancellation(id, this.reviewedCancellation!, impact.impactToken, this.key);
    this.busy.set(true); this.error.set(null);
    request.pipe(finalize(() => this.busy.set(false))).subscribe({
      next: result => { this.mode.set(null); this.discardReview(true); this.changed.emit(result); },
      error: (e: HttpErrorResponse) => {
        // A 4xx means the reviewed impact is no longer valid; a network failure keeps it and the key for a retry.
        if (e.status >= 400 && e.status < 500) this.discardReview(true);
        this.handle(e, 'Não foi possível aplicar. Nada foi alterado; tente novamente, a repetição não duplica.');
      },
    });
  }

  fieldLabel(field: string) { return FIELD_LABELS[field] ?? field; }
  preservedLabel(reason: string) { return PRESERVED[reason] ?? reason; }

  valueLabel(field: string, value: string | null) {
    if (value === null || value === '') return '—';
    if (field === 'categoryId') return this.categories().find(c => c.id === value)?.name ?? 'categoria';
    if (field === 'responsibleUserId') return this.members().find(m => m.userId === value)?.displayName ?? 'membro';
    if (field === 'status') return value === 'CANCELLED' ? 'Cancelada' : 'Pendente';
    return value;
  }

  private open(mode: 'change' | 'cancel') { this.discardReview(true); this.error.set(null); this.mode.set(mode); }

  private discardReview(force = false) {
    if (this.busy() && !force) return;
    this.impact.set(null); this.reviewedChange = null; this.reviewedCancellation = null;
  }

  private changeData(): InstallmentChangeData | null {
    const v = this.changeForm.getRawValue();
    const fields: InstallmentChangeField[] = [];
    if (v.changeDescription) fields.push('description');
    if (v.changeCategory) fields.push('categoryId');
    if (v.changeResponsible) fields.push('responsibleUserId');
    if (v.changeDueDate) fields.push('dueDate');
    if (!v.fromNumber) { this.error.set('Escolha a parcela a partir da qual alterar.'); return null; }
    if (fields.length === 0) { this.error.set('Marque ao menos um campo para alterar.'); return null; }
    if (v.changeDescription && !v.description.trim()) { this.error.set('Descrição: informe a nova descrição.'); return null; }
    if (v.changeDueDate && !v.dueDate) { this.error.set('Vencimento: informe a nova data.'); return null; }
    return { fromNumber: Number(v.fromNumber), scope: v.scope, changedFields: fields,
      description: v.changeDescription ? v.description.trim() : null,
      categoryId: v.changeCategory ? v.categoryId || null : null,
      responsibleUserId: v.changeResponsible ? v.responsibleUserId || null : null,
      dueDate: v.changeDueDate ? v.dueDate : null };
  }

  private cancellationData(): InstallmentCancellationData | null {
    if (this.cancelForm.invalid) { this.cancelForm.markAllAsTouched(); this.error.set('Revise o motivo e os dados da nova compra.'); return null; }
    const v = this.cancelForm.getRawValue();
    const numbers = this.selected().map(i => i.number).sort((a, b) => a - b);
    if (numbers.length === 0) { this.error.set('Selecione ao menos uma parcela pendente.'); return null; }
    return { installmentNumbers: numbers, reason: v.reason.trim(), replacement: v.withReplacement ? {
      description: v.description.trim(), totalAmount: v.totalAmount.replace(',', '.'),
      installmentCount: Number(v.installmentCount), firstDueDate: v.firstDueDate, categoryId: v.categoryId || null,
      responsibleUserId: v.responsibleUserId || null } : null };
  }

  private handle(e: HttpErrorResponse, fallback: string) {
    const body = e.error as { message?: string; field?: string; code?: string } | null;
    const label = body?.field && body.field !== 'impactToken' ? FIELD_LABELS[body.field] : undefined;
    const message = body?.message ?? fallback;
    this.error.set(label ? `${label}: ${message}` : message);
  }
}
