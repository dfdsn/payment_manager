package com.malyah.accountmanager.expenses.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import com.malyah.accountmanager.expenses.application.CategoryConflictException;
import com.malyah.accountmanager.expenses.application.CategoryNotFoundException;
import com.malyah.accountmanager.expenses.application.CategoryView;
import com.malyah.accountmanager.expenses.application.port.CategoryRepository;
import com.malyah.accountmanager.expenses.domain.CategoryName;
import org.springframework.transaction.annotation.Transactional;

public class JdbcCategoryRepository implements CategoryRepository {
    private final JdbcTemplate jdbc;
    public JdbcCategoryRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public List<CategoryView> findAll(UUID spaceId, boolean includeArchived) {
        return jdbc.query("select id,name,archived_at,version,updated_at from expense_categories where space_id=?" +
                (includeArchived ? "" : " and archived_at is null") + " order by archived_at nulls first, lower(name), id",
                (rs, row) -> map(rs), spaceId);
    }
    @Override @Transactional public CategoryView create(UUID id, UUID spaceId, UUID actorId, CategoryName name, Instant now) {
        try {
            jdbc.update("insert into expense_categories(id,space_id,name,normalized_name,created_by_user_id,created_at,updated_by_user_id,updated_at) values (?,?,?,?,?,?,?,?)",
                    id, spaceId, name.value(), name.normalized(), actorId, Timestamp.from(now), actorId, Timestamp.from(now));
            event(id, spaceId, actorId, "CREATED", 0, null, name.value(), now); return find(spaceId, id);
        } catch (DuplicateKeyException exception) { throw new CategoryConflictException("Já existe uma categoria com esse nome."); }
    }
    @Override @Transactional public CategoryView rename(UUID spaceId, UUID id, UUID actorId, long version, CategoryName name, Instant now) {
        var current = lock(spaceId, id);
        if (current.archived()) throw new CategoryConflictException("Categoria arquivada não pode ser renomeada.");
        if (current.version() != version) throw new CategoryConflictException("A categoria foi alterada por outra pessoa.");
        try { jdbc.update("update expense_categories set name=?,normalized_name=?,updated_by_user_id=?,updated_at=?,version=version+1 where id=? and space_id=?",
                name.value(), name.normalized(), actorId, Timestamp.from(now), id, spaceId); }
        catch (DuplicateKeyException exception) { throw new CategoryConflictException("Já existe uma categoria com esse nome."); }
        event(id, spaceId, actorId, "RENAMED", version + 1, current.name(), name.value(), now); return find(spaceId, id);
    }
    @Override @Transactional public CategoryView archive(UUID spaceId, UUID id, UUID actorId, long version, Instant now) {
        var current = lock(spaceId, id);
        if (current.archived()) throw new CategoryConflictException("A categoria já está arquivada.");
        if (current.version() != version) throw new CategoryConflictException("A categoria foi alterada por outra pessoa.");
        jdbc.update("update expense_categories set archived_at=?,updated_by_user_id=?,updated_at=?,version=version+1 where id=? and space_id=?",
                Timestamp.from(now), actorId, Timestamp.from(now), id, spaceId);
        event(id, spaceId, actorId, "ARCHIVED", version + 1, current.name(), current.name(), now); return find(spaceId, id);
    }
    @Override public void requireSelectable(UUID spaceId, UUID categoryId) {
        if (categoryId == null) return;
        var ids = jdbc.query("select id from expense_categories where space_id=? and id=? and archived_at is null for share",
                (rs,row)->rs.getObject(1,UUID.class), spaceId, categoryId);
        if (ids.size() != 1) throw new CategoryConflictException("A categoria não está disponível para novas associações.");
    }
    private CategoryView lock(UUID spaceId, UUID id) {
        var list = jdbc.query("select id,name,archived_at,version,updated_at from expense_categories where space_id=? and id=? for update", (rs,row)->map(rs), spaceId,id);
        if (list.isEmpty()) throw new CategoryNotFoundException(); return list.getFirst();
    }
    private CategoryView find(UUID spaceId, UUID id) { return findAll(spaceId,true).stream().filter(c->c.id().equals(id)).findFirst().orElseThrow(CategoryNotFoundException::new); }
    private CategoryView map(java.sql.ResultSet rs) throws java.sql.SQLException { return new CategoryView(rs.getObject(1,UUID.class),rs.getString(2),rs.getTimestamp(3)!=null,rs.getLong(4),rs.getTimestamp(5).toInstant()); }
    private void event(UUID id, UUID space, UUID actor, String type, long version, String before, String after, Instant now) {
        jdbc.update("insert into expense_category_events(category_id,space_id,event_type,actor_user_id,occurred_at,category_version,previous_name,current_name) values (?,?,?,?,?,?,?,?)", id,space,type,actor,Timestamp.from(now),version,before,after);
    }
}
