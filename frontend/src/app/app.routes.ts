import { Routes } from '@angular/router';
import { InitialSetupComponent } from './identity/initial-setup.component';
import { AccountAccessComponent } from './identity/account-access.component';

export const routes: Routes = [
  { path: '', redirectTo: 'entrar', pathMatch: 'full' },
  { path: 'entrar', component: AccountAccessComponent, data: { mode: 'login' } },
  { path: 'confirmar-email', component: AccountAccessComponent, data: { mode: 'confirm' } },
  { path: 'recuperar-acesso', component: AccountAccessComponent, data: { mode: 'forgot' } },
  { path: 'redefinir-senha', component: AccountAccessComponent, data: { mode: 'reset' } },
  { path: 'configuracao-inicial', component: InitialSetupComponent },
  { path: '**', redirectTo: 'entrar' },
];
