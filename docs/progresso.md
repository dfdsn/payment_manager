# Progresso de implementação — account_Manager

Versão 1.0 • Atualizado em 28/09/2026.

**Estado atual: H04.3 concluída.** A consulta reconciliada do mês atual mais 12 meses e a antecipação idempotente foram aprovadas. E01 e E02 estão concluídos e H03.3 mantém validação independente.

## Resumo

| Item | Estado |
|---|---|
| PRD v2.0 e backlog | Documentados anteriormente; acompanhar esta entrega em `docs/`. |
| Especificação, decisões e AGENTS | Preservados e atualizados com a baseline efetiva e seus limites de validação. |
| Histórias | 15 de 46 concluídas; E01 e E02 concluídos, E03 depende da validação final de H03.3 e E04 está em andamento. |
| Critérios do PRD | CA-01 aprovado. CA-02 comprova uso, quitação individual/em lote, correção, reversão e cancelamento pelos dois papéis, mantendo WhatsApp para E08; CA-03 segue parcialmente validado; CA-04 tem cadastro, quitação, correção, reversão/cancelamento, lote atômico, histórico e conflitos aprovados, restando reflexos de dashboard/fechamento para E06/E07. CA-06 está aprovado no escopo existente; totais e alertas serão revalidados quando E06/E08 existirem. |
| Provedores e infraestrutura | P00 e P04 encerradas. Gmail real validado para confirmação, recuperação e convite. P01–P03 e P05–P09 mantêm seus estados em `decisoes-pendentes.md`. |
| Próxima ação | H04.4 — Confirmar valores variáveis. |

## Estados permitidos

- **Não iniciado:** sem implementação registrada.
- **Em andamento:** trabalho iniciado; identificar responsável e escopo.
- **Bloqueado:** indicar causa real, dependência, responsável e condição de desbloqueio.
- **Em validação:** implementação aguardando verificações ou aceite identificados.
- **Concluído:** critérios atendidos, testes aplicáveis aprovados e evidências registradas.

Uma dependência prevista não significa tentativa fracassada. Não marcar todos os itens como bloqueados só porque suas predecessoras ainda não começaram. Uma integração com mock pode ficar em validação, mas não ser concluída como integração real.

## Preparação habilitadora

Não representa novo épico funcional. Pode ser dividida em PRs pequenos sem iniciar funcionalidades antes de sua base necessária.

| ID | Entrega | Estado | Evidência necessária |
|---|---|---|---|
| PREP-01 | Provar compatibilidade e fixar versões | Concluído | CI `36038781176` em Java 21/Node 24.18 e repetições locais aprovaram Testcontainers/PostgreSQL 17.6, unitários, ArchUnit, JaCoCo, PIT, frontend, build, audit e smoke. P00 encerrada. |
| PREP-02 | Estruturar repositório e execução local | Concluído | Wrappers/lockfile, camadas, Flyway, Compose PostgreSQL/Mailpit e comandos validados; E2E full-stack aplicou V1–V3 e exercitou UI/backend/banco/email local. |
| PREP-03 | Configurar CI e gates | Concluído | Workflows e limites implementados; CI real do commit `737f46a` aprovada. Proteção de branch continua uma configuração do repositório a conferir, sem impedir a base técnica. |
| PREP-04 | Preparar operação e documentação inicial | Concluído | Duas imagens, Compose image-only, migration explícita, README, scripts e guia P01–P09 preparados. Publicação, VPS, backup e restauração continuam corretamente não executados e dependentes de P01/P02/P06/P07. |

## Histórias do MVP

Títulos e IDs preservados do backlog. Consulte cada história para critérios completos e dependências. E11 começa cedo, mas só conclui com validação integrada; responsividade, segurança e testes começam na primeira história.

| História | Entrega | Estado | Evidências |
|---|---|---|---|
| H01.1 | Configurar administrador e espaço | Concluído | Concorrência/reinício em PostgreSQL, CI Java 21 e E2E full-stack pela interface aprovados. Evidência: `docs/evidencias/H01.1.md`. |
| H01.2 | Entrar, confirmar email e recuperar acesso | Concluído | 41 unitários, 4 ITs PostgreSQL, JaCoCo, PIT 90%, 11 frontend, E2E full-stack e entrega Gmail real de confirmação/recuperação aprovados. Evidência: `docs/evidencias/H01.2.md`. |
| H01.3 | Convidar e aceitar o segundo membro | Concluído | 52 unitários, 7 ITs PostgreSQL, JaCoCo, PIT 78%, 17 frontend, E2E Mailpit e entrega/aceite Gmail real aprovados. Evidência: `docs/evidencias/H01.3.md`. |
| H01.4 | Aplicar papéis e gerenciar saída | Concluído | 65 testes Java, 10 ITs PostgreSQL, JaCoCo 94,93%/80,52%, PIT 80%, 20 frontend, build e E2E aprovados. Evidência: `docs/evidencias/H01.4.md`. |
| H02.1 | Cadastrar e listar uma despesa avulsa | Concluído | 77 testes Java, 14 ITs PostgreSQL, JaCoCo 95,38%/84,26%, PIT 80%, 27 frontend, build e E2E aprovados. Evidência: `docs/evidencias/H02.1.md`. |
| H02.2 | Quitar e identificar quem pagou | Concluído | 81 testes Java, 19 ITs PostgreSQL, JaCoCo 95,58%/85,29%, PIT 80%, 30 frontend, build e E2E aprovados. Evidência: `docs/evidencias/H02.2.md`. |
| H02.3 | Corrigir com proteção contra conflito | Concluído | 85 testes Java, 23 ITs PostgreSQL, JaCoCo 95,77%/84,87%, PIT 81%, 33 frontend, build e E2E de conflito em duas páginas aprovados. Evidência: `docs/evidencias/H02.3.md`. |
| H02.4 | Desfazer quitação e cancelar | Concluído | 91 Java, 28 ITs PostgreSQL (18 de despesas), JaCoCo 95,57%/85,55%, PIT 82%, 37 frontend, build e E2E aprovados. P10 encerrada com correção prévia do vencimento. Evidência: `docs/evidencias/H02.4.md`. |
| H02.5 | Quitar vários lançamentos | Concluído | 97 Java, 34 ITs PostgreSQL (24 de despesas), JaCoCo 95,73%/85,64%, PIT 83%, 40 frontend, build e E2E atômico aprovados. Evidência: `docs/evidencias/H02.5.md`. |
| H03.1 | Gerenciar categorias | Concluído | 100 Java, 36 ITs PostgreSQL (26 de despesas), JaCoCo 95,55%/85,86%, PIT 81%, 42 frontend, build e E2E aprovados. Evidência: `docs/evidencias/H03.1.md`. |
| H03.2 | Atribuir responsável e consultar histórico | Concluído | 101 Java, 32 ITs PostgreSQL, JaCoCo 95,38%/85,02%, PIT 75%, 44 frontend, build e E2E aprovados. Evidência: `docs/evidencias/H03.2.md`. |
| H03.3 | Anexar e acessar documentos | Em validação | Fluxo implementado; 101 Java, 31 ITs selecionados PostgreSQL e 44 frontend aprovados. Faltam gates finais/E2E específico. Evidência: `docs/evidencias/H03.3.md`. |
| H03.4 | Buscar e filtrar lançamentos | Concluído | Busca/filtros/paginação autorizados e V14 aprovados em PostgreSQL, gates e E2E. Evidência: `docs/evidencias/H03.4.md`. |
| H04.1 | Cadastrar recorrência e calcular calendário | Concluído | Cinco frequências, modalidade fixa/estimada, dia-base sem deslocamento, idempotência, autorização, V15, PostgreSQL e E2E aprovados. Evidência: `docs/evidencias/H04.1.md`. |
| H04.2 | Gerar ocorrências sem duplicação | Concluído | Job PostgreSQL com lease/fencing, unicidade por recorrência/data, rollback e retomada; V16, 38 ITs selecionados e E2E aprovados. Evidência: `docs/evidencias/H04.2.md`. |
| H04.3 | Visualizar e antecipar previsões | Concluído | Horizonte de 13 meses somente leitura, reconciliação por identidade estável e antecipação idempotente aprovados em PostgreSQL, gates e E2E. Evidência: `docs/evidencias/H04.3.md`. |
| H04.4 | Confirmar valores variáveis | Não iniciado | — |
| H04.5 | Alterar e encerrar recorrência | Não iniciado | — |
| H05.1 | Criar compra e parcelas | Não iniciado | — |
| H05.2 | Consultar e quitar parcelas | Não iniciado | — |
| H05.3 | Ajustar e cancelar parcelas pendentes | Não iniciado | — |
| H06.1 | Consultar dashboard por vencimento | Não iniciado | — |
| H06.2 | Consultar pagamentos e ajustes | Não iniciado | — |
| H06.3 | Consultar planejamento futuro integrado | Não iniciado | — |
| H06.4 | Exportar CSV filtrado | Não iniciado | — |
| H07.1 | Fechar mês com resumo | Não iniciado | — |
| H07.2 | Sinalizar alterações posteriores | Não iniciado | — |
| H07.3 | Gerar e consultar versões | Não iniciado | — |
| H08.1 | Configurar canal, consentimento e horários | Não iniciado | — |
| H08.2 | Calcular elegibilidade e resumo | Não iniciado | — |
| H08.3 | Disponibilizar notificações internas | Não iniciado | — |
| H08.4 | Enviar e acompanhar WhatsApp real | Não iniciado | — |
| H08.5 | Revalidar e tratar falhas sem acúmulo | Não iniciado | — |
| H09.1 | Instalar e navegar pelo celular | Não iniciado | — |
| H09.2 | Capturar ou selecionar imagem | Não iniciado | — |
| H09.3 | Tratar desconexão de forma clara | Não iniciado | — |
| H10.1 | Solicitar análise e revisar sugestões | Não iniciado | — |
| H10.2 | Criar avulsa ou vincular a existente | Não iniciado | — |
| H10.3 | Criar recorrência ou sugerir quitação | Não iniciado | — |
| H10.4 | Controlar cota compartilhada | Não iniciado | — |
| H10.5 | Controlar retenção e anexação | Não iniciado | — |
| H11.1 | Preparar execução e implantação | Não iniciado | — |
| H11.2 | Fazer backup diário externo | Não iniciado | — |
| H11.3 | Restaurar e retomar serviços | Não iniciado | — |
| H11.4 | Validar capacidade e operação | Não iniciado | — |
| H11.5 | Executar aceite integrado do MVP | Não iniciado | — |

## Aceite integrado

| Verificação | Estado inicial | Evidência ao executar |
|---|---|---|
| CA-01 — configuração, login e convite | Aprovado | E2E full-stack H01.1–H01.4, ITs PostgreSQL e entregas Gmail reais cobrem fechamento do setup, identidade destinatária, expiração, revogação e reenvio. |
| CA-02 — uso pelos dois perfis | Em validação transversal | Matriz/bloqueio administrativo, cadastro/listagem e quitação da mesma despesa pelos dois papéis aprovados; ausência de WhatsApp será executada em E08. |
| CA-03 — saída/remoção e transferência | Em validação transversal | Revogação, histórico de associação, vaga e transferência aprovados; responsabilidades/anexos/novo consentimento serão revalidados em E03/E04/E08. |
| CA-04 — avulsa pendente/paga | Em validação transversal | Cadastro pendente/já pago, atraso, quitação posterior, valor efetivo e identificação de pagador/autor aprovados; reflexos no dashboard e histórico mensal dependem de E06/E07. |
| CA-05 a CA-32 do PRD | Não executados | Registrar resultado individual, cenário, ambiente e evidência; não inferir aprovação a partir de cobertura. |
| Email real | Não executado | Confirmação, convite, reset, expiração/reenvio; sem expor tokens. |
| WhatsApp real | Não executado | Entrega ao administrador, ausência para convidado, agrupamento, quitação/falha/retomada. |
| Groq real | Não executado | Extração por imagem, revisão, cota concorrente, retenção e fallback manual. |
| Android nos dois aparelhos | Não executado | Instalação PWA, câmera/galeria, sessão, conexão interrompida; modelos/versões sem identificadores pessoais. |
| Windows Chrome/Edge | Não executado | Fluxos críticos e responsividade. |
| Release e VPS | Não executado | Par de digests, migração explícita, HTTPS, saúde e smoke pós-deploy. |
| Backup e restauração | Não executados | Cópia externa íntegra, restauração limpa, anexos acessíveis, tempos medidos e data da última cópia válida. |
| Exportação completa/exclusão | Não executadas | Procedimento definido em P09 e ensaio com dados fictícios. |

## Registro por história: modelo para preencher

Copiar este bloco ao iniciar cada história; não substituir o histórico por uma lista sem evidências.

```text
História / objetivo:
Estado / responsável / data:
Requisitos e critérios de aceite relacionados:
Decisões e pendências aplicáveis:
Branch / commit / PR:
Arquivos e comportamento alterados:
Migrações e impacto sobre dados:
Testes executados (comando, ambiente e resultado):
Cobertura e mutação aplicáveis (valores e relatório):
Validação manual / integração real:
Limitações e cenários não executados:
Documentação atualizada:
Condição de conclusão ou desbloqueio:
Próximo passo:
```

Não incluir credenciais, números pessoais ou dados financeiros reais nas evidências. Para um teste falho, registrar também a correção e nova execução, sem apagar o fato de que a primeira tentativa falhou.

## Registro H01.1

```text
História / objetivo: H01.1 — Configurar administrador e espaço.
Estado / responsável / data: Concluído / desenvolvimento / 24/09/2026.
Requisitos e critérios de aceite relacionados: RF-ACC-01; D11; setup único, atômico e persistente; defaults BRL/pt-BR/America/Sao_Paulo; responsividade, validação e proteção CSRF.
Decisões e pendências aplicáveis: T04 registrada. P00 encerrada; nenhuma conta externa é necessária.
Branch / commit / PR: base `737f46a` em `main`; CI `36038781176`; trabalho posterior na branch local `h01-2-auth-access`.
Arquivos e comportamento alterados: módulo identity em domínio/aplicação/infraestrutura/API; configuração de segurança; UI Angular; Compose/entrypoint; README e guias.
Migrações e impacto sobre dados: V2 cria identity_users, family_spaces, space_memberships, installation_state e tabelas Spring Session. V1/V2 aplicadas em bancos PostgreSQL 17.6 efêmeros do Testcontainers; nenhum dado compartilhado alterado.
Testes executados: 25 JUnit/Mockito/Spring/ArchUnit e 2 ITs originais aprovados; CI Java 21/Node 24.18 verde; E2E full-stack posterior repetiu setup desde banco vazio pela interface. A suíte atual, já com H01.2, mantém H01.1 verde em 41 unitários e 4 ITs.
Cobertura e mutação aplicáveis: JaCoCo 100% linhas e 94,23% branches; PIT 90,32% (28/31), todos acima dos gates.
Validação manual / integração real: persistência/concorrência/reinício executados em PostgreSQL 17.6 real; E2E full-stack executado com V1–V3, frontend, backend e Mailpit. CI confirmou JDK 21.
Limitações e cenários não executados: nenhuma limitação material restante para os critérios da H01.1. O processo webServer do smoke interceptado ainda não encerra sozinho no Windows, sem invalidar a asserção nem o E2E full-stack.
Documentação atualizada: README.md, docs/openapi.yaml, docs/guias/operacao.md, docs/decisoes-pendentes.md, docs/evidencias/H01.1.md e este arquivo.
Condição de conclusão ou desbloqueio: satisfeita em 24/09/2026.
Próximo passo: H01.2 — entrar, confirmar email e recuperar acesso.
```

## Registro H01.2

```text
História / objetivo: H01.2 — Entrar, confirmar email e recuperar acesso.
Estado / responsável / data: Concluído / desenvolvimento + Diego na validação Gmail / 24/09/2026.
Requisitos e critérios de aceite relacionados: RF-ACC-02 a RF-ACC-04; D09, D10 e D12; sessão JDBC; confirmação; recuperação de uso único; expiração; revogação; entrega real de email.
Decisões e pendências aplicáveis: T05 registrada. A parte de confirmação/recuperação de P04 foi validada; P04 segue aberta para o convite de H01.3. P08 continua aberta para parâmetros de rate limiting e não recebeu default silencioso.
Branch / commit / PR: branch local h01-2-auth-access, base 737f46a; sem commit/PR novo nesta execução.
Arquivos e comportamento alterados: identity nas camadas domínio/aplicação/infra/API; segurança e sessão; V3; SMTP/entrypoint/Compose; telas Angular e E2E; OpenAPI, README e evidência H01.2.
Migrações e impacto sobre dados: V3 cria tokens de acesso com finalidade, hash, expiração, consumo e revogação. V1–V3 aplicadas em bancos efêmeros PostgreSQL 17.6 e no Compose descartável; nenhum banco compartilhado foi alterado.
Testes executados: 41 unitários/HTTP/ArchUnit (0/0/0); 4 ITs Failsafe (0 ignorados/falhas/erros); 11 Vitest; build Angular; 1 E2E full-stack aprovado; smoke interceptado com asserção verde e processo interrompido depois; Compose full-local saudável.
Cobertura e mutação aplicáveis: JaCoCo 99,48% linhas (192/193) e 89,02% branches (73/82) em domínio/aplicação; PIT 90% (56/62), linhas mutadas 99% (153/154).
Validação manual / integração real: PostgreSQL 17.6, Spring Session JDBC e SMTP Mailpit reais locais. O E2E confirmou setup, confirmação, login inválido/válido, reset, revogação, token reutilizado e logout global. Gmail real confirmou entrega e consumo dos links de confirmação e recuperação; o reset terminou com 0 sessões residuais.
Limitações e cenários não executados: convite Gmail pertence à H01.3. npm global do host continua quebrado; CLIs locais do lockfile executaram testes/build/E2E. A rede local usa inspeção TLS Avast; sua CA pública foi adicionada somente à imagem descartável do ensaio, sem desativar validação.
Documentação atualizada: README.md, docs/openapi.yaml, docs/decisoes-pendentes.md, docs/evidencias/H01.1.md, docs/evidencias/H01.2.md e este arquivo.
Condição de conclusão ou desbloqueio: satisfeita em 24/09/2026 com gates locais e entrega Gmail real de confirmação/recuperação.
Próximo passo: H01.3 — convidar e aceitar o segundo membro, incluindo a validação do convite Gmail restante em P04.
```

## Registro H01.3

```text
História / objetivo: H01.3 — Convidar e aceitar o segundo membro.
Estado / responsável / data: Concluído / desenvolvimento + Diego na validação Gmail / 24/09/2026.
Requisitos e critérios de aceite relacionados: RF-ACC-05 a RF-ACC-07 e RF-ACC-11; D10/D12; administrador autorizado; email destinatário; sete dias; uso único; reenvio/revogação; máximo de dois membros; acesso GUEST ao mesmo espaço.
Decisões e pendências aplicáveis: T06 registrada. P04 encerrada após entrega e aceite Gmail real; Mailpit continua tratado apenas como captura local.
Branch / commit / PR: branch local `h01-3-invite-member`, base no commit local H01.2 `985cfd5`; sem publicação, deploy ou PR nesta execução.
Arquivos e comportamento alterados: identity em aplicação/portas/infra/API; segurança; SMTP; V4; telas Angular de gestão/aceite; serviço frontend; E2E; OpenAPI, README, decisões e evidência H01.3.
Migrações e impacto sobre dados: V4 cria space_invitations com FKs, hash único e um convite ativo por espaço. V1–V4 foram aplicadas somente em PostgreSQL 17.6 efêmero/Testcontainers e Compose descartável; nenhum banco compartilhado foi alterado.
Testes executados: 52 unitários/HTTP/ArchUnit aprovados, 0 ignorados/falhas/erros; 7 ITs Failsafe aprovados, 0 ignorados/falhas/erros (3 específicos de convite); 17 Vitest; build Angular; 1 E2E full-stack aprovado. PostgreSQL `postgres:17.6-alpine`, Flyway V1–V4 e BUILD SUCCESS.
Cobertura e mutação aplicáveis: JaCoCo 326/348 linhas (93,68%) e 112/140 branches (80%) em domínio/aplicação; PIT 78% (102/130), 91% de cobertura das linhas mutadas e força 87%; gates mantidos.
Validação manual / integração real: Docker Desktop no contexto desktop-linux; fluxo local entre Chrome, Nginx, backend, PostgreSQL e Mailpit confirmou reenvio, link antigo recusado, aceite, mesmo espaço, papel GUEST e bloqueio de gestão. Em composição descartável separada, Gmail SMTP/STARTTLS entregou o convite e o destinatário o aceitou; PostgreSQL confirmou 1 convite consumido, 2 membros ativos, 1 GUEST confirmado e 1 espaço.
Limitações e cenários não executados: nenhuma limitação material restante para H01.3. O npm global do host continua inconsistente; o CLI local do lockfile executou testes/build/E2E. A credencial, a CA exportada, contêineres e volumes descartáveis foram removidos após o ensaio Gmail.
Documentação atualizada: README.md, docs/openapi.yaml, docs/decisoes-pendentes.md, docs/evidencias/H01.3.md e este arquivo.
Condição de conclusão ou desbloqueio: satisfeita em 24/09/2026 com gates locais, PostgreSQL/Mailpit e entrega/aceite Gmail real.
Próximo passo: H01.4 — aplicar papéis e gerenciar saída.
```

## Registro H01.4

```text
História / objetivo: H01.4 — Aplicar papéis e gerenciar saída.
Estado / responsável / data: Concluído / desenvolvimento / 25/09/2026.
Requisitos e critérios de aceite relacionados: RF-ACC-08 a RF-ACC-11; matriz de permissões; CA-02/CA-03; autorização no backend, remoção/saída, transferência, histórico, vaga e sessões.
Decisões e pendências aplicáveis: T07 registrada. P09 permanece aberta para encerramento/exclusão definitiva. E03/E04/E08 revalidarão responsabilidades, anexos e consentimento WhatsApp quando seus módulos existirem.
Branch / commit / PR: branch `h01-4-membership-lifecycle`, base `02310b8`; entrega registrada no Git ao final da execução, sem publicação de imagens, deploy ou PR.
Arquivos e comportamento alterados: identity em aplicação/portas/infra/API; V5; tela Angular de membros; serviço frontend; E2E; OpenAPI, README, decisões e evidência H01.4.
Migrações e impacto sobre dados: V5 adiciona encerramento auditável à associação e eventos de ciclo de membro. V1–V5 aplicadas em PostgreSQL 17.6 efêmero/Testcontainers e Compose descartável; nenhuma migração anterior foi editada e nenhum banco compartilhado foi alterado.
Testes executados: 65 unitários/HTTP/ArchUnit; 10 ITs Failsafe (3 específicos H01.4); 20 Vitest; build Angular; 1 E2E full-stack. Todos finais aprovados, sem ignorados/falhas/erros. PostgreSQL `postgres:17.6-alpine`, Flyway V1–V5 e BUILD SUCCESS.
Cobertura e mutação aplicáveis: JaCoCo 393/414 linhas (94,93%) e 124/154 branches (80,52%); PIT 80% (126/157), 93% das linhas mutadas cobertas e força 88%; gates mantidos.
Validação manual / integração real: E2E Chromium em Nginx/backend/PostgreSQL/Mailpit comprovou transferência nos dois sentidos, atualização de permissões, saída, sessão revogada e vaga para novo convite. Mailpit foi apenas captura local; H01.4 não exigiu novo envio externo.
Limitações e cenários não executados: despesas/responsabilidades/anexos/WhatsApp ainda não existem; somente identidade histórica e contrato de eventos foram concluídos. CA-02/CA-03 continuam parcialmente em validação nesses efeitos transversais. Encerramento do espaço/exclusão não foi implementado.
Documentação atualizada: README.md, docs/openapi.yaml, docs/decisoes-pendentes.md, docs/evidencias/H01.4.md e este arquivo.
Condição de conclusão ou desbloqueio: H01.4 e a demonstração própria do E01 satisfeitas em 25/09/2026; integrações futuras explicitamente diferidas conforme backlog.
Próximo passo: H02.1 — cadastrar e listar uma despesa avulsa.
```

## Registro H02.1

```text
História / objetivo: H02.1 — Cadastrar e listar uma despesa avulsa.
Estado / responsável / data: Concluído / desenvolvimento / 25/09/2026.
Requisitos e critérios de aceite relacionados: RF-DES-01, parcela inicial de RF-DES-10, RF-WEB-04 a RF-WEB-06, D16–D18 e CA-02/CA-04; cadastro pendente/pago, autorização, isolamento, paginação, ordenação e idempotência.
Decisões e pendências aplicáveis: T08 registrada. P08 permanece aberta somente para retenção/limpeza operacional da idempotência; a proteção durável atual está ativa. Categoria/responsável seguem para E03 e quitação posterior para H02.2.
Branch / commit / PR: branch `h02-1-single-expense`, base `b2c8497`; entrega registrada no Git ao final da execução, sem publicação de imagens, deploy ou PR.
Arquivos e comportamento alterados: novo módulo expenses nas quatro camadas; V6; rota/tela Angular `/despesas`; integração na navegação e E2E; OpenAPI, README, decisões e evidência H02.1.
Migrações e impacto sobre dados: V6 cria despesas avulsas e reservas/resultados de idempotência, com NUMERIC, versão, auditoria e constraints. V1–V6 aplicadas em PostgreSQL 17.6 efêmero/Testcontainers e Compose descartável; nenhuma migração anterior foi editada e nenhum banco compartilhado foi alterado.
Testes executados: 77 unitários/HTTP/ArchUnit; 14 ITs Failsafe (4 específicos H02.1); 27 Vitest; build Angular; 1 E2E full-stack. Todos finais aprovados, sem ignorados/falhas/erros. PostgreSQL `postgres:17.6-alpine`, Flyway V1–V6 e BUILD SUCCESS.
Cobertura e mutação aplicáveis: JaCoCo 516/541 linhas (95,38%) e 182/216 branches (84,26%); PIT 80% (163/203), 94% das linhas mutadas cobertas e força 87%; gates mantidos.
Validação manual / integração real: E2E Chromium em Nginx/backend/PostgreSQL/Mailpit comprovou administrador criando pendente vencida, convidado lendo-a e criando lançamento pago. Mailpit foi apenas captura local; H02.1 não introduziu entrega externa.
Limitações e cenários não executados: quitação posterior, correção/histórico, cancelamento, lote, categorias e responsáveis seguem nas histórias definidas. Android/Edge reais continuam no aceite transversal. Retenção de idempotência permanece em P08.
Documentação atualizada: README.md, docs/openapi.yaml, docs/decisoes-pendentes.md, docs/evidencias/H02.1.md e este arquivo.
Condição de conclusão ou desbloqueio: critérios executáveis da H02.1 satisfeitos em 25/09/2026, sem antecipar H02.2.
Próximo passo: H02.2 — quitar e identificar quem pagou.
```

## Registro H02.2

```text
História / objetivo: H02.2 — Quitar e identificar quem pagou.
Estado / responsável / data: Concluído / desenvolvimento / 25/09/2026.
Requisitos e critérios de aceite relacionados: RF-DES-02, RF-DES-10, D16–D18 e CA-02/CA-04; quitação integral, valor/data efetivos, pagador, autor, autorização, idempotência, conflito e atomicidade.
Decisões e pendências aplicáveis: T09 registrada. P08 permanece aberta somente para retenção operacional da idempotência. Dashboard, fechamento mensal e notificações continuam E06/E07/E08; estorno/cancelamento/lote continuam H02.4/H02.5.
Branch / commit / PR: branch `h02-2-settle-expense`, base `488e48e`; entrega registrada no Git ao final da execução, sem publicação de imagens, deploy ou PR.
Arquivos e comportamento alterados: módulo expenses nas quatro camadas; contrato público de membros ativos; V7; quitação e criação paga na tela `/despesas`; E2E; OpenAPI, README, decisões e evidência H02.2.
Migrações e impacto sobre dados: V7 adiciona valor/data/pagador/autor/observação de pagamento e `expense_payment_events`, incluindo backfill auditável dos lançamentos pagos preexistentes. V1–V7 aplicadas em PostgreSQL 17.6 efêmero/Testcontainers e Compose descartável; nenhuma migração anterior foi editada e nenhum banco compartilhado foi alterado.
Testes executados: 81 unitários/HTTP/ArchUnit; 19 ITs Failsafe (9 específicos de despesas); 30 Vitest; build Angular; 1 E2E full-stack. Todos finais aprovados, sem ignorados/falhas/erros. PostgreSQL `postgres:17.6-alpine`, Flyway V1–V7 e BUILD SUCCESS.
Cobertura e mutação aplicáveis: JaCoCo 562/588 linhas (95,58%) e 232/272 branches (85,29%); PIT 80% (183/229), 94% das linhas mutadas cobertas e força 86%; gates mantidos.
Validação manual / integração real: E2E Chromium em Nginx/backend/PostgreSQL/Mailpit comprovou convidado quitando despesa criada pelo administrador, com valor efetivo diferente, administrador como pagador e convidado como autor; também criou despesa já paga pelas mesmas regras e regrediu E01/H02.1. Mailpit permaneceu apenas captura local e H02.2 não introduziu envio de email.
Limitações e cenários não executados: dashboard/relatórios, fechamento mensal, notificações e responsabilidades futuras não existem e serão validados em seus épicos. Estorno, cancelamento, lote, juros/descontos estruturados, pagamento parcial e múltiplos pagadores não foram antecipados. Android/Edge reais continuam no aceite transversal.
Documentação atualizada: README.md, docs/openapi.yaml, docs/decisoes-pendentes.md, docs/evidencias/H02.2.md e este arquivo.
Condição de conclusão ou desbloqueio: critérios executáveis da H02.2 satisfeitos em 25/09/2026, sem antecipar H02.3.
Próximo passo: H02.3 — corrigir com proteção contra conflito.
```

## Registro H02.3

```text
História / objetivo: H02.3 — Corrigir com proteção contra conflito.
Estado / responsável / data: Concluído / desenvolvimento / 25/09/2026.
Requisitos e critérios de aceite relacionados: RF-DES-03, D16–D18 e CA-02/CA-04; correção por estado, autorização, versão otimista, idempotência, auditoria antes/depois, atomicidade e conflito revisável.
Decisões e pendências aplicáveis: T10 registrada. P08 permanece aberta somente para retenção operacional da idempotência. Relatórios, fechamento e notificações serão validados em E06–E08; reversão/cancelamento continuam H02.4.
Branch / commit / PR: branch `h02-3-correct-expense`, base H02.2 `b992ec5`; entrega ainda não publicada, implantada ou aberta em PR.
Arquivos e comportamento alterados: módulo expenses nas quatro camadas; V8; GET/PUT de despesa; formulário de correção pendente/paga e revisão explícita de conflito; E2E; OpenAPI, README, decisões e evidência H02.3.
Migrações e impacto sobre dados: V8 cria somente o histórico `expense_correction_events`; nenhuma migração aplicada foi editada. V1–V8 foram aplicadas em PostgreSQL 17.6 efêmero/Testcontainers e no Compose descartável, sem alterar banco compartilhado.
Testes executados: 85 unitários/HTTP/ArchUnit; suíte completa com 23 ITs Failsafe (13 específicos de despesas) e repetição final selecionada com 14 ITs (despesas + Flyway); 33 Vitest; build Angular; 1 E2E full-stack. Todos aprovados, sem ignorados/falhas/erros. PostgreSQL `postgres:17.6-alpine`, Flyway V1–V8 e BUILD SUCCESS.
Cobertura e mutação aplicáveis: JaCoCo 589/615 linhas (95,77%) e 258/304 branches (84,87%); PIT 81% (203/251), 94% das linhas mutadas cobertas e força 86%; gates mantidos.
Validação manual / integração real: E2E Chrome em Nginx/backend/PostgreSQL/Mailpit abriu a mesma despesa em duas páginas, aplicou a primeira correção, recusou a versão antiga, preservou o texto e só reaplicou após revisão explícita. Mailpit permaneceu apenas captura local; H02.3 não envia email.
Limitações e cenários não executados: reflexos futuros em dashboard, fechamento e notificações ainda não existem; H02.4 implementará reversão/cancelamento e seus motivos. Android/Edge reais continuam no aceite transversal. A primeira execução PIT ficou sem progresso no executor e foi interrompida; a repetição isolada com timeout explícito concluiu com sucesso e gerou o relatório usado como evidência.
Documentação atualizada: README.md, docs/openapi.yaml, docs/decisoes-pendentes.md, docs/evidencias/H02.3.md e este arquivo.
Condição de conclusão ou desbloqueio: critérios executáveis da H02.3 satisfeitos em 25/09/2026, sem antecipar H02.4.
Próximo passo: H02.4 — desfazer quitação e cancelar.
```

## Registro H02.4

```text
História / objetivo: H02.4 — Desfazer quitação e cancelar.
Estado / responsável / data: Concluído / desenvolvimento / 25/09/2026.
Requisitos e critérios de aceite relacionados: RF-DES-07 a RF-DES-12, RF-CON-03/04, CA-06/07; motivo, autor, histórico, estado, autorização, versão, idempotência, concorrência e atomicidade.
Decisões e pendências aplicáveis: P10 encerrada: paga sem vencimento exige correção prévia para informar o vencimento antes da reversão, sem derivar a data do pagamento. Relatórios, fechamentos e alertas seguem para E06–E08; anexos para E03.
Branch / commit / PR: branch `h02-4-reverse-cancel-expense`, base H02.3 `fd2f7c3`; sem publicação, deploy ou PR.
Arquivos e comportamento alterados: expenses nas quatro camadas; V9; endpoints de reversão/cancelamento e histórico; confirmações Angular; E2E; OpenAPI, README, decisões e evidência.
Migrações e impacto sobre dados: V9 adiciona estado CANCELLED, metadados/eventos de cancelamento e eventos PAYMENT_REVERSED. V1–V9 aplicadas em PostgreSQL 17.6 efêmero; migrações anteriores não foram alteradas.
Testes executados: 91 unitários/HTTP/ArchUnit; 28 ITs completos (18 de despesas); 37 Vitest; build Angular; 1 E2E full-stack. Finais aprovados sem ignorados/falhas/erros. O E2E final partiu de banco vazio e incluiu bloqueio da reversão sem vencimento, correção e continuidade do fluxo.
Cobertura e mutação aplicáveis: JaCoCo 626/655 linhas (95,57%) e 296/346 branches (85,55%); PIT 228/278 (82%), 94% das linhas mutadas e força 87%; gates mantidos.
Validação manual / integração real: PostgreSQL 17.6/Flyway V1–V9 e E2E Chrome em Nginx/backend/PostgreSQL/Mailpit comprovaram bloquear reversão sem vencimento, corrigir a data, quitar, reverter, quitar novamente, reverter, cancelar, ocultar da lista e consultar todo o histórico.
Limitações e cenários não executados: reflexos futuros em anexos/relatórios/fechamentos/alertas não existem ainda e serão validados em E03/E06–E08. Não há restauração de cancelado, reembolso ou operação em lote nesta história.
Documentação atualizada: README.md, docs/openapi.yaml, docs/decisoes-pendentes.md, docs/evidencias/H02.4.md e este arquivo.
Condição de conclusão ou desbloqueio: critérios executáveis satisfeitos e P10 encerrada em 25/09/2026; H02.4 concluída.
Próximo passo: H02.5 — quitar vários lançamentos, sem iniciá-la nesta execução.
```

## Registro H02.5

```text
História / objetivo: H02.5 — Quitar vários lançamentos.
Estado / responsável / data: Concluído / desenvolvimento / 25/09/2026.
Requisitos e critérios de aceite relacionados: RF-DES-06, RF-DES-10, D16–D19 e CA-02/CA-04; seleção, confirmação, data/pagador comuns, valor integral por item, autorização, atomicidade, versão, idempotência, concorrência e auditoria correlacionada.
Decisões e pendências aplicáveis: T12 registrada. P08 permanece aberta somente para retenção operacional dos registros idempotentes. Relatórios, fechamentos e notificações serão revalidados em E06–E08; pagamento parcial, rateio e múltiplos pagadores não foram introduzidos.
Branch / commit / PR: branch `h02-5-batch-settlement`, base H02.4 `fa6be85`; entrega não publicada, implantada ou aberta em PR.
Arquivos e comportamento alterados: lote no módulo expenses nas quatro camadas; V10; POST `/expenses/batch-payment`; seleção/resumo/confirmação Angular; correlação no histórico; E2E; OpenAPI, README, decisões e evidência H02.5.
Migrações e impacto sobre dados: V10 cria operação/itens de lote e adiciona correlação opcional ao evento individual de pagamento; V1–V10 aplicadas em PostgreSQL 17.6 efêmero. Nenhuma migração anterior foi editada e nenhum banco compartilhado foi alterado.
Testes executados: 97 unitários/HTTP/ArchUnit; 34 ITs Failsafe (24 específicos de despesas); 40 Vitest; build Angular; 1 E2E full-stack. Todos aprovados, sem ignorados/falhas/erros. PostgreSQL `postgres:17.6-alpine`, Flyway V1–V10 e BUILD SUCCESS.
Cobertura e mutação aplicáveis: JaCoCo 672/702 linhas (95,73%) e 322/376 branches (85,64%); PIT 83% (247/298), 94% das linhas mutadas cobertas e força 87%; gates mantidos. A recusa de cobrança não confirmada foi validada no domínio; o cenário persistido depende da futura H04.4 porque a V6 exige confirmação em despesas avulsas.
Validação manual / integração real: Testcontainers comprovou lote integral, estados/versões/espaço, replay/conflito de chave, locks concorrentes, disputas com quitação/correção/cancelamento e rollback forçado. E2E Chrome em Nginx/backend/PostgreSQL/Mailpit rejeitou integralmente uma seleção desatualizada, preservou o formulário e depois quitou os dois itens em uma única chamada.
Limitações e cenários futuros: reflexos de dashboard, fechamento e notificação ainda não existem e serão verificados em E06–E08. Mailpit foi apenas captura local; H02.5 não envia email. Android/Edge reais continuam no aceite transversal. Não há limite numérico aprovado além do lote não vazio; nenhum teto arbitrário foi criado.
Documentação atualizada: README.md, docs/openapi.yaml, docs/decisoes-pendentes.md, docs/evidencias/H02.5.md e este arquivo.
Condição de conclusão ou desbloqueio: critérios executáveis da H02.5 e o escopo próprio do E02 satisfeitos em 25/09/2026; integrações futuras explicitamente diferidas conforme backlog.
Próximo passo: H03.1 — gerenciar categorias.
```

## Registro H03.1

```text
História / objetivo: H03.1 — Gerenciar categorias.
Estado / responsável / data: Concluído / desenvolvimento / 25/09/2026.
Requisitos e critérios de aceite relacionados: RF-ORG-01, D16–D18, CA-02/CA-04; categorias iniciais, nome/duplicidade, ambos os papéis, associação opcional, arquivamento sem perda, versão, isolamento, auditoria e concorrência.
Decisões e pendências aplicáveis: T13 registrada. Comparação usa trim + minúsculas e preserva acentos. Recorrências, relatórios, fechamentos e IA serão integrados em E04/E06/E07/E10, sem antecipação.
Branch / commit / PR: branch `h03-1-manage-categories`, base H02.5 `2197873`; entrega não publicada, implantada ou aberta em PR.
Arquivos e comportamento alterados: módulo expenses nas quatro camadas; V11; `/categories`; categoria opcional no cadastro/correção/leitura; tela `/categorias`; seletores e histórico de correção; E2E; OpenAPI, README, decisões e evidência H03.1.
Migrações e impacto sobre dados: V11 cria categorias/eventos, semeia sete categorias para espaços existentes e futuros, adiciona FK opcional à despesa e campos históricos à correção. Despesas existentes permanecem sem categoria; V1–V10 não foram editadas.
Testes executados: 100 unitários/HTTP/ArchUnit; 36 ITs Failsafe completos (26 de despesas) e repetição final selecionada de 27; 42 Vitest; build Angular; 1 E2E full-stack. Todos aprovados, sem ignorados/falhas/erros. PostgreSQL `postgres:17.6-alpine`, Flyway V1–V11 e BUILD SUCCESS.
Cobertura e mutação aplicáveis: JaCoCo 708/741 linhas (95,55%) e 340/396 branches (85,86%); PIT 260/322 (81%), 93% das linhas mutadas e força 86%; gates mantidos.
Validação manual / integração real: Testcontainers comprovou seeds, ambos os papéis, isolamento, duplicidade concorrente, versão e preservação após arquivo. E2E Chrome em Nginx/backend/PostgreSQL/Mailpit criou Pets, associou a Energia, renomeou para Casa por convidado, arquivou e confirmou que a despesa preservou Casa enquanto novas associações foram bloqueadas.
Limitações e cenários futuros: categoria arquivada em recorrência ativa será tratada em E04. Relatórios/fechamentos/IA serão validados em E06/E07/E10. Mailpit foi somente captura local; H03.1 não envia email. Android/Edge reais continuam no aceite transversal.
Documentação atualizada: README.md, docs/openapi.yaml, docs/decisoes-pendentes.md, docs/evidencias/H03.1.md e este arquivo.
Condição de conclusão ou desbloqueio: critérios executáveis da H03.1 satisfeitos em 25/09/2026; integrações futuras explicitamente diferidas conforme backlog.
Próximo passo: H03.2 — atribuir responsável e consultar histórico.
```

## Registro H03.2

```text
História / objetivo: H03.2 — Atribuir responsável e consultar histórico.
Estado / responsável / data: Concluído / desenvolvimento / 25/09/2026.
Requisitos e critérios de aceite relacionados: RF-ACC-09, RF-CON-04, RF-ALT-01, CA-02/CA-03/CA-04; responsabilidade opcional, elegibilidade, distinção de pagador/autor, saída, histórico autorizado/paginado e fuso.
Decisões e pendências aplicáveis: T14 registrada. O aviso ao administrador após liberação da responsabilidade depende do módulo de notificações de E08; a evidência durável foi preparada sem simular entrega. Recorrências, parcelas, filtros, CSV e alertas serão integrados em E04–E06/E08.
Branch / commit / PR: branch `h03-2-responsible-history`, base H03.1 `cbda94a`; entrega não publicada, implantada ou aberta em PR.
Arquivos e comportamento alterados: módulo expenses nas quatro camadas; contrato público de saída no identity; V12; responsável opcional no cadastro/correção/leitura; GET paginado `/expenses/{id}/history`; limpeza transacional na saída; interface, E2E, OpenAPI, README, decisões e evidência H03.2.
Migrações e impacto sobre dados: V12 adiciona FK anulável e campos históricos anterior/posterior. Despesas existentes permanecem sem responsável; V1–V11 não foram editadas. Nenhum autor/evento retroativo foi inventado.
Testes executados: regressão final com 101 unitários/HTTP/ArchUnit; 32 ITs selecionados (28 despesas, 3 associação, 1 Flyway); 44 Vitest; build Angular; 1 E2E full-stack. Finais aprovados sem ignorados/falhas/erros. PostgreSQL `postgres:17.6-alpine`, Flyway V1–V12 e BUILD SUCCESS.
Cobertura e mutação aplicáveis: JaCoCo 743/779 linhas (95,38%) e 352/414 branches (85,02%); PIT 262/348 (75%), 91% das linhas mutadas cobertas e força 85%; gates mantidos.
Validação manual / integração real: Testcontainers comprovou atribuição/troca/remoção, membro inelegível ou alheio, isolamento, paginação, eventos de todo o ciclo e limpeza na saída preservando histórico. E2E Chrome em stack limpa atribuiu ao convidado, exibiu a mudança no histórico e manteve a regressão completa.
Falhas de validação encontradas e corrigidas: a primeira execução PostgreSQL tentou bloquear o lado anulável de um LEFT JOIN e terminou com 27 testes de despesas e Flyway aprovados, porém 1 falha e 2 erros de associação. O lock passou a `FOR UPDATE OF e`. O novo teste concorrente também revelou que sua simulação direta de saída omitia os campos obrigatórios do contrato H01.4; o arranjo foi corrigido. A execução final gerou relatórios novos e aprovou 32/32. Execuções falhas não foram tratadas como aprovação.
Limitações e cenários futuros: o aviso interno/WhatsApp ao administrador ainda não existe e será validado em E08; associação em recorrências/parcelas, filtros/CSV e alertas pertencem a E04–E06/E08. Mailpit foi apenas infraestrutura local; H03.2 não envia email. Android/Edge reais continuam no aceite transversal.
Documentação atualizada: README.md, docs/openapi.yaml, docs/decisoes-pendentes.md, docs/evidencias/H03.2.md e este arquivo.

## H03.3 — Anexar e acessar documentos (em validação)

Implementação iniciada em 26/09/2026: migração V13, volume privado, staging/promoção/compensação, validação efetiva de PDF/JPG/PNG, limite de cinco/10 MB, idempotência, autorização por espaço, download seguro, remoção lógica auditada e interface integrada ao detalhe. A primeira regressão revelou dependência ausente apenas nos slices HTTP e mocks antigos do frontend; os ajustes foram aplicados e a repetição dos testes permanece obrigatória antes de concluir a história.

## H03.4 — Buscar e filtrar lançamentos (concluída)

Implementação iniciada em 27/09/2026: busca na descrição; período inclusivo por vencimento/referência ou pagamento; filtros de categoria, responsável, pagador e situação; mês atual e cancelados ocultos por padrão; atraso no fuso do espaço; paginação/contagem autorizadas no PostgreSQL; ordenação permitida e determinística; índices V14; interface com limpeza, estado vazio contextual, proteção contra respostas fora de ordem e seleção de lote preservada. Categorias arquivadas e pessoas históricas permanecem filtráveis sem restaurar associação.

Validações finais: 102 testes Java/HTTP/ArchUnit, 46 testes frontend, build Angular e 32 ITs selecionados (31 despesas e 1 Flyway) em PostgreSQL 17.6 com V1–V14, todos sem ignorados, falhas ou erros. JaCoCo domínio/aplicação: 771/810 linhas (95,19%) e 373/442 branches (84,39%). PIT: 270/365 mutações eliminadas (74%), força 85%. O E2E full-stack em Chrome passou 1/1 desde banco vazio, incluindo pesquisa sem resultado e limpeza. A stack descartável foi removida depois do teste.

Falhas encontradas e corrigidas, sem contabilizá-las como aprovação: escape SQL inválido; fixture de saída sem o motivo persistido `ADMIN_REMOVAL`; seletores E2E ambíguos após a inclusão dos filtros; lançamentos de regressão fora do mês padrão; e volume privado sem propriedade para o UID não-root no Compose. O Dockerfile prepara o mountpoint e o passo de migração recebe o mesmo volume privado.

Próximo passo: concluir a validação específica de H03.3. H03.4 está concluída, mas E03 ainda não, pois upload/download/remoção de anexo não foi exercitado no E2E próprio e a evidência H03.3 permanece em validação.
Condição de conclusão ou desbloqueio: critérios executáveis da H03.2 satisfeitos em 25/09/2026; integrações futuras explicitamente diferidas conforme backlog.
Próximo passo: H03.3 — anexar e acessar documentos.
```

## Registro de release: modelo

```text
Versão / tag / commit de main / data:
Workflow e gates:
Backend: referência e digest:
Frontend: referência e digest:
Migrações incluídas e compatibilidade de rollback:
Backup anterior: ID, data, integridade e localização lógica:
Migração executada: resultado:
Deploy autorizado e executado: ambiente e resultado:
Smoke e validação de integrações:
Pendências conhecidas:
Procedimento de retorno e evidência:
```

Publicação de uma imagem isolada não fecha a release. Uma restauração deve registrar a cópia efetivamente utilizada e o intervalo de dados perdido; PC desligado pode ampliar o RPO além de 24 horas.

## Histórico desta entrega

| Data | Registro |
|---|---|
| 23/09/2026 | Quatro documentos técnicos preparados. Conferência documental de IDs e inventário do backlog; nenhuma execução de código do produto. |
| 23/09/2026 | PREP-01 iniciado. Leitura integral de `AGENTS.md`, PRD, backlog, especificação, decisões e progresso; inventário confirmou apenas documentação e ausência de `.git`. Ambiente: Java/Javac 23.0.1, Maven 3.9.9, Node 24.18.0, Docker CLI 28.5.1 e Compose 2.40.3; `npm --version` falhou por instalação global inconsistente e Docker Engine não estava acessível. |
| 23/09/2026 | PREP-01: Maven compilou para Java 21 no JDK 23; 8 testes/ArchUnit e JaCoCo passaram. PIT inicialmente reprovou cobertura de 71%, o desenho/teste foi corrigido e o gate passou com 4/4 mutações eliminadas e 100% de cobertura do código mutado. Flyway/Testcontainers não executou sem Docker. |
| 23/09/2026 | PREP-01 frontend: dependências atualizadas até `npm audit` informar 0 vulnerabilidades; 2 testes de componente e build passaram. A asserção E2E passou (1 teste); no Windows, `ng serve` não encerrou automaticamente e precisou ser interrompido. |
| 23/09/2026 | PREP-02 a PREP-04 preparadas: estrutura modular, wrappers/lockfile, Compose local/produção, Dockerfiles, CI/release, README, scripts e guia operacional. Nenhuma imagem foi publicada e nenhum deploy, backup ou restauração foi executado. Próxima história recomendada: H01.1, após fechar P00 em JDK 21/Docker/CI. |
| 23/09/2026 | Verificações finais: Maven Wrapper 3.9.16 validou seu SHA-256; três arquivos Compose passaram em `config --quiet`; scripts Bash/PowerShell passaram na análise sintática; busca no código versionável não encontrou credenciais reais. |
| 23/09/2026 | `mvnw verify` completo descobriu `FlywayPostgresIT`, mas reprovou antes de iniciar PostgreSQL: havia entradas Python malformadas no `PATH` e Testcontainers não encontrou Docker a partir do sandbox. A configuração global não foi alterada; em 24/09 confirmou-se que o Docker Desktop estava disponível fora do isolamento. |
| 23/09/2026 | H01.1 iniciada após revisão da preparação e dos contratos. PREP permanece em validação apenas por dependências ambientais independentes; nenhuma pendência externa de provedor é necessária para criar administrador/espaço. Diretório continua sem `.git`. |
| 23/09/2026 | H01.1 implementada nas quatro camadas e na interface: segredo temporário no header, CSRF, validações, BCrypt, V2, criação transacional e bloqueio singleton persistente. Compose de produção usa Docker secret; login continua corretamente reservado a H01.2. |
| 23/09/2026 | H01.1: 22 testes Java, 5 testes frontend, build, smoke Playwright, ArchUnit, JaCoCo 97,27% linhas/94,23% branches e PIT 90% aprovados. Os três Compose passaram em `config --quiet`. O smoke usa interceptação explícita e o servidor foi interrompido após o teste verde porque não encerrou sozinho no Windows. |
| 23/09/2026 | `mvn verify` alcançou `InitialSetupPostgresIT` e `FlywayPostgresIT`; após isolar o PATH corrompido, o sandbox ainda não ofereceu um ambiente Docker válido. Nenhum teste foi declarado aprovado; ambos foram executados com sucesso fora do sandbox em 24/09. |
| 24/09/2026 | Revisão de H01.1 adicionou `/api/v1/identity/me`, consulta da associação ativa por email normalizado e autorização obrigatória, sem antecipar login/sessão de H01.2. A consulta também foi adicionada ao teste PostgreSQL pendente. |
| 24/09/2026 | Gates reexecutados após a revisão: 25 testes Java, JaCoCo 100% linhas/94,23% branches e PIT 90,32% (28/31) aprovados. O `verify` dentro do sandbox alcançou os ITs, mas não pôde acessar o named pipe; diagnóstico posterior confirmou o daemon disponível em `desktop-linux`. |
| 24/09/2026 | Maven Wrapper corrigido para Windows quando `~/.m2` é um diretório normal (`Target` nulo). `mvnw.cmd -version`, `mvnw.cmd test` e `mvnw.cmd verify -DskipITs` passaram; os comandos do README voltaram a ser reproduzíveis. |
| 24/09/2026 | Contrato OpenAPI 3.1 criado para os endpoints efetivamente implementados em H01.1, incluindo segredo de setup, CSRF, sessão, schemas e erros aplicáveis. Nenhum endpoint futuro foi inventado. |
| 24/09/2026 | Diagnóstico fora do sandbox confirmou Docker Desktop 4.50.0/Engine 28.5.1, cliente Windows no contexto `desktop-linux` e servidor Linux/amd64 sobre WSL2. Dentro do sandbox, `.docker/config.json` e o named pipe retornam `Access is denied`; a indisponibilidade anterior era do isolamento. |
| 24/09/2026 | Novo `backend/scripts/run-integration-tests.ps1` validado: no sandbox preservou o erro Docker e saiu 1 antes do Maven; pela execução oficial autorizada gerou relatórios novos e aprovou 25 unitários e 2 ITs (0 ignorados/falhas/erros), PostgreSQL 17.6 `postgres:17.6-alpine`, V1/V2 e `BUILD SUCCESS`. |
| 24/09/2026 | GitHub Actions `36038781176` do commit `737f46a` aprovou backend/Testcontainers/JaCoCo/PIT em Temurin 21 e frontend/audit/build/smoke em Node 24.18.0. O E2E full-stack local repetido desde banco vazio encerrou H01.1 e P00. |
| 24/09/2026 | H01.2 implementou confirmação, login, sessão JDBC, logout, recuperação, tokens de uso único, expiração e revogação. O E2E revelou a modularização do Boot 4: `flyway-core`/`spring-session-jdbc` sem seus starters não ativavam auto-configuração; os starters oficiais foram adotados e a execução limpa passou. |
| 24/09/2026 | Gates finais H01.2: 41 unitários sem skip/falha/erro; 4 ITs Failsafe sem skip/falha/erro em PostgreSQL 17.6 e V1–V3; JaCoCo aprovado; PIT 90%; 11 testes frontend e build aprovados; E2E full-stack 1/1 aprovado com Mailpit. H01.2 permanece em validação exclusivamente por P04/Gmail real. |
| 24/09/2026 | Revisão final da H01.2 passou a exigir `operationId` também nos 401 testados e atualizou o guia operacional para V1–V3 e SMTP Gmail via Docker secret. O primeiro recheck foi bloqueado no sandbox por PKIX; na execução autorizada, uma expectativa nova usou o código incorreto, foi corrigida para `AUTHENTICATION_REQUIRED` e o `verify -DskipITs` repetido terminou com 41 testes, 0 ignorados/falhas/erros, JaCoCo/ArchUnit aprovados e `BUILD SUCCESS`. |
| 24/09/2026 | Validação Gmail real da H01.2 concluída: SMTP AUTH/STARTTLS saudável após confiar, somente na imagem descartável, a CA pública de inspeção Avast já confiada no Windows. Confirmação e recuperação chegaram à caixa externa e foram consumidas; banco confirmou email, tokens consumidos/não revogados e 0 sessões residuais. Segredo não foi versionado nem registrado. H01.2 concluída; convite real segue para H01.3/P04. |
| 24/09/2026 | H01.3 iniciada após leitura integral dos contratos. H01.2 foi preservada no commit local `985cfd5` e a branch `h01-3-invite-member` foi criada. Regras derivadas: aceite cria/confirmar conta nova pelo token entregue ao email; conta confirmada preexistente precisa autenticar com a mesma identidade; vínculo ativo prévio, terceiro membro e substituição implícita de convite são recusados. |
| 24/09/2026 | H01.3 implementada nas quatro camadas e UI com V4, token hash/uso único/sete dias, autorização administrativa, papel GUEST, limite/lock concorrente, reenvio/revogação e recuperação após falha SMTP. Regressão: 52 unitários, 7 ITs PostgreSQL 17.6, JaCoCo 93,68%/80%, PIT 78%, 17 frontend, build e E2E full-stack Mailpit aprovados. História permanece em validação externa somente por P04/Gmail real. |
| 24/09/2026 | Convite Gmail real entregue e aceito com SMTP AUTH/STARTTLS e validação TLS ativa. PostgreSQL descartável confirmou 1 convite consumido, 2 membros ativos, 1 GUEST confirmado e 1 espaço. Credencial, CA exportada, contêineres e volumes foram removidos. P04 e H01.3 concluídas; próximo passo H01.4. |
| 25/09/2026 | H01.4 implementou matriz de papéis, remoção/saída, transferência atômica, revogação JDBC, associação histórica e eventos V5. Regressão final: 65 Java, 10 ITs PostgreSQL, JaCoCo 94,93%/80,52%, PIT 80%, 20 frontend, build e E2E aprovados. H01.4 e o escopo próprio do E01 concluídos; parcelas transversais de CA-02/CA-03 seguem para E02/E03/E04/E08. |
| 25/09/2026 | H02.1 implementou cadastro/listagem avulsa nas quatro camadas e UI, V6, autorização por associação, dinheiro decimal, datas, paginação, isolamento e idempotência concorrente. Regressão final: 77 Java, 14 ITs PostgreSQL, JaCoCo 95,38%/84,26%, PIT 80%, 27 frontend, build e E2E aprovados. H02.1 concluída; próximo passo H02.2. |
| 25/09/2026 | H02.2 implementou quitação integral nas quatro camadas e UI, V7, pagador distinto do autor, valor efetivo, auditoria/rollback, versão e idempotência concorrente. Regressão final: 81 Java, 19 ITs PostgreSQL, JaCoCo 95,58%/85,29%, PIT 80%, 30 frontend, build e E2E aprovados. H02.2 concluída; próximo passo H02.3. |
| 25/09/2026 | H02.3 implementou correções pendentes/pagas nas quatro camadas e UI, V8, histórico antes/depois, versão, idempotência, concorrência/rollback e revisão manual do conflito. Regressão final: 85 Java, 23 ITs PostgreSQL, JaCoCo 95,77%/84,87%, PIT 81%, 33 frontend, build e E2E aprovados. H02.3 concluída; próximo passo H02.4. |
| 25/09/2026 | H02.4 implementou reversão e cancelamento lógico com motivo, versão, idempotência, histórico imutável, concorrência e rollback. P10 foi encerrada exigindo correção prévia do vencimento para paga sem data. Regressão final: 91 Java, 28 ITs PostgreSQL, JaCoCo 95,57%/85,55%, PIT 82%, 37 frontend, build e E2E aprovados. H02.4 concluída; próximo passo H02.5. |
| 25/09/2026 | H03.1 implementou categorias iniciais/opcionais, criação, renomeação e arquivamento pelos dois papéis, integração com despesas, isolamento, versão, concorrência e auditoria. Regressão final: 100 Java, 36 ITs PostgreSQL, JaCoCo 95,55%/85,86%, PIT 81%, 42 frontend, build e E2E aprovados. H03.1 concluída; próximo passo H03.2. |
| 27/09/2026 | H03.4 implementou busca por descrição, período/base de data, categoria, responsável, pagador e situação, com mês atual, atraso no fuso, cancelados ocultos, paginação/contagem autorizadas, ordenação determinística, opções históricas, V14 e proteção de respostas concorrentes. Regressão final: 102 Java, 32 ITs PostgreSQL selecionados, JaCoCo 95,19%/84,39%, PIT 74%/força 85%, 46 frontend, build e 1 E2E full-stack aprovados. H03.4 concluída; E03 aguarda a validação específica de H03.3. |
| 27/09/2026 | H04.1 implementou cadastro/listagem de definições fixa ou estimada, cinco frequências e prévia reutilizável por dia-base, com autorização, categoria/responsável elegíveis, idempotência, auditoria e V15, sem gerar despesas. Regressão final: 110 Java, 3 ITs PostgreSQL selecionados, JaCoCo 95,24%/83,67%, PIT 75%/força 85%, 49 frontend, build e 1 E2E full-stack aprovados. H04.1 concluída; próxima história H04.2. |
| 28/09/2026 | H04.2 implementou geração mensal idempotente com fila PostgreSQL, `SKIP LOCKED`, lease/fencing, retomada após falha, rollback atômico, origem recorrente e distinção fixa/estimada. Regressão final: 111 Java, 38 ITs PostgreSQL 17.6 selecionados, JaCoCo aprovado, PIT 75%/força 86%, 49 frontend, build e 1 E2E full-stack aprovado. H04.2 concluída; próxima história H04.3. |
| 28/09/2026 | H04.3 implementou horizonte somente leitura do mês atual + 12, reconciliação por identidade estável e antecipação confirmada/idempotente usando a materialização da H04.2. Regressão: 111 Java, 9 ITs selecionados e repetição específica de 5 ITs com concorrência em PostgreSQL 17.6/V17, JaCoCo aprovado, PIT 71%/força 86%, 51 frontend, build e 1 E2E full-stack aprovado. H04.3 concluída; próxima história H04.4. |
