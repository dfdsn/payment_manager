package com.malyah.accountmanager.expenses.application.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.malyah.accountmanager.expenses.application.CategoryView;
import com.malyah.accountmanager.expenses.domain.CategoryName;

public interface CategoryRepository {
    List<CategoryView> findAll(UUID spaceId, boolean includeArchived);
    CategoryView create(UUID id, UUID spaceId, UUID actorId, CategoryName name, Instant now);
    CategoryView rename(UUID spaceId, UUID id, UUID actorId, long version, CategoryName name, Instant now);
    CategoryView archive(UUID spaceId, UUID id, UUID actorId, long version, Instant now);
    void requireSelectable(UUID spaceId, UUID categoryId);
}
