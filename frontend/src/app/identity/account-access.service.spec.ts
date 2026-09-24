import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withXsrfConfiguration } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AccountAccessService } from './account-access.service';

describe('AccountAccessService', () => {
  let service: AccountAccessService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withXsrfConfiguration({ cookieName: 'XSRF-TOKEN', headerName: 'X-XSRF-TOKEN' })),
        provideHttpClientTesting(),
      ],
    });
    service = TestBed.inject(AccountAccessService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('obtains CSRF before posting credentials', () => {
    service.login('admin@example.com', 'senha').subscribe();
    http.expectOne('/api/v1/auth/csrf').flush({ headerName: 'X-XSRF-TOKEN' });
    const login = http.expectOne('/api/v1/auth/login');
    expect(login.request.method).toBe('POST');
    expect(login.request.body).toEqual({ email: 'admin@example.com', password: 'senha' });
    login.flush(null);
  });

  it('marks context lookup as explicit human activity', () => {
    service.context().subscribe();
    const context = http.expectOne('/api/v1/identity/me');
    expect(context.request.headers.get('X-User-Activity')).toBe('true');
    context.flush({});
  });

  it('uses the public generic recovery endpoint without exposing tokens', () => {
    service.requestPasswordReset('person@example.com').subscribe();
    http.expectOne('/api/v1/auth/csrf').flush({ headerName: 'X-XSRF-TOKEN' });
    const reset = http.expectOne('/api/v1/auth/password-resets');
    expect(reset.request.body).toEqual({ email: 'person@example.com' });
    reset.flush({ message: 'Resposta genérica' });
  });
});
