# Instruções de desenvolvimento — account_Manager

Este repositório implementa um gerenciador pessoal de despesas. Trabalhe de forma incremental e preserve as decisões do produto. Este arquivo orienta execução; não concede por si só acesso a contas, autorização para publicação ou exclusão de dados reais.

## Ler antes de implementar

1. `docs/prd.md`: regras e critérios de aceite.
2. `docs/epicos-desenvolvimento.md`: histórias, dependências e entregas.
3. `docs/decisoes-pendentes.md`: decisões posteriores aprovadas e dependências externas.
4. `docs/especificacao-tecnica.md`: arquitetura, contratos e procedimentos.
5. `docs/progresso.md`: estado real, próxima história e evidências.

Os dois primeiros documentos já existem e devem acompanhar esta entrega em `docs/`. Decisões posteriores explicitamente registradas prevalecem somente nos pontos alterados. Instruções atuais do usuário têm precedência. Se houver contradição não resolvida que afete comportamento, exponha o ponto antes de implementá-lo; não invente uma regra. Não trate uma proposta de versão como compatibilidade comprovada.

## Modo de trabalho

- Inspecione o código e o estado do Git antes de editar. Preserve alterações do usuário e não sobrescreva trabalho alheio.
- Comece pela preparação técnica, depois uma história por vez respeitando dependências. Agrupamento maior precisa de motivo explícito, sem perder rastreabilidade.
- Atualize progresso no início e ao concluir. Registre arquivos, testes, resultados, limitações e próximo passo.
- Resolva autonomamente escolhas internas reversíveis que não alterem contrato, custo ou produto. Pergunte apenas por lacunas relevantes; avance no que estiver independente delas.
- Não amplie o MVP com receitas, múltiplos clientes, modo offline, microserviços, Redis ou broker.
- Não crie integrações falsas que pareçam reais. Mocks locais/CI devem ser explícitos e proibidos em produção.
- Não declare concluída uma história apenas por compilar. Valide seus critérios, estados de erro e regras de autorização/concorrência pertinentes.
- Não implante, publique ou faça operações destrutivas sem autorização da tarefa/sessão. Não peça novamente o que já estiver autorizado.

## Arquitetura e persistência

- Backend Java/Spring Boot/Maven em `backend/`; Angular/TypeScript/Material em `frontend/`; operação em `deploy/`; documentação em `docs/`.
- Monólito modular. Domínio sem dependência de Spring/JPA; aplicação sem dependência de infraestrutura. Adaptadores implementam portas; DTOs não são entidades.
- Contratos Java públicos entre módulos, sem HTTP interno nem acesso ao repositório JPA de outro módulo. Projeções de leitura para relatórios são exceção documentada.
- PostgreSQL real nos testes de persistência com Testcontainers. Flyway para mudanças de esquema; nunca editar migração já aplicada em ambiente compartilhado.
- Produção não executa atualização automática de esquema. Migração é passo explícito, com backup e interrupção em falha.
- BigDecimal/NUMERIC para dinheiro; API usa strings decimais. Datas de vencimento são LocalDate; eventos técnicos são instantes UTC. Relógio e fuso devem ser controláveis nos testes.
- Lotes são atômicos. Usar versão otimista, idempotência e constraints duráveis; testar repetição/conflito nas operações que podem duplicar cobrança, pagamento ou envio.
- Não reescrever registros pagos/cancelados nem confirmações protegidas ao editar recorrência.
- Jobs usam reservas no banco; chamadas a provedores fora de transações longas. Timeout não prova que um provedor não recebeu a requisição.

## Segurança e dados

- Sessões JDBC no servidor; cookies seguros e CSRF nas operações autenticadas mutáveis. Sem credenciais em localStorage.
- Autorização por usuário/espaço e papel no backend, inclusive para anexos, exportações e caminhos indiretos.
- Segredos somente por configuração apropriada; `.env.example` contém placeholders. Nunca versionar `.env`, banco, backups, documentos pessoais ou tokens.
- Não registrar senha, token, imagem, documento, conteúdo financeiro completo ou URL assinada nos logs.
- Arquivos privados servidos pelo backend. Validar tipo/tamanho e nome seguro; temporários de IA expiram em até 24h e ficam fora do backup.
- Confirmar resultado da IA antes de criar despesa/recorrência/quitação. Cota compartilhada reservada atomicamente; não ativar provedor pago automaticamente.
- Restaurar backup exige revisar sessões, jobs e exclusões; não disparar notificações antigas.

## Testes e gates

Antes da implementação funcional, executar PREP-01: provar uma combinação estável de Java/Spring/JUnit, Flyway/PostgreSQL/Testcontainers, JaCoCo/PIT, ArchUnit e Angular/Material. Fixar versões e registrar evidência. Não remover uma ferramenta obrigatória porque a primeira combinação falhou.

- JaCoCo em domínio/aplicação: linhas ≥80% e branches ≥70%.
- PIT nas regras de domínio/aplicação: mutação ≥70%.
- Exclusões apenas de código estrutural justificável, documentadas; não excluir regras difíceis nem diminuir limites para passar.
- ArchUnit verifica módulos/camadas. Testes de integração usam PostgreSQL, não H2.
- Frontend: testes de componentes e E2E dos fluxos essenciais. PR executa smoke; release executa suíte completa. Instalação/câmera PWA em Android exigem teste real.
- Testes devem verificar comportamento e riscos concretos, não repetir a implementação. Uma alteração documental não requer inventar testes de produto.
- Não apresentar teste não executado como aprovado. Se faltar Docker, credencial ou aparelho, registre exatamente o que ficou sem validação.

Comandos e perfis sugeridos na especificação ainda são contratos a implementar. Inspecione `pom.xml`, scripts npm e Compose para usar comandos existentes. O README final deve conter comandos realmente executados e resultados reproduzíveis.

### Integração PostgreSQL no Windows/Codex

- Quando a tarefa exigir integração com PostgreSQL, use `backend/scripts/run-integration-tests.ps1`. Sem `-Tests`, ele executa toda a suíte Failsafe; para classes específicas, informe nomes exatos em `-Tests`.
- O script resolve caminhos por `$PSScriptRoot`, verifica acesso ao servidor com `docker version`, limpa somente os relatórios Failsafe da nova execução, chama o Maven Wrapper com `verify` e confere relatórios novos. Teste ausente ou ignorado torna a validação incompleta.
- Neste host Windows, o sandbox do Codex pode impedir a leitura de `C:\Users\diego\.docker\config.json` e o acesso ao named pipe do Docker, mesmo com Docker Desktop ativo no contexto `desktop-linux`. Isso é restrição do sandbox, não prova de indisponibilidade do daemon.
- Se esse bloqueio ocorrer, use somente o mecanismo oficial do ambiente para solicitar a execução com as permissões necessárias, quando disponível e permitido. O script não deve autoelevar, desabilitar o sandbox, expor Docker por TCP nem alterar permissões do Docker.
- Se a execução autorizada não estiver disponível, registre a pendência e forneça para execução manual o comando exato documentado no README. Nunca trate teste apenas compilado, descoberto, ausente ou ignorado como aprovado.

## Git, CI e release

Uma branch por história, PR para `main`, sem `develop`. Código e documentação juntos. Não reescrever histórico compartilhado. Proteções e checks devem ser configurados, não apenas citados no README.

Release por tag semântica sobre commit de `main`, após gates. Publicar duas imagens privadas no Docker Hub, backend e frontend, e registrar digests do mesmo commit. Verificar P01 antes de publicar. Não tornar imagens públicas ou mudar registry por conveniência.

A VPS Hostinger executa somente Docker Compose com imagens prontas. Não compilar no servidor. Publicação parcial não é release pronta; não sobrescrever tags para ocultar falha. Banco e backend não ficam expostos à internet.

## Documentação obrigatória

Manter `README.md` com visão geral, pré-requisitos, estrutura, variáveis, execução Windows/Linux, testes/gates, mocks locais e como habilitar integrações reais. Documentar passo a passo:

1. Clonar, configurar ambiente e subir banco local; executar backend/frontend; fluxo inicial do administrador.
2. Executar testes, interpretar relatórios e resolver problemas comuns.
3. Configurar GitHub Actions, segredos e repositórios privados Docker Hub; gerar uma release identificável.
4. Preparar VPS, DNS `contas.malyah.tech`, TLS/renovação, volumes, permissões e login de leitura no registry.
5. Fazer backup consistente, baixar imagens, executar migração, subir versão e verificar saúde/fluxos.
6. Tratar falha de migração e rollback; distinguir rollback de aplicação de restauração do banco.
7. Agendar backup criptografado e download no Windows, validar integridade/retenção e recuperar com a chave externa.
8. Restaurar em ambiente limpo; registrar duração e perda de dados possível com base na última cópia externa válida.
9. Rotacionar segredos, consultar logs e tratar indisponibilidade de Gmail, Meta e Groq.
10. Exportar dados completos e executar exclusão administrativa após definição de P09.

Incluir caminhos, comandos exatos, resultados esperados e diagnóstico de falhas; não deixar “configure o servidor” como instrução suficiente. Não escrever senhas reais em exemplos. Identificar procedimentos ainda não testados.

## Critério de conclusão e resposta

Uma história concluída tem critérios de aceite satisfeitos, testes pertinentes aprovados, gates aplicáveis, documentação atualizada e evidências em `docs/progresso.md`. Integração real pendente impede concluir o trecho que depende dela, mesmo com mock funcionando.

Ao encerrar uma tarefa, informar o resultado, mudanças principais, testes executados, limitações materiais e próximo passo. Não afirmar que o MVP inteiro está pronto quando apenas uma história foi entregue.
