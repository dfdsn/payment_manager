import { HttpErrorResponse, HttpEvent, HttpEventType } from '@angular/common/http';
import { Component, input, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { Observable } from 'rxjs';
import { attachmentName } from './csv-export.service';

/**
 * H06.4 download button with progress, empty result and error messages. The request is built when pressed, so it
 * always carries the filters currently applied on the page.
 */
@Component({
  selector: 'app-csv-export-button',
  imports: [MatButtonModule],
  template: `
    <div class="csv-export">
      <button matButton="outlined" type="button" (click)="export()" [disabled]="busy()" [attr.aria-describedby]="statusId()">
        {{ busy() ? 'Gerando arquivo…' : label() }}
      </button>
      <span [id]="statusId()" role="status" aria-live="polite" class="csv-status">{{ progress() }}</span>
      @if (message()) { <p class="csv-message" role="status" data-testid="csv-export-message">{{ message() }}</p> }
      @if (error()) { <p class="csv-error" role="alert" data-testid="csv-export-error">{{ error() }}</p> }
    </div>`,
  styles: `
    .csv-export { display: flex; flex-wrap: wrap; align-items: center; gap: .25rem .75rem; min-width: 0; }
    .csv-status { color: #354a5f; }
    .csv-message, .csv-error { flex-basis: 100%; margin: .25rem 0 0; overflow-wrap: anywhere; }
    .csv-error { color: #93000a; }
  `,
})
export class CsvExportButtonComponent {
  readonly label = input.required<string>();
  readonly request = input.required<() => Observable<HttpEvent<Blob>>>();
  readonly fallbackName = input('exportacao.csv');
  readonly emptyMessage = input('Nenhum registro com os filtros atuais; nenhum arquivo foi baixado.');
  readonly statusId = input('csv-export-status');
  readonly busy = signal(false);
  readonly progress = signal('');
  readonly message = signal<string | null>(null);
  readonly error = signal<string | null>(null);

  export(): void {
    if (this.busy()) return;
    this.busy.set(true);
    this.message.set(null);
    this.error.set(null);
    this.progress.set('Gerando arquivo no servidor…');
    this.request()().subscribe({
      next: event => {
        if (event.type === HttpEventType.DownloadProgress) {
          this.progress.set(event.total ? `Baixando… ${Math.round((event.loaded / event.total) * 100)}%` : 'Baixando…');
        } else if (event.type === HttpEventType.Response) {
          this.finish();
          const rows = Number(event.headers.get('X-Export-Rows') ?? '0');
          if (!event.body || rows === 0) { this.message.set(this.emptyMessage()); return; }
          const name = attachmentName(event.headers.get('Content-Disposition'), this.fallbackName());
          save(event.body, name);
          this.message.set(`Arquivo ${name} baixado com ${rows} ${rows === 1 ? 'registro' : 'registros'}.`);
        }
      },
      error: error => {
        this.finish();
        void errorMessage(error).then(message => this.error.set(message));
      },
    });
  }

  private finish(): void {
    this.busy.set(false);
    this.progress.set('');
  }
}

function save(blob: Blob, name: string): void {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = name;
  link.rel = 'noopener';
  document.body.appendChild(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 0);
}

async function errorMessage(error: unknown): Promise<string> {
  const fallback = 'Não foi possível gerar o arquivo. Verifique a conexão e tente novamente.';
  if (!(error instanceof HttpErrorResponse) || error.status === 0) return fallback;
  if (error.status === 401) return 'Sua sessão terminou. Entre novamente para exportar.';
  try {
    const body = error.error instanceof Blob ? JSON.parse(await readText(error.error)) : error.error;
    if (body && typeof body.message === 'string' && body.message) return body.message;
  } catch { /* not JSON: use the fallback */ }
  return fallback;
}

function readText(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.onerror = () => reject(reader.error);
    reader.readAsText(blob);
  });
}
