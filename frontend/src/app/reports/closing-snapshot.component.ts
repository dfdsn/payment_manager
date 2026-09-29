import { Component, computed, input } from '@angular/core';
import { MatCardModule } from '@angular/material/card';
import { ClosingLine, ClosingSnapshot, formatCurrency, formatDate, formatSigned } from './report.service';

/**
 * E07: renders one closing content (a saved version or the current data): totals by due date, categories, pending
 * entries and every entry. It only formats what the server sent; no value is recalculated here.
 */
@Component({
  selector: 'app-closing-snapshot',
  imports: [MatCardModule],
  templateUrl: './closing-snapshot.component.html',
  styleUrl: './due-dashboard.component.scss',
})
export class ClosingSnapshotComponent {
  readonly snapshot = input.required<ClosingSnapshot>();
  /** Prefix of the ids and test ids, so two contents can be shown on the same page. */
  readonly prefix = input('saved');
  readonly heading = input('Resumo');
  readonly formatCurrency = formatCurrency;
  readonly formatSigned = formatSigned;
  readonly formatDate = formatDate;
  readonly pending = computed(() => this.snapshot().lines.filter(line => line.status === 'PENDING'));

  origin(line: ClosingLine): string {
    if (line.origin === 'INSTALLMENT' && line.installmentNumber)
      return `Parcela ${line.installmentNumber}/${line.installmentCount}`;
    return line.origin === 'RECURRENCE' ? 'Recorrência' : 'Avulsa';
  }

  situation(line: ClosingLine): string {
    if (line.status === 'PAID') return 'Paga';
    return line.overdue ? 'Atrasada' : 'Pendente';
  }
}
