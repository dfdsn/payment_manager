package com.malyah.accountmanager.expenses.infrastructure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.malyah.accountmanager.expenses.application.AttachmentContent;
import com.malyah.accountmanager.expenses.application.AttachmentException;
import com.malyah.accountmanager.expenses.application.AttachmentUseCase;
import com.malyah.accountmanager.expenses.application.AttachmentView;
import com.malyah.accountmanager.identity.application.AuthenticatedUserContextQuery;

final class FileSystemAttachmentUseCase implements AttachmentUseCase {
    private static final int MAX_SIZE = 10 * 1024 * 1024;
    private final JdbcTemplate jdbc;
    private final AuthenticatedUserContextQuery contexts;
    private final TransactionTemplate transactions;
    private final Path permanent;
    private final Path staging;
    private final Clock clock;

    FileSystemAttachmentUseCase(JdbcTemplate jdbc, AuthenticatedUserContextQuery contexts,
            TransactionTemplate transactions, Path root, Clock clock) {
        this.jdbc = jdbc; this.contexts = contexts; this.transactions = transactions; this.clock = clock;
        this.permanent = root.toAbsolutePath().normalize().resolve("permanent");
        this.staging = root.toAbsolutePath().normalize().resolve("staging");
        try { Files.createDirectories(permanent); Files.createDirectories(staging); }
        catch (IOException e) { throw new IllegalStateException("Não foi possível preparar o volume privado.", e); }
        reconcile();
    }

    @Override public AttachmentView upload(String email, UUID expenseId, UUID key, String name, byte[] bytes) {
        var context = contexts.findByEmail(email);
        var safeName = validateName(name);
        var type = detect(bytes);
        var requestHash = hash(expenseId + "\n" + safeName + "\n" + hash(bytes));
        var existing = jdbc.query("select attachment_id, request_hash from expense_attachment_requests where space_id=? and actor_user_id=? and idempotency_key=?",
                (rs, n) -> new Object[]{rs.getObject(1, UUID.class), rs.getString(2)}, context.spaceId(), context.userId(), key);
        if (!existing.isEmpty()) {
            if (!requestHash.equals(existing.getFirst()[1])) throw new AttachmentException("ATTACHMENT_IDEMPOTENCY_CONFLICT", "A chave já foi usada com outro arquivo.");
            return findAvailable(context.spaceId(), expenseId, (UUID) existing.getFirst()[0]);
        }
        var id = UUID.randomUUID(); var storageKey = UUID.randomUUID();
        var temp = staging.resolve(storageKey.toString()); var target = permanent.resolve(storageKey.toString());
        try { Files.write(temp, bytes); }
        catch (IOException e) { throw new AttachmentException("ATTACHMENT_STORAGE_FAILED", "Não foi possível gravar o arquivo no volume privado."); }
        try {
            transactions.executeWithoutResult(status -> {
                lockExpense(context.spaceId(), expenseId);
                Integer count = jdbc.queryForObject("select count(*) from expense_attachments where expense_id=? and status in ('STAGED','AVAILABLE')", Integer.class, expenseId);
                if (count != null && count >= 5) throw new AttachmentException("ATTACHMENT_LIMIT", "A despesa já possui cinco anexos.");
                var now = clock.instant();
                jdbc.update("insert into expense_attachments(id,expense_id,space_id,storage_key,original_name,media_type,size_bytes,status,uploaded_by_user_id,uploaded_at) values(?,?,?,?,?,?,?,'STAGED',?,?)",
                        id, expenseId, context.spaceId(), storageKey, safeName, type, bytes.length, context.userId(), java.sql.Timestamp.from(now));
                jdbc.update("insert into expense_attachment_requests(space_id,actor_user_id,idempotency_key,request_hash,attachment_id,created_at) values(?,?,?,?,?,?)",
                        context.spaceId(), context.userId(), key, requestHash, id, java.sql.Timestamp.from(now));
            });
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            transactions.executeWithoutResult(status -> {
                jdbc.update("update expense_attachments set status='AVAILABLE' where id=? and status='STAGED'", id);
                jdbc.update("insert into expense_attachment_audit values(?,?,?,?,?,'ATTACHMENT_ADDED',?)", UUID.randomUUID(), id, expenseId, context.spaceId(), context.userId(), java.sql.Timestamp.from(clock.instant()));
            });
            return findAvailable(context.spaceId(), expenseId, id);
        } catch (RuntimeException | IOException e) {
            try { Files.deleteIfExists(temp); Files.deleteIfExists(target); } catch (IOException ignored) { }
            transactions.executeWithoutResult(s -> { jdbc.update("delete from expense_attachment_requests where attachment_id=?", id); jdbc.update("delete from expense_attachments where id=? and status='STAGED'", id); });
            if (e instanceof AttachmentException attachment) throw attachment;
            throw new AttachmentException("ATTACHMENT_STORAGE_FAILED", "O upload não foi concluído; tente novamente.");
        }
    }

    @Override public List<AttachmentView> list(String email, UUID expenseId) {
        var c=contexts.findByEmail(email); ensureExpense(c.spaceId(), expenseId);
        return jdbc.query(select()+" where a.space_id=? and a.expense_id=? and a.status='AVAILABLE' order by a.uploaded_at,a.id", this::map, c.spaceId(), expenseId);
    }
    @Override public AttachmentContent download(String email, UUID expenseId, UUID attachmentId) {
        var c=contexts.findByEmail(email); var view=findAvailable(c.spaceId(), expenseId, attachmentId);
        UUID key=jdbc.queryForObject("select storage_key from expense_attachments where id=?", UUID.class, attachmentId);
        try { return new AttachmentContent(view, Files.readAllBytes(safe(permanent, key.toString()))); }
        catch(IOException e){ throw new AttachmentException("ATTACHMENT_CONTENT_UNAVAILABLE", "O arquivo está temporariamente indisponível."); }
    }
    @Override public void remove(String email, UUID expenseId, UUID attachmentId) {
        var c=contexts.findByEmail(email); var view=findAvailable(c.spaceId(),expenseId,attachmentId);
        UUID key=jdbc.queryForObject("select storage_key from expense_attachments where id=?", UUID.class, attachmentId);
        transactions.executeWithoutResult(s->{
            int changed=jdbc.update("update expense_attachments set status='REMOVED',removed_by_user_id=?,removed_at=? where id=? and status='AVAILABLE'",c.userId(),java.sql.Timestamp.from(clock.instant()),attachmentId);
            if(changed==0) throw new AttachmentException("ATTACHMENT_NOT_FOUND","Anexo não encontrado.");
            jdbc.update("insert into expense_attachment_audit values(?,?,?,?,?,'ATTACHMENT_REMOVED',?)",UUID.randomUUID(),attachmentId,expenseId,c.spaceId(),c.userId(),java.sql.Timestamp.from(clock.instant()));
        });
        try { Files.deleteIfExists(safe(permanent,key.toString())); } catch(IOException ignored) { }
    }
    private void lockExpense(UUID space, UUID expense){ if(jdbc.query("select id from expense_entries where id=? and space_id=? for update",(r,n)->r.getObject(1,UUID.class),expense,space).isEmpty()) throw new AttachmentException("EXPENSE_NOT_FOUND","Despesa não encontrada."); }
    private void ensureExpense(UUID space,UUID expense){ lockExpense(space,expense); }
    private AttachmentView findAvailable(UUID space,UUID expense,UUID id){ var r=jdbc.query(select()+" where a.space_id=? and a.expense_id=? and a.id=? and a.status='AVAILABLE'",this::map,space,expense,id); if(r.isEmpty()) throw new AttachmentException("ATTACHMENT_NOT_FOUND","Anexo não encontrado."); return r.getFirst(); }
    private String select(){ return "select a.id,a.original_name,a.media_type,a.size_bytes,a.uploaded_by_user_id,u.display_name,a.uploaded_at from expense_attachments a join identity_users u on u.id=a.uploaded_by_user_id"; }
    private AttachmentView map(java.sql.ResultSet r,int n)throws java.sql.SQLException{return new AttachmentView(r.getObject(1,UUID.class),r.getString(2),r.getString(3),r.getLong(4),r.getObject(5,UUID.class),r.getString(6),r.getTimestamp(7).toInstant());}
    private static String validateName(String name){ if(name==null||name.isBlank()) throw new AttachmentException("ATTACHMENT_NAME_INVALID","Informe o nome do arquivo."); var clean=Path.of(name).getFileName().toString().replaceAll("[\\r\\n\\u0000]",""); if(clean.length()>255||!clean.equals(name)||clean.equals(".")||clean.equals("..")) throw new AttachmentException("ATTACHMENT_NAME_INVALID","Nome de arquivo inválido."); return clean; }
    private static String detect(byte[] b){ if(b==null||b.length==0)throw new AttachmentException("ATTACHMENT_EMPTY","O arquivo está vazio."); if(b.length>MAX_SIZE)throw new AttachmentException("ATTACHMENT_TOO_LARGE","O arquivo deve ter no máximo 10 MB."); if(b.length>=5&&b[0]=='%'&&b[1]=='P'&&b[2]=='D'&&b[3]=='F'&&b[4]=='-')return "application/pdf"; if(b.length>=3&&(b[0]&255)==255&&(b[1]&255)==216&&(b[2]&255)==255)return "image/jpeg"; byte[] p={(byte)137,80,78,71,13,10,26,10}; if(b.length>=8&&java.util.Arrays.equals(java.util.Arrays.copyOf(b,8),p))return "image/png"; throw new AttachmentException("ATTACHMENT_TYPE_INVALID","Somente PDF, JPG e PNG são aceitos, conforme o conteúdo real."); }
    private static String hash(Object value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value instanceof byte[] b?b:value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static Path safe(Path root,String key){var p=root.resolve(key).normalize();if(!p.startsWith(root))throw new AttachmentException("ATTACHMENT_PATH_INVALID","Identificador de armazenamento inválido.");return p;}
    private void reconcile(){ jdbc.update("delete from expense_attachment_requests r where r.attachment_id in (select id from expense_attachments where status='STAGED')"); var keys=jdbc.query("delete from expense_attachments where status='STAGED' returning storage_key",(r,n)->r.getObject(1,UUID.class)); for(var k:keys){try{Files.deleteIfExists(staging.resolve(k.toString()));Files.deleteIfExists(permanent.resolve(k.toString()));}catch(IOException ignored){}} try(var paths=Files.list(staging)){paths.forEach(p->{try{Files.deleteIfExists(p);}catch(IOException ignored){}});}catch(IOException ignored){} }
}
