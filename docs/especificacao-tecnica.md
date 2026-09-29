# Especificação técnica — account_Manager

**Versão:** 1.0 — 23/09/2026  
**Responsável pelo produto:** Diego  
**Base:** PRD v2.0 e backlog de 11 épicos/46 histórias.  
**Estado:** fundação PREP-01 a PREP-04 implementada e em validação; histórias funcionais, infraestrutura real e integrações reais ainda não implementadas/validadas.

## 1. Objetivo, autoridade e leitura

Orientar a implementação do gerenciador pessoal de despesas por Codex, com escolhas coerentes para até dois usuários, sem requisitos de operação comercial. Ler junto de `prd.md`, `epicos-desenvolvimento.md`, `decisoes-pendentes.md`, `progresso.md` e do `AGENTS.md` na raiz.

As decisões aprovadas na entrevista técnica complementam o PRD. Quando alteram algo explicitamente — por exemplo, backup baixado no Windows e regra de valor zero — o registro Dxx em `decisoes-pendentes.md` identifica a alteração e prevalece naquele ponto. Não interpretar esta especificação como autorização para mudar regras funcionais não discutidas. Os arquivos antigos não foram reescritos nesta entrega; o registro de decisões funciona como adendo rastreável.

Detalhes classificados como **baseline técnica proposta** são escolhas de implementação para validar na preparação, não testes já executados nem novas aprovações de produto. Dependências de conta/provedor têm bloqueios explícitos. Não há instalação nem publicação autorizada por este documento isoladamente.

## 2. Arquitetura e implantação

### 2.1 Arquitetura aprovada

- Monólito modular Java/Spring Boot: um backend, uma implantação e módulos por domínio.
- Angular/TypeScript/Angular Material como frontend separado, com PWA e layout responsivo.
- PostgreSQL em Docker, banco único; Flyway versiona todas as alterações de estrutura.
- API JSON no mesmo domínio do frontend: `https://contas.malyah.tech/api/v1`.
- VPS Hostinger KVM 2, uso pessoal. Confirmar CPU, RAM, disco, arquitetura e serviços existentes antes de definir limites; nome do plano não comprova os recursos livres.
- Backend e frontend como duas imagens privadas no Docker Hub; frontend servido por Nginx. Produção executa Compose com `image:`, sem `build:`.
- Sem Redis, broker, Kubernetes, microserviços ou serviços de identidade separados no MVP.

### 2.2 Módulos e responsabilidades

| Módulo sugerido | Propriedade |
|---|---|
| `identity` | Usuários, sessões, espaço, convites, papéis e ciclo de participação. |
| `expenses` | Lançamentos, quitações, cancelamentos, categorias e referências dos anexos. |
| `planning` | Recorrências, ocorrências, estimativas, parcelamentos e projeções. |
| `reporting` | Consultas agregadas, CSV e retratos de fechamento. |
| `notifications` | Programação, resumos in-app, consentimento e entregas WhatsApp/email. |
| `intake` | Imagens temporárias, pedidos de análise, cota, sugestões e revisão humana. |

Nomes em inglês são convenção técnica proposta; descrições e interface são em português. Infraestrutura compartilhada pequena para relógio, transações, arquivos e tarefas não deve se tornar um módulo genérico que concentra regras financeiras.

Cada módulo contém `domain`, `application`, `infrastructure` e `api`:

- **Domain:** regras, entidades e objetos de valor sem Spring, JPA, HTTP ou SDK externo.
- **Application:** casos de uso, contratos de entrada/saída e portas para persistência/serviços.
- **Infrastructure:** JPA, mapeamento de persistência, adapters HTTP/SMTP, execução de jobs.
- **API:** controllers, DTOs, validação de formato e tradução de erros.

Baseline: manter application sem dependência de implementações de infraestrutura. A fronteira transacional pode ser um decorator/configuração de infraestrutura envolvendo o caso de uso, usando abstração pequena onde necessário. Não injetar repositórios JPA no domínio nem usar entidade persistida como DTO.

### 2.3 Comunicação entre módulos

Chamadas Java síncronas por contratos públicos de aplicação. Sem HTTP interno. Planejamento e IA usam operações públicas de despesas; não replicam regras de cadastro/quitação. Relatórios podem combinar tabelas em leitura por adapters explicitamente isolados. Não há escrita direta nas tabelas/repositórios de outro módulo.

Transações locais podem coordenar módulos quando necessário. Chamadas externas ficam fora dessas transações. Para evitar ciclo `expenses ↔ notifications`, a alteração financeira registra um evento/tarefa durável no mesmo commit, através de contrato neutro; o consumidor de notificações processa depois. Alternativamente, elegibilidade pode ser recalculada pelas consultas públicas na execução do resumo. A solução escolhida precisa preservar o vínculo atômico e ser documentada antes de codificar.

## 3. Baseline de versões e compatibilidade

Consulta documental e preparação executadas em 23–24/09/2026. As versões abaixo foram fixadas em wrappers, lockfiles e imagens. Testcontainers/PostgreSQL foram comprovados no Docker Desktop; consulte `evidencias/PREP-01.md`. P00 permanece aberta até executar JDK 21 e CI real.

| Componente | Baseline técnica proposta | Regra de fixação |
|---|---|---|
| Java | 21 LTS | Mesmo major no build/teste/runtime; distribuição e patch registrados. |
| Spring Boot | 4.1.1, conforme documentação estável consultada | Confirmar disponibilidade no repositório e combinar dependências via BOM; não usar snapshot. |
| Maven | 3.9.16 por Maven Wrapper | Não depender do Maven global. |
| Spring Security, Data JPA, Session JDBC | Linha gerenciada pelo Boot | Não substituir versões transitivas sem justificar compatibilidade. |
| Flyway/PostgreSQL JDBC | Versões compatíveis gerenciadas pelo Boot, incluindo módulo PostgreSQL necessário | Migrações próprias também para tabelas de sessão/tarefas. |
| PostgreSQL | 17.6-alpine na preparação | Mesmo major nos ambientes e Testcontainers; patch/imagem por digest na liberação. |
| Angular, CLI, Material e CDK | Core/CLI 21.2.24; Material/CDK 21.2.14 | Lockfile exato; mesma família 21 sem misturar majors. |
| Node.js | 24.18.0 | Patch suportado pela versão Angular e fixado na imagem de build. |
| TypeScript / RxJS | 5.9.3 / 7.8.2 | Tabela Angular 21 aceita TypeScript >=5.9 e <6; lockfile exato. |
| Testes Java | JUnit Jupiter 6.0.3 na linha do BOM | Não forçado fora do BOM; plugin PIT/JUnit validado localmente. |
| Testes frontend | Vitest 4.1.11 e Playwright 1.63.0 | Fixados no lockfile; browser instalado/fixado pela CI. |
| JaCoCo / PIT / plugin JUnit / ArchUnit / Testcontainers | 0.8.15 / 1.20.5 / 1.2.3 / 1.4.2 / 2.0.5 | Todos executados; Testcontainers aprovado com PostgreSQL 17.6 no Docker Desktop. |
| Docker Engine / Compose | Engine suportado na VPS e plugin Compose v2 | Registrar versões verificadas; usar `docker compose`. |
| Nginx / ferramenta de certificado | Imagens estáveis com patch/digest fixados | Certificação HTTPS e renovação são P02, não implementação existente. |

**Prova mínima PREP-01:** iniciar Spring, rodar um teste de domínio, uma integração PostgreSQL/Flyway com Testcontainers, relatório/check JaCoCo, PIT que elimina uma mutação conhecida e teste ArchUnit; compilar Angular/Material e rodar componente + E2E básico. Registrar versões efetivas e resultados. Incompatibilidade é bloqueio técnico a resolver, não motivo para remover PIT ou reduzir gates. Não escolher versão sem suporte por conveniência.

A documentação consultada confirma a faixa Java/Maven do Boot e a matriz Angular/Node/TypeScript. Ela não estabelece sozinha a compatibilidade completa de todos os plugins. Fontes em §17.

## 4. Repositório e ambientes

Monorepo GitHub, com os seguintes caminhos a implementar:

| Caminho | Conteúdo |
|---|---|
| `backend/` | Maven Wrapper, código, testes, migrations, Dockerfile. |
| `frontend/` | Angular, Material, PWA, testes, lockfile e Dockerfile Nginx. |
| `deploy/` | Compose produção/local completo, Nginx, exemplos de configuração e scripts operacionais. |
| `docs/` | Documentos de produto e técnicos, evidências e guias. |
| `.github/workflows/` | Verificação de PR e publicação por tag. |
| `AGENTS.md` | Instruções de execução para Codex. |
| `README.md` | Entrada detalhada para execução, testes e deploy. |

### Ambientes

- **Local habitual:** PostgreSQL em Compose, backend IDE/Maven, Angular dev server. Email capturado localmente; IA e WhatsApp simulados e visivelmente identificados. Proxy Angular para `/api` mantém fluxo de cookies/CSRF semelhante ao domínio único.
- **Local completo/E2E:** todos os componentes em containers de desenvolvimento, banco separado e integrações simuladas. Volumes e nomes distintos de produção.
- **CI:** PostgreSQL descartável/Testcontainers, dados sintéticos e integrações controladas. Sem credenciais reais em jobs de PR.
- **Produção:** imagens publicadas, configurações/segredos fornecidos no servidor; startup rejeita combinação de produção com adapters simulados habilitados.

O mesmo artefato é promovido sem recompilar na VPS. Não testar migrações ou E2E no banco pessoal de produção.

## 5. Modelo de dados orientador

Nomes abaixo são orientação lógica; o modelo físico deve ser detalhado por história, com constraints e migrações. Não criar todas as tabelas antecipadamente sem fluxo correspondente.

| Agregado/registro | Dados e invariantes |
|---|---|
| Usuário e participação | Email normalizado único, senha com hash, confirmação, vínculo único ativo, papel. No máximo dois membros ativos por espaço. |
| Espaço/configurações | Fuso, BRL, horários, preferências administrativas e cota. |
| Lançamento | ID estável, espaço, origem, descrição, cobrança, situação, vencimento, referência mensal, estimativa/confirmado, categoria, responsável e versão. |
| Quitação/histórico | Valor pago, data, pagador, autor, reversão com motivo. No máximo uma quitação ativa por lançamento. |
| Recorrência | Configuração e mudanças com vigência por ocorrência, frequência, primeiro vencimento/dia-base, tipo de valor e término. |
| Identidade de ocorrência | Chave recorrência + posição/período lógico estável. Não usar vencimento editável como única identidade. |
| Parcelamento | Total, número de parcelas; unicidade compra + índice. Soma exatamente igual ao total. |
| Categoria | Espaço, nome, arquivamento, sem exclusão destrutiva do histórico. |
| Anexo | ID interno, localização opaca, hash/tamanho/tipo, vínculo e autorização; bytes fora do banco. |
| Fechamento | Mês, versão única, snapshot imutável incluindo rótulos/valores, autor e instante. Não reconstruir versão antiga por joins com valores atuais. |
| Sessão | Spring Session JDBC + metadados para limite absoluto/atividade quando necessário. |
| Token de acesso | Finalidade, hash do token, destinatário, expiração e consumo/revogação. |
| Idempotência | Escopo usuário/espaço/operação, chave, hash do payload, estado, resultado/recurso e prazo definido. |
| Tarefa/entrega | Tipo, chave lógica única, execução, prazo, tentativas, lease, resultado e identificador externo. |
| Análise IA | Autor, imagem temporária, dia da reserva, estado, modelo, resultado validável e uso, sem bytes de imagem no log. |

### Dinheiro e datas

- `BigDecimal` no Java e `NUMERIC(10,2)` para valores individuais até 99.999.999,99. Validar faixa >0 no servidor e constraints de dados aplicáveis.
- Totais agregados não podem ser truncados ao limite individual; projeções/fechamentos exigem precisão maior.
- API representa dinheiro como string decimal canônica; não usar `double`/`float` para cálculos oficiais.
- Dividir parcelas em centavos inteiros; base por divisão inteira e resto na última. Todas >=0,01; quantidade 2–360. Total mínimo deve permitir todas positivas.
- Vencimento/pagamento como `LocalDate`/`DATE`; auditoria como instante UTC/`TIMESTAMPTZ`; agendamento e dia de cota calculados pelo fuso do espaço.
- Injetar relógio (`Clock` por contrato) para testar atraso, expiração, janela e virada de mês sem sleeps.

### Mudanças aprovadas nas regras abertas

Valor zero não permitido em cobrança, estimativa ou pagamento. Isenção integral é cancelamento com motivo. Descrição até 200, observação até 2.000, categoria até 60 caracteres. Operações em lote são atômicas. Metadados (descrição/categoria/responsável) de recorrência podem alcançar pendentes confirmados; valores variáveis confirmados e vencimentos confirmados exigem correção individual. Pagos/cancelados preservados. Detalhes D18–D20 no registro.

## 6. Transações, concorrência e tarefas

### 6.1 Edição e idempotência

- Versão explícita transportada na edição e verificada na persistência; mapear conflito otimista para resposta consistente de conflito (409 como baseline).
- Alteração financeira, trilha de auditoria e registro durável que exige consistência imediata no mesmo commit.
- Chave idempotente por comando relevante. Mesma chave/payload retorna resultado original; payload diferente gera conflito. Escopo inclui identidade autorizada; nunca permite consultar resultado de outro usuário.
- Solicitações concorrentes da mesma chave não executam a mutação duas vezes. Estado em processamento tem resposta/retentativa definida e não abre transação duplicada.
- Constraints de ocorrência, parcela, quitação ativa e resumo protegem contra concorrência além do navegador. Janela de retenção de idempotência é P08; invariantes duráveis continuam após limpeza dessa tabela.

### 6.2 Lotes

Quitação e alteração/cancelamento coletivo: validar todos os registros e versões, aplicar todos com seus históricos numa transação ou nenhum. Mostrar prévia, protegidos e motivos. Conflito entre prévia e confirmação invalida lote; a prévia não é uma reserva permanente. Chamadas a provedores não participam dessa transação.

### 6.2.1 Fechamento mensal (E07)

- **Resumo consistente (H07.1).** A transação de fechamento é `READ COMMITTED`, começa bloqueando o espaço (`FinancialMemberAccess.requireActiveParticipants`, `select ... for update` em `family_spaces`), o mesmo bloqueio de toda escrita financeira do espaço; por isso nenhuma inclusão, correção, quitação, reversão ou cancelamento do espaço é confirmada entre a leitura e a gravação do retrato. Os lançamentos do mês são lidos numa única instrução SQL (`ExpenseReportQueries.entries`), e totais, categorias, pendências e `content_digest` são calculados dessas mesmas linhas em memória (`ClosingSummary`, regras `DueIndicators` da H06.1). Cabeçalho, versão, categorias, linhas, evento `MONTH_CLOSED` e chave de idempotência são gravados na mesma transação; falha em qualquer etapa desfaz tudo.
- **Unicidade.** `unique (space_id, month)` no cabeçalho e `unique (closing_id, version_number)` na versão; `insert ... on conflict do nothing` decide o vencedor de fechamentos simultâneos. Chave idempotente por (espaço, autor, operação, chave) com hash do pedido.
- **Imutabilidade.** Gatilho `reject_month_closing_snapshot_change` recusa `UPDATE`/`DELETE` em versões, categorias, linhas e eventos; a consulta lê só o retrato, nunca reconstrói com joins aos valores atuais. Leituras usam transação somente leitura `REPEATABLE READ`.
- **Detecção de alteração posterior (H07.2).** Derivada, não por eventos: cada consulta compara as linhas da versão vigente com as linhas atuais do mês lidas pela mesma porta (`ClosingComparison`), usando só os campos que formam a chave de conteúdo do `content_digest` (referência, situação, cobrança, estimativa, valor pago e categoria). Nenhuma escrita de despesa precisa lembrar de marcar o fechamento, então não há marcador para perder em concorrência nem segunda fonte de eventos; a lista anual compara o digest salvo com o digest atual de cada mês a partir de uma leitura do ano.
- **Versões (H07.3).** Gerar versão toma o bloqueio do espaço e bloqueia o cabeçalho do mês (`select ... for update`); a versão vigente precisa ser a `expectedVersion` do pedido, senão `409`. A versão n+1 é gravada com categorias e linhas, `current_version` avança com `update ... where current_version = n` (e a versão existe), e o evento `VERSION_GENERATED` e a chave idempotente vão na mesma transação `READ COMMITTED`. Versões anteriores nunca são alteradas (gatilhos); a consulta de uma versão lê só as suas linhas.

### 6.2.2 Lembretes: configuração do canal (E08)

- **Configuração (H08.1).** Uma linha por espaço em `reminder_settings` (horários, número E.164, ativação, versão); sem linha valem 09:00/18:00 e canal desativado. Consentimentos em `whatsapp_consents`, nunca reescritos exceto para registrar a revogação; índice único parcial garante um ativo por espaço. Toda escrita é `READ COMMITTED`: bloqueia o espaço (`FinancialMemberAccess.requireActiveParticipants`), relê o papel, reserva a chave idempotente, cria/bloqueia a linha (`insert ... on conflict do nothing` + `select ... for update`), confere `expectedVersion`, grava estado, consentimento e evento e conclui a chave na mesma transação.
- **Transferência.** `identity` publica `AdministrationTransferHandler`; `notifications` o implementa. `MembershipManagementService.transferAdministration` chama os handlers dentro da transação da transferência, então uma falha desfaz transferência e revogação juntas.
- **Canal x provedor.** O estado efetivo (`RECIPIENT_REQUIRED`, `CONSENT_REQUIRED`, `DISABLED`, `SUSPENDED` desde a H08.5, `PROVIDER_UNAVAILABLE`, `READY`) combina a configuração com a porta `WhatsAppProviderStatus`. Desde a H08.4 o adapter é `MetaWhatsAppProvider`: `PROVIDER_DISABLED` (padrão), `PROVIDER_NOT_CONFIGURED` (ligado com valor faltando) ou `PROVIDER_READY`; a configuração do canal não mudou.
- **Elegibilidade e resumo (H08.2).** Domínio puro em `notifications.domain`: `ReminderCalendar` (primeiro horário: atrasadas e vencimentos até 5 dias; segundo: hoje e amanhã), `ReminderWindow` (do instante programado até o primeiro entre +1 h e o próximo horário), `ReminderSummary` (dedup por identidade `E:<despesa>`/`F:<recorrência>:<vencimento>`, ordem atrasadas → vencimento → descrição sem caixa → identidade, até 5 detalhes, totais `BigDecimal` de todas as elegíveis) e `ReminderSummaryText` (texto pronto para canal). Leitura de `expenses` pela porta pública `ExpenseReminderQueries` (JDBC, só `PENDING` com vencimento); previsões por `RecurrenceForecastQueries`; materialização antecipada pela porta `UpcomingOccurrenceGeneration` de `recurrences`, que usa a mesma fila/materializador da H04.2 restrita ao espaço.
- **Job de horários.** `ReminderSummaryJob` (`app.jobs.reminders.*`, padrão 60 s) avalia ontem e hoje de cada espaço no fuso do espaço. Cada horário: materialização antecipada fora da transação do resumo; depois uma transação `REPEATABLE READ` reserva `reminder_slot_runs` (`insert ... on conflict do nothing`), compõe a partir de um único snapshot e grava `reminder_summaries`, `reminder_summary_items` e `reminder_summary_channels`. Resultados: `GENERATED`, `EMPTY`, `MISSED`; perda de corrida (`ConcurrencyFailureException`/violação de unicidade) é tratada como já processado. Logs só com contagens.
- **Canais do resumo.** `IN_APP` sempre `PLANNED`; `WHATSAPP` `PLANNED` com o administrador como destinatário somente com estado `READY` e consentimento do administrador atual para o número atual; senão `SKIPPED` com o estado como motivo. Nenhum envio nesta história; H08.3–H08.5 consomem esses registros.
- **API.** `GET /api/v1/notifications/reminders/preview?date&slot` (sem gravar nem materializar; hoje até +60 dias) e `GET /api/v1/notifications/reminders/summaries/{id}` (lista completa, `404` fora do espaço ativo). Decisão: T32.
- **Avisos internos (H08.3).** `member_notifications` (V26): um aviso por resumo + destinatário + tipo. `ReminderSummaryService.process` entrega `REMINDER_SUMMARY` aos membros ativos e, se o canal ativado com consentimento ficou `PROVIDER_UNAVAILABLE`, `WHATSAPP_DELIVERY_FAILURE` ao administrador, tudo na transação `REPEATABLE READ` do horário. Consulta e escrita sempre por espaço + destinatário; avisos `ADMINISTRATOR` exigem papel atual de administrador. Motivos de falha vêm do catálogo `WhatsAppFailureReason`; nenhum texto do provedor é persistido. `POST /notifications/inbox/{id}/read|dismiss` são idempotentes e protegidos por CSRF. Retenção não definida (T33).
- **Envio WhatsApp (H08.4).** `whatsapp_deliveries` (V27): uma entrega por resumo (`UNIQUE summary_id`) ou por chave de teste (`UNIQUE space_id, test_key`); `whatsapp_attempts` (uma linha por chamada, `attempt_number` pronto para a H08.5); `whatsapp_status_events` (PK mensagem + situação, deduplica o webhook). `WhatsAppDeliveryJob` (30 s): `expireStale` (tentativa há mais de 10 min vira `UNCERTAIN`) → `prepare` em transação `READ COMMITTED` curta com `select … for update` em `reminder_settings` (mesmo bloqueio das alterações de configuração), revalidação de administrador, consentimento, número, ativação, provedor, janela (`min(agendado + 1 h, próximo horário)`) e conteúdo (recomposto agora e intersectado com as chaves do resumo gerado) e `insert … on conflict do nothing` → chamada HTTP fora de transação → `record` em outra transação curta (só a partir de `ATTEMPTING`, ou de `UNCERTAIN` sem id quando a resposta tardia chega). Estados e classificação no README. Webhook `/integrations/whatsapp/webhook`: `permitAll` e exceção de CSRF só para esse caminho (`PathPatternRequestMatcher`), autenticidade por verify token em tempo constante e HMAC-SHA256 do corpo bruto antes do parse; aplica só `statuses` do `phone_number_id` configurado e de `wamid` conhecido, com `canAdvanceTo` monotônico; `503` quando há tentativa sem id gravado, para a Meta reenviar. Segredos por `META_WHATSAPP_*_FILE`; HTTPS obrigatório em produção; nada de token, número completo ou texto do provedor em tabela, resposta ou log.
- **Retentativas, reconciliação e suspensão (H08.5, T35).** V28: estado `RETRY_WAITING` com `next_attempt_at` (constraint: um existe se e somente se o outro), `reconciled_at`, `reminder_settings.whatsapp_suspended_at/whatsapp_suspension_reason` (suspenso implica desativado) e evento `CHANNEL_SUSPENDED` sem autor. `WhatsAppRetryPolicy` (domínio): esperas de 1, 5, 15 e 30 min, no máximo 5 tentativas, `Retry-After` em segundos honrado se maior (teto 1 h), nunca depois de `ReminderWindow.deadlineOf` (o menor entre agendado + 1 h e o próximo horário da configuração atual). Só `UNAVAILABLE` (recusa certa: conexão, 429, `130429`/`131048`/`131056`/`80007`/`133016`, `131016`) de resumo vira `RETRY_WAITING`; `UNCERTAIN` nunca. `WhatsAppDeliveryJob` faz `expireStale` → primeiras tentativas → `retryingDeliveries(now)`: para cada uma, `prepareRetry` bloqueia `reminder_settings` e depois a entrega (mesma ordem de `prepare`; `record` e webhook bloqueiam a entrega e só depois a configuração, e apenas para suspender), aplica a mesma `revalidate` da primeira tentativa, encerra (`FAILED`/`NOT_SENT_IN_WINDOW` ou `SKIPPED` com motivo) ou grava a tentativa `n+1` em `ATTEMPTING`; a chamada continua fora de transação. Reinício: esperas válidas seguem; vencidas encerram com log `whatsapp_retry_closed`. Reconciliação: o id da tentativa vai em `biz_opaque_callback_data`; o webhook localiza a entrega por ele (`lockByAttempt`) e, se `UNCERTAIN` sem id, grava o `wamid`, a situação e `reconciled_at`. Suspensão: `REJECTED`, `RECIPIENT_INVALID` e falha do webhook com código de destinatário (`131026`/`131030`) desativam o canal só se consentimento e número em vigor forem os da tentativa; reativar ou trocar o número limpa a suspensão.

### 6.3 Jobs persistentes

Baseline: polling curto no backend, tabela de tarefas no PostgreSQL e reserva com exclusão mútua/lease. Processamento não exige broker. Padrão operacional:

1. Buscar/reservar tarefa elegível numa transação curta.
2. Revalidar prazo, permissões, consentimento e conteúdo.
3. Fazer chamada externa fora da transação.
4. Registrar resultado numa nova transação.
5. Agendar tentativa permitida ou encerrar.

Workers interrompidos deixam lease recuperável. Um lease vencido não prova que chamada externa falhou: tratar aceite incerto antes de reenviar. Nunca afirmar exactly-once no provedor sem contrato que o garanta. Jobs de geração deduplicam ocorrências; retomada de notificação descarta resumo vencido em vez de enviar fila antiga. Polling, lotes, timeout, backoff e prazo de lease precisam de valores fixados e testados na história, registrados em P08.

## 7. Segurança, autenticação e autorização

### 7.1 Sessões

Spring Security + Spring Session JDBC. Cookie de sessão `HttpOnly`, `Secure` em produção, host-only e `SameSite` adequado ao fluxo no mesmo domínio (baseline Lax). Token CSRF é separado do cookie de sessão, integrado ao Angular; não tornar cookie de sessão legível para resolver CSRF.

- Inatividade de **7 dias**; duração absoluta de **30 dias**, mesmo com uso contínuo.
- Cookie persistente respeita validade; servidor é autoridade. Limite absoluto requer verificação própria se não fornecido pelo mecanismo escolhido.
- Atividade humana autenticada renova inatividade. Polling de fundo, healthcheck e jobs não mantêm sessão viva indefinidamente.
- Rotacionar identificador no login; logout revoga a sessão. Encerrar todas ou redefinir senha revoga todas.
- Remoção de membro revoga sessões e valida vínculo em requisições protegidas, downloads e comandos pendentes.
- Local HTTP pode ter configuração específica de cookie; produção não permite desabilitar Secure/CSRF por conveniência.

### 7.2 Primeiro administrador e tokens

Segredo temporário de instalação fornecido fora do código/imagens. Usar somente quando nenhum setup foi concluído. Marcação persistente e transação impedem dois cadastros simultâneos. Depois do cadastro, bloquear setup em reinícios e remover segredo do ambiente; reenvio de confirmação não reabre instalação.

| Finalidade | Expiração | Regra |
|---|---|---|
| Confirmar email | 24 horas | Uso único; reenvio invalida anterior da mesma finalidade. |
| Recuperar senha | 30 minutos | Uso único; resposta genérica ao solicitar, sem enumerar usuários. |
| Aceitar convite | 7 dias | Vinculado ao email confirmado; reenvio/revogação invalidam anterior. |

Armazenar hashes de tokens aleatórios fortes; não registrar URLs completas em logs. Aplicar limites de frequência por identidade/IP sem impedir o uso pessoal normal; números são P08. Senhas com algoritmo adaptativo e parâmetros medidos; usar PasswordEncoder suportado, não criptografia reversível. Política de senha final documentada na história de identidade.

### 7.3 Arquivos e APIs

Autorização no backend em toda operação. Arquivo físico com ID gerado e caminho controlado, sem aceitar caminho fornecido pelo usuário. Conferir tamanho/tipo real e download seguro; sem exposição direta pelo Nginx. URLs históricas não contornam revogação. Não cachear dados privados, anexos ou respostas autenticadas no service worker.

Webhooks são exceções pontuais ao login/CSRF de navegador, com verificação de autenticidade própria do provedor; nunca desabilitar CSRF globalmente para recebê-los. Headers encaminhados pelo proxy só são confiados de proxy conhecido.

## 8. Contrato HTTP e frontend

### 8.1 API

Prefixo `/api/v1`, JSON, DTOs explícitos e OpenAPI. Datas `AAAA-MM-DD`, instantes ISO-8601 UTC, dinheiro `"150.00"`. Paginado no servidor com ordenação estável e filtros; parâmetros máximos são P08. Cancelados podem ser exibidos, mas não entram em totais ativos.

Baseline de erro:

```json
{
  "code": "EXPENSE_VERSION_CONFLICT",
  "message": "A despesa foi alterada. Atualize os dados antes de salvar.",
  "fieldErrors": [],
  "operationId": "identificador-sem-dados-pessoais"
}
```

Sem stack trace, SQL ou segredos ao cliente. Documentar 400/401/403/404/409/429 e falhas de serviço aplicáveis. DTO financeiro não expõe JPA nem resposta bruta do provedor. Idempotência e versão precisam constar no OpenAPI com exemplos. Controllers validam formato; invariantes continuam no caso de uso/domínio.

### 8.2 Angular

Organização por funcionalidades, componentes compartilhados apenas quando reutilizados de fato. Angular Material com tema próprio, estados financeiros com texto/ícone, formulários acessíveis e feedback de operação. Cálculo oficial no backend; conversão de decimal não pode introduzir centavos falsos.

Baseline técnica: Reactive Forms, serviços HTTP/fachadas por funcionalidade e estado local/sinais quando suficiente. Não adicionar store global complexo sem necessidade concreta. Interceptor trata sessão/CSRF/erros, sem reexecutar POST automaticamente com chave nova. Preservar a chave da operação na repetição da mesma intenção.

PWA no Chrome Android dos dois usuários; desktop Chrome/Edge Windows. Sem obrigação de Safari/iOS no MVP. App shell pode ser cacheado, dados financeiros não. Offline apresenta estado indisponível; sem edição offline, fila escondida ou saldo antigo apresentado como atual. Câmera negada oferece upload/manual. PWA instalada não é teste de câmera concluído: validar nos aparelhos.

## 9. Integrações

### 9.1 Email — Gmail SMTP

Conta dedicada a criar, SMTP configurável. Configurar autenticação e TLS seguindo documentação vigente; baseline `smtp.gmail.com`, porta 587/STARTTLS, remetente igual à conta autorizada. Usar verificação em duas etapas e senha de app se disponível; não usar senha normal nem desabilitar segurança se a opção não existir. Confirmar essa disponibilidade antes da ativação, alternativa de autenticação fica pendente.

Email apenas para acesso/convite, não backup de lembretes. Captura local (ex.: Mailpit, escolha técnica) sem envio real por padrão. Tentativa técnica não cria outro token; reenvio explicitamente solicitado invalida o anterior. Após expirar/revogar, tarefa não deve entregar link antigo como se válido. Documentar falhas SMTP, rotação de credencial e teste real.

### 9.2 WhatsApp — Meta Cloud API

Escolha aprovada: API oficial, número dedicado remetente, administrador como único receptor. Endpoint de webhook `/api/v1/integrations/whatsapp/webhook` (H08.4), HTTPS público com verificação `hub.*` e assinatura `X-Hub-Signature-256`, conferidas na documentação oficial em 29/09/2026. Versão da Graph API configurável (`META_WHATSAPP_API_VERSION`); formato exato do template, da resposta e dos eventos `statuses` e códigos de erro não puderam ser conferidos nas páginas de referência neste ambiente e devem ser revistos na P03 antes do envio real. Não há integração WhatsApp Web por automação de navegador.

Registrar envio, ID do provedor, aceite, entrega e falha separadamente. Webhooks podem ser repetidos/fora de ordem; deduplicar e impedir regressão indevida de estado. Retentativa com resultado incerto só após estratégia documentada; sem suporte de consulta, adotar estado incerto e decisão controlada, não envio cego.

Manter calendário e agrupamento do PRD: até dois resumos lógicos/dia, janela até uma hora, cinco detalhes e link, sem acúmulo. Templates, categoria aceita, nome de exibição e habilitação do negócio/número são dependências externas. Não assumir aprovação por ser projeto pessoal nem classificar a mensagem como utility sem confirmação. A página oficial informa cobrança por mensagem entregue variando por categoria/mercado; nenhum preço ou gratuidade foi contratado nesta especificação. Falta confirmar a tabela e a elegibilidade na conta (P03).

### 9.3 IA — Groq gratuita

Groq é o primeiro provedor, modelo de visão parametrizado e validado antes da liberação. Não fixar no código o nome de um modelo citado em conversa: consultar modelos ativos e limites da conta, fixar configuração da versão testada e registrar evidência. Testar etiquetas, contas e comprovantes sintéticos/anonimizados antes dos reais. Ativar/verificar Zero Data Retention disponível na conta, sem assumir que isso elimina metadados de uso.

Porta de análise recebe imagem e contexto mínimo (por exemplo, categorias permitidas), retorna sugestão validada por schema, campos ausentes/ambíguos e possíveis correspondências. Não enviar todo o histórico financeiro quando não necessário. Não habilitar tools para executar cadastro; a resposta do modelo é dado não confiável. Revisão humana usa os mesmos comandos de despesas/planejamento.

Uma conta/despesa por imagem. Foto ilegível não produz valor inventado. Resposta malformada mantém opção manual; reparo/reanálise externa tem contabilização explícita. Chave somente no backend, sem fallback automático pago. OpenAI permaneceu somente alternativa de custo discutida, não adapter obrigatório nem segundo provedor contratado.

#### Cota aprovada

20 análises/dia por espaço, ajustável pelo administrador, dia no fuso local:

| Resultado | Cota |
|---|---|
| Entrada inválida antes de chamar IA | Não consome. |
| Falha comprovada antes do envio | Libera a reserva. |
| Resposta utilizável | Consome. |
| Resposta ilegível/ambígua | Consome, pois houve processamento. |
| Timeout após envio com resultado incerto | Reserva mantida até verificar; sem possibilidade de verificar, contabiliza uso. |
| Repetição técnica da mesma solicitação | Não desconta outra vez. |
| Nova análise explicitamente solicitada | Novo uso. |

Reservar atomicamente antes da chamada e associar a um ID. Repetição técnica deve consultar estado/resultado; não significa reenviar ao provedor indefinidamente usando uma única cota. Resultado após meia-noite ajusta a reserva do dia original, não consome um dia novo. Limites internos não substituem cotas reais do provedor. Prazo de resolução de reservas incertas é P08, não pode ficar preso para sempre.

#### Retenção

Imagem temporária separada, excluída até 24h quando não promovida a anexo, inclusive abandono. Não copiar temporários para backups. “Guardar como anexo” desmarcado; promoção apenas após confirmação e com limites de cinco arquivos/10 MB. Não logar bytes, OCR completo ou dados financeiros completos. Procedimento de limpeza deve sobreviver a reinício e não remover anexo promovido por concorrência.

## 10. Armazenamento de arquivos

Volume Docker persistente montado somente onde necessário, com áreas separadas `permanent/` e `temporary/`. Banco contém metadados, não bytes. Nginx não serve esse volume. Dados permanecem fora da camada gravável do container/imagem. Usuário do processo sem privilégios desnecessários e permissões explícitas do volume.

Arquivo e banco não possuem uma transação distribuída. Implementar staging + gravação de metadados + promoção idempotente, com reconciliação de órfãos e falhas; não retornar sucesso se metadado aponta para arquivo inexistente. Remoção registra histórico e permite limpeza segura. Backup deve produzir um conjunto banco/anexos consistente, não duas cópias independentes em momentos incompatíveis.

## 11. Testes e gates

### 11.1 Backend

- Unitários de domínio/casos de uso com JUnit Jupiter e Mockito somente onde útil, sem iniciar Spring para regra pura.
- Integração real PostgreSQL em Testcontainers, mesma versão major de produção e migrations Flyway. Sem H2 como substituto.
- API/segurança verificam sessão, expiração absoluta/inatividade, CSRF, autorização, conflito e idempotência.
- JaCoCo nos pacotes explícitos de regras de domínio/aplicação: **linhas >=80%, branches >=70%**.
- PIT no mesmo escopo de regras: **mutation score >=70%**, com plugin compatível de JUnit. Definir escopo versionado; não excluir regras difíceis.
- Testar calendário, precisão, concorrência, rollback total de lote e retomada de jobs de forma determinística. Sem waits longos usados como prova de calendário.
- ArchUnit: domínio sem frameworks/persistência/API; aplicação sem implementações externas; módulos sem acesso a JPA alheio nem ciclos; DTOs sem entidades JPA. Consultas reporting têm exceção delimitada de leitura.

Gates sobre pacotes vazios não são evidência de qualidade. Na preparação usar prova mínima da ferramenta; ao existir código de negócio o gate deve abranger todo escopo estabelecido. Não mudar denominador/exclusões/thresholds para passar build.

### 11.2 Frontend e integração

Vitest/componentes para formulários, filtros, formatos e erros; Playwright E2E com aplicação e banco isolados. PR executa componentes e E2E críticos. Tag executa conjunto completo; finalização também exige teste PWA/câmera real em Android, email e WhatsApp reais, Groq e restauração.

Provedores simulados em CI não comprovam produção. Salvar resultados JUnit, relatórios JaCoCo/PIT e E2E como artifacts, evitando fotos/segredos nos relatórios. README informa paths reais após implementação. Nenhum threshold numérico de cobertura Angular foi aprovado; não copiar silenciosamente os thresholds Java.

## 12. GitHub Actions, Git e imagens

`main` protegida, sem `develop`; branch por história, PR e checks obrigatórios. Monorepo, imagens backend/frontend separadas. Tag `v1.0.0` em commit validado de `main` produz duas imagens `1.0.0` com commit e digests registrados. Não usar `latest` em produção nem sobrescrever versão publicada. Build/push só depois de testes.

| Evento | Execução |
|---|---|
| PR | Build, unitários/integração, JaCoCo, PIT no escopo definido, ArchUnit, componentes e E2E críticos. |
| Tag de versão | Revalidar commit/qualidade e E2E completos, construir imagens, publicar par e manifesto de entrega. |
| VPS | Operador escolhe versão, baixa, faz backup/migração e atualiza manualmente. |

Credencial CI com permissão de publicação; credencial VPS somente leitura conforme suporte do plano. Secrets não ficam em build args/layers. Não usar workflows privilegiados com código de PR não confiável. Não fazer push/merge/tag/publicação só porque a documentação prevê pipeline; executar conforme solicitação concreta.

**Imutabilidade:** evitar concorrência de publicação por versão, verificar existência e rejeitar sobrescrita; habilitar proteção do registry se disponível. Se backend publicou e frontend falhou, release fica incompleta; não recriar imagem já publicada. Recuperar a entrega preservando o digest existente e completar a segunda somente a partir do mesmo commit validado, ou abandonar a versão e gerar outra. Manifesto só é final quando ambas existem.

**Limitação confirmada a resolver:** Docker Personal oferece um repositório privado. Confirmar plano que comporte os dois aprovados; não tornar imagens públicas, trocar registry ou compactar em um repositório sem nova decisão. P01 bloqueia publicação, não desenvolvimento.

## 13. Compose, HTTPS e deploy

### 13.1 Serviços previstos

| Serviço lógico | Função |
|---|---|
| `db` | PostgreSQL interno, volume permanente, healthcheck. |
| `migrate` | Execução temporária da imagem backend no modo migrador; sem HTTP/jobs; encerra com código do resultado. |
| `backend` | API/jobs com banco já migrado; sem executar migrações automaticamente em produção. |
| `frontend` | Angular estático via Nginx, encaminha `/api` para backend. |
| Certificado/backup | Ferramenta/rotina a definir sem necessidade de novo serviço permanente se desnecessário. |

Sem publicar porta PostgreSQL ou backend na internet. DNS `A` de `contas` aponta ao IP público da VPS. Entrada web 80/443 conforme estratégia de certificado. Nginx precisa renovar/recarregar certificado e encaminhar corretamente host/protocolo. Não há DNS ou certificado configurados nesta entrega. Escolher cliente ACME/renovação em P02.

Configure healthcheck e ordem; estar “running” não significa pronto. Compose pode exigir DB saudável e serviço migrador concluído com sucesso, mas a atualização precisa executar migrador explicitamente a cada versão, sem reutilizar equivocadamente um container antigo já concluído.

### 13.2 Procedimento de atualização a implementar e validar

1. Selecionar uma release completa e registrar versão/digests atuais e novos.
2. Verificar espaço, configuração, conectividade e backup restaurável. Para operação que exige proteção externa, confirmar cópia disponível no Windows.
3. Baixar imagens versionadas na VPS autenticada no Docker Hub. Não compilar nada ali.
4. Entrar em manutenção ou pausar escritas/jobs para snapshot consistente e migração quando necessário.
5. Gerar/verificar backup pré-atualização de banco e anexos; guardar manifesto com hashes, versão e estado de schema.
6. Executar novo `migrate` temporário da imagem-alvo; em erro, parar atualização. Não iniciar backend novo nem rodar Flyway repair às cegas.
7. Após sucesso, subir backend/frontend da mesma entrega, conferir healthchecks e executar smoke tests.
8. Retirar manutenção, verificar login, consulta, jobs e versão efetiva. Registrar evidência e resultado.

Um deploy simples pode ter indisponibilidade; não há exigência de zero downtime. Rollback de imagem exige schema compatível. Migrações destrutivas precisam plano de recuperação específico. Restaurar backup pode perder alterações posteriores e exige autorização operacional explícita. Não incluir `docker compose down -v` em atualização, pois apaga volumes.

## 14. Backups no Windows e recuperação

**Decisão final:** destino externo é computador Windows de Diego, não S3. Rotina diária produz backup criptografado na VPS; PowerShell + Agendador de Tarefas inicia download por SSH/SFTP, com chave dedicada e acesso restrito, sem expor o PC.

- Persistir banco e anexos permanentes de forma consistente. Baseline simples: pequena janela de manutenção para impedir gravações/remoções enquanto o conjunto é capturado; alternativa consistente pode ser documentada tecnicamente.
- Criptografia com chave de recuperação também guardada fora da VPS. Escolher ferramenta e procedimento verificável em P06.
- Baixar para arquivo temporário, verificar hash/integridade e promover atomicamente; registrar sucesso só após validação. Teste de restauração valida utilidade do arquivo além do checksum.
- Executar diariamente após geração e tentar quando o Windows voltar se execução foi perdida. Tratar suspensão, logon e conta de execução no guia; não prometer download com PC desligado.
- Retenção diária de sete dias na VPS/Windows, preservando a última cópia válida quando rotina falhar. Aplicar limpeza depois de confirmar nova cópia; sinalizar falta de espaço e não apagar a última cópia para esconder falha.
- Imagens temporárias de IA nunca entram no conjunto.
- **RPO efetivo:** para perda total da VPS, recuperar até a última cópia externa válida. A meta de 24h depende de Windows receber backup diariamente. Se ficar desligado vários dias, exposição aumenta; não declarar RPO de 24h garantido.
- Após restaurar: revogar sessões/tokens restaurados ou revalidar antes de abrir acesso, conferir remoções de membros e registros de apagamento, impedir reenvios acumulados, testar geração sem duplicação e anexos.

Não fixado RTO; medir primeira restauração e documentar. Falha de backup/transferência deve produzir log e status verificável. Identificar separadamente “gerado na VPS” e “cópia externa confirmada”.

## 15. Logs, saúde e limites operacionais

Logs estruturados para stdout/stderr, nível, módulo, timestamp e operationId. Nada de cookies, tokens, senhas, fotos, corpo financeiro completo ou URLs com token. Detalhes de health/Actuator internos/protegidos; health público mínimo quando necessário. Falha de Groq/WhatsApp não deve derrubar readiness de toda aplicação manual. Readiness considera capacidade de servir API/banco, não sucesso de cada provedor.

Rotação/limite de logs no Docker, eventos de jobs e métricas mínimas de uso/erro. Sem plataforma de monitoramento separada. Uma instância backend/frontend, pool JDBC pequeno ajustado por teste e heap menor que limite do container para deixar espaço nativo. Não assumir que KVM 2 está vazia; orçamento de memória/CPU/disco é P07.

## 16. Contrato obrigatório do README e guias

Os comandos abaixo são **contrato proposto para o código futuro**, não instruções executáveis já testadas. Codex deve criar os arquivos/scripts equivalentes, validar e então colocar comandos reais no README. Não copiar exemplos e dizer que foram verificados sem executar.

| Ação | Comando/caminho a disponibilizar e documentar |
|---|---|
| Unitários Java | `backend/mvnw test` (executado no diretório correto) e equivalente `mvnw.cmd` no Windows. |
| Integração + JaCoCo + ArchUnit | `./mvnw verify`, com Surefire/Failsafe configurados e relatórios identificados. |
| PIT | Perfil Maven documentado, por exemplo `./mvnw -Pmutation verify`. |
| Frontend | `npm ci`, `npm start`, `npm run test:ci`, `npm run e2e:smoke`, `npm run e2e`, `npm run build`. |
| Infra local | `deploy/compose.dev.yml` com DB e captura email, portas locais restritas. |
| Stack local completa | Compose separado para validação de imagens e contratos. |
| Produção | `deploy/compose.prod.yml`, `.env.example` sem segredos e script de update com abortos claros. |
| Backup Windows | Script PowerShell e configuração do Agendador, destino, host key e usuário SFTP. |

README na raiz deve permitir a uma pessoa iniciar sem adivinhar:

1. Pré-requisitos e versões, instalação no Windows/Docker Desktop e checagem de Docker para Testcontainers.
2. Clone do repositório real, configuração local por cópia de exemplos, banco, migração, backend e frontend; diretório de execução e resultado esperado de cada passo.
3. Endereços locais e captura de email, primeiro administrador, simulações e como ativar teste real explicitamente.
4. Testes e relatórios: comandos Windows/Linux, paths efetivos, limites JaCoCo/PIT e como diagnosticar falhas sem desabilitar checks.
5. GitHub: protections, secrets por nome, tag, acompanhamento dos workflows, par de imagens e digests.
6. Primeira VPS: SO/arquitetura verificados, instalação Docker/Compose, usuário/diretórios/permissões, DNS, HTTPS e renovação; sem valores secretos em exemplos.
7. Deploy inicial, migração explícita, checks, criação inicial e remoção do segredo.
8. Atualização e rollback compatível, aviso de diferença entre voltar imagem e restaurar dados.
9. Backup, transferência Windows, integridade, retenção e restauração completa com teste; cenário PC desligado.
10. Diagnóstico: `docker compose ps`, logs por serviço, status de migração/certificado/disco/jobs, erros SMTP/IA/Meta e próximos passos.
11. Procedimentos administrativos de exportação completa e apagamento, com confirmações e tratamento de backups, sem confundir com CSV/saída.

Guias extensos podem ficar em `docs/guias/` com links claros. Exemplos de variáveis incluem DB URL/usuários, `APP_BASE_URL`, fuso, modo de integração, SMTP, Groq, Meta, imagem/versionamento e caminhos. Variáveis secretas devem ser identificadas, nunca preenchidas com dados reais. IDs exatos, links e comandos são atualizados por quem implementa a configuração, mantendo progresso de validação.

## 17. Referências e limites da verificação

Consultadas em 23/09/2026; fatos externos podem mudar. Conteúdo acima combina decisões aprovadas e projeto técnico, não transcrição dessas fontes.

- [Spring Boot — requisitos](https://docs.spring.io/spring-boot/system-requirements.html): faixa Java/Maven da linha estável consultada.
- [Spring Boot — testes](https://docs.spring.io/spring-boot/reference/testing/): geração de JUnit gerenciada e starter de testes.
- [Angular — compatibilidade](https://angular.dev/reference/versions) e [testes](https://angular.dev/guide/testing): matriz de versões e integração do runner.
- [PIT — plugin JUnit](https://github.com/pitest/pitest-junit5-plugin): combinação plugin/core deve ser validada com a plataforma JUnit efetiva.
- [JaCoCo — histórico](https://www.jacoco.org/jacoco/trunk/doc/changes.html), [ArchUnit](https://www.archunit.org/userguide/html/000_Index.html), [Testcontainers PostgreSQL](https://java.testcontainers.org/modules/databases/postgres/), [Playwright](https://playwright.dev/docs/intro): ferramentas escolhidas, sem prova de build nesta entrega.
- [PostgreSQL — suporte](https://www.postgresql.org/support/versioning/): linha major e janela de manutenção.
- [Compose — ordem de inicialização](https://docs.docker.com/compose/how-tos/startup-order/): diferenciar startup, saúde e conclusão de migrador.
- [Docker Hub — registry e plano](https://docs.docker.com/get-started/docker-concepts/the-basics/what-is-a-registry/): restrição do plano Personal a um repositório privado.
- [Gmail — senha de app](https://support.google.com/mail/answer/185833): duas etapas, disponibilidade da função e revogação após troca de senha.
- [Groq — dados](https://console.groq.com/docs/your-data), [visão](https://console.groq.com/docs/vision), [limites](https://console.groq.com/docs/rate-limits): verificar modelo, cota e retenção na conta antes de ativar.
- [WhatsApp — preços oficiais](https://whatsappbusiness.com/products/platform-pricing/): preço depende de mercado/categoria. A página técnica Cloud API retornou limitação de acesso nesta consulta; versão, templates e autenticação/webhook deverão ser conferidos antes de implementação, sem alegar verificação completa.

**Não executado nesta entrega:** build, testes de compatibilidade, provisionamento, migrações, DNS, publicação de imagens, envio de mensagens, análise de foto ou restauração. Tudo permanece tarefa de implementação/validação no progresso.
