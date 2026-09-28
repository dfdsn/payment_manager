package com.malyah.accountmanager.expenses.application;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import com.malyah.accountmanager.expenses.application.port.CategoryRepository;
import com.malyah.accountmanager.expenses.application.port.ExpenseIdentifierGenerator;
import com.malyah.accountmanager.expenses.domain.CategoryName;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextQuery;

public final class CategoryService {
    private final CategoryRepository repository;
    private final AuthenticatedUserContextQuery context;
    private final ExpenseIdentifierGenerator identifiers;
    private final Clock clock;
    public CategoryService(CategoryRepository repository, AuthenticatedUserContextQuery context,
            ExpenseIdentifierGenerator identifiers, Clock clock) {
        this.repository = repository; this.context = context; this.identifiers = identifiers; this.clock = clock;
    }
    public List<CategoryView> list(String email, boolean archived) {
        var actor = context.findByEmail(email); return repository.findAll(actor.spaceId(), archived);
    }
    public CategoryView create(String email, String name) {
        var actor = context.findByEmail(email);
        return repository.create(identifiers.next(), actor.spaceId(), actor.userId(), new CategoryName(name), clock.instant());
    }
    public CategoryView rename(String email, UUID id, long version, String name) {
        validate(id, version); var actor = context.findByEmail(email);
        return repository.rename(actor.spaceId(), id, actor.userId(), version, new CategoryName(name), clock.instant());
    }
    public CategoryView archive(String email, UUID id, long version) {
        validate(id, version); var actor = context.findByEmail(email);
        return repository.archive(actor.spaceId(), id, actor.userId(), version, clock.instant());
    }
    private void validate(UUID id, long version) {
        if (id == null || version < 0) throw new ExpenseQueryValidationException("category", "Informe categoria e versão válidas.");
    }
}
