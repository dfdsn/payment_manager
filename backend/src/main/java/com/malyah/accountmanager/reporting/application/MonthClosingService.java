package com.malyah.accountmanager.reporting.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import com.malyah.accountmanager.expenses.application.ExpenseDateBasis;
import com.malyah.accountmanager.expenses.application.ExpenseReportQueries;
import com.malyah.accountmanager.expenses.application.ExpenseSelection;
import com.malyah.accountmanager.expenses.application.ExpenseStatusFilter;
import com.malyah.accountmanager.expenses.application.ReportedExpense;
import com.malyah.accountmanager.expenses.domain.ExpenseStatus;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContext;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextQuery;
import com.malyah.accountmanager.identity.application.FinancialMemberAccess;
import com.malyah.accountmanager.reporting.application.port.MonthClosingRepository;
import com.malyah.accountmanager.reporting.domain.ClosingLine;
import com.malyah.accountmanager.reporting.domain.ClosingPeriod;
import com.malyah.accountmanager.reporting.domain.ClosingSummary;
import com.malyah.accountmanager.reporting.domain.Situation;

/**
 * E07 month closing (RF-FEC-01/02). The content is computed from the entries of the month read by the expenses
 * module in one statement, with the H06.1 rules; the closing only writes its own tables, so it never settles,
 * cancels or hides an expense. Space and business date always come from the authenticated membership.
 */
public final class MonthClosingService implements MonthClosingUseCase {
    static final String CLOSE_OPERATION = "CLOSE_MONTH";
    static final String MONTH_CLOSED_EVENT = "MONTH_CLOSED";

    private final MonthClosingRepository repository;
    private final ExpenseReportQueries expenses;
    private final AuthenticatedUserContextQuery contexts;
    private final FinancialMemberAccess members;
    private final Clock clock;
    private final Supplier<UUID> identifiers;

    public MonthClosingService(MonthClosingRepository repository, ExpenseReportQueries expenses,
            AuthenticatedUserContextQuery contexts, FinancialMemberAccess members, Clock clock,
            Supplier<UUID> identifiers) {
        this.repository = Objects.requireNonNull(repository);
        this.expenses = Objects.requireNonNull(expenses);
        this.contexts = Objects.requireNonNull(contexts);
        this.members = Objects.requireNonNull(members);
        this.clock = Objects.requireNonNull(clock);
        this.identifiers = Objects.requireNonNull(identifiers);
    }

    @Override
    public MonthClosingView view(String actorEmail, String month) {
        var requested = ReportingService.parseMonth(month);
        var actor = contexts.findByEmail(actorEmail);
        var today = today(actor);
        var yearMonth = requested == null ? YearMonth.from(today) : requested;
        return view(actor, yearMonth, today);
    }

    @Override
    public CloseMonthResult close(String actorEmail, CloseMonthCommand command) {
        Objects.requireNonNull(command, "command");
        if (command.idempotencyKey() == null)
            throw new ReportQueryValidationException("Idempotency-Key", "Informe uma chave de repetição válida.");
        var month = ReportingService.parseMonth(command.month());
        if (month == null) throw new ReportQueryValidationException("month", "Informe o mês no formato AAAA-MM.");
        var actor = contexts.findByEmail(actorEmail);
        // Locks the space: serializes with member departure and keeps the author an active participant.
        members.requireActiveParticipants(actor.spaceId(), actor.userId(), null);
        var now = clock.instant();
        var claim = repository.claim(actor.spaceId(), actor.userId(), CLOSE_OPERATION, command.idempotencyKey(),
                hash(CLOSE_OPERATION, month.toString(), Boolean.toString(command.acknowledgePending())), now);
        var today = today(actor);
        if (claim.replayed()) {
            var version = repository.versionById(actor.spaceId(), claim.versionId())
                    .orElseThrow(() -> new IllegalStateException("The replayed closing no longer exists."));
            return new CloseMonthResult(view(actor, version.month(), today), true);
        }
        ClosingPeriod.requireClosable(month, today);
        var closingId = identifiers.get();
        if (!repository.create(closingId, actor.spaceId(), month, now)) throw new MonthAlreadyClosedException();
        var summary = ClosingSummary.of(lines(actor, month, today));
        if (summary.hasPending() && !command.acknowledgePending())
            throw new ClosingPendingConfirmationRequiredException(summary.indicators().pendingCount());
        var version = ClosingVersion.of(identifiers.get(), closingId, month, 1, actor.userId(), actor.displayName(),
                now, today, actor.timeZone(), command.acknowledgePending(), summary);
        repository.insertVersion(actor.spaceId(), version);
        repository.recordEvent(closingId, actor.spaceId(), version.number(), MONTH_CLOSED_EVENT, actor.userId(), now);
        repository.complete(actor.spaceId(), actor.userId(), CLOSE_OPERATION, command.idempotencyKey(), version.id(),
                now);
        return new CloseMonthResult(view(actor, month, today), false);
    }

    MonthClosingView view(AuthenticatedUserContext actor, YearMonth month, LocalDate today) {
        var saved = repository.find(actor.spaceId(), month)
                .map(closing -> repository.version(actor.spaceId(), closing.id(), closing.currentVersion())
                        .orElseThrow(() -> new IllegalStateException("The version in force is missing.")))
                .map(MonthClosingService::savedView).orElse(null);
        var current = currentView(ClosingSummary.of(lines(actor, month, today)), today, actor.timeZone());
        return new MonthClosingView(month.toString(), month.atDay(1), month.atEndOfMonth(),
                ExpenseDateBasis.DUE_DATE.name(), today, actor.timeZone(), ClosingPeriod.closable(month, today), saved,
                current);
    }

    /** Every non-cancelled entry of the month by due date, without filters, read in one statement. */
    List<ClosingLine> lines(AuthenticatedUserContext actor, YearMonth month, LocalDate today) {
        var selection = new ExpenseSelection(null, month.atDay(1), month.atEndOfMonth(), ExpenseDateBasis.DUE_DATE,
                null, false, null, false, null, ExpenseStatusFilter.ACTIVE, today);
        return expenses.entries(actor.spaceId(), selection).stream().map(MonthClosingService::line).toList();
    }

    static ClosingLine line(ReportedExpense expense) {
        var installment = expense.installment();
        return new ClosingLine(expense.id(), expense.description(), expense.origin(),
                installment == null ? null : installment.number(), installment == null ? null : installment.count(),
                expense.referenceDate(), expense.dueDateInformed(), situation(expense.status()),
                expense.chargeAmount(), !expense.chargeConfirmed(), expense.paidAmount(), expense.overdue(),
                expense.categoryId(), expense.categoryName());
    }

    static ClosingSnapshotView savedView(ClosingVersion version) {
        return new ClosingSnapshotView(version.number(), version.authorUserId(), version.authorDisplayName(),
                version.createdAt(), version.businessDate(), version.timeZone(), version.pendingAcknowledged(),
                version.contentDigest(), DueIndicatorsView.of(version.indicators()),
                version.categories().stream().map(ClosingCategoryView::of).toList(),
                version.lines().stream().map(ClosingLineView::of).toList());
    }

    static ClosingSnapshotView currentView(ClosingSummary summary, LocalDate today, String timeZone) {
        return new ClosingSnapshotView(null, null, null, null, today, timeZone, false, summary.digest(),
                DueIndicatorsView.of(summary.indicators()),
                summary.categories().stream().map(ClosingCategoryView::of).toList(),
                summary.lines().stream().map(ClosingLineView::of).toList());
    }

    private static Situation situation(ExpenseStatus status) {
        return switch (status) {
            case PENDING -> Situation.PENDING;
            case PAID -> Situation.PAID;
            case CANCELLED -> throw new IllegalStateException("Lançamento cancelado não compõe o fechamento.");
        };
    }

    private LocalDate today(AuthenticatedUserContext actor) {
        return LocalDate.now(clock.withZone(ZoneId.of(actor.timeZone())));
    }

    static String hash(String... parts) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(String.join("|", parts).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
