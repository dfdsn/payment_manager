import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { finalize } from 'rxjs';
import { AccountAccessService, SpaceMember } from '../identity/account-access.service';
import { Category, CategoryService } from '../expenses/category.service';
import { InstallmentPreview, InstallmentPurchase, InstallmentPurchaseData, InstallmentPurchaseService } from './installment-purchase.service';

const FIELD_LABELS: Record<string, string> = { description: 'Descrição', totalAmount: 'Valor total',
  installmentCount: 'Quantidade de parcelas', firstDueDate: 'Primeiro vencimento', categoryId: 'Categoria',
  responsibleUserId: 'Responsável' };

@Component({ selector: 'app-installment-purchase',
  imports: [ReactiveFormsModule, RouterLink, MatButtonModule, MatCardModule, MatFormFieldModule, MatInputModule],
  templateUrl: './installment-purchase.component.html', styleUrl: './installment-purchase.component.scss' })
export class InstallmentPurchaseComponent implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly api = inject(InstallmentPurchaseService);
  private readonly categoriesApi = inject(CategoryService);
  private readonly identity = inject(AccountAccessService);
  readonly categories = signal<Category[]>([]);
  readonly members = signal<SpaceMember[]>([]);
  readonly preview = signal<InstallmentPreview | null>(null);
  readonly created = signal<InstallmentPurchase | null>(null);
  readonly previewing = signal(false);
  readonly submitting = signal(false);
  readonly error = signal<string | null>(null);
  /** Exact request the person reviewed; confirmation sends it unchanged, so the key never covers other data. */
  private reviewed: InstallmentPurchaseData | null = null;
  private key = '';
  readonly form = this.fb.nonNullable.group({
    description: ['', [Validators.required, Validators.maxLength(200)]],
    totalAmount: ['', [Validators.required, Validators.pattern(/^\d{1,8}([.,]\d{1,2})?$/)]],
    installmentCount: [2, [Validators.required, Validators.min(2), Validators.max(360)]],
    firstDueDate: ['', Validators.required],
    categoryId: [''],
    responsibleUserId: [''],
  });

  ngOnInit() {
    this.categoriesApi.list(false).subscribe(v => this.categories.set(v));
    this.identity.members().subscribe(v => this.members.set(v));
    // Any edit after the review invalidates it: amounts and dates must be recalculated by the server.
    this.form.valueChanges.subscribe(() => { if (!this.submitting()) { this.preview.set(null); this.reviewed = null; } });
  }

  review() {
    if (this.form.invalid || this.previewing()) { this.form.markAllAsTouched(); return; }
    const data = this.data();
    this.previewing.set(true); this.error.set(null); this.created.set(null);
    this.api.preview(data).pipe(finalize(() => this.previewing.set(false))).subscribe({
      next: p => { this.preview.set(p); this.reviewed = data; this.key = this.api.newIdempotencyKey(); },
      error: e => this.handle(e, 'Não foi possível calcular as parcelas.'),
    });
  }

  confirm() {
    const data = this.reviewed;
    if (!data || this.submitting()) return;
    this.submitting.set(true); this.error.set(null);
    this.api.create(data, this.key).pipe(finalize(() => this.submitting.set(false))).subscribe({
      next: purchase => {
        this.created.set(purchase); this.preview.set(null); this.reviewed = null;
        this.form.reset({ description: '', totalAmount: '', installmentCount: 2, firstDueDate: '', categoryId: '', responsibleUserId: '' });
      },
      error: (e: HttpErrorResponse) => {
        // Validation or conflict means the reviewed data is no longer acceptable: review again with a new key.
        // Network failures keep the review and key so a retry cannot create a second purchase.
        if (e.status >= 400 && e.status < 500) { this.preview.set(null); this.reviewed = null; }
        this.handle(e, 'Não foi possível criar a compra parcelada. Tente novamente; a repetição não duplica parcelas.');
      },
    });
  }

  hasAdjustment(p: InstallmentPreview) { return p.lastInstallmentAdjustment !== '0.00'; }

  private data(): InstallmentPurchaseData {
    const v = this.form.getRawValue();
    return { description: v.description.trim(), totalAmount: v.totalAmount.replace(',', '.'),
      installmentCount: Number(v.installmentCount), firstDueDate: v.firstDueDate,
      categoryId: v.categoryId || null, responsibleUserId: v.responsibleUserId || null };
  }

  private handle(e: HttpErrorResponse, fallback: string) {
    const body = e.error as { message?: string; field?: string } | null;
    const label = body?.field ? FIELD_LABELS[body.field] : undefined;
    const message = body?.message ?? fallback;
    this.error.set(label ? `${label}: ${message}` : message);
  }
}
