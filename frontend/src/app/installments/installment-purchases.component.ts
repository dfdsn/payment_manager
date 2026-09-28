import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, effect, inject, input, signal, untracked } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { finalize } from 'rxjs';
import { ExpenseService } from '../expenses/expense.service';
import { AccountAccessService, SpaceMember } from '../identity/account-access.service';
import { InstallmentItem, InstallmentPurchase, InstallmentPurchasePage, InstallmentPurchaseService,
  InstallmentPurchaseSummary } from './installment-purchase.service';

/**
 * H05.2: purchases with their progress and the payment of selected installments. Installments are ordinary
 * expenses, so payment goes through the same atomic batch settlement of Despesas (T12/D19).
 */
@Component({ selector: 'app-installment-purchases',
  imports: [ReactiveFormsModule, MatButtonModule, MatCardModule, MatFormFieldModule, MatInputModule],
  templateUrl: './installment-purchases.component.html', styleUrl: './installment-purchases.component.scss' })
export class InstallmentPurchasesComponent implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly api = inject(InstallmentPurchaseService);
  private readonly expensesApi = inject(ExpenseService);
  private readonly identity = inject(AccountAccessService);
  /** Changes whenever the page creates a purchase, so the list shows it. */
  readonly refresh = input(0);
  readonly pageSize = 10;
  readonly page = signal<InstallmentPurchasePage | null>(null);
  readonly selected = signal<InstallmentPurchase | null>(null);
  readonly selection = signal(new Map<string, InstallmentItem>());
  readonly members = signal<SpaceMember[]>([]);
  readonly paying = signal(false);
  readonly submitting = signal(false);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly message = signal<string | null>(null);
  private today = '';
  private payKey = '';
  readonly payForm = this.fb.nonNullable.group({
    paymentDate: ['', Validators.required],
    paidByUserId: ['', Validators.required],
    confirmed: [false, Validators.requiredTrue],
  });

  constructor() {
    effect(() => { this.refresh(); untracked(() => this.load(this.page()?.page ?? 0)); });
  }

  ngOnInit() {
    this.identity.context().subscribe({ next: context => {
      const parts = new Intl.DateTimeFormat('en-CA', { timeZone: context.timeZone, year: 'numeric', month: '2-digit',
        day: '2-digit' }).formatToParts(new Date());
      const part = (type: string) => parts.find(value => value.type === type)!.value;
      this.today = `${part('year')}-${part('month')}-${part('day')}`;
      this.payForm.patchValue({ paymentDate: this.today, paidByUserId: context.userId });
    } });
    this.identity.members().subscribe({ next: members => this.members.set(members) });
  }

  load(page = 0) {
    this.loading.set(true);
    this.api.list(page, this.pageSize).pipe(finalize(() => this.loading.set(false))).subscribe({
      next: result => this.page.set(result),
      error: e => this.handle(e, 'Não foi possível carregar as compras parceladas.'),
    });
  }

  hasNext(p: InstallmentPurchasePage) { return (p.page + 1) * p.size < p.totalItems; }

  open(purchase: InstallmentPurchaseSummary) {
    this.message.set(null); this.error.set(null);
    this.reloadDetail(purchase.id, true);
  }

  close() { this.selected.set(null); this.selection.set(new Map()); this.paying.set(false); }

  toggle(item: InstallmentItem) {
    if (item.status !== 'PENDING' || !item.expenseId) return;
    const next = new Map(this.selection());
    if (next.has(item.expenseId)) next.delete(item.expenseId); else next.set(item.expenseId, item);
    this.selection.set(next);
    if (next.size === 0) this.paying.set(false);
  }

  isSelected(item: InstallmentItem) { return !!item.expenseId && this.selection().has(item.expenseId); }

  openPayment() {
    if (this.selection().size === 0) return;
    this.payKey = this.expensesApi.newIdempotencyKey();
    this.payForm.reset({ paymentDate: this.today, paidByUserId: this.members().find(m => m.currentUser)?.userId ?? '',
      confirmed: false });
    this.paying.set(true);
  }

  confirmPayment() {
    const purchase = this.selected();
    if (!purchase || !this.paying() || this.selection().size === 0 || this.submitting()) return;
    if (this.payForm.invalid) { this.payForm.markAllAsTouched(); return; }
    const value = this.payForm.getRawValue();
    const items = [...this.selection().values()].map(i => ({ expenseId: i.expenseId!, version: i.version ?? 0 }));
    this.submitting.set(true); this.error.set(null); this.message.set(null);
    this.expensesApi.settleBatch({ items, paymentDate: value.paymentDate, paidByUserId: value.paidByUserId,
      confirmed: value.confirmed }, this.payKey).pipe(finalize(() => this.submitting.set(false))).subscribe({
      next: result => {
        this.paying.set(false); this.selection.set(new Map());
        this.message.set(result.items.length === 1 ? '1 parcela quitada.' : `${result.items.length} parcelas quitadas de uma vez.`);
        this.reloadDetail(purchase.id, false); this.load(this.page()?.page ?? 0);
      },
      error: (e: HttpErrorResponse) => {
        if (e.status === 409) {
          // D19: nothing was paid. The selection keeps only what is still pending, with fresh versions.
          this.error.set('Nenhuma parcela foi quitada: uma ou mais mudaram ou não podem mais ser quitadas. Revise a seleção atualizada.');
          this.paying.set(false);
          this.reloadDetail(purchase.id, false);
        } else this.handle(e, 'Não foi possível quitar as parcelas. Nada foi alterado; tente novamente.');
      },
    });
  }

  selectedTotal() {
    const cents = [...this.selection().values()].reduce((sum, i) => {
      const [whole, fraction = ''] = i.amount.split('.');
      return sum + BigInt(whole) * 100n + BigInt(fraction.padEnd(2, '0'));
    }, 0n);
    return `${cents / 100n}.${(cents % 100n).toString().padStart(2, '0')}`;
  }

  installmentsLabel(count: number) { return count === 1 ? 'da parcela selecionada' : `das ${count} parcelas`; }

  situation(item: InstallmentItem) {
    switch (item.status) {
      case 'PAID': return `Paga em ${item.paymentDate} (R$ ${item.paidAmount})`;
      case 'CANCELLED': return 'Cancelada';
      default: return item.overdue ? 'Atrasada' : 'Pendente';
    }
  }

  private reloadDetail(id: string, resetSelection: boolean) {
    this.api.get(id).subscribe({
      next: purchase => {
        this.selected.set(purchase);
        const pending = new Map(purchase.installments.filter(i => i.status === 'PENDING' && i.expenseId)
          .map(i => [i.expenseId!, i] as const));
        const kept = resetSelection ? new Map<string, InstallmentItem>()
          : new Map([...this.selection().keys()].filter(k => pending.has(k)).map(k => [k, pending.get(k)!] as const));
        this.selection.set(kept);
      },
      error: e => this.handle(e, 'Não foi possível carregar as parcelas da compra.'),
    });
  }

  private handle(e: HttpErrorResponse, fallback: string) {
    const body = e.error as { message?: string } | null;
    this.error.set(body?.message ?? fallback);
  }
}
