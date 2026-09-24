import { Routes } from '@angular/router';
import { InitialSetupComponent } from './identity/initial-setup.component';

export const routes: Routes = [
  { path: '', component: InitialSetupComponent },
  { path: '**', redirectTo: '' },
];
