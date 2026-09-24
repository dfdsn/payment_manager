import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { finalize } from 'rxjs';
import { ApiError, InitialSetupResult, InitialSetupService, InitialSetupStatus } from './initial-setup.service';

@Component({
  selector: 'app-initial-setup',
  imports: [ReactiveFormsModule, MatButtonModule, MatCardModule, MatFormFieldModule, MatInputModule, MatProgressSpinnerModule],
  templateUrl: './initial-setup.component.html',
  styleUrl: './initial-setup.component.scss',
})
export class InitialSetupComponent implements OnInit {
  private readonly formBuilder = inject(FormBuilder);
  private readonly setupService = inject(InitialSetupService);

  readonly loading = signal(true);
  readonly submitting = signal(false);
  readonly status = signal<InitialSetupStatus | null>(null);
  readonly result = signal<InitialSetupResult | null>(null);
  readonly errorMessage = signal<string | null>(null);

  readonly form = this.formBuilder.nonNullable.group({
    setupSecret: ['', Validators.required],
    administratorName: ['', [Validators.required, Validators.minLength(2), Validators.maxLength(100)]],
    email: ['', [Validators.required, Validators.email, Validators.maxLength(254)]],
    password: ['', [Validators.required, Validators.minLength(12), Validators.maxLength(72)]],
    spaceName: ['Minha casa', [Validators.required, Validators.minLength(2), Validators.maxLength(100)]],
  });

  ngOnInit(): void {
    this.loadStatus();
  }

  submit(): void {
    this.errorMessage.set(null);
    if (this.form.invalid || this.submitting()) {
      this.form.markAllAsTouched();
      return;
    }
    const value = this.form.getRawValue();
    this.submitting.set(true);
    this.setupService.configure({
      administratorName: value.administratorName,
      email: value.email,
      password: value.password,
      spaceName: value.spaceName,
    }, value.setupSecret).pipe(finalize(() => this.submitting.set(false))).subscribe({
      next: (result) => {
        this.result.set(result);
        this.status.set('COMPLETED');
        this.form.reset();
      },
      error: (error: HttpErrorResponse) => this.handleError(error),
    });
  }

  retryStatus(): void {
    this.loadStatus();
  }

  fieldHasError(field: keyof typeof this.form.controls, error: string): boolean {
    const control = this.form.controls[field];
    return control.hasError(error) && (control.touched || control.dirty);
  }

  private loadStatus(): void {
    this.loading.set(true);
    this.errorMessage.set(null);
    this.setupService.status().pipe(finalize(() => this.loading.set(false))).subscribe({
      next: ({ status }) => this.status.set(status),
      error: () => this.errorMessage.set('Não foi possível consultar o estado da configuração.'),
    });
  }

  private handleError(error: HttpErrorResponse): void {
    const apiError = error.error as ApiError | undefined;
    if (apiError?.code === 'SETUP_ALREADY_COMPLETED') {
      this.status.set('COMPLETED');
    }
    for (const fieldError of apiError?.fieldErrors ?? []) {
      const control = this.form.get(fieldError.field);
      control?.setErrors({ ...control.errors, server: fieldError.message });
    }
    this.errorMessage.set(apiError?.message ?? 'Não foi possível concluir a configuração inicial.');
  }
}
