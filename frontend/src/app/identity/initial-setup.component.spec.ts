import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { of } from 'rxjs';
import { InitialSetupComponent } from './initial-setup.component';
import { InitialSetupService } from './initial-setup.service';
import { provideRouter } from '@angular/router';

describe('InitialSetupComponent', () => {
  let fixture: ComponentFixture<InitialSetupComponent>;
  const service = {
    status: vi.fn(() => of({ status: 'AVAILABLE' as const })),
    configure: vi.fn(() => of({
      administratorId: 'admin-id', administratorName: 'Diego', email: 'diego@example.com',
      spaceId: 'space-id', spaceName: 'Minha casa', currency: 'BRL' as const,
      locale: 'pt-BR' as const, timeZone: 'America/Sao_Paulo' as const,
    })),
  };

  beforeEach(async () => {
    service.status.mockClear();
    service.configure.mockClear();
    await TestBed.configureTestingModule({
      imports: [InitialSetupComponent],
      providers: [{ provide: InitialSetupService, useValue: service }, provideRouter([])],
    }).compileComponents();
    fixture = TestBed.createComponent(InitialSetupComponent);
    fixture.detectChanges();
  });

  it('shows the protected setup form when installation is available', () => {
    expect(fixture.nativeElement.querySelector('mat-card-title').textContent).toContain('Configurar administrador e espaço');
    expect(fixture.nativeElement.querySelector('input[formcontrolname="setupSecret"]')).toBeTruthy();
  });

  it('does not submit invalid data', () => {
    fixture.componentInstance.form.reset();
    fixture.debugElement.query(By.css('form')).triggerEventHandler('ngSubmit');
    expect(service.configure).not.toHaveBeenCalled();
    expect(fixture.componentInstance.form.controls.email.touched).toBe(true);
  });

  it('keeps the secret outside the body and closes the form after success', () => {
    fixture.componentInstance.form.setValue({
      setupSecret: 'segredo-temporario', administratorName: 'Diego', email: 'diego@example.com',
      password: 'frase segura 2026', spaceName: 'Minha casa',
    });
    fixture.debugElement.query(By.css('form')).triggerEventHandler('ngSubmit');
    fixture.detectChanges();
    expect(service.configure).toHaveBeenCalledWith({
      administratorName: 'Diego', email: 'diego@example.com', password: 'frase segura 2026', spaceName: 'Minha casa',
    }, 'segredo-temporario');
    expect(fixture.nativeElement.textContent).toContain('Espaço configurado');
    expect(fixture.nativeElement.querySelector('form')).toBeNull();
  });
});
