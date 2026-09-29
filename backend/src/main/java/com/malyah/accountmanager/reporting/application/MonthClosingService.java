package com.malyah.accountmanager.reporting.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Year;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
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
import com.malyah.accountmanager.reporting.domain.ClosingComparison;
import com.malyah.accountmanager.reporting.domain.ClosingLine;
import com.malyah.accountmanager.reporting.domain.ClosingPeriod;
import com.malyah.accountmanager.reporting.domain.ClosingStatus;
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
    static final String VERSION_OPERATION = "GENERATE_VERSION";
    static final String VERSION_GENERATED_EVENT = "VERSION_GENERATED";

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

    /**
     * H07.3 (RF-FEC-04): the new version is the content of the month now, with the rules of the closing. The
     * header is locked and must still have {@code expectedVersion} in force, so two members never create two
     * versions from the same view; version, switch of the version in force, event and idempotency record are
     * written together or not at all.
     */
    @Override
    public CloseMonthResult generateVersion(String actorEmail, GenerateVersionCommand command) {
        Objects.requireNonNull(command, "command");
        if (command.idempotencyKey() == null)
            throw new ReportQueryValidationException("Idempotency-Key", "Informe uma chave de repetição válida.");
        var month = ReportingService.parseMonth(command.month());
        if (month == null) throw new ReportQueryValidationException("month", "Informe o mês no formato AAAA-MM.");
        if (command.expectedVersion() == null || command.expectedVersion() < 1)
            throw new ReportQueryValidationException("expectedVersion", "Informe a versão vigente que você revisou.");
        var actor = contexts.findByEmail(actorEmail);
        // Same lock as every financial write of the space: no expense change lands between the read and the save.
        members.requireActiveParticipants(actor.spaceId(), actor.userId(), null);
        var now = clock.instant();
        var claim = repository.claim(actor.spaceId(), actor.userId(), VERSION_OPERATION, command.idempotencyKey(),
                hash(VERSION_OPERATION, month.toString(), command.expectedVersion().toString(),
                        Boolean.toString(command.acknowledgePending())), now);
        var today = today(actor);
        if (claim.replayed()) {
            var version = repository.versionById(actor.spaceId(), claim.versionId())
                    .orElseThrow(() -> new IllegalStateException("The replayed version no longer exists."));
            return new CloseMonthResult(view(actor, version.month(), today), true);
        }
        var closing = repository.lock(actor.spaceId(), month).orElseThrow(MonthNotClosedException::new);
        if (closing.currentVersion() != command.expectedVersion())
            throw new ClosingVersionConflictException(closing.currentVersion());
        var summary = ClosingSummary.of(lines(actor, month, today));
        if (summary.hasPending() && !command.acknowledgePending())
            throw new ClosingPendingConfirmationRequiredException(summary.indicators().pendingCount());
        var version = ClosingVersion.of(identifiers.get(), closing.id(), month, closing.currentVersion() + 1,
                actor.userId(), actor.displayName(), now, today, actor.timeZone(), command.acknowledgePending(),
                summary);
        repository.insertVersion(actor.spaceId(), version);
        if (!repository.advance(actor.spaceId(), closing.id(), closing.currentVersion(), version.number(), now))
            throw new IllegalStateException("The locked closing changed its version in force.");
        repository.recordEvent(closing.id(), actor.spaceId(), version.number(), VERSION_GENERATED_EVENT,
                actor.userId(), now);
        repository.complete(actor.spaceId(), actor.userId(), VERSION_OPERATION, command.idempotencyKey(),
                version.id(), now);
        return new CloseMonthResult(view(actor, month, today), false);
    }

    @Override
    public ClosingVersionListView versions(String actorEmail, String month) {
        var yearMonth = requiredMonth(month);
        var actor = contexts.findByEmail(actorEmail);
        return repository.find(actor.spaceId(), yearMonth)
                .map(closing -> new ClosingVersionListView(yearMonth.toString(), closing.currentVersion(),
                        repository.versions(actor.spaceId(), closing.id()).stream()
                                .map(entry -> ClosingVersionListView.Item.of(entry, closing.currentVersion()))
                                .toList()))
                .orElseGet(() -> new ClosingVersionListView(yearMonth.toString(), null, List.of()));
    }

    @Override
    public ClosingVersionView version(String actorEmail, String month, String number) {
        var yearMonth = requiredMonth(month);
        var requested = parseVersion(number);
        var actor = contexts.findByEmail(actorEmail);
        var closing = repository.find(actor.spaceId(), yearMonth).orElseThrow(ClosingVersionNotFoundException::new);
        var version = repository.version(actor.spaceId(), closing.id(), requested)
                .orElseThrow(ClosingVersionNotFoundException::new);
        return new ClosingVersionView(yearMonth.toString(), closing.currentVersion(),
                requested == closing.currentVersion(), savedView(version));
    }

    private static YearMonth requiredMonth(String month) {
        var parsed = ReportingService.parseMonth(month);
        if (parsed == null) throw new ReportQueryValidationException("month", "Informe o mês no formato AAAA-MM.");
        return parsed;
    }

    static int parseVersion(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,5}"))
            throw new ReportQueryValidationException("version", "Informe o número da versão.");
        return Integer.parseInt(value);
    }

    MonthClosingView view(AuthenticatedUserContext actor, YearMonth month, LocalDate today) {
        var version = repository.find(actor.spaceId(), month)
                .map(closing -> repository.version(actor.spaceId(), closing.id(), closing.currentVersion())
                        .orElseThrow(() -> new IllegalStateException("The version in force is missing.")));
        var current = ClosingSummary.of(lines(actor, month, today));
        // H07.2: derived on every read from the saved lines and the current ones; nothing is stored or flagged.
        var changes = version.map(saved -> ClosingComparison.between(saved.lines(), current.lines()))
                .orElse(List.of());
        return new MonthClosingView(month.toString(), month.atDay(1), month.atEndOfMonth(),
                ExpenseDateBasis.DUE_DATE.name(), today, actor.timeZone(), ClosingPeriod.closable(month, today),
                ClosingComparison.status(version.isPresent(), changes).name(),
                changes.stream().map(ClosingChangeView::of).toList(),
                version.map(MonthClosingService::savedView).orElse(null),
                currentView(current, today, actor.timeZone()));
    }

    @Override
    public MonthClosingListView list(String actorEmail, String year) {
        var requested = parseYear(year);
        var actor = contexts.findByEmail(actorEmail);
        var today = today(actor);
        var selected = requested == null ? Year.from(today) : requested;
        var heads = repository.list(actor.spaceId(), selected);
        if (heads.isEmpty()) return new MonthClosingListView(selected.getValue(), List.of());
        // One statement for the whole year, grouped by month: the same lines a closing of each month would read.
        var selection = new ExpenseSelection(null, selected.atDay(1), selected.atMonth(12).atEndOfMonth(),
                ExpenseDateBasis.DUE_DATE, null, false, null, false, null, ExpenseStatusFilter.ACTIVE, today);
        var byMonth = new HashMap<YearMonth, List<ClosingLine>>();
        expenses.entries(actor.spaceId(), selection).forEach(expense -> byMonth
                .computeIfAbsent(YearMonth.from(expense.referenceDate()), key -> new ArrayList<>()).add(line(expense)));
        var items = heads.stream().map(head -> {
            var digest = ClosingSummary.of(byMonth.getOrDefault(head.month(), List.of())).digest();
            var status = digest.equals(head.contentDigest()) ? ClosingStatus.UP_TO_DATE : ClosingStatus.OUTDATED;
            return new MonthClosingListView.Item(head.month().toString(), head.currentVersion(),
                    head.authorDisplayName(), head.createdAt(), status.name());
        }).toList();
        return new MonthClosingListView(selected.getValue(), items);
    }

    static Year parseYear(String value) {
        if (value == null || value.isBlank()) return null;
        if (!value.matches("\\d{4}") || Integer.parseInt(value) < 2000)
            throw new ReportQueryValidationException("year", "Informe o ano no formato AAAA.");
        return Year.of(Integer.parseInt(value));
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
