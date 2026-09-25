import { Routes } from '@angular/router';
import { InitialSetupComponent } from './identity/initial-setup.component';
import { AccountAccessComponent } from './identity/account-access.component';
import { InvitationComponent } from './identity/invitation.component';

export const routes: Routes = [
  { path: '', redirectTo: 'entrar', pathMatch: 'full' },
  { path: 'entrar', component: AccountAccessComponent, data: { mode: 'login' } },
  { path: 'confirmar-email', component: AccountAccessComponent, data: { mode: 'confirm' } },
  { path: 'recuperar-acesso', component: AccountAccessComponent, data: { mode: 'forgot' } },
  { path: 'redefinir-senha', component: AccountAccessComponent, data: { mode: 'reset' } },
  { path: 'membros', component: InvitationComponent, data: { mode: 'manage' } },
  { path: 'despesas', loadComponent: () => import('./expenses/expense.component').then(module => module.ExpenseComponent) },
  { path: 'aceitar-convite', component: InvitationComponent, data: { mode: 'accept' } },
  { path: 'configuracao-inicial', component: InitialSetupComponent },
  { path: '**', redirectTo: 'entrar' },
];
