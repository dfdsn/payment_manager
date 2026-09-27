package com.malyah.accountmanager.recurrences.application;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.malyah.accountmanager.expenses.application.port.CategoryRepository;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextQuery;
import com.malyah.accountmanager.identity.application.FinancialMemberAccess;
import com.malyah.accountmanager.recurrences.application.port.RecurrenceRepository;
import com.malyah.accountmanager.recurrences.domain.RecurrenceCalendar;
import com.malyah.accountmanager.recurrences.domain.RecurrenceDefinition;
import com.malyah.accountmanager.recurrences.domain.RecurrenceFrequency;
import com.malyah.accountmanager.recurrences.domain.RecurrenceValidationException;

public final class RecurrenceService implements RecurrenceUseCase {
    private final RecurrenceRepository repository;
    private final AuthenticatedUserContextQuery context;
    private final CategoryRepository categories;
    private final FinancialMemberAccess members;
    private final Clock clock;
    private final java.util.function.Supplier<UUID> identifiers;
    private final RecurrenceCalendar calendar;

    public RecurrenceService(RecurrenceRepository repository, AuthenticatedUserContextQuery context,
            CategoryRepository categories, FinancialMemberAccess members, Clock clock,
            java.util.function.Supplier<UUID> identifiers, RecurrenceCalendar calendar) {
        this.repository = repository; this.context = context; this.categories = categories;
        this.members = members; this.clock = clock; this.identifiers = identifiers; this.calendar = calendar;
    }

    @Override
    public RecurrenceCreationResult create(String actorEmail, CreateRecurrenceCommand command) {
        Objects.requireNonNull(command);
        if (command.idempotencyKey() == null) throw new RecurrenceValidationException("Idempotency-Key", "Informe uma chave de repetição válida.");
        var actor = context.findByEmail(actorEmail);
        members.requireActiveParticipants(actor.spaceId(), actor.userId(), command.responsibleUserId());
        categories.requireSelectable(actor.spaceId(), command.categoryId());
        var definition = new RecurrenceDefinition(identifiers.get(), actor.spaceId(), command.description(),
                parseAmount(command.amount()), command.valueType(), command.frequency(), command.firstDueDate(),
                command.lastDueDate(), command.categoryId(), command.responsibleUserId(), actor.userId(), clock.instant(), 0);
        var stored = repository.createIdempotently(definition, actor.userId(), command.idempotencyKey(), fingerprint(definition), clock.instant());
        return new RecurrenceCreationResult(view(stored.recurrence()), stored.replayed());
    }

    @Override public List<RecurrenceView> list(String actorEmail) {
        var actor = context.findByEmail(actorEmail);
        return repository.findAll(actor.spaceId()).stream().map(this::view).toList();
    }

    @Override public List<LocalDate> preview(LocalDate firstDueDate, LocalDate lastDueDate, RecurrenceFrequency frequency) {
        return calendar.firstDates(firstDueDate, lastDueDate, frequency, 12);
    }

    private RecurrenceView view(StoredRecurrence stored) {
        var d = stored.definition();
        return new RecurrenceView(d.id(), d.description(), d.amount().setScale(2).toPlainString(), d.valueType(), d.frequency(),
                d.firstDueDate(), d.lastDueDate(), d.firstDueDate().getDayOfMonth(), d.categoryId(), stored.categoryName(),
                d.responsibleUserId(), stored.responsibleDisplayName(), d.createdByUserId(), stored.createdByDisplayName(),
                d.createdAt(), d.version(), calendar.firstDates(d.firstDueDate(), d.lastDueDate(), d.frequency(), 12));
    }

    private BigDecimal parseAmount(String raw) {
        try { return raw == null ? null : new BigDecimal(raw.trim()); }
        catch (NumberFormatException exception) { throw new RecurrenceValidationException("amount", "Informe um valor decimal válido."); }
    }

    private String fingerprint(RecurrenceDefinition d) {
        var value = String.join("|", d.description(), d.amount().toPlainString(), d.valueType().name(), d.frequency().name(),
                d.firstDueDate().toString(), Objects.toString(d.lastDueDate(), ""), Objects.toString(d.categoryId(), ""),
                Objects.toString(d.responsibleUserId(), ""));
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
