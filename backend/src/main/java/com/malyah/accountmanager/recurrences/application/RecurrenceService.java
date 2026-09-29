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
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.malyah.accountmanager.expenses.application.AnticipatedRecurringExpenseCommand;
import com.malyah.accountmanager.expenses.application.ChargeConfirmationUseCase;
import com.malyah.accountmanager.expenses.application.ConfirmChargeCommand;
import com.malyah.accountmanager.expenses.application.RecurringExpenseMaterializer;
import com.malyah.accountmanager.expenses.application.RecurringOccurrenceAdjuster;
import com.malyah.accountmanager.expenses.application.RecurringOccurrenceAdjustment;
import com.malyah.accountmanager.expenses.application.port.CategoryRepository;
import com.malyah.accountmanager.expenses.domain.VariableEstimateReference;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContext;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextQuery;
import com.malyah.accountmanager.identity.application.FinancialMemberAccess;
import com.malyah.accountmanager.recurrences.application.port.RecurrenceRepository;
import com.malyah.accountmanager.recurrences.domain.RecurrenceCalendar;
import com.malyah.accountmanager.recurrences.domain.RecurrenceChangeField;
import com.malyah.accountmanager.recurrences.domain.RecurrenceChangePlanner;
import com.malyah.accountmanager.recurrences.domain.RecurrenceChangePlanner.Action;
import com.malyah.accountmanager.recurrences.domain.RecurrenceChangePlanner.Effect;
import com.malyah.accountmanager.recurrences.domain.RecurrenceChangePlanner.OccurrenceState;
import com.malyah.accountmanager.recurrences.domain.RecurrenceConfiguration;
import com.malyah.accountmanager.recurrences.domain.RecurrenceDefinition;
import com.malyah.accountmanager.recurrences.domain.RecurrenceFrequency;
import com.malyah.accountmanager.recurrences.domain.RecurrenceSchedule;
import com.malyah.accountmanager.recurrences.domain.RecurrenceSegment;
import com.malyah.accountmanager.recurrences.domain.RecurrenceValidationException;
import com.malyah.accountmanager.recurrences.domain.RecurrenceValueType;

public final class RecurrenceService implements RecurrenceUseCase {
    private static final int HORIZON_MONTHS = 12;
    private static final int REASON_LIMIT = 2000;
    private final RecurrenceRepository repository;
    private final AuthenticatedUserContextQuery context;
    private final CategoryRepository categories;
    private final FinancialMemberAccess members;
    private final Clock clock;
    private final java.util.function.Supplier<UUID> identifiers;
    private final RecurrenceCalendar calendar;
    private final RecurringExpenseMaterializer materializer;
    private final ChargeConfirmationUseCase chargeConfirmation;
    private final RecurringOccurrenceAdjuster adjuster;

    public RecurrenceService(RecurrenceRepository repository, AuthenticatedUserContextQuery context,
            CategoryRepository categories, FinancialMemberAccess members, Clock clock,
            java.util.function.Supplier<UUID> identifiers, RecurrenceCalendar calendar,
            RecurringExpenseMaterializer materializer, ChargeConfirmationUseCase chargeConfirmation,
            RecurringOccurrenceAdjuster adjuster) {
        this.repository = repository; this.context = context; this.categories = categories;
        this.members = members; this.clock = clock; this.identifiers = identifiers; this.calendar = calendar;
        this.materializer = materializer; this.chargeConfirmation = chargeConfirmation; this.adjuster = adjuster;
    }

    public RecurrenceService(RecurrenceRepository repository, AuthenticatedUserContextQuery context,
            CategoryRepository categories, FinancialMemberAccess members, Clock clock,
            java.util.function.Supplier<UUID> identifiers, RecurrenceCalendar calendar,
            RecurringExpenseMaterializer materializer, ChargeConfirmationUseCase chargeConfirmation) {
        this(repository, context, categories, members, clock, identifiers, calendar, materializer, chargeConfirmation, null);
    }

    public RecurrenceService(RecurrenceRepository repository, AuthenticatedUserContextQuery context,
            CategoryRepository categories, FinancialMemberAccess members, Clock clock,
            java.util.function.Supplier<UUID> identifiers, RecurrenceCalendar calendar,
            RecurringExpenseMaterializer materializer) {
        this(repository, context, categories, members, clock, identifiers, calendar, materializer, null, null);
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
        return new RecurrenceCreationResult(view(initialSchedule(stored.recurrence()), currentMonth(actor), List.of()),
                stored.replayed());
    }

    @Override public List<RecurrenceView> list(String actorEmail) {
        var actor = context.findByEmail(actorEmail);
        var changes = repository.findChanges(actor.spaceId());
        var current = currentMonth(actor);
        return repository.findSchedules(actor.spaceId()).stream().map(stored -> view(stored, current,
                changes.getOrDefault(stored.recurrence().definition().id(), List.of()))).toList();
    }

    @Override public List<LocalDate> preview(LocalDate firstDueDate, LocalDate lastDueDate, RecurrenceFrequency frequency) {
        return calendar.firstDates(firstDueDate, lastDueDate, frequency, 12);
    }

    @Override public ForecastPeriodView forecasts(String actorEmail) {
        var actor=context.findByEmail(actorEmail);
        var from=currentMonth(actor);
        return forecasts(actor.spaceId(),from,from.plusMonths(HORIZON_MONTHS));
    }

    @Override public AnticipationResult anticipate(String actorEmail,UUID recurrenceId,LocalDate scheduledDueDate,
            UUID idempotencyKey) {
        if(recurrenceId==null||scheduledDueDate==null||idempotencyKey==null)
            throw new RecurrenceOccurrenceException("Informe a recorrência, o vencimento previsto e a chave de repetição.");
        var actor=context.findByEmail(actorEmail);
        members.requireActiveParticipants(actor.spaceId(),actor.userId(),null);
        var target=scheduledOccurrence(actor,recurrenceId,scheduledDueDate,ScheduleLock.SHARE);
        var claim=repository.claimAnticipation(actor.spaceId(),actor.userId(),recurrenceId,scheduledDueDate,
                idempotencyKey,fingerprint(recurrenceId,scheduledDueDate),clock.instant());
        if(!claim.replayed()) {
            var expenseId=materialize(actor.spaceId(),actor.userId(),target,scheduledDueDate);
            repository.completeAnticipation(actor.spaceId(),actor.userId(),idempotencyKey,expenseId,clock.instant());
        }
        return new AnticipationResult(reconciled(actor.spaceId(),recurrenceId,scheduledDueDate),claim.replayed());
    }

    /**
     * H04.4: confirming a forecast reuses the anticipation materialization (same occurrence identity) and then the
     * expense confirmation contract, atomically. The forecast was seen unmaterialized, so version 0 is expected.
     * The definition is locked for update first, like a change, so both serialize without upgrading a share lock.
     */
    @Override public AnticipationResult confirmForecastCharge(String actorEmail,UUID recurrenceId,
            LocalDate scheduledDueDate,String confirmedAmount,UUID idempotencyKey) {
        if(recurrenceId==null||scheduledDueDate==null||idempotencyKey==null)
            throw new RecurrenceOccurrenceException("Informe a recorrência, o vencimento previsto e a chave de repetição.");
        Objects.requireNonNull(chargeConfirmation,"chargeConfirmation");
        var actor=context.findByEmail(actorEmail);
        members.requireActiveParticipants(actor.spaceId(),actor.userId(),null);
        var target=scheduledOccurrence(actor,recurrenceId,scheduledDueDate,ScheduleLock.UPDATE);
        if(!variable(target.stored()))
            throw new RecurrenceOccurrenceException("Somente recorrências de valor estimado têm valor a confirmar.");
        var expenseId=materialize(actor.spaceId(),actor.userId(),target,scheduledDueDate);
        var result=chargeConfirmation.confirmCharge(actorEmail,
                new ConfirmChargeCommand(expenseId,0,confirmedAmount,idempotencyKey));
        return new AnticipationResult(reconciled(actor.spaceId(),recurrenceId,scheduledDueDate),result.replayed());
    }

    @Override public RecurrenceImpactView previewChange(String actorEmail, ChangeRecurrenceCommand command) {
        var actor = context.findByEmail(actorEmail);
        members.requireActiveParticipants(actor.spaceId(), actor.userId(), null);
        var stored = repository.loadSchedule(actor.spaceId(), requireRecurrence(command == null ? null : command.recurrenceId()),
                ScheduleLock.NONE);
        return planChange(actor, stored, command, false).impact();
    }

    /**
     * RF-REC-14/15, D19: applies "este e os próximos" atomically. The impact is recomputed under locks; if it no
     * longer matches the token the user confirmed, nothing is applied.
     */
    @Override public RecurrenceChangeResult change(String actorEmail, ChangeRecurrenceCommand command) {
        requireApply(command == null ? null : command.idempotencyKey(), command == null ? null : command.impactToken());
        var actor = context.findByEmail(actorEmail);
        members.requireActiveParticipants(actor.spaceId(), actor.userId(), null);
        var stored = repository.loadSchedule(actor.spaceId(), requireRecurrence(command.recurrenceId()), ScheduleLock.UPDATE);
        var claim = repository.claimChange(actor.spaceId(), actor.userId(), command.idempotencyKey(),
                fingerprint(command), command.recurrenceId(), clock.instant());
        if (claim.replayed()) return replay(actor, command.recurrenceId(), claim.changeId());
        requireVersion(stored, command.version());
        return apply(actor, planChange(actor, stored, command, true), command.impactToken(), command.idempotencyKey(), null);
    }

    @Override public RecurrenceImpactView previewClosure(String actorEmail, CloseRecurrenceCommand command) {
        var actor = context.findByEmail(actorEmail);
        members.requireActiveParticipants(actor.spaceId(), actor.userId(), null);
        var stored = repository.loadSchedule(actor.spaceId(), requireRecurrence(command == null ? null : command.recurrenceId()),
                ScheduleLock.NONE);
        return planClosure(actor, stored, command, false).impact();
    }

    /** RF-REC-16/17: stops new occurrences after the last period; the history and earlier launches are kept. */
    @Override public RecurrenceChangeResult close(String actorEmail, CloseRecurrenceCommand command) {
        requireApply(command == null ? null : command.idempotencyKey(), command == null ? null : command.impactToken());
        var reason = requireReason(command.reason());
        var actor = context.findByEmail(actorEmail);
        members.requireActiveParticipants(actor.spaceId(), actor.userId(), null);
        var stored = repository.loadSchedule(actor.spaceId(), requireRecurrence(command.recurrenceId()), ScheduleLock.UPDATE);
        var claim = repository.claimChange(actor.spaceId(), actor.userId(), command.idempotencyKey(),
                fingerprint(command, reason), command.recurrenceId(), clock.instant());
        if (claim.replayed()) return replay(actor, command.recurrenceId(), claim.changeId());
        requireVersion(stored, command.version());
        return apply(actor, planClosure(actor, stored, command, true), command.impactToken(), command.idempotencyKey(), reason);
    }

    private Plan planChange(AuthenticatedUserContext actor, StoredSchedule stored, ChangeRecurrenceCommand command,
            boolean lock) {
        if (command.effectiveDueDate() == null)
            throw new RecurrenceValidationException("effectiveDueDate", "Informe o vencimento a partir do qual a alteração vale.");
        var current = currentMonth(actor);
        var from = YearMonth.from(command.effectiveDueDate());
        if (from.isBefore(current) || from.isAfter(current.plusMonths(HORIZON_MONTHS)))
            throw new RecurrenceValidationException("effectiveDueDate",
                    "A alteração deve começar no mês atual ou em um dos próximos 12 meses.");
        var before = stored.schedule();
        var start = before.occurrenceIn(from).orElseThrow(() -> new RecurrenceValidationException("effectiveDueDate",
                "Escolha um vencimento que exista na programação atual da recorrência."));
        var requested = new RecurrenceConfiguration(command.description(), parseAmount(command.amount()),
                command.frequency(), command.dueDay() == null ? 0 : command.dueDay(), command.categoryId(),
                command.responsibleUserId());
        var edited = requested.differencesFrom(start.segment().configuration());
        if (edited.contains(RecurrenceChangeField.CATEGORY) && requested.categoryId() != null)
            categories.requireSelectable(actor.spaceId(), requested.categoryId());
        if (edited.contains(RecurrenceChangeField.RESPONSIBLE) && requested.responsibleUserId() != null)
            members.requireActiveParticipants(actor.spaceId(), actor.userId(), requested.responsibleUserId());
        var after = before.withChange(from, requested);
        var states = states(actor.spaceId(), stored, lock ? from : null);
        var effects = RecurrenceChangePlanner.planChange(after, from, edited, variable(stored), states);
        var forecasts = forecastImpact(before, after, variable(stored), states, from.isBefore(current) ? current : from,
                current.plusMonths(HORIZON_MONTHS));
        var fields = edited.stream().sorted().map(RecurrenceChangeField::apiName).toList();
        return new Plan("CHANGE", stored, before, after, start.dueDate(), fields, effects, forecasts,
                describe(requested) + "|from=" + from);
    }

    private Plan planClosure(AuthenticatedUserContext actor, StoredSchedule stored, CloseRecurrenceCommand command,
            boolean lock) {
        if (command.lastDueDate() == null)
            throw new RecurrenceValidationException("lastDueDate", "Informe o último vencimento da recorrência.");
        var before = stored.schedule();
        var after = before.endingAt(command.lastDueDate());
        var current = currentMonth(actor);
        var firstAfterEnd = after.endMonth().plusMonths(1);
        var states = states(actor.spaceId(), stored, lock ? firstAfterEnd : null);
        var effects = RecurrenceChangePlanner.planClosure(after.endMonth(), states);
        var forecasts = forecastImpact(before, after, variable(stored), states,
                firstAfterEnd.isBefore(current) ? current : firstAfterEnd, current.plusMonths(HORIZON_MONTHS));
        return new Plan("CLOSURE", stored, before, after, after.lastDueDate(), List.of("lastDueDate"), effects,
                forecasts, "end=" + after.endMonth());
    }

    private RecurrenceChangeResult apply(AuthenticatedUserContext actor, Plan plan, String token, UUID key, String reason) {
        if (!plan.token().equals(token)) throw new RecurrenceImpactChangedException();
        Objects.requireNonNull(adjuster, "adjuster");
        var d = plan.stored().recurrence().definition();
        var changeId = identifiers.get();
        var now = clock.instant();
        var current = currentMonth(actor);
        var reviews = new HashMap<UUID, String>();
        var adjustments = new ArrayList<RecurringOccurrenceAdjustment>();
        for (var effect : plan.effects()) {
            var o = effect.occurrence();
            switch (effect.action()) {
                case REVIEW -> reviews.put(o.expenseId(), effect.reason().name());
                case REMOVE -> adjustments.add(new RecurringOccurrenceAdjustment(o.expenseId(), o.version(), true,
                        removalReason(plan, reason), o.description(), o.amount(), o.dueDate(), o.categoryId(),
                        o.responsibleUserId(), List.of()));
                case UPDATE -> adjustments.add(new RecurringOccurrenceAdjustment(o.expenseId(), o.version(), false, null,
                        effect.description(), effect.amount(), effect.dueDate(), effect.categoryId(),
                        effect.responsibleUserId(), effect.changedFields().stream().map(RecurrenceService::expenseField).toList()));
                case PRESERVE -> { }
            }
        }
        var impact = plan.impact();
        repository.saveChange(new RecurrenceChangeRecord(changeId, d.id(), d.spaceId(), actor.userId(), plan.operation(),
                now, d.version(), plan.effectiveDueDate(), plan.fields(), describe(plan.before()), describe(plan.after()),
                reason, token, impact.updatedCount(), impact.removedCount(), impact.reviewCount(), impact.preservedCount(),
                plan.after().segments(), plan.after().segmentFor(current).configuration(), plan.after().lastDueDate(),
                reviews));
        adjuster.apply(d.spaceId(), actor.userId(), changeId, now, adjustments);
        repository.completeChange(d.spaceId(), actor.userId(), key, changeId, now);
        return result(actor, d.id(), changeId, false);
    }

    private RecurrenceChangeResult replay(AuthenticatedUserContext actor, UUID recurrenceId, UUID changeId) {
        return result(actor, recurrenceId, changeId, true);
    }

    private RecurrenceChangeResult result(AuthenticatedUserContext actor, UUID recurrenceId, UUID changeId, boolean replayed) {
        var stored = repository.loadSchedule(actor.spaceId(), recurrenceId, ScheduleLock.NONE);
        var changes = repository.findChanges(actor.spaceId()).getOrDefault(recurrenceId, List.of());
        return new RecurrenceChangeResult(view(stored, currentMonth(actor), changes),
                repository.findChange(actor.spaceId(), changeId), replayed);
    }

    private static String removalReason(Plan plan, String reason) {
        var text = "CLOSURE".equals(plan.operation()) ? "Recorrência encerrada: " + reason
                : "Fora da nova programação da recorrência.";
        return text.length() <= REASON_LIMIT ? text : text.substring(0, REASON_LIMIT);
    }

    private static String expenseField(RecurrenceChangeField field) {
        return switch (field) {
            case DESCRIPTION -> "description";
            case AMOUNT -> "amount";
            case DUE_DAY, FREQUENCY -> "dueDate";
            case CATEGORY -> "categoryId";
            case RESPONSIBLE -> "responsibleUserId";
        };
    }

    /** A fixed launch is stored as confirmed; only a variable one carries a charge confirmed by a member. */
    private List<OccurrenceState> states(UUID spaceId, StoredSchedule stored, YearMonth lockFrom) {
        Objects.requireNonNull(adjuster, "adjuster");
        var variable = variable(stored);
        return adjuster.occurrences(spaceId, stored.recurrence().definition().id(), lockFrom).stream()
                .map(o -> new OccurrenceState(o.expenseId(), o.scheduledDueDate(), o.version(), o.status(),
                        variable && o.chargeConfirmed(), o.amount(), o.dueDate(), o.description(), o.categoryId(),
                        o.responsibleUserId(), o.dueDateCorrected())).toList();
    }

    /** Forecast periods (not materialized) whose projected date, value or description changes. */
    private static List<ImpactForecastView> forecastImpact(RecurrenceSchedule before, RecurrenceSchedule after,
            boolean variable, Collection<OccurrenceState> states, YearMonth from, YearMonth to) {
        var materialized = states.stream().map(OccurrenceState::month).collect(Collectors.toSet());
        var confirmed = confirmed(states);
        var result = new ArrayList<ImpactForecastView>();
        for (var month = from; !month.isAfter(to); month = month.plusMonths(1)) {
            if (materialized.contains(month)) continue;
            var previous = projection(before, variable, confirmed, month);
            var next = projection(after, variable, confirmed, month);
            if (previous.isEmpty() && next.isEmpty() || previous.equals(next)) continue;
            var action = previous.isEmpty() ? "ADDED" : next.isEmpty() ? "REMOVED" : "CHANGED";
            result.add(new ImpactForecastView(month.atDay(1), action,
                    previous.map(Projection::dueDate).orElse(null), next.map(Projection::dueDate).orElse(null),
                    previous.map(Projection::amount).orElse(null), next.map(Projection::amount).orElse(null),
                    previous.map(Projection::description).orElse(null), next.map(Projection::description).orElse(null)));
        }
        return List.copyOf(result);
    }

    private static Optional<Projection> projection(RecurrenceSchedule schedule, boolean variable,
            List<VariableEstimateReference.ConfirmedCharge> confirmed, YearMonth month) {
        return schedule.occurrenceIn(month).map(occurrence -> {
            var configuration = occurrence.segment().configuration();
            var amount = variable ? VariableEstimateReference.estimateFor(bases(schedule), confirmed, occurrence.dueDate())
                    : configuration.amount();
            return new Projection(occurrence.dueDate(), amount.setScale(2).toPlainString(), configuration.description());
        });
    }

    private static List<VariableEstimateReference.EstimateBase> bases(RecurrenceSchedule schedule) {
        return schedule.segments().stream().filter(RecurrenceSegment::estimateReset)
                .map(s -> new VariableEstimateReference.EstimateBase(s.effectiveMonth(), s.configuration().amount())).toList();
    }

    private static List<VariableEstimateReference.ConfirmedCharge> confirmed(Collection<OccurrenceState> states) {
        return states.stream().filter(OccurrenceState::chargeConfirmed)
                .map(o -> new VariableEstimateReference.ConfirmedCharge(o.scheduledDueDate(), o.amount())).toList();
    }

    private Target scheduledOccurrence(AuthenticatedUserContext actor,UUID recurrenceId,LocalDate scheduledDueDate,
            ScheduleLock lock) {
        var current=currentMonth(actor);
        var month=YearMonth.from(scheduledDueDate);
        if(month.isBefore(current)||month.isAfter(current.plusMonths(HORIZON_MONTHS)))
            throw new RecurrenceOccurrenceException("A ocorrência deve estar no mês atual ou nos próximos 12 meses.");
        final StoredSchedule stored;
        try { stored=repository.loadSchedule(actor.spaceId(),recurrenceId,lock); }
        catch(RecurrenceNotFoundException error) {
            throw new RecurrenceOccurrenceException("A ocorrência prevista não está disponível neste espaço.");
        }
        var expected=stored.schedule().occurrenceIn(month);
        if(expected.isEmpty()||!expected.get().dueDate().equals(scheduledDueDate))
            throw new RecurrenceOccurrenceException("A data não corresponde ao calendário atual da recorrência.");
        return new Target(stored,expected.get());
    }

    private UUID materialize(UUID spaceId,UUID actorId,Target target,LocalDate scheduledDueDate) {
        var d=target.stored().recurrence().definition();
        var configuration=target.occurrence().segment().configuration();
        return materializer.materializeAnticipated(new AnticipatedRecurringExpenseCommand(identifiers.get(),
                d.id(),spaceId,configuration.description(),configuration.amount(),!variable(target.stored()),
                scheduledDueDate,configuration.categoryId(),configuration.responsibleUserId(),actorId,clock.instant()));
    }

    private ForecastView reconciled(UUID spaceId,UUID recurrenceId,LocalDate scheduledDueDate) {
        var month=YearMonth.from(scheduledDueDate);
        return forecasts(spaceId,month,month).occurrences().stream()
                .filter(item->recurrenceId.equals(item.recurrenceId())&&month.equals(YearMonth.from(item.scheduledDueDate())))
                .findFirst().orElseThrow(()->new RecurrenceOccurrenceException("Não foi possível reconciliar o lançamento antecipado."));
    }

    /**
     * Forecasts follow the configuration in force for each period. A materialized occurrence is matched by period and
     * always shown, even outside the current schedule, unless it was cancelled and left the program.
     */
    private ForecastPeriodView forecasts(UUID spaceId,YearMonth from,YearMonth to) {
        var result=new ArrayList<ForecastView>(reconcile(spaceId,from,to).stream().map(Reconciled::view).toList());
        result.sort(Comparator.comparing(ForecastView::scheduledDueDate).thenComparing(ForecastView::recurrenceId));
        return new ForecastPeriodView(from,to,List.copyOf(result));
    }

    /**
     * H06.3: the forecasts of the period that have no materialized occurrence (same reconciliation by recurrence and
     * period as {@link #forecasts(String)}), with the category and responsible the generation would use: an archived
     * category or a member who left becomes none, as in the materialization. Nothing is written.
     */
    public List<PlannedForecast> plannedForecasts(UUID spaceId,YearMonth from,YearMonth to) {
        var eligibility=repository.generationEligibility(spaceId);
        var result=new ArrayList<PlannedForecast>();
        for(var item:reconcile(spaceId,from,to)) {
            if(!"FORECAST".equals(item.view().state())) continue;
            var segment=item.stored().schedule().segmentFor(item.month());
            var configuration=segment.configuration();
            var names=item.stored().segmentViews().stream()
                    .filter(v->YearMonth.from(v.effectiveMonth()).equals(segment.effectiveMonth())).findFirst();
            var category=configuration.categoryId()!=null&&eligibility.activeCategoryIds().contains(configuration.categoryId())
                    ?configuration.categoryId():null;
            var responsible=configuration.responsibleUserId()!=null
                    &&eligibility.activeMemberIds().contains(configuration.responsibleUserId())
                    ?configuration.responsibleUserId():null;
            result.add(new PlannedForecast(item.view().recurrenceId(),item.view().scheduledDueDate(),
                    item.view().description(),new BigDecimal(item.view().amount()),item.view().estimated(),category,
                    category==null?null:names.map(RecurrenceSegmentView::categoryName).orElse(null),responsible,
                    responsible==null?null:names.map(RecurrenceSegmentView::responsibleDisplayName).orElse(null)));
        }
        result.sort(Comparator.comparing(PlannedForecast::dueDate)
                .thenComparing(f->f.recurrenceId().toString()));
        return List.copyOf(result);
    }

    private List<Reconciled> reconcile(UUID spaceId,YearMonth from,YearMonth to) {
        Map<PeriodKey,StoredOccurrence> actual=repository.findOccurrences(spaceId,from.atDay(1),to.atEndOfMonth())
                .stream().collect(Collectors.toMap(o->new PeriodKey(o.recurrenceId(),YearMonth.from(o.scheduledDueDate())),
                        Function.identity(),(a,b)->a));
        Map<UUID,List<VariableEstimateReference.ConfirmedCharge>> confirmed=repository.findConfirmedCharges(spaceId)
                .stream().collect(Collectors.groupingBy(StoredOccurrence::recurrenceId,Collectors.mapping(
                        o->new VariableEstimateReference.ConfirmedCharge(o.scheduledDueDate(),o.amount()),
                        Collectors.toList())));
        var result=new ArrayList<Reconciled>();
        for(var stored:repository.findSchedules(spaceId)) {
            var id=stored.recurrence().definition().id();
            var schedule=stored.schedule();
            var variable=variable(stored);
            for(var month=from;!month.isAfter(to);month=month.plusMonths(1)) {
                var scheduled=schedule.occurrenceIn(month);
                var materialized=actual.get(new PeriodKey(id,month));
                if(materialized!=null&&(scheduled.isPresent()||!"CANCELLED".equals(materialized.status()))) {
                    var description=schedule.segmentFor(month).configuration().description();
                    result.add(new Reconciled(stored,month,new ForecastView(id,description,
                            materialized.amount().setScale(2).toPlainString(),
                            !materialized.chargeConfirmed()&&variable,materialized.scheduledDueDate(),"MATERIALIZED",
                            materialized.expenseId(),materialized.actualDueDate(),materialized.status(),
                            materialized.chargeConfirmed(),materialized.reviewReason())));
                } else if(scheduled.isPresent()) {
                    var projected=projection(schedule,variable,confirmed.getOrDefault(id,List.of()),month).orElseThrow();
                    result.add(new Reconciled(stored,month,new ForecastView(id,projected.description(),
                            projected.amount(),variable,projected.dueDate(),"FORECAST",null,null,null,false)));
                }
            }
        }
        return result;
    }

    private RecurrenceView view(StoredSchedule stored, YearMonth current, List<RecurrenceChangeView> changes) {
        var d = stored.recurrence().definition();
        var schedule = stored.schedule();
        var segment = schedule.segmentFor(current);
        var configuration = segment.configuration();
        var names = stored.segmentViews().stream()
                .filter(v -> YearMonth.from(v.effectiveMonth()).equals(segment.effectiveMonth())).findFirst();
        var first = schedule.occurrenceIn(schedule.firstMonth()).map(RecurrenceSchedule.ScheduledOccurrence::dueDate)
                .orElse(d.firstDueDate());
        return new RecurrenceView(d.id(), configuration.description(), configuration.amount().setScale(2).toPlainString(),
                d.valueType(), configuration.frequency(), first, schedule.lastDueDate(), configuration.baseDay(),
                configuration.categoryId(), names.map(RecurrenceSegmentView::categoryName).orElse(null),
                configuration.responsibleUserId(), names.map(RecurrenceSegmentView::responsibleDisplayName).orElse(null),
                d.createdByUserId(), stored.recurrence().createdByDisplayName(), d.createdAt(), d.version(),
                schedule.firstDates(12), schedule.dates(current, current.plusMonths(HORIZON_MONTHS), HORIZON_MONTHS + 1),
                stored.closedAt(), stored.closedByDisplayName(), stored.closureReason(), stored.segmentViews(), changes);
    }

    /** A just-created definition has a single segment equal to its initial configuration. */
    private static StoredSchedule initialSchedule(StoredRecurrence stored) {
        var d = stored.definition();
        var month = YearMonth.from(d.firstDueDate());
        var configuration = new RecurrenceConfiguration(d.description(), d.amount(), d.frequency(),
                d.firstDueDate().getDayOfMonth(), d.categoryId(), d.responsibleUserId());
        return new StoredSchedule(stored, List.of(new RecurrenceSegment(month, configuration, true)),
                List.of(new RecurrenceSegmentView(month.atDay(1), d.description(), d.amount().setScale(2).toPlainString(),
                        d.frequency(), d.firstDueDate().getDayOfMonth(), d.categoryId(), stored.categoryName(),
                        d.responsibleUserId(), stored.responsibleDisplayName())), null, null, null);
    }

    private YearMonth currentMonth(AuthenticatedUserContext actor) {
        return YearMonth.now(clock.withZone(ZoneId.of(actor.timeZone())));
    }

    private static boolean variable(StoredSchedule stored) {
        return stored.recurrence().definition().valueType() == RecurrenceValueType.VARIABLE_ESTIMATE;
    }

    private static UUID requireRecurrence(UUID recurrenceId) {
        if (recurrenceId == null) throw new RecurrenceValidationException("recurrenceId", "Informe a recorrência.");
        return recurrenceId;
    }

    private static void requireApply(UUID key, String token) {
        if (key == null) throw new RecurrenceValidationException("Idempotency-Key", "Informe uma chave de repetição válida.");
        if (token == null || token.isBlank())
            throw new RecurrenceValidationException("impactToken", "Revise o impacto antes de confirmar.");
    }

    private static void requireVersion(StoredSchedule stored, long version) {
        if (stored.recurrence().definition().version() != version) throw new RecurrenceVersionConflictException();
    }

    private static String requireReason(String raw) {
        var reason = raw == null ? "" : raw.trim();
        if (reason.isEmpty() || reason.length() > REASON_LIMIT)
            throw new RecurrenceValidationException("reason", "Informe o motivo do encerramento, com até 2.000 caracteres.");
        return reason;
    }

    private BigDecimal parseAmount(String raw) {
        try { return raw == null ? null : new BigDecimal(raw.trim()); }
        catch (NumberFormatException exception) { throw new RecurrenceValidationException("amount", "Informe um valor decimal válido."); }
    }

    private static String describe(RecurrenceSchedule schedule) {
        var text = new StringBuilder("first=").append(schedule.firstMonth()).append(";end=")
                .append(Objects.toString(schedule.endMonth(), ""));
        for (var segment : schedule.segments())
            text.append(";segment=").append(segment.effectiveMonth()).append('|').append(describe(segment.configuration()))
                    .append("|estimateReset=").append(segment.estimateReset());
        return text.toString();
    }

    private static String describe(RecurrenceConfiguration c) {
        return String.join("|", c.description(), c.amount().setScale(2).toPlainString(), c.frequency().name(),
                String.valueOf(c.baseDay()), Objects.toString(c.categoryId(), ""), Objects.toString(c.responsibleUserId(), ""));
    }

    private String fingerprint(RecurrenceDefinition d) {
        var value = String.join("|", d.description(), d.amount().toPlainString(), d.valueType().name(), d.frequency().name(),
                d.firstDueDate().toString(), Objects.toString(d.lastDueDate(), ""), Objects.toString(d.categoryId(), ""),
                Objects.toString(d.responsibleUserId(), ""));
        return sha256(value);
    }
    private String fingerprint(UUID recurrenceId,LocalDate dueDate) { return sha256(recurrenceId+"|"+dueDate); }
    private static String fingerprint(ChangeRecurrenceCommand c) {
        return sha256(String.join("|", "CHANGE", c.recurrenceId().toString(), String.valueOf(c.version()),
                String.valueOf(c.effectiveDueDate()), String.valueOf(c.description()), String.valueOf(c.amount()),
                String.valueOf(c.frequency()), String.valueOf(c.dueDay()), String.valueOf(c.categoryId()),
                String.valueOf(c.responsibleUserId()), c.impactToken()));
    }
    private static String fingerprint(CloseRecurrenceCommand c, String reason) {
        return sha256(String.join("|", "CLOSURE", c.recurrenceId().toString(), String.valueOf(c.version()),
                String.valueOf(c.lastDueDate()), reason, c.impactToken()));
    }

    static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private record PeriodKey(UUID recurrenceId, YearMonth month) { }
    private record Reconciled(StoredSchedule stored, YearMonth month, ForecastView view) { }
    private record Target(StoredSchedule stored, RecurrenceSchedule.ScheduledOccurrence occurrence) { }
    private record Projection(LocalDate dueDate, String amount, String description) { }

    /** A computed change or closure; its token identifies the exact impact shown to the user. */
    private record Plan(String operation, StoredSchedule stored, RecurrenceSchedule before, RecurrenceSchedule after,
            LocalDate effectiveDueDate, List<String> fields, List<Effect> effects, List<ImpactForecastView> forecasts,
            String request) {
        String token() {
            var d = stored.recurrence().definition();
            var text = new StringBuilder(operation).append('|').append(d.id()).append('|').append(d.version())
                    .append('|').append(effectiveDueDate).append('|').append(String.join(",", fields)).append('|')
                    .append(request).append('|').append(describe(after));
            for (var e : effects) {
                var o = e.occurrence();
                text.append("\no=").append(o.expenseId()).append('|').append(o.version()).append('|').append(e.action())
                        .append('|').append(e.reason()).append('|').append(e.description()).append('|')
                        .append(e.amount() == null ? "" : e.amount().setScale(2).toPlainString()).append('|')
                        .append(e.dueDate()).append('|').append(e.categoryId()).append('|').append(e.responsibleUserId());
            }
            for (var f : forecasts) text.append("\nf=").append(f);
            return sha256(text.toString());
        }

        RecurrenceImpactView impact() {
            var d = stored.recurrence().definition();
            var occurrences = effects.stream().map(Plan::occurrenceView).toList();
            return new RecurrenceImpactView(d.id(), operation, d.version(), effectiveDueDate, fields, token(),
                    occurrences, forecasts, count(Action.UPDATE), count(Action.REMOVE), count(Action.REVIEW),
                    count(Action.PRESERVE));
        }

        private int count(Action action) { return (int) effects.stream().filter(e -> e.action() == action).count(); }

        private static ImpactOccurrenceView occurrenceView(Effect e) {
            var o = e.occurrence();
            var changes = new ArrayList<ImpactFieldChange>();
            for (var field : e.changedFields()) changes.add(switch (field) {
                case DESCRIPTION -> new ImpactFieldChange("description", o.description(), e.description());
                case AMOUNT -> new ImpactFieldChange("amount", money(o.amount()), money(e.amount()));
                case DUE_DAY, FREQUENCY -> new ImpactFieldChange("dueDate", String.valueOf(o.dueDate()), String.valueOf(e.dueDate()));
                case CATEGORY -> new ImpactFieldChange("categoryId", Objects.toString(o.categoryId(), null),
                        Objects.toString(e.categoryId(), null));
                case RESPONSIBLE -> new ImpactFieldChange("responsibleUserId", Objects.toString(o.responsibleUserId(), null),
                        Objects.toString(e.responsibleUserId(), null));
            });
            return new ImpactOccurrenceView(o.expenseId(), o.scheduledDueDate(), o.dueDate(), o.description(),
                    money(o.amount()), o.status(), o.chargeConfirmed(), e.action().name(), e.reason().name(),
                    changes, e.preservedFields().stream().map(f -> f == RecurrenceChangeField.DUE_DAY ? "dueDate"
                            : f.apiName()).toList());
        }

        private static String money(BigDecimal value) { return value == null ? null : value.setScale(2).toPlainString(); }
    }
}
