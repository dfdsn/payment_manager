# Progresso de implementação — account_Manager

Versão 1.0 • Atualizado em 24/09/2026.

**Estado atual: H01.1 em validação em 24/09/2026.** Implementação, contexto protegido, 25 unitários, 2 integrações PostgreSQL, frontend, arquitetura, JaCoCo e PIT foram aprovados. A validação PostgreSQL 17.6/concorrência/reinício está concluída; faltam JDK 21/CI e smoke full-stack pela interface. O bloqueio Docker anterior era restrição do sandbox do Codex, não indisponibilidade do Docker Desktop.

## Resumo

| Item | Estado |
|---|---|
| PRD v2.0 e backlog | Documentados anteriormente; acompanhar esta entrega em `docs/`. |
| Especificação, decisões e AGENTS | Preservados e atualizados com a baseline efetiva e seus limites de validação. |
| Histórias | 0 de 46 concluídas; 11 épicos ainda não concluídos. |
| Critérios do PRD | CA-01 a CA-32 não executados. |
| Provedores e infraestrutura | Pendências P00–P09 em `decisoes-pendentes.md`. |
| Próxima ação | Repetir gates em JDK 21/CI e executar smoke full-stack pela interface; depois concluir H01.1 e iniciar H01.2. |

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
| PREP-01 | Provar compatibilidade e fixar versões | Em validação | Testcontainers/PostgreSQL 17.6, unitários, ArchUnit, JaCoCo, PIT, frontend, build, audit e smoke aprovados. JDK 21/CI real pendentes. Evidência: `docs/evidencias/PREP-01.md`. |
| PREP-02 | Estruturar repositório e execução local | Em validação | Wrappers/lockfile, camadas, Flyway, Compose PostgreSQL/Mailpit e comandos criados; V1/V2 aplicadas em PostgreSQL real. Execução full-stack completa ainda pendente. |
| PREP-03 | Configurar CI e gates | Em validação | Workflows de CI/release, relatórios e limites implementados; PIT reprovou e depois aprovou sem reduzir gates. Execução em PR pendente por ausência de Git/remoto. |
| PREP-04 | Preparar operação e documentação inicial | Em validação | Duas imagens, Compose image-only, migration explícita, README, scripts e guia P01–P09 preparados. Docker/VPS/publicação/backup/restauração não executados. |

## Histórias do MVP

Títulos e IDs preservados do backlog. Consulte cada história para critérios completos e dependências. E11 começa cedo, mas só conclui com validação integrada; responsividade, segurança e testes começam na primeira história.

| História | Entrega | Estado | Evidências |
|---|---|---|---|
| H01.1 | Configurar administrador e espaço | Em validação | Fluxo/contexto implementados. 25 unitários e 2 ITs PostgreSQL sem skip/falha/erro, 5 frontend, smoke, JaCoCo 100%/94,23% e PIT 90,32% aprovados. Falta JDK 21/CI e smoke full-stack real. Evidência: `docs/evidencias/H01.1.md`. |
| H01.2 | Entrar, confirmar email e recuperar acesso | Não iniciado | — |
| H01.3 | Convidar e aceitar o segundo membro | Não iniciado | — |
| H01.4 | Aplicar papéis e gerenciar saída | Não iniciado | — |
| H02.1 | Cadastrar e listar uma despesa avulsa | Não iniciado | — |
| H02.2 | Quitar e identificar quem pagou | Não iniciado | — |
| H02.3 | Corrigir com proteção contra conflito | Não iniciado | — |
| H02.4 | Desfazer quitação e cancelar | Não iniciado | — |
| H02.5 | Quitar vários lançamentos | Não iniciado | — |
| H03.1 | Gerenciar categorias | Não iniciado | — |
| H03.2 | Atribuir responsável e consultar histórico | Não iniciado | — |
| H03.3 | Anexar e acessar documentos | Não iniciado | — |
| H03.4 | Buscar e filtrar lançamentos | Não iniciado | — |
| H04.1 | Cadastrar recorrência e calcular calendário | Não iniciado | — |
| H04.2 | Gerar ocorrências sem duplicação | Não iniciado | — |
| H04.3 | Visualizar e antecipar previsões | Não iniciado | — |
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
| CA-01 a CA-32 do PRD | Não executados | Registrar resultado individual, cenário, ambiente e evidência; não inferir aprovação a partir de cobertura. |
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
Estado / responsável / data: Em validação / desenvolvimento / 24/09/2026.
Requisitos e critérios de aceite relacionados: RF-ACC-01; D11; setup único, atômico e persistente; defaults BRL/pt-BR/America/Sao_Paulo; responsividade, validação e proteção CSRF.
Decisões e pendências aplicáveis: T04 registrada. Persistência real aprovada; P00 permanece somente por JDK 21/CI. Nenhuma conta externa é necessária.
Branch / commit / PR: não disponível; o diretório recebido não contém .git.
Arquivos e comportamento alterados: módulo identity em domínio/aplicação/infraestrutura/API; configuração de segurança; UI Angular; Compose/entrypoint; README e guias.
Migrações e impacto sobre dados: V2 cria identity_users, family_spaces, space_memberships, installation_state e tabelas Spring Session. V1/V2 aplicadas em bancos PostgreSQL 17.6 efêmeros do Testcontainers; nenhum dado compartilhado alterado.
Testes executados: 25 JUnit/Mockito/Spring/ArchUnit aprovados; InitialSetupPostgresIT e FlywayPostgresIT aprovados (2/0/0/0); 5 Vitest aprovados; build Angular aprovado; 1 smoke Playwright aprovado com APIs explicitamente interceptadas; 3 Compose válidos.
Cobertura e mutação aplicáveis: JaCoCo 100% linhas e 94,23% branches; PIT 90,32% (28/31), todos acima dos gates.
Validação manual / integração real: persistência/concorrência/reinício executados em PostgreSQL 17.6 real. O smoke Playwright ainda usa interceptação e não representa integração full-stack.
Limitações e cenários não executados: JDK local é 23 compilando release 21; CI/PR e smoke full-stack pela interface não executados. npm audit não repetiu por instalação global quebrada; PREP-01 já o aprovou e dependências não mudaram.
Documentação atualizada: README.md, docs/openapi.yaml, docs/guias/operacao.md, docs/decisoes-pendentes.md, docs/evidencias/H01.1.md e este arquivo.
Condição de conclusão ou desbloqueio: gates em JDK 21/CI e smoke full-stack real de setup. Disputa concorrente e reinício já foram aprovados no IT PostgreSQL.
Próximo passo: concluir JDK 21/CI e smoke full-stack; então H01.2 — entrar, confirmar email e recuperar acesso.
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
