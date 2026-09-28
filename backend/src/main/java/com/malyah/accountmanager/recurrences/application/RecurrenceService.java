package com.malyah.accountmanager.recurrences.application;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.malyah.accountmanager.expenses.application.port.CategoryRepository;
import com.malyah.accountmanager.expenses.application.AnticipatedRecurringExpenseCommand;
import com.malyah.accountmanager.expenses.application.RecurringExpenseMaterializer;
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
    private final RecurringExpenseMaterializer materializer;

    public RecurrenceService(RecurrenceRepository repository, AuthenticatedUserContextQuery context,
            CategoryRepository categories, FinancialMemberAccess members, Clock clock,
            java.util.function.Supplier<UUID> identifiers, RecurrenceCalendar calendar,
            RecurringExpenseMaterializer materializer) {
        this.repository = repository; this.context = context; this.categories = categories;
        this.members = members; this.clock = clock; this.identifiers = identifiers; this.calendar = calendar;
        this.materializer = materializer;
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

    @Override public ForecastPeriodView forecasts(String actorEmail) {
        var actor=context.findByEmail(actorEmail);
        var from=YearMonth.now(clock.withZone(ZoneId.of(actor.timeZone())));
        var to=from.plusMonths(12);
        return forecasts(actor.spaceId(),from,to);
    }

    @Override public AnticipationResult anticipate(String actorEmail,UUID recurrenceId,LocalDate scheduledDueDate,
            UUID idempotencyKey) {
        if(recurrenceId==null||scheduledDueDate==null||idempotencyKey==null)
            throw new RecurrenceOccurrenceException("Informe a recorrência, o vencimento previsto e a chave de repetição.");
        var actor=context.findByEmail(actorEmail);
        members.requireActiveParticipants(actor.spaceId(),actor.userId(),null);
        var current=YearMonth.now(clock.withZone(ZoneId.of(actor.timeZone())));
        var month=YearMonth.from(scheduledDueDate);
        if(month.isBefore(current)||month.isAfter(current.plusMonths(12)))
            throw new RecurrenceOccurrenceException("A ocorrência deve estar no mês atual ou nos próximos 12 meses.");
        final StoredRecurrence stored;
        try { stored=repository.findById(actor.spaceId(),recurrenceId); }
        catch(org.springframework.dao.EmptyResultDataAccessException error) {
            throw new RecurrenceOccurrenceException("A ocorrência prevista não está disponível neste espaço.");
        }
        var d=stored.definition();
        var expected=calendar.occurrenceInMonth(d.firstDueDate(),d.lastDueDate(),d.frequency(),month);
        if(expected.isEmpty()||!expected.get().equals(scheduledDueDate))
            throw new RecurrenceOccurrenceException("A data não corresponde ao calendário atual da recorrência.");
        var claim=repository.claimAnticipation(actor.spaceId(),actor.userId(),recurrenceId,scheduledDueDate,
                idempotencyKey,fingerprint(recurrenceId,scheduledDueDate),clock.instant());
        UUID expenseId=claim.expenseId();
        if(!claim.replayed()) {
            expenseId=materializer.materializeAnticipated(new AnticipatedRecurringExpenseCommand(identifiers.get(),
                    recurrenceId,actor.spaceId(),d.description(),d.amount(),
                    d.valueType()==com.malyah.accountmanager.recurrences.domain.RecurrenceValueType.FIXED,
                    scheduledDueDate,d.categoryId(),d.responsibleUserId(),actor.userId(),clock.instant()));
            repository.completeAnticipation(actor.spaceId(),actor.userId(),idempotencyKey,expenseId,clock.instant());
        }
        var occurrence=forecasts(actor.spaceId(),month,month).occurrences().stream()
                .filter(item->recurrenceId.equals(item.recurrenceId())&&scheduledDueDate.equals(item.scheduledDueDate()))
                .findFirst().orElseThrow(()->new RecurrenceOccurrenceException("Não foi possível reconciliar o lançamento antecipado."));
        return new AnticipationResult(occurrence,claim.replayed());
    }

    private ForecastPeriodView forecasts(UUID spaceId,YearMonth from,YearMonth to) {
        var definitions=repository.findAll(spaceId);
        Map<OccurrenceKey,StoredOccurrence> actual=repository.findOccurrences(spaceId,from.atDay(1),to.atEndOfMonth())
                .stream().collect(Collectors.toMap(o->new OccurrenceKey(o.recurrenceId(),o.scheduledDueDate()),Function.identity()));
        var result=new ArrayList<ForecastView>();
        for(var stored:definitions) for(var month=from;!month.isAfter(to);month=month.plusMonths(1)) {
            var d=stored.definition();
            calendar.occurrenceInMonth(d.firstDueDate(),d.lastDueDate(),d.frequency(),month).ifPresent(date->{
                var materialized=actual.get(new OccurrenceKey(d.id(),date));
                if(materialized==null) result.add(new ForecastView(d.id(),d.description(),d.amount().setScale(2).toPlainString(),
                        d.valueType()==com.malyah.accountmanager.recurrences.domain.RecurrenceValueType.VARIABLE_ESTIMATE,
                        date,"FORECAST",null,null,null,false));
                else result.add(new ForecastView(d.id(),d.description(),materialized.amount().setScale(2).toPlainString(),
                        !materialized.chargeConfirmed(),date,"MATERIALIZED",materialized.expenseId(),
                        materialized.actualDueDate(),materialized.status(),materialized.chargeConfirmed()));
            });
        }
        result.sort(Comparator.comparing(ForecastView::scheduledDueDate).thenComparing(ForecastView::recurrenceId));
        return new ForecastPeriodView(from,to,List.copyOf(result));
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
    private String fingerprint(UUID recurrenceId,LocalDate dueDate) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((recurrenceId+"|"+dueDate).getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    private record OccurrenceKey(UUID recurrenceId,LocalDate scheduledDueDate) { }
}
