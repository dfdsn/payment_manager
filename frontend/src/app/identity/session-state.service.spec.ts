import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { Subject, of, throwError } from 'rxjs';
import { AccountAccessService, AuthenticatedUserContext } from './account-access.service';
import { SessionStateService } from './session-state.service';

const DIEGO = { userId: 'u', displayName: 'Diego', role: 'ADMINISTRATOR' } as AuthenticatedUserContext;

describe('SessionStateService (H09.1)', () => {
  const access = { context: vi.fn() };
  let session: SessionStateService;

  beforeEach(() => {
    vi.clearAllMocks();
    TestBed.configureTestingModule({ providers: [{ provide: AccountAccessService, useValue: access }] });
    session = TestBed.inject(SessionStateService);
  });

  it('starts unknown, then follows what the server answers', () => {
    expect(session.user()).toBeUndefined();
    access.context.mockReturnValue(of(DIEGO));
    session.refresh();
    expect(session.user()).toEqual(DIEGO);

    access.context.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 401 })));
    session.refresh();
    expect(session.user()).toBeNull();
  });

  it('treats a failed check (no connection) as no session, so no menu is offered on stale data', () => {
    access.context.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 0 })));
    session.refresh();
    expect(session.user()).toBeNull();
  });

  it('lets only the latest check win and an explicit logout cancel a check in flight', () => {
    const slow = new Subject<AuthenticatedUserContext>();
    access.context.mockReturnValueOnce(slow);
    session.refresh();
    session.signedOut();
    slow.next(DIEGO);
    expect(session.user()).toBeNull();

    const old = new Subject<AuthenticatedUserContext>();
    access.context.mockReturnValueOnce(old).mockReturnValueOnce(of({ ...DIEGO, displayName: 'Nova' }));
    session.refresh();
    session.refresh();
    old.next(DIEGO);
    expect(session.user()?.displayName).toBe('Nova');

    session.signedIn(DIEGO);
    expect(session.user()).toEqual(DIEGO);
  });
});
