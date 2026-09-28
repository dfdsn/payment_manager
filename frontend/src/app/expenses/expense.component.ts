import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, inject, signal } from '@angular/core';
import { AbstractControl, FormBuilder, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { ApiError } from '../identity/initial-setup.service';
import { AccountAccessService, SpaceMember } from '../identity/account-access.service';
import { Category, CategoryService } from './category.service';
import {
  Expense, ExpenseService, ExpenseSort, ExpenseStatus, SortDirection,
} from './expense.service';

const MONEY = /^\d{1,8}([.,]\d{1,2})?$/;
const positiveAmount = (control: AbstractControl<string>): ValidationErrors | null =>
  control.value && Number(control.value.replace(',', '.')) <= 0 ? { positive: true } : null;

@Component({
  selector: 'app-expense',
  imports: [
    ReactiveFormsModule, RouterLink, MatButtonModule, MatCardModule,
    MatFormFieldModule, MatInputModule,
  ],
  templateUrl: './expense.component.html',
  styleUrl: './expense.component.scss',
})
export class ExpenseComponent implements OnInit {
  private readonly formBuilder = inject(FormBuilder);
  private readonly expensesApi = inject(ExpenseService);
  private readonly identity = inject(AccountAccessService);
  private readonly categoryApi = inject(CategoryService);
  readonly categories = signal<Category[]>([]);
  readonly members = signal<SpaceMember[]>([]);
  readonly responsibleFilterPeople = signal<import('./expense.service').ExpenseFilterPerson[]>([]);
  readonly payerFilterPeople = signal<import('./expense.service').ExpenseFilterPerson[]>([]);
  readonly settling = signal<Expense | null>(null);
  readonly editing = signal<Expense | null>(null);
  readonly conflictCurrent = signal<Expense | null>(null);
  readonly confirmingCharge = signal<Expense | null>(null);
  readonly chargeConflictCurrent = signal<Expense | null>(null);
  readonly lifecycleAction = signal<{ expense: Expense; type: 'REVERSE' | 'CANCEL' } | null>(null);
  readonly detail = signal<Expense | null>(null);
  readonly history = signal<import('./expense.service').ExpenseHistoryEvent[]>([]);
  readonly historyPage = signal(0);
  readonly historyTotalPages = signal(0);
  readonly historyLoading = signal(false);
  readonly historyError = signal<string | null>(null);
  readonly attachments = signal<import('./expense.service').ExpenseAttachment[]>([]);
  readonly attachmentLoading = signal(false);
  readonly attachmentError = signal<string | null>(null);
  readonly selectedForBatch = signal(new Map<string, Expense>());
  readonly batchOpen = signal(false);
  private paymentKey = '';
  private correctionKey = '';
  private actionKey = '';
  private chargeKey = '';
  private batchKey = '';
  private today = '';
  private spaceTimeZone = 'America/Sao_Paulo';
  readonly paymentForm = this.formBuilder.nonNullable.group({
    paidAmount: ['', [Validators.required, Validators.pattern(/^\d{1,8}([.,]\d{1,2})?$/)]],
    paymentDate: ['', Validators.required],
    paidByUserId: ['', Validators.required],
    paymentNotes: ['', Validators.maxLength(2000)],
    confirmedChargeAmount: [''],
  });
  readonly chargeForm = this.formBuilder.nonNullable.group({
    confirmedAmount: ['', [Validators.required, Validators.pattern(MONEY), positiveAmount]],
  });
  readonly editForm = this.formBuilder.nonNullable.group({
    description: ['', [Validators.required, Validators.maxLength(200)]],
    amount: ['', [Validators.required, Validators.pattern(/^\d{1,8}([.,]\d{1,2})?$/)]],
    dueDate: [''],
    notes: ['', Validators.maxLength(2000)],
    categoryId: [''],
    responsibleUserId: [''],
    paidAmount: ['', Validators.pattern(/^\d{1,8}([.,]\d{1,2})?$/)],
    paymentDate: [''],
    paidByUserId: [''],
    paymentNotes: ['', Validators.maxLength(2000)],
  });
  readonly actionForm = this.formBuilder.nonNullable.group({
    reason: ['', [Validators.required, Validators.maxLength(2000)]],
  });
  readonly batchForm = this.formBuilder.nonNullable.group({
    paymentDate: ['', Validators.required],
    paidByUserId: ['', Validators.required],
    confirmed: [false, Validators.requiredTrue],
  });

  readonly loading = signal(true);
  readonly submitting = signal(false);
  readonly message = signal<string | null>(null);
  readonly errorMessage = signal<string | null>(null);
  readonly expenses = signal<Expense[]>([]);
  readonly page = signal(0);
  readonly totalPages = signal(0);
  readonly totalElements = signal(0);
  readonly sort = signal<ExpenseSort>('REFERENCE_DATE');
  readonly direction = signal<SortDirection>('ASC');
  readonly filtersActive = signal(false);
  private loadSequence = 0;
  readonly filterForm = this.formBuilder.nonNullable.group({
    search: ['', Validators.maxLength(200)], dateFrom: [''], dateTo: [''], dateBasis: ['DUE_DATE' as 'DUE_DATE'|'PAYMENT_DATE'],
    category: [''], responsible: [''], payerUserId: [''], status: ['ACTIVE' as import('./expense.service').ExpenseStatusFilter],
  });
  private idempotencyKey = this.expensesApi.newIdempotencyKey();

  readonly form = this.formBuilder.nonNullable.group({
    description: ['', [Validators.required, Validators.maxLength(200)]],
    amount: ['', [Validators.required, Validators.pattern(/^\d{1,8}([.,]\d{1,2})?$/)]],
    status: ['PENDING' as ExpenseStatus, Validators.required],
    dueDate: ['', Validators.required],
    paymentDate: [''],
    notes: ['', Validators.maxLength(2000)],
    categoryId: [''],
    responsibleUserId: [''],
  });

  ngOnInit(): void {
    this.identity.context().subscribe({ next: context => {
      this.spaceTimeZone = context.timeZone;
      const parts = new Intl.DateTimeFormat('en-CA', { timeZone: context.timeZone, year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(new Date());
      const part = (type: string) => parts.find(value => value.type === type)!.value;
      this.today = `${part('year')}-${part('month')}-${part('day')}`;
      this.paymentForm.patchValue({ paymentDate: this.today, paidByUserId: context.userId });
      this.batchForm.patchValue({ paymentDate: this.today, paidByUserId: context.userId });
    }, error: error => this.handleError(error, 'Não foi possível consultar sua sessão.') });
    this.identity.members().subscribe({ next: members => this.members.set(members),
      error: error => this.handleError(error, 'Não foi possível carregar os pagadores.') });
    this.form.controls.status.valueChanges.subscribe(status => this.updateDateRules(status));
    this.loadCategories();
    this.loadFilterOptions();
    this.load();
  }

  submit(): void {
    if (this.form.invalid || this.submitting()) {
      this.form.markAllAsTouched();
      return;
    }
    const value = this.form.getRawValue();
    if (value.status === 'PAID') {
      this.paymentForm.controls.paymentDate.setValue(value.paymentDate);
      if (this.paymentForm.invalid) { this.paymentForm.markAllAsTouched(); return; }
    }
    this.submitting.set(true);
    this.message.set(null);
    this.errorMessage.set(null);
    this.expensesApi.create({
      description: value.description,
      amount: value.amount.replace(',', '.'),
      status: value.status,
      dueDate: value.dueDate || null,
      paymentDate: value.paymentDate || null,
      notes: value.notes.trim() || null,
      categoryId: value.categoryId || null,
      responsibleUserId: value.responsibleUserId || null,
      ...(value.status === 'PAID' ? {
        paidAmount: this.paymentForm.controls.paidAmount.value.replace(',', '.'),
        paidByUserId: this.paymentForm.controls.paidByUserId.value,
        paymentNotes: this.paymentForm.controls.paymentNotes.value.trim() || null,
      } : {}),
    }, this.idempotencyKey).pipe(finalize(() => this.submitting.set(false))).subscribe({
      next: () => {
        this.message.set('Despesa cadastrada com sucesso.');
        this.idempotencyKey = this.expensesApi.newIdempotencyKey();
        this.form.reset({
          description: '', amount: '', status: 'PENDING', dueDate: '', paymentDate: '', notes: '', categoryId: '',
          responsibleUserId: '',
        });
        this.page.set(0);
        this.load();
      },
      error: error => this.handleError(error, 'Não foi possível salvar. Os campos foram preservados para nova tentativa.'),
    });
  }

  changeSort(sort: ExpenseSort): void {
    this.sort.set(sort);
    this.page.set(0);
    this.load();
  }

  openPayment(expense: Expense): void {
    this.settling.set(expense);
    this.paymentKey = this.expensesApi.newIdempotencyKey();
    this.paymentForm.reset({ paidAmount: expense.amount, paymentDate: this.today,
      paidByUserId: this.members().find(member => member.currentUser)?.userId ?? '', paymentNotes: '',
      confirmedChargeAmount: '' });
    const confirmed = this.paymentForm.controls.confirmedChargeAmount;
    confirmed.setValidators(this.isEstimated(expense) ? [Validators.required, Validators.pattern(MONEY), positiveAmount] : []);
    confirmed.updateValueAndValidity();
    this.errorMessage.set(null);
    this.message.set(null);
  }

  confirmPayment(): void {
    const expense = this.settling();
    if (!expense || this.submitting()) return;
    if (this.paymentForm.invalid) { this.paymentForm.markAllAsTouched(); return; }
    const { confirmedChargeAmount, ...values } = this.paymentForm.getRawValue();
    this.submitting.set(true);
    this.errorMessage.set(null);
    this.expensesApi.settle(expense.id, { ...values, version: expense.version,
      paidAmount: values.paidAmount.replace(',', '.'), paymentNotes: values.paymentNotes.trim() || null,
      ...(this.isEstimated(expense) ? { confirmedChargeAmount: confirmedChargeAmount.replace(',', '.') } : {}),
    }, this.paymentKey).pipe(finalize(() => this.submitting.set(false))).subscribe({
      next: () => { this.settling.set(null); this.message.set('Quitação registrada com sucesso.'); this.load(); },
      error: error => this.handleError(error, 'Não foi possível quitar. Os campos foram preservados para nova tentativa.'),
    });
  }

  isEstimated(expense: Expense): boolean {
    return expense.origin === 'RECURRENCE' && expense.status === 'PENDING' && !expense.chargeConfirmed;
  }

  openChargeConfirmation(expense: Expense): void {
    if (!this.isEstimated(expense)) return;
    this.confirmingCharge.set(expense);
    this.chargeConflictCurrent.set(null);
    this.chargeKey = this.expensesApi.newIdempotencyKey();
    this.chargeForm.reset({ confirmedAmount: '' });
    this.errorMessage.set(null);
    this.message.set(null);
  }

  closeChargeConfirmation(): void {
    this.confirmingCharge.set(null);
    this.chargeConflictCurrent.set(null);
  }

  confirmChargeAmount(): void {
    const expense = this.confirmingCharge();
    if (!expense || this.submitting()) return;
    if (this.chargeForm.invalid) { this.chargeForm.markAllAsTouched(); return; }
    const confirmedAmount = this.chargeForm.controls.confirmedAmount.value.replace(',', '.');
    this.submitting.set(true);
    this.errorMessage.set(null);
    this.chargeConflictCurrent.set(null);
    this.expensesApi.confirmCharge(expense.id, { version: expense.version, confirmedAmount }, this.chargeKey)
      .pipe(finalize(() => this.submitting.set(false))).subscribe({
        next: confirmed => {
          this.confirmingCharge.set(null);
          this.message.set(`Valor da cobrança confirmado em ${this.formatCurrency(confirmed.amount)}. A despesa continua pendente de quitação.`);
          this.load();
        },
        error: error => {
          if (error instanceof HttpErrorResponse && error.status === 409) {
            this.handleError(error, 'A cobrança mudou antes da confirmação. O valor digitado foi preservado.');
            this.expensesApi.get(expense.id).subscribe({
              next: current => this.chargeConflictCurrent.set(current),
              error: refreshError => this.handleError(refreshError, 'Houve conflito e não foi possível consultar os dados atuais.'),
            });
            this.load(false);
          } else this.handleError(error, 'Não foi possível confirmar o valor. O valor digitado foi preservado para nova tentativa.');
        },
      });
  }

  useCurrentChargeVersion(): void {
    const current = this.chargeConflictCurrent();
    if (!current || !this.isEstimated(current)) return;
    this.confirmingCharge.set(current);
    this.chargeConflictCurrent.set(null);
    this.chargeKey = this.expensesApi.newIdempotencyKey();
    this.errorMessage.set(null);
  }

  toggleBatch(expense: Expense): void {
    if (expense.status !== 'PENDING' || this.isEstimated(expense)) return;
    const selection = new Map(this.selectedForBatch());
    if (selection.has(expense.id)) selection.delete(expense.id);
    else selection.set(expense.id, expense);
    this.selectedForBatch.set(selection);
    if (selection.size === 0) this.batchOpen.set(false);
  }

  isBatchSelected(id: string): boolean {
    return this.selectedForBatch().has(id);
  }

  openBatch(): void {
    if (this.selectedForBatch().size === 0) return;
    this.batchKey = this.expensesApi.newIdempotencyKey();
    this.batchForm.reset({ paymentDate: this.today,
      paidByUserId: this.members().find(member => member.currentUser)?.userId ?? '', confirmed: false });
    this.batchOpen.set(true);
    this.errorMessage.set(null);
    this.message.set(null);
  }

  confirmBatch(): void {
    if (!this.batchOpen() || this.selectedForBatch().size === 0 || this.submitting()) return;
    if (this.batchForm.invalid) { this.batchForm.markAllAsTouched(); return; }
    const value = this.batchForm.getRawValue();
    const items = [...this.selectedForBatch().values()].map(expense => ({
      expenseId: expense.id, version: expense.version,
    }));
    this.submitting.set(true);
    this.errorMessage.set(null);
    this.expensesApi.settleBatch({ items, paymentDate: value.paymentDate,
      paidByUserId: value.paidByUserId, confirmed: value.confirmed }, this.batchKey)
      .pipe(finalize(() => this.submitting.set(false))).subscribe({
        next: result => {
          this.batchOpen.set(false);
          this.selectedForBatch.set(new Map());
          this.message.set(`${result.items.length} lançamentos quitados no lote ${result.operationId}.`);
          this.load();
        },
        error: error => {
          if (error instanceof HttpErrorResponse && error.status === 409) {
            this.errorMessage.set('O lote inteiro foi rejeitado porque um ou mais lançamentos mudaram ou não podem ser quitados. A seleção e os dados foram preservados para revisão.');
            this.load(false);
          } else this.handleError(error,
            'Não foi possível quitar o lote. Nenhum item foi alterado e os dados foram preservados.');
        },
      });
  }

  selectedBatchItems(): Expense[] {
    return [...this.selectedForBatch().values()];
  }

  batchTotal(): string {
    const cents = this.selectedBatchItems().reduce((sum, expense) => {
      const [whole, fraction = ''] = expense.amount.split('.');
      return sum + BigInt(whole) * 100n + BigInt(fraction.padEnd(2, '0'));
    }, 0n);
    const whole = (cents / 100n).toString().replace(/\B(?=(\d{3})+(?!\d))/g, '.');
    return `R$ ${whole},${(cents % 100n).toString().padStart(2, '0')}`;
  }

  openCorrection(expense: Expense): void {
    this.editing.set(expense);
    this.conflictCurrent.set(null);
    this.correctionKey = this.expensesApi.newIdempotencyKey();
    this.editForm.reset({
      description: expense.description, amount: expense.amount, dueDate: expense.dueDate ?? '',
      notes: expense.notes ?? '', paidAmount: expense.paidAmount ?? '', paymentDate: expense.paymentDate ?? '',
      paidByUserId: expense.paidByUserId ?? '', paymentNotes: expense.paymentAudit?.notes ?? '',
      categoryId: expense.categoryId ?? '',
      responsibleUserId: expense.responsibleUserId ?? '',
    });
    if (expense.status === 'PAID') {
      this.editForm.controls.paidAmount.setValidators([Validators.required, Validators.pattern(/^\d{1,8}([.,]\d{1,2})?$/)]);
      this.editForm.controls.paymentDate.setValidators(Validators.required);
      this.editForm.controls.paidByUserId.setValidators(Validators.required);
      this.editForm.controls.dueDate.clearValidators();
    } else {
      this.editForm.controls.dueDate.setValidators(Validators.required);
      this.editForm.controls.paidAmount.clearValidators();
      this.editForm.controls.paymentDate.clearValidators();
      this.editForm.controls.paidByUserId.clearValidators();
    }
    Object.values(this.editForm.controls).forEach(control => control.updateValueAndValidity());
    this.errorMessage.set(null);
    this.message.set(null);
  }

  confirmCorrection(): void {
    const expense = this.editing();
    if (!expense || this.submitting()) return;
    if (this.editForm.invalid) { this.editForm.markAllAsTouched(); return; }
    const value = this.editForm.getRawValue();
    this.submitting.set(true);
    this.errorMessage.set(null);
    this.conflictCurrent.set(null);
    this.expensesApi.correct(expense.id, {
      version: expense.version, status: expense.status, description: value.description,
      amount: value.amount.replace(',', '.'), dueDate: value.dueDate || null, notes: value.notes.trim() || null,
      categoryId: value.categoryId || null,
      responsibleUserId: value.responsibleUserId || null,
      ...(expense.status === 'PAID' ? {
        paidAmount: value.paidAmount.replace(',', '.'), paymentDate: value.paymentDate,
        paidByUserId: value.paidByUserId, paymentNotes: value.paymentNotes.trim() || null,
      } : {}),
    }, this.correctionKey).pipe(finalize(() => this.submitting.set(false))).subscribe({
      next: () => { this.editing.set(null); this.message.set('Despesa corrigida com sucesso.'); this.load(); },
      error: error => {
        if (error instanceof HttpErrorResponse && error.status === 409) {
          this.errorMessage.set('Outra alteração foi salva antes da sua. Seus campos foram preservados; consulte os dados atuais antes de reaplicar.');
          this.expensesApi.get(expense.id).subscribe({
            next: current => this.conflictCurrent.set(current),
            error: refreshError => this.handleError(refreshError, 'Houve conflito e não foi possível consultar os dados atuais.'),
          });
        } else this.handleError(error, 'Não foi possível corrigir. Os campos foram preservados para nova tentativa.');
      },
    });
  }

  useCurrentVersion(): void {
    const current = this.conflictCurrent();
    if (!current) return;
    this.editing.set(current);
    this.conflictCurrent.set(null);
    this.correctionKey = this.expensesApi.newIdempotencyKey();
    this.errorMessage.set(null);
  }

  openLifecycleAction(expense: Expense, type: 'REVERSE' | 'CANCEL'): void {
    this.lifecycleAction.set({ expense, type });
    this.actionForm.reset({ reason: '' });
    this.actionKey = this.expensesApi.newIdempotencyKey();
    this.errorMessage.set(null);
    this.message.set(null);
  }

  confirmLifecycleAction(): void {
    const action = this.lifecycleAction();
    if (!action || this.submitting()) return;
    if (action.type === 'REVERSE' && !action.expense.dueDate) {
      this.errorMessage.set('Informe o vencimento pela correção da despesa antes de desfazer esta quitação.');
      return;
    }
    if (this.actionForm.invalid) { this.actionForm.markAllAsTouched(); return; }
    const reason = this.actionForm.controls.reason.value.trim();
    const request = action.type === 'REVERSE'
      ? this.expensesApi.reversePayment(action.expense.id, action.expense.version, reason, this.actionKey)
      : this.expensesApi.cancel(action.expense.id, action.expense.version, reason, this.actionKey);
    this.submitting.set(true);
    this.errorMessage.set(null);
    request.pipe(finalize(() => this.submitting.set(false))).subscribe({
      next: () => {
        const success = action.type === 'REVERSE'
          ? 'Quitação desfeita. A despesa voltou a ficar pendente.'
          : 'Despesa cancelada e removida da lista ativa.';
        this.lifecycleAction.set(null);
        this.message.set(success);
        this.loadDetail(action.expense.id);
        this.load();
      },
      error: error => {
        if (error instanceof HttpErrorResponse && error.status === 409) {
          this.errorMessage.set('A despesa mudou antes da confirmação. Consulte os dados atuais e tente novamente; nenhuma alteração foi sobrescrita.');
          this.loadDetail(action.expense.id);
        } else this.handleError(error, 'Não foi possível concluir a operação. O motivo foi preservado para nova tentativa.');
      },
    });
  }

  loadDetail(id: string): void {
    this.historyPage.set(0);
    this.expensesApi.get(id).subscribe({
      next: expense => { this.detail.set(expense); this.loadHistory(id, 0); this.loadAttachments(id); },
      error: error => this.handleError(error, 'Não foi possível consultar o histórico da despesa.'),
    });
  }

  loadAttachments(id: string) {
    if (typeof this.expensesApi.attachments !== 'function') return;
    this.attachmentLoading.set(true); this.attachmentError.set(null);
    this.expensesApi.attachments(id).pipe(finalize(() => this.attachmentLoading.set(false))).subscribe({
      next: items => this.attachments.set(items),
      error: () => this.attachmentError.set('Não foi possível carregar os anexos.'),
    });
  }

  uploadAttachment(event: Event, expenseId: string) {
    const input = event.target as HTMLInputElement; const file = input.files?.[0];
    if (!file) return;
    this.attachmentLoading.set(true); this.attachmentError.set(null);
    this.expensesApi.uploadAttachment(expenseId, file, this.expensesApi.newIdempotencyKey())
      .pipe(finalize(() => { this.attachmentLoading.set(false); input.value = ''; }))
      .subscribe({ next: () => this.loadAttachments(expenseId), error: e => this.attachmentError.set(e.error?.message ?? 'O anexo não foi enviado.') });
  }

  downloadAttachment(expenseId: string, item: import('./expense.service').ExpenseAttachment) {
    this.expensesApi.downloadAttachment(expenseId, item.id).subscribe({ next: blob => {
      const url=URL.createObjectURL(blob); const link=document.createElement('a'); link.href=url; link.download=item.name; link.click(); URL.revokeObjectURL(url);
    }, error: () => this.attachmentError.set('Não foi possível baixar o anexo.') });
  }

  removeAttachment(expenseId: string, id: string) {
    if (!confirm('Remover este anexo? O histórico da remoção será preservado.')) return;
    this.attachmentLoading.set(true);
    this.expensesApi.removeAttachment(expenseId,id).pipe(finalize(()=>this.attachmentLoading.set(false))).subscribe({
      next:()=>this.loadAttachments(expenseId), error:()=>this.attachmentError.set('Não foi possível remover o anexo.'),
    });
  }

  loadHistory(id: string, page: number): void {
    this.historyLoading.set(true);
    this.historyError.set(null);
    this.expensesApi.history(id, page, 10).pipe(finalize(() => this.historyLoading.set(false))).subscribe({
      next: result => {
        this.history.set(result.content);
        this.historyPage.set(result.page);
        this.historyTotalPages.set(result.totalPages);
      },
      error: () => this.historyError.set('Não foi possível carregar esta página do histórico.'),
    });
  }

  historyLabel(type: string): string {
    return ({ EXPENSE_CREATED: 'Despesa cadastrada', EXPENSE_PAID: 'Quitação registrada', PAYMENT_REVERSED: 'Quitação desfeita',
      EXPENSE_CORRECTED: 'Despesa corrigida', EXPENSE_CANCELLED: 'Despesa cancelada',
      CHARGE_CONFIRMED: 'Valor da cobrança confirmado', ESTIMATE_UPDATED: 'Estimativa atualizada por confirmação anterior',
      RECURRENCE_CHANGE_APPLIED: 'Alterado pela recorrência (este e os próximos)',
      RECURRENCE_OCCURRENCE_REMOVED: 'Retirado da programação da recorrência' } as Record<string, string>)[type] ?? type;
  }

  historyField(field: string): string {
    return ({ description: 'Descrição', amount: 'Valor', dueDate: 'Vencimento', notes: 'Observação',
      paidAmount: 'Valor pago', paymentDate: 'Data do pagamento', paidByUserId: 'Pagador',
      categoryId: 'Categoria', responsibleUserId: 'Responsável' } as Record<string, string>)[field] ?? field;
  }

  historyValue(value: string | null): string {
    return value === null || value === '' ? 'Não definido' : value;
  }

  formatInstant(value: string): string {
    return new Intl.DateTimeFormat('pt-BR', {
      timeZone: this.spaceTimeZone, dateStyle: 'short', timeStyle: 'medium',
    }).format(new Date(value));
  }

  toggleDirection(): void {
    this.direction.update(value => value === 'ASC' ? 'DESC' : 'ASC');
    this.page.set(0);
    this.load();
  }

  applyFilters(): void {
    if (this.filterForm.invalid) { this.filterForm.markAllAsTouched(); return; }
    const value=this.filterForm.getRawValue();
    if(value.dateFrom && value.dateTo && value.dateFrom>value.dateTo){this.errorMessage.set('A data inicial não pode ser posterior à final.');return;}
    this.page.set(0); this.filtersActive.set(Object.values(value).some(v=>v!==''&&v!=='DUE_DATE'&&v!=='ACTIVE'));
    this.load();
  }

  clearFilters(): void {
    this.filterForm.reset({search:'',dateFrom:'',dateTo:'',dateBasis:'DUE_DATE',category:'',responsible:'',payerUserId:'',status:'ACTIVE'});
    this.page.set(0); this.filtersActive.set(false); this.load();
  }

  previous(): void {
    if (this.page() === 0) return;
    this.page.update(value => value - 1);
    this.load();
  }

  next(): void {
    if (this.page() + 1 >= this.totalPages()) return;
    this.page.update(value => value + 1);
    this.load();
  }

  formatCurrency(value: string): string {
    return new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' }).format(Number(value));
  }

  formatDate(value: string | null): string {
    if (!value) return 'Não informado';
    const [year, month, day] = value.split('-');
    return `${day}/${month}/${year}`;
  }

  private updateDateRules(status: ExpenseStatus): void {
    const dueDate = this.form.controls.dueDate;
    const paymentDate = this.form.controls.paymentDate;
    if (status === 'PENDING') {
      dueDate.setValidators(Validators.required);
      paymentDate.clearValidators();
      paymentDate.setValue('');
    } else {
      dueDate.clearValidators();
      paymentDate.setValidators(Validators.required);
      paymentDate.setValue(this.today);
      this.paymentForm.patchValue({ paidAmount: this.form.controls.amount.value,
        paidByUserId: this.members().find(member => member.currentUser)?.userId ?? '', paymentNotes: '' });
    }
    dueDate.updateValueAndValidity();
    paymentDate.updateValueAndValidity();
  }

  private load(clearError = true): void {
    const sequence=++this.loadSequence;
    this.loading.set(true);
    if (clearError) this.errorMessage.set(null);
    const f=this.filterForm.getRawValue();
    this.expensesApi.list(this.page(), 20, this.sort(), this.direction(), {
      search:f.search.trim()||undefined,dateFrom:f.dateFrom||undefined,dateTo:f.dateTo||undefined,dateBasis:f.dateBasis,
      categoryId:f.category && f.category!=='NONE'?f.category:undefined,withoutCategory:f.category==='NONE',
      responsibleUserId:f.responsible&&f.responsible!=='NONE'?f.responsible:undefined,withoutResponsible:f.responsible==='NONE',
      payerUserId:f.payerUserId||undefined,status:f.status,
    }).pipe(finalize(() => { if(sequence===this.loadSequence)this.loading.set(false); }))
      .subscribe({
        next: result => {
          if(sequence!==this.loadSequence)return;
          this.expenses.set(result.content);
          this.page.set(result.page);
          this.totalPages.set(result.totalPages);
          this.totalElements.set(result.totalElements);
        },
        error: error => { if(sequence===this.loadSequence)this.handleError(error, 'Não foi possível carregar as despesas.'); },
      });
  }

  private loadCategories(): void {
    this.categoryApi.list(true).subscribe({next: values => this.categories.set(values),
      error: error => this.handleError(error, 'Não foi possível carregar as categorias.')});
  }

  private loadFilterOptions(): void {
    this.expensesApi.filterOptions().subscribe({
      next: options => {
        this.responsibleFilterPeople.set(options.responsiblePeople);
        this.payerFilterPeople.set(options.payerPeople);
      },
      error: error => this.handleError(error, 'Não foi possível carregar as opções dos filtros.'),
    });
  }

  private handleError(error: HttpErrorResponse, fallback: string): void {
    const apiError = error.error as ApiError | undefined;
    this.errorMessage.set(apiError?.message ?? fallback);
  }
}
