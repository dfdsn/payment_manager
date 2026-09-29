package com.malyah.accountmanager.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.malyah.accountmanager.expenses.application.ExpenseDateBasis;
import com.malyah.accountmanager.expenses.application.ExpenseReportQueries;
import com.malyah.accountmanager.expenses.application.ExpenseSelection;
import com.malyah.accountmanager.expenses.application.ExpenseStatusFilter;
import com.malyah.accountmanager.expenses.application.ExpenseTotalsBucket;
import com.malyah.accountmanager.expenses.application.InstallmentLink;
import com.malyah.accountmanager.expenses.application.PaymentRecordPage;
import com.malyah.accountmanager.expenses.application.PaymentSort;
import com.malyah.accountmanager.expenses.application.ReportedExpense;
import com.malyah.accountmanager.expenses.application.SortDirection;
import com.malyah.accountmanager.expenses.domain.ExpenseStatus;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContext;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextNotFoundException;
import com.malyah.accountmanager.identity.domain.SpaceRole;
import com.malyah.accountmanager.reporting.application.port.MonthClosingRepository;
import com.malyah.accountmanager.reporting.domain.ClosingMonthNotAllowedException;

/** H07.1 use case over in-memory ports: what is read, what is written, and every refusal. */
class MonthClosingServiceTest {
    static final UUID SPACE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    static final UUID CATEGORY = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    // 12:00 in São Paulo on 15/10/2026.
    static final Instant NOW = Instant.parse("2026-10-15T15:00:00Z");

    final InMemoryClosings repository = new InMemoryClosings();
    final List<ExpenseSelection> selections = new ArrayList<>();
    final List<String> locks = new ArrayList<>();
    List<ReportedExpense> entries = List.of();

    final ExpenseReportQueries queries = new ExpenseReportQueries() {
        @Override
        public List<ExpenseTotalsBucket> totals(UUID spaceId, ExpenseSelection selection) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PaymentRecordPage payments(UUID spaceId, ExpenseSelection selection, int page, int size,
                PaymentSort sort, SortDirection direction) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ReportedExpense> entries(UUID spaceId, ExpenseSelection selection) {
            assertThat(spaceId).isEqualTo(SPACE);
            selections.add(selection);
            return entries;
        }
    };

    MonthClosingService service(Instant now) {
        var ids = new java.util.concurrent.atomic.AtomicInteger();
        return new MonthClosingService(repository, queries, email -> {
            if (!email.equals("ana@example.com")) throw new AuthenticatedUserContextNotFoundException();
            return new AuthenticatedUserContext(USER, "Ana", email, SPACE, "Casa", SpaceRole.GUEST, "BRL", "pt-BR",
                    "America/Sao_Paulo");
        }, (space, actor, payer) -> locks.add(space + "/" + actor + "/" + payer), Clock.fixed(now, ZoneOffset.UTC),
                () -> UUID.fromString("00000000-0000-0000-0000-%012d".formatted(100 + ids.incrementAndGet())));
    }

    static ReportedExpense pendingEntry(int id, String charge, int day, boolean confirmed, boolean overdue) {
        return new ReportedExpense(uuid(id), "RECURRENCE", null, "Conta " + id, LocalDate.of(2026, 10, day), true,
                ExpenseStatus.PENDING, new BigDecimal(charge), confirmed, null, overdue, null, null);
    }

    static ReportedExpense paidEntry(int id, String charge, String paid, int day) {
        return new ReportedExpense(uuid(id), "INSTALLMENT", new InstallmentLink(uuid(900), 1, 3), "Parcela " + id,
                LocalDate.of(2026, 10, day), false, ExpenseStatus.PAID, new BigDecimal(charge), true,
                new BigDecimal(paid), false, CATEGORY, "Casa");
    }

    static UUID uuid(int id) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(id));
    }

    @Test
    void closesTheMonthWithAllItsEntriesAuditAndIdempotencyRecord() {
        entries = List.of(pendingEntry(1, "180.00", 20, false, false), paidEntry(2, "150.00", "155.00", 5));
        var key = UUID.randomUUID();

        var result = service(NOW).close("ana@example.com", new CloseMonthCommand("2026-10", true, key));

        assertThat(result.replayed()).isFalse();
        assertThat(locks).containsExactly(SPACE + "/" + USER + "/null");
        var selection = selections.getFirst();
        assertThat(selection).isEqualTo(new ExpenseSelection(null, LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 31), ExpenseDateBasis.DUE_DATE, null, false, null, false, null,
                ExpenseStatusFilter.ACTIVE, LocalDate.of(2026, 10, 15)));
        var version = repository.versions.values().iterator().next();
        assertThat(version.number()).isEqualTo(1);
        assertThat(version.month()).isEqualTo(YearMonth.of(2026, 10));
        assertThat(version.authorUserId()).isEqualTo(USER);
        assertThat(version.authorDisplayName()).isEqualTo("Ana");
        assertThat(version.createdAt()).isEqualTo(NOW);
        assertThat(version.businessDate()).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(version.timeZone()).isEqualTo("America/Sao_Paulo");
        assertThat(version.pendingAcknowledged()).isTrue();
        assertThat(version.indicators().plannedTotal()).isEqualByComparingTo("330.00");
        assertThat(version.indicators().pendingEstimated()).isEqualByComparingTo("180.00");
        assertThat(repository.events).containsExactly("MONTH_CLOSED/1/" + USER);
        assertThat(repository.completed).containsEntry(key, version.id());
        var saved = result.closing().saved();
        assertThat(saved.version()).isEqualTo(1);
        assertThat(saved.indicators().plannedTotal()).isEqualTo("330.00");
        assertThat(saved.indicators().adjustmentNet()).isEqualTo("5.00");
        assertThat(saved.categories()).extracting(ClosingCategoryView::categoryName).containsExactly("Casa", null);
        var parcel = saved.lines().getFirst();
        assertThat(parcel.origin()).isEqualTo("INSTALLMENT");
        assertThat(parcel.installmentNumber()).isEqualTo(1);
        assertThat(parcel.installmentCount()).isEqualTo(3);
        assertThat(parcel.dueDateInformed()).isFalse();
        assertThat(parcel.paidAmount()).isEqualTo("155.00");
        assertThat(parcel.adjustment()).isEqualTo("5.00");
        assertThat(parcel.status()).isEqualTo("PAID");
        var estimate = saved.lines().getLast();
        assertThat(estimate.estimated()).isTrue();
        assertThat(estimate.paidAmount()).isNull();
        assertThat(estimate.adjustment()).isNull();
        assertThat(result.closing().current().version()).isNull();
        assertThat(result.closing().current().contentDigest()).isEqualTo(saved.contentDigest());
        assertThat(result.closing().closable()).isTrue();
        assertThat(result.closing().dateBasis()).isEqualTo("DUE_DATE");
        assertThat(result.closing().periodEnd()).isEqualTo(LocalDate.of(2026, 10, 31));
    }

    @Test
    void aRepeatedKeyReturnsTheStoredClosingWithoutWritingAgain() {
        entries = List.of(paidEntry(2, "150.00", "150.00", 5));
        var key = UUID.randomUUID();
        var first = service(NOW).close("ana@example.com", new CloseMonthCommand("2026-10", false, key));
        entries = List.of();
        var repeated = service(NOW).close("ana@example.com", new CloseMonthCommand("2026-10", false, key));
        assertThat(repeated.replayed()).isTrue();
        assertThat(repeated.closing().saved()).isEqualTo(first.closing().saved());
        assertThat(repository.versions).hasSize(1);
        assertThat(repository.events).hasSize(1);
        assertThat(repository.hashes.get(key)).isEqualTo(MonthClosingService.hash("CLOSE_MONTH", "2026-10", "false"));
    }

    @Test
    void refusesPendingEntriesWithoutTheConfirmedWarning() {
        entries = List.of(pendingEntry(1, "100.00", 10, true, true), pendingEntry(3, "50.00", 11, true, true));
        assertThatThrownBy(() -> service(NOW).close("ana@example.com", new CloseMonthCommand("2026-10", false,
                UUID.randomUUID()))).isInstanceOfSatisfying(ClosingPendingConfirmationRequiredException.class,
                        error -> {
                            assertThat(error.pendingCount()).isEqualTo(2);
                            assertThat(error.getMessage()).contains("2 conta(s) pendente(s)");
                        });
        assertThat(repository.versions).isEmpty();
        assertThat(repository.events).isEmpty();
    }

    @Test
    void anEmptyMonthNeedsNoWarning() {
        var saved = service(NOW).close("ana@example.com", new CloseMonthCommand("2026-08", false, UUID.randomUUID()))
                .closing().saved();
        assertThat(saved.lines()).isEmpty();
        assertThat(saved.pendingAcknowledged()).isFalse();
    }

    @Test
    void refusesFutureMonthsAlreadyClosedMonthsAndInvalidRequests() {
        var service = service(NOW);
        assertThatThrownBy(() -> service.close("ana@example.com", new CloseMonthCommand("2026-11", true,
                UUID.randomUUID()))).isInstanceOf(ClosingMonthNotAllowedException.class);
        assertThat(repository.headers).isEmpty();
        service.close("ana@example.com", new CloseMonthCommand("2026-09", false, UUID.randomUUID()));
        assertThatThrownBy(() -> service.close("ana@example.com", new CloseMonthCommand("2026-09", false,
                UUID.randomUUID()))).isInstanceOf(MonthAlreadyClosedException.class)
                .hasMessageContaining("já foi fechado");
        assertThatThrownBy(() -> service.close("ana@example.com", new CloseMonthCommand("2026-09", false, null)))
                .isInstanceOfSatisfying(ReportQueryValidationException.class,
                        error -> assertThat(error.field()).isEqualTo("Idempotency-Key"));
        assertThatThrownBy(() -> service.close("ana@example.com", new CloseMonthCommand(" ", false,
                UUID.randomUUID()))).isInstanceOfSatisfying(ReportQueryValidationException.class,
                        error -> assertThat(error.field()).isEqualTo("month"));
        assertThatThrownBy(() -> service.close("ana@example.com", new CloseMonthCommand("10/2026", false,
                UUID.randomUUID()))).isInstanceOf(ReportQueryValidationException.class);
        assertThatThrownBy(() -> service.close("bia@example.com", new CloseMonthCommand("2026-10", false,
                UUID.randomUUID()))).isInstanceOf(AuthenticatedUserContextNotFoundException.class);
        assertThatThrownBy(() -> service.close("ana@example.com", null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void viewDefaultsToTheCurrentMonthInTheSpaceTimeZoneAndShowsOnlyCurrentDataBeforeClosing() {
        // 02:30 UTC on 01/11 is still 31/10 in São Paulo.
        entries = List.of(pendingEntry(1, "100.00", 10, true, true));
        var view = service(Instant.parse("2026-11-01T02:30:00Z")).view("ana@example.com", null);
        assertThat(view.month()).isEqualTo("2026-10");
        assertThat(view.today()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(view.timeZone()).isEqualTo("America/Sao_Paulo");
        assertThat(view.saved()).isNull();
        assertThat(view.closable()).isTrue();
        assertThat(view.current().businessDate()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(view.current().authorUserId()).isNull();
        assertThat(view.current().indicators().pendingTotal()).isEqualTo("100.00");
        assertThat(service(NOW).view("ana@example.com", "2026-12").closable()).isFalse();
        assertThat(service(NOW).view("ana@example.com", "2026-12").periodStart()).isEqualTo(LocalDate.of(2026, 12, 1));
    }

    @Test
    void cancelledEntriesNeverReachAClosing() {
        entries = List.of(new ReportedExpense(uuid(1), "ONE_OFF", null, "x", LocalDate.of(2026, 10, 1), true,
                ExpenseStatus.CANCELLED, BigDecimal.TEN, true, null, false, null, null));
        assertThatThrownBy(() -> service(NOW).view("ana@example.com", "2026-10"))
                .isInstanceOf(IllegalStateException.class);
    }

    /** In-memory closing store with the same contract as the PostgreSQL adapter. */
    static final class InMemoryClosings implements MonthClosingRepository {
        final Map<YearMonth, StoredClosing> headers = new HashMap<>();
        final Map<UUID, ClosingVersion> versions = new HashMap<>();
        final Map<UUID, String> hashes = new HashMap<>();
        final Map<UUID, UUID> completed = new HashMap<>();
        final List<String> events = new ArrayList<>();

        @Override
        public ClosingClaim claim(UUID spaceId, UUID actorId, String operation, UUID key, String requestHash,
                Instant at) {
            var existing = hashes.putIfAbsent(key, requestHash);
            if (existing == null) return new ClosingClaim(false, null);
            if (!existing.equals(requestHash) || !completed.containsKey(key))
                throw new ClosingIdempotencyConflictException();
            return new ClosingClaim(true, completed.get(key));
        }

        @Override
        public void complete(UUID spaceId, UUID actorId, String operation, UUID key, UUID versionId, Instant at) {
            completed.put(key, versionId);
        }

        @Override
        public boolean create(UUID closingId, UUID spaceId, YearMonth month, Instant at) {
            return headers.putIfAbsent(month, new StoredClosing(closingId, month, 1, at, at)) == null;
        }

        @Override
        public Optional<StoredClosing> find(UUID spaceId, YearMonth month) {
            return Optional.ofNullable(headers.get(month));
        }

        @Override
        public void insertVersion(UUID spaceId, ClosingVersion version) {
            versions.put(version.id(), version);
        }

        @Override
        public void recordEvent(UUID closingId, UUID spaceId, int versionNumber, String eventType, UUID actorId,
                Instant at) {
            events.add(eventType + "/" + versionNumber + "/" + actorId);
        }

        @Override
        public Optional<ClosingVersion> version(UUID spaceId, UUID closingId, int number) {
            return versions.values().stream().filter(v -> v.closingId().equals(closingId) && v.number() == number)
                    .findFirst();
        }

        @Override
        public Optional<ClosingVersion> versionById(UUID spaceId, UUID versionId) {
            return Optional.ofNullable(versions.get(versionId));
        }
    }
}
