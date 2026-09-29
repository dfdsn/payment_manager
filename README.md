# account_Manager

Gerenciador pessoal de despesas para um administrador e um convidado. A fundação técnica, o E01, o E02 e o E04 estão validados. Além dos fluxos manuais, H04.1–H04.3 cadastram recorrências, calculam o calendário, geram com segurança a ocorrência vigente e permitem visualizar/antecipar previsões. H04.4 permite confirmar o valor real de cobranças variáveis e H04.5 permite alterar “este e os próximos” e encerrar recorrências (veja `docs/progresso.md`). H05.1 cadastra compras parceladas, com cada parcela lançada em Despesas, H05.2 mostra o progresso de cada compra e quita as parcelas selecionadas, e H05.3 altera ou cancela parcelas pendentes preservando as pagas. H06.1 mostra o painel do mês por vencimento (previsto, pago, pendente, atrasado e ajustes) H06.2 mostra os pagamentos do mês pela data efetiva, com pagador, autor e correções, e H06.3 mostra o planejamento do mês atual e dos 12 seguintes, somando lançamentos e previsões sem contar duas vezes.

As regras do produto estão em [`docs/prd.md`](docs/prd.md), a sequência em [`docs/epicos-desenvolvimento.md`](docs/epicos-desenvolvimento.md), as decisões em [`docs/decisoes-pendentes.md`](docs/decisoes-pendentes.md) e a evidência atual em [`docs/progresso.md`](docs/progresso.md).

O contrato HTTP implementado está versionado em [`docs/openapi.yaml`](docs/openapi.yaml). Ele descreve somente endpoints existentes; não antecipa contratos das próximas histórias.

## Stack fixada nesta preparação

| Componente | Versão |
|---|---|
| Java | 21 LTS para build/teste/runtime (o host desta execução tinha apenas 23.0.1; CI usa 21) |
| Spring Boot / Framework | 4.1.1 / 7.0.9 efetivo |
| Maven Wrapper | 3.9.16 |
| JUnit Jupiter | 6.0.3 efetivo pelo BOM do Boot |
| JaCoCo / PIT / plugin PIT JUnit | 0.8.15 / 1.20.5 / 1.2.3 |
| ArchUnit / Testcontainers | 1.4.2 / 2.0.5 |
| PostgreSQL | 17.6 Alpine na preparação; major 17 é o contrato |
| Angular Core/CLI/build | 21.2.24 |
| Angular Material/CDK | 21.2.14 |
| Node / TypeScript / RxJS | 24.18.0 / 5.9.3 / 7.8.2 |
| Vitest / Playwright | 4.1.11 / 1.63.0 |

Material/CDK e Core estão na mesma família 21 LTS; seus patches mais recentes publicados não possuem o mesmo número. O `package-lock.json` fixa toda a árvore. `npm audit` terminou sem vulnerabilidades conhecidas nesta execução.

## Estrutura

```text
backend/             Spring Boot, camadas, migration e testes
frontend/            Angular/Material, teste de componente e E2E
deploy/              Compose local/produção e scripts operacionais
docs/                produto, arquitetura, progresso, evidências e guias
.github/workflows/   CI de PR/main e publicação por tag
```

O backend começa em `com.malyah.accountmanager`. Cada módulo funcional tem `domain`, `application`, `infrastructure` e `api`. O domínio não depende de Spring/JPA/HTTP; a aplicação não depende de adapters. `ArchitectureTest` torna essas fronteiras executáveis. V1–V14 cobrem identidade, despesas e organização; V15 adiciona definições de recorrência, idempotência e auditoria, sem materializar lançamentos; V16–V17 cobrem geração e antecipação; V18 adiciona a auditoria de confirmação de valores variáveis; V19, alteração e encerramento de recorrências; V20, compras parceladas e a origem `INSTALLMENT` das parcelas.

## Pré-requisitos

- Git.
- JDK 21 (Temurin recomendado) e `JAVA_HOME` apontando para ele.
- Docker Desktop/Engine com Compose v2 ativo. Testcontainers também precisa do daemon.
- Node 24.18.x e npm 11.16.x.
- Windows 11/PowerShell 7 ou Linux com shell POSIX.

Verifique:

```powershell
java -version
backend\mvnw.cmd -version
node --version
npm --version
docker version
docker compose version
```

No Linux/macOS, use `./backend/mvnw`. Em rede corporativa com inspeção TLS, instale a CA no trust store apropriado; não use `strict-ssl=false` nem ignore certificados.

## Configuração e execução local habitual

### 1. Banco e email capturado

Na raiz:

```powershell
docker compose -f deploy/compose.dev.yml up -d
docker compose -f deploy/compose.dev.yml ps
```

Resultado esperado: `db` saudável em `127.0.0.1:5432`; Mailpit em [http://localhost:8025](http://localhost:8025). Mailpit é uma simulação local explícita: nenhum email é entregue externamente.

### 2. Migration explícita

PowerShell:

```powershell
$env:APP_MODE='migrate'
$env:SPRING_DATASOURCE_URL='jdbc:postgresql://localhost:5432/account_manager'
$env:SPRING_DATASOURCE_USERNAME='account_manager'
$env:SPRING_DATASOURCE_PASSWORD='local-only-change-me'
$env:APP_SETUP_SECRET='substitua-por-um-segredo-temporario-aleatorio'
Set-Location backend
.\mvnw.cmd spring-boot:run
Set-Location ..
```

Linux:

```bash
cd backend
APP_MODE=migrate \
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/account_manager \
SPRING_DATASOURCE_USERNAME=account_manager \
SPRING_DATASOURCE_PASSWORD=local-only-change-me \
APP_SETUP_SECRET=substitua-por-um-segredo-temporario-aleatorio \
./mvnw spring-boot:run
cd ..
```

Resultado esperado: Flyway aplica V1–V8 (ou informa que estão atuais) e o processo termina. Produção nunca executa migration automaticamente no runtime.

### 3. Backend

No mesmo terminal, altere `APP_MODE` para `runtime` e execute:

```powershell
$env:APP_MODE='runtime'
Set-Location backend
.\mvnw.cmd spring-boot:run
```

Saúde: `http://localhost:8080/api/v1/actuator/health`. Mantenha `APP_SETUP_SECRET` somente até concluir a configuração inicial. A autenticação usa exclusivamente a conta criada; a auto-configuração de usuário/senha do Spring está desabilitada.

### 4. Frontend

Em outro terminal:

```powershell
Set-Location frontend
npm ci
npm start
```

Acesse [http://localhost:4200](http://localhost:4200). O proxy encaminha `/api` ao backend sem criar configuração CORS divergente da produção.

### 5. Stack local em containers

Com Docker ativo:

```powershell
docker compose -f deploy/compose.full-local.yml up --build
```

Acesse `http://localhost:8080`. Essa composição é apenas local, usa build e credenciais locais conhecidas, inclusive o segredo explícito `local-only-setup-secret-change-me`. `compose.prod.yml` não contém `build:`.

### Administração inicial

1. Gere um segredo temporário aleatório e informe-o ao backend em `APP_SETUP_SECRET`. Não o reutilize como senha do administrador.
2. Execute as migrações V1–V8 antes do runtime, conforme os passos anteriores.
3. Abra o frontend, preencha nome, email, senha, nome do espaço e o segredo temporário. A senha deve ter de 12 caracteres a 72 bytes UTF-8 e conter ao menos uma letra e um número.
4. A criação de usuário, espaço, papel `ADMINISTRATOR` e fechamento do setup ocorre em uma única transação. O espaço começa com moeda `BRL`, idioma `pt-BR` e fuso `America/Sao_Paulo`.
5. Ao receber sucesso, remova `APP_SETUP_SECRET` e reinicie o backend. A linha de controle no PostgreSQL mantém o setup fechado mesmo após reinício ou troca do segredo. Uma nova tentativa retorna conflito e não cria registros extras.

O segredo é enviado apenas no header `X-Setup-Secret`, nunca no corpo ou armazenamento do navegador. O endpoint público de estado emite o cookie CSRF e o POST exige o par `XSRF-TOKEN`/`X-XSRF-TOKEN`; o frontend trata isso automaticamente. Sem segredo configurado, o estado informa indisponibilidade e o POST é recusado. O setup não autentica automaticamente: confirme o email e entre pelo fluxo abaixo.

### Confirmar email, entrar e recuperar acesso

1. Em `/confirmar-email`, informe o email do administrador. A resposta é sempre genérica para não revelar contas. No ambiente local, abra Mailpit em `http://localhost:8025` e siga o link recebido; ele expira em 24 horas e deixa de funcionar após uso ou reenvio.
2. Entre em `/entrar`. Somente email confirmado e credenciais válidas criam o cookie `SESSION`, HttpOnly e SameSite=Lax. O contexto protegido `GET /api/v1/identity/me` retorna identidade, papel e espaço; chamadas sem sessão recebem 401.
3. Em `/recuperar-acesso`, solicite o link e abra-o pelo Mailpit. O token expira em 30 minutos, é de uso único e um reenvio invalida o anterior. A nova senha segue a mesma política do setup e o sucesso revoga todas as sessões existentes.
4. “Sair” invalida a sessão atual; “Encerrar todas as sessões” remove todas as sessões JDBC da conta.

As sessões expiram após sete dias sem atividade humana e sempre após 30 dias desde sua criação. Requisições GET só renovam a inatividade quando enviam `X-User-Activity: true`; mutações autenticadas contam como atividade. Nenhuma credencial ou token é armazenado no frontend. Mailpit comprova apenas captura local, não entrega externa.

### Convidar o segundo membro

1. Entre como administrador e abra `/membros`. Informe o email destinatário; a mensagem avisa que todo o histórico do espaço será compartilhado. Somente o administrador pode criar, reenviar ou revogar o convite.
2. No ambiente local, abra Mailpit em `http://localhost:8025` e siga o link. O convite expira em sete dias, só pode ser usado uma vez e pertence ao email destinatário. “Reenviar e substituir link” invalida imediatamente o link anterior.
3. Para um email sem conta, informe nome e senha: a posse do link confirma o email e cria a associação `GUEST`. Uma conta existente já confirmada precisa entrar com exatamente o email convidado antes do aceite; uma conta existente ainda não confirmada é confirmada pelo próprio convite.
4. Após aceitar, entre em `/entrar`. O contexto deve mostrar o mesmo espaço com papel `GUEST`; `/membros` informa que o convidado não pode gerenciar membros.

O espaço aceita no máximo dois membros ativos e cada usuário só pode ter uma associação ativa. O aceite bloqueia o espaço no PostgreSQL e as constraints impedem duplicação mesmo em requisições concorrentes. Se o SMTP falhar depois da gravação, a API informa que o convite foi preservado e a tela oferece reenvio explícito; essa nova tentativa substitui o token anterior. Mailpit é somente captura local. Entrega Gmail real do convite deve ser registrada separadamente, sem expor destinatário, credencial ou token.

### Papéis, transferência e saída

Em `/membros`, ambos veem as associações ativas e o papel atual. O administrador pode remover o convidado ou transferir-lhe a administração; o convidado não acessa essas operações nem por chamada direta à API. A transferência troca os dois papéis atomicamente e não encerra sessões: a autorização é recalculada no banco a cada operação, de modo que sessões abertas recebem imediatamente o novo papel.

O convidado pode usar “Sair deste espaço”. Remoção ou saída marca a associação como inativa, preserva usuário e referências históricas, grava evento de auditoria e remove todas as sessões JDBC desse usuário na mesma transação. A vaga fica disponível para novo convite. O administrador não pode sair enquanto mantiver esse papel: transfira antes. Encerramento do espaço/exclusão definitiva continua fora deste fluxo e depende de P09.

A V5 mantém eventos duráveis `MEMBER_LEFT`, `MEMBER_REMOVED` e `ADMINISTRATION_TRANSFERRED`. E03/E04 usarão esse contrato para retirar responsabilidades futuras sem apagar autoria, e E08 exigirá novo consentimento/número do novo administrador; esses módulos ainda não existem e não são apresentados como integrações já validadas.

### Cadastrar e listar despesa avulsa

1. Entre como administrador ou convidado e abra `/despesas` pelo link “Cadastrar e consultar despesas”. A API sempre deriva o espaço da associação ativa; o cliente não escolhe `spaceId`.
2. Para uma conta pendente, informe descrição, valor, situação `Pendente` e vencimento. Para uma despesa já paga, selecione `Já paga`, informe valor efetivamente pago, data e pessoa pagadora e, opcionalmente, vencimento/observação. O valor pago pode diferir da cobrança, mas a quitação continua integral.
3. Valores aceitam vírgula na interface, mas a API usa string decimal canônica, por exemplo `"150.25"`. A faixa é R$ 0,01 a R$ 99.999.999,99, com até duas casas. Zero e negativos são recusados.
4. A lista mostra `Pendente`, `Atrasada` ou `Paga`, “Sem categoria” e responsável não definido. Atraso usa a data local do espaço: no próprio vencimento ainda não há atraso.
5. Ordene por data de referência, valor ou descrição. A paginação usa 20 itens por padrão e aceita no máximo 100 por requisição.

O frontend cria um `Idempotency-Key` UUID para cada nova intenção e preserva a chave e os campos enquanto a tela continua aberta após falha. Repetir a mesma chave com o mesmo payload retorna o lançamento original; usar a mesma chave com conteúdo diferente retorna 409. Uma chave nova permite cadastrar duas despesas legítimas com dados iguais. Os registros de idempotência permanecem duráveis; a retenção/limpeza operacional continua dentro de P08 e não enfraquece a unicidade.

### Quitar e consultar quem pagou

1. Em `/despesas`, localize uma despesa `Pendente` ou `Atrasada` e use **Quitar despesa**. A tela sugere o valor cobrado, a data local atual e o usuário da sessão como pagador.
2. Corrija o valor efetivamente pago, a data, o membro ativo que pagou e a observação quando necessário. O autor do registro sempre vem da sessão e pode ser diferente do pagador selecionado.
3. Confirme. A lista passa a exibir `Paga`, preserva o valor original da cobrança e mostra valor/data do pagamento, pagador e autor da operação.
4. Repetir a mesma intenção é idempotente. Uma segunda tentativa com chave distinta, versão antiga ou estado já pago retorna conflito e não sobrescreve a quitação existente.

Administrador e convidado ativos podem quitar despesas do próprio espaço. A API não aceita `spaceId`, valida o pagador contra as associações ativas e usa a versão retornada na listagem. Pagamento, mudança de situação, auditoria e resultado idempotente são gravados na mesma transação. Não há pagamento parcial, múltiplos pagadores, estorno, cancelamento ou lote nesta história.

### Corrigir com proteção contra conflito

1. Em `/despesas`, use **Corrigir despesa** no lançamento desejado. A tela mostra a versão e a situação carregadas e preenche os valores atuais.
2. Em uma pendente, podem ser corrigidos descrição, valor cobrado, vencimento e observação. Em uma paga, esses campos continuam editáveis e também podem ser corrigidos valor pago, data do pagamento, pagador ativo e observação do pagamento. A situação não pode ser alterada por este fluxo.
3. Salve a correção. O backend deriva o espaço e o autor da sessão, valida a versão e grava despesa, idempotência e auditoria antes/depois na mesma transação. Não é exigido motivo em H02.3; motivo pertence às futuras reversão e cancelamento.
4. Para testar conflito, abra a mesma despesa em duas abas, edite e salve na primeira e tente salvar na segunda. A segunda recebe conflito, mantém seus campos e mostra os dados atuais. Use **Revisei: usar versão atual mantendo meus campos**, revise novamente e só então salve manualmente; a aplicação nunca força nem reenvia a sobrescrita.

Criador, origem, situação, instante de criação e autoria/data do pagamento original são históricos protegidos. Cada correção grava autor, instante, versões anterior/nova, lista de campos e valores antes/depois em `expense_correction_events`. Reflexos futuros em relatórios, fechamentos e notificações serão validados nos respectivos épicos.

### Desfazer quitação e cancelar

1. Em uma despesa paga com vencimento, use **Desfazer quitação**, leia a consequência, informe o motivo e confirme. A despesa volta a pendente (ou atrasada pela data local); o pagamento anterior e a reversão continuam no histórico.
2. A despesa pode ser quitada novamente: somente a quitação mais recente fica ativa, sem apagar os eventos anteriores.
3. Em uma pendente, use **Cancelar despesa**, informe o motivo e confirme. Ela sai da listagem ativa, mas permanece consultável no detalhe com autor, instante e motivo. Uma paga precisa ser revertida antes.
4. **Ver histórico** mostra quitações, reversões, correções e cancelamento. Não existe exclusão física, reembolso nem restauração de cancelado nesta história.

Administrador e convidado com associação ativa podem executar ambas as operações apenas no próprio espaço. A API exige sessão, CSRF, `Idempotency-Key`, versão carregada e motivo de até 2.000 caracteres. Estado/versão divergente retorna conflito sem sobrescrita; a tela preserva o motivo e consulta os dados atuais. Despesa, evento e idempotência são gravados atomicamente.

Para despesa criada como paga **sem vencimento**, a reversão exige primeiro uma correção que informe o vencimento. Backend e interface recusam a reversão até essa correção; a data do pagamento não é convertida silenciosamente em vencimento. Essa decisão preserva a referência financeira original e garante que toda despesa pendente tenha vencimento.

### Quitar vários lançamentos

1. Em `/despesas`, marque duas ou mais despesas pendentes pelos seletores ao lado da descrição e use **Quitar selecionadas**. Itens pagos e cancelados não são selecionáveis.
2. Revise a quantidade, cada valor e o total; informe a data e o pagador comuns ao lote. O valor pago de cada item será exatamente o valor confirmado de sua cobrança.
3. Marque a confirmação explícita e use **Quitar todos ou nenhum**. A interface envia uma única requisição; não encadeia quitações individuais.
4. Em sucesso, a lista é recarregada e cada lançamento mostra sua quitação. O histórico apresenta o mesmo identificador de operação em lote nos eventos individuais.

Para testar a rejeição integral, abra a confirmação do lote e, em outra aba, corrija, quite ou cancele um dos itens. Ao confirmar na primeira aba, a API responde `409`, mantém seleção, data e pagador para revisão e não altera nenhum item do lote. Recarregue, selecione as versões atuais e confirme uma nova intenção. IDs repetidos, lote vazio, item de outro espaço, estado incompatível, versão antiga ou valor ainda não confirmado também rejeitam tudo. Repetir a mesma chave com conteúdo idêntico retorna o resultado original sem duplicar pagamento/auditoria; conteúdo diferente com a mesma chave conflita.

Administrador e convidado ativos podem executar o lote no próprio espaço. O backend deriva espaço e autor da sessão, valida o pagador ativo, bloqueia os lançamentos em ordem estável e grava operação, pagamentos, versões, auditorias e correlação em uma transação PostgreSQL. Não há limite numérico arbitrário além do lote não vazio, pois os requisitos aprovados não definem outro limite. Pagamento parcial, rateio e múltiplos pagadores não fazem parte deste fluxo.

### Gerenciar categorias

1. Entre como administrador ou convidado e abra `/categorias`. Todo espaço recebe Moradia, Alimentação, Transporte, Saúde, Educação, Lazer e Outros; ambos os papéis podem criar, renomear e arquivar categorias do próprio espaço.
2. O nome é obrigatório, tem até 60 caracteres e é comparado após remover espaços externos e converter para minúsculas. Assim, `Moradia` e ` moradia ` conflitam; acentos continuam significativos.
3. No cadastro ou na correção de `/despesas`, selecione uma categoria ativa ou mantenha **Sem categoria**. O backend deriva o espaço da sessão e rejeita categoria arquivada ou pertencente a outro espaço.
4. Renomear atualiza o nome apresentado pelas despesas vinculadas sem apagar a auditoria da alteração. Arquivar remove a categoria das novas seleções, mas as despesas antigas continuam mostrando a categoria. Não existe exclusão física nem reativação nesta história.

Versões otimistas e constraints PostgreSQL tratam renomeações, arquivamentos e criações concorrentes sem sobrescrita ou duplicidade. Despesas existentes antes da V11 permanecem com categoria nula; nenhuma classificação arbitrária é aplicada. A substituição de uma categoria arquivada em recorrências será validada em E04; relatórios, fechamentos e sugestões de IA serão integrados em seus próprios épicos.

### Atribuir responsável e consultar histórico

1. Em `/despesas`, escolha opcionalmente um **Responsável** ao cadastrar ou use **Corrigir despesa** para atribuir, trocar ou voltar a **Não definido**. Administrador e convidado ativos podem fazer isso; o servidor aceita somente membro ativo do mesmo espaço e exige a versão carregada.
2. Responsável indica quem acompanha a conta. Ele não altera quem pode editar, não é o pagador e não substitui o autor da operação. A quitação continua exibindo separadamente pagador e usuário que a registrou.
3. Use **Ver histórico** para consultar, em páginas de dez eventos, criação, correções, quitações, reversões e cancelamento. Correções mostram valores anterior e posterior disponíveis; autor e instante permanecem associados ao evento original. A interface converte o instante UTC para o fuso configurado no espaço.
4. Se o responsável sair ou for removido, a associação atual é limpa na mesma transação da saída, a versão da despesa avança e o histórico registra quem executou a liberação. Autores e pagadores antigos não são reatribuídos. O aviso ao administrador será entregue pelo módulo de notificações de E08; o evento durável necessário já fica preservado.

Despesas canceladas continuam imutáveis pelo fluxo de correção. A API paginada é `GET /api/v1/expenses/{id}/history?page=0&size=10` (máximo 100) e aplica a mesma autorização por associação/espaço do detalhe. Não são criados autores ou eventos retroativos sem evidência: a criação deriva do próprio registro persistido e as alterações vêm da auditoria existente.

Na VPS, crie `deploy/secrets/setup_secret.txt` com permissão restrita antes do primeiro runtime. O Compose monta o arquivo como Docker secret e o entrypoint exporta seu conteúdo apenas para o processo. Após o primeiro setup, esvazie o conteúdo (mantenha o arquivo-fonte exigido pelo Compose) e recrie o backend; não o coloque em `.env`, logs, comandos compartilhados ou Git.

## Testes e gates

### Backend

No Windows, o ponto de entrada recomendado para integração PostgreSQL é o script abaixo. Ele pode ser invocado por caminho relativo ou absoluto a partir de qualquer diretório, pois resolve o backend pela própria localização. O script verifica o **servidor** Docker, remove somente relatórios Failsafe anteriores, executa `mvnw.cmd verify`, preserva o código do Maven e resume apenas XMLs gerados na execução atual.

Da raiz, para toda a suíte de integração configurada:

```powershell
& .\backend\scripts\run-integration-tests.ps1
```

Somente para os testes de setup, migração e acesso da H01.2:

```powershell
& .\backend\scripts\run-integration-tests.ps1 `
  -Tests InitialSetupPostgresIT,FlywayPostgresIT,AccountAccessPostgresIT
```

Somente para persistência e concorrência dos convites da H01.3:

```powershell
& .\backend\scripts\run-integration-tests.ps1 -Tests InvitationPostgresIT
```

Somente para papéis, saída, revogação e concorrência da H01.4:

```powershell
& .\backend\scripts\run-integration-tests.ps1 -Tests MembershipPostgresIT,FlywayPostgresIT
```

Somente para persistência, isolamento, paginação, idempotência, quitação individual/em lote, correção, reversão e cancelamento do E02:

```powershell
& .\backend\scripts\run-integration-tests.ps1 -Tests ExpensePostgresIT,FlywayPostgresIT
```

De outro diretório, use o caminho absoluto do checkout:

```powershell
& 'C:\CAMINHO\account_Manager\backend\scripts\run-integration-tests.ps1' `
  -Tests InitialSetupPostgresIT,FlywayPostgresIT,AccountAccessPostgresIT
```

O script não solicita elevação nem modifica configuração persistente. Se o sandbox do Codex negar acesso a `.docker/config.json` ou ao named pipe, a execução deve ser solicitada pelo mecanismo oficial de permissões do ambiente. Se isso não for autorizado, execute manualmente um dos comandos acima no PowerShell do usuário. Não exponha Docker por TCP, não altere suas permissões e não desabilite o sandbox globalmente.

No Windows:

```powershell
Set-Location backend
.\mvnw.cmd test
.\mvnw.cmd verify
.\mvnw.cmd -Pmutation verify -DskipITs
```

No Linux:

```bash
cd backend
./mvnw test
./mvnw verify
./mvnw -Pmutation verify -DskipITs
```

- `test`: JUnit/Spring e ArchUnit; não executa classes `*IT`.
- `verify`: inclui os `*IT` com PostgreSQL 17 real via Testcontainers e aplica JaCoCo. Eles verificam V1–V12, identidade, despesas, categorias, responsabilidade e histórico, incluindo lote atômico, quitação/correção/auditoria, conflitos otimistas, idempotência concorrente, isolamento por espaço e constraints duráveis.
- `-Pmutation`: PIT sobre domínio/aplicação. `-DskipITs` evita criar PostgreSQL novamente; não elimina unitários nem gates.
- JaCoCo: linhas ≥80% e branches ≥70% em domínio/aplicação.
- PIT: mutação ≥70% e cobertura de linhas ≥80% no código mutado.

Relatórios:

- JUnit: `backend/target/surefire-reports/` e `backend/target/failsafe-reports/`.
- JaCoCo: `backend/target/site/jacoco/index.html`.
- PIT: `backend/target/pit-reports/index.html`.

Falha de conexão Docker não deve ser contornada com H2. O script exige resposta do servidor a `docker version` e termina com código diferente de zero preservando o erro original. Falha de coverage/mutation exige teste ou desenho melhor; não reduza os limites.

Neste host Windows, o `PATH` contém entradas Python concatenadas. O script ignora entradas inválidas somente no processo filho e preserva Java, Docker, PowerShell e ferramentas do Windows; não altera o `PATH` do usuário/sistema. O sandbox do Codex também pode negar o arquivo de configuração e o named pipe do Docker, embora o Docker Desktop esteja ativo em `desktop-linux`.

### Frontend

```powershell
Set-Location frontend
npm ci
npm audit --audit-level=moderate
npm run test:ci
npm run build
npm run e2e:smoke
npm run e2e:full-stack
npm run e2e
```

`e2e:smoke` usa respostas simuladas pelo próprio Playwright (nunca em produção) e cobre a configuração inicial e a confirmação de valor variável com conflito. `e2e:full-stack` pressupõe `compose.full-local.yml` saudável e banco vazio; ele percorre configuração, confirmação, login, convite e papéis, além de criar/usar/renomear/arquivar categoria, atribuir responsável, consultar histórico, cadastrar/quitar despesas, validar a correção obrigatória antes de reverter uma paga sem vencimento, reverter/cancelar, simular duas edições concorrentes e provar a rejeição integral e o sucesso de um lote pela interface, além de confirmar uma cobrança variável na despesa e numa previsão. Depois valida transferência, saída/revogação e recuperação de senha. O E2E local usa Chrome instalado. A CI instala Chromium fixado pelo Playwright. Relatórios ficam em `frontend/test-results/` e `frontend/playwright-report/`. Instalação PWA/câmera em Android não é simulada e pertence a H09.

## Integrações locais e reais

- Email local: Mailpit, identificável e sem entrega externa. O backend usa SMTP em `SMTP_HOST`/`SMTP_PORT` e `SMTP_FROM`.
- Email real: configure `SMTP_HOST=smtp.gmail.com`, porta 587, autenticação e STARTTLS; injete usuário por variável e senha de app pelo secret `smtp_password`. Não use a senha normal da conta nem registre o valor. P04 comprovou entrega real de confirmação, recuperação e convite; Mailpit continua sendo apenas a captura local reproduzível.
- WhatsApp e IA: nenhum adapter falso foi criado nesta preparação; ficam indisponíveis até suas histórias.
- Produção: `APP_ENVIRONMENT=production` rejeita `APP_INTEGRATIONS_MODE` diferente de `real`.
- Gmail real foi validado em P04; Meta e Groq reais continuam dependentes de P03/P05 e de credenciais fornecidas fora do Git.

Nunca versione `.env`, arquivos em `deploy/secrets/`, banco, backups, anexos, tokens ou imagens pessoais.

## CI, branches e checks

`.github/workflows/ci.yml` executa em PR e em `main`:

1. Java 21, `verify` com Testcontainers/PostgreSQL, JaCoCo, ArchUnit e PIT.
2. Node 24.18, `npm ci`, audit, Vitest, build e smoke Playwright.
3. Upload dos relatórios mesmo em falha.

No GitHub, configure `main` protegida, exija PR e os jobs `backend` e `frontend`, proíba force-push e merge sem checks. A execução CI do commit `737f46a` passou em Java 21/Node 24.18; proteção de branch continua sendo configuração externa a conferir no repositório.

## Docker Hub e release

Antes de publicar, resolva P01: a conta precisa comportar **dois repositórios privados**. Não torne imagens públicas nem junte os artefatos para contornar o plano.

Secrets do GitHub:

- `DOCKERHUB_USERNAME`
- `DOCKERHUB_TOKEN` com escrita apenas nos repositórios necessários
- `DOCKERHUB_BACKEND_REPOSITORY` (`usuario/account-manager-backend`)
- `DOCKERHUB_FRONTEND_REPOSITORY` (`usuario/account-manager-frontend`)

Após checks verdes e commit em `main`, uma tag como `v1.0.0` aciona `.github/workflows/release.yml`. O workflow valida SemVer, reexecuta gates, publica as duas imagens com tag `1.0.0` e salva um manifesto com commit/digests. Não publica `latest`. A publicação não foi executada nesta etapa.

## VPS, atualização e rollback

O roteiro detalhado está em [`docs/guias/operacao.md`](docs/guias/operacao.md). Em resumo:

1. Confirmar recursos da VPS (P07), DNS/TLS/renovação (P02) e acesso read-only ao Docker Hub.
2. Copiar `deploy/compose.prod.yml` e criar `.env`/secrets somente no servidor.
3. Selecionar tags/digests do mesmo manifesto; nunca compilar na VPS.
4. Confirmar backup externo válido; executar migration com o serviço `migrate`.
5. Subir backend/frontend, conferir saúde e smoke.

`deploy/scripts/update.sh` exige `BACKUP_CONFIRMED=yes` para impedir atualização acidental sem confirmação. Ele ainda não foi exercitado numa VPS. O Compose expõe HTTP 80 apenas como baseline; não use dados reais até P02 adicionar e validar HTTPS/renovação.

Voltar a imagem não reverte schema. Em falha de migration, pare antes de subir o runtime e investigue; não execute `flyway repair` às cegas. Restaurar banco é uma ação distinta, potencialmente destrutiva, que pode perder dados posteriores e exige decisão explícita.

## Backup e restauração

A arquitetura aprovada gera backup criptografado na VPS e o Windows baixa por SSH/SFTP. O script [`deploy/scripts/download-backup.ps1`](deploy/scripts/download-backup.ps1) usa arquivo temporário, verifica SHA-256 e só então promove a cópia. Exemplo:

```powershell
.\deploy\scripts\download-backup.ps1 `
  -RemoteHost 'contas.malyah.tech' `
  -RemoteUser 'backup-reader' `
  -IdentityFile 'C:\CAMINHO\backup_ed25519' `
  -RemoteFile '/srv/account-manager/backups/account-manager-AAAA-MM-DD.tar.age' `
  -DestinationDirectory 'D:\Backups\account-manager'
```

Criptografia, agenda, chave externa, usuário restrito, retenção e restauração completa permanecem P06. O PC desligado não baixa a cópia; o RPO real passa a ser a data da última cópia externa válida. Nenhuma restauração foi executada nesta preparação.

## Diagnóstico rápido

```powershell
docker compose -f deploy/compose.dev.yml ps
docker compose -f deploy/compose.dev.yml logs db
docker system df
```

- Migration falha: preserve logs, não inicie backend novo e não edite migration aplicada.
- Banco indisponível: confira healthcheck, URL e espaço em disco; não exponha 5432 na internet.
- SMTP/Groq/Meta indisponível: mantenha cadastro manual e demais fluxos; não ative provider pago automaticamente.
- Certificado: valide DNS, cadeia, renovação e reload antes de uso real.
- npm/Maven TLS: corrija trust store; nunca desative validação de certificado.

## Exportação completa e exclusão

CSV financeiro não é exportação pessoal completa. P09 precisa definir formato, verificação de identidade, histórico compartilhado, anexos, backups e reaplicação após restore. Não há comando ou tela destrutiva nesta preparação.

## Estado e próximo passo

Consulte [`docs/progresso.md`](docs/progresso.md) para resultados executados e limites. H04.1–H04.5 e H05.1 estão concluídas, com E2E full-stack aprovado em 28/09/2026; E04 está concluído. H03.3 permanece em validação independente. H05.2 e H05.3 estão concluídas, fechando o E05 (compras parceladas), com CI aprovada em 28/09/2026.

## Anexos privados (H03.3)

Na visualização de uma despesa, ambos os membros ativos podem anexar, listar, baixar e remover até cinco arquivos PDF, JPG ou PNG de no máximo 10 MB cada. O backend valida a assinatura real do conteúdo; o nome original é apenas metadado e nunca compõe o caminho físico. Downloads autenticados usam `Content-Disposition: attachment`, `nosniff` e `no-store`.

O backend usa `APP_FILES_ROOT` (padrão `/var/lib/account-manager/files`) e separa `staging/` de `permanent/`. Em Docker, o volume privado `files-full-local`/`private-files` é montado somente no backend e não no Nginx. Para comprovar persistência local, envie um arquivo, recrie somente o container backend sem remover volumes e baixe-o novamente. `docker compose down -v` apaga deliberadamente o ambiente local e não deve ser usado numa atualização.

Backup operacional deve capturar PostgreSQL e a área `permanent/` na mesma janela de manutenção. `staging/` não é backup permanente. A restauração completa continua pendente de P06/H11.2–H11.3 e não foi declarada validada nesta história.

## Busca e filtros de despesas (H03.4)

A tela de despesas abre no mês atual do fuso do espaço, pela data de vencimento/referência, e oculta canceladas. A busca textual consulta somente a descrição. É possível combinar período inclusivo, base de data (vencimento ou pagamento), categoria, responsável, pagador e situação (`ACTIVE`, `PENDING`, `OVERDUE`, `PAID`, `CANCELLED` ou `ALL`). Atrasadas continuam sendo pendentes e não formam uma segunda contagem.

As opções incluem categorias arquivadas e pessoas ainda referenciadas em despesas; quem já saiu aparece como “membro anterior”. “Sem categoria” e “Sem responsável” são filtros explícitos. As ordenações aceitas são `REFERENCE_DATE`, `AMOUNT` e `DESCRIPTION`, com `ASC`/`DESC` e desempate estável. Ao trocar um filtro, a interface volta à primeira página, cancela respostas antigas e preserva explicitamente a seleção do lote entre páginas/filtros.

Exemplos autenticados:

```text
GET /api/v1/expenses?search=energia&dateFrom=2026-09-01&dateTo=2026-09-30&status=OVERDUE&sort=AMOUNT&direction=DESC
GET /api/v1/expenses?dateBasis=PAYMENT_DATE&status=PAID&payerUserId=<UUID>&page=0&size=20
GET /api/v1/expenses/filter-options
```

Parâmetros inválidos, página negativa, tamanho fora de 1–100, período invertido, enum ou campo de ordenação desconhecido retornam `400`; a API não aceita SQL ou nome livre como ordenação.

## Recorrências, calendário, geração e previsões (H04.1–H04.3)

Após entrar, abra `/recorrencias` (também há um link na tela de despesas). Administrador e convidado ativos podem cadastrar descrição, valor, modalidade fixa ou estimativa variável, frequência, primeiro vencimento e, opcionalmente, término, categoria e responsável. Categoria e responsável precisam estar ativos e pertencer ao mesmo espaço.

As frequências são mensal, bimestral, trimestral, semestral e anual. O primeiro vencimento fixa o dia-base: `31/01/2027` produz `28/02/2027` e depois `31/03/2027`, sem deslocamento acumulado. Em ano bissexto, fevereiro usa dia 29. Não há ajuste de fim de semana/feriado. O término é inclusivo e datas passadas são aceitas como referência, mas não geram ocorrências retroativas.

“Calcular próximas datas” chama o backend e mostra até 12 datas; o frontend não replica o algoritmo. O cadastro persiste a definição e o job verifica, por padrão a cada 30 segundos, se existe uma ocorrência no mês vigente no fuso do espaço. Meses anteriores não são gerados retroativamente. Uma cobrança fixa nasce confirmada; uma estimativa variável aparece como **valor estimado a confirmar**, e só pode ser quitada depois de ter o valor confirmado (veja a seção seguinte).

A identidade durável da ocorrência é `(recurrence_id, scheduled_due_date)`. A fila usa reserva PostgreSQL com `FOR UPDATE SKIP LOCKED`, lease de 120 segundos, fencing token e lote de 25. Processo interrompido pode ser retomado após expirar o lease; constraints impedem segunda despesa/auditoria. Categoria arquivada e responsável inativo são omitidos no novo lançamento, preservando a definição para tratamento definitivo em H04.5. Não existe endpoint público para disparar o job.

A seção **Previsões e lançamentos** consulta o mês atual mais os 12 meses seguintes no fuso do espaço. Consultar não grava dados. Cada item é rotulado como previsão fixa/estimada ou lançamento real; quando a ocorrência já existe, valor, vencimento atual, confirmação e situação da despesa prevalecem, inclusive para paga ou cancelada, sem recriar a previsão. **Antecipar lançamento** exige confirmação, aceita apenas uma data ainda válida nesse horizonte e materializa a ocorrência numa transação. A mesma chave/conteúdo reproduz o resultado; chave reutilizada com outra ocorrência retorna `409`. Antecipar não confirma uma estimativa variável.

Configuração operacional opcional: `APP_JOBS_RECURRENCE_ENABLED`, `APP_JOBS_RECURRENCE_FIXED_DELAY_MS`, `APP_JOBS_RECURRENCE_LEASE_SECONDS` e `APP_JOBS_RECURRENCE_BATCH_SIZE`. Os padrões são `true`, `30000`, `120` e `25`; não desabilite o job em produção. O modo explícito de migração o desativa automaticamente.

Exemplos autenticados (operações `POST` também exigem CSRF):

```text
POST /api/v1/recurrences/calendar-preview
POST /api/v1/recurrences  Idempotency-Key: <UUID>
GET  /api/v1/recurrences
GET  /api/v1/recurrences/forecasts
POST /api/v1/recurrences/<recurrenceId>/occurrences/<AAAA-MM-DD>/anticipation  Idempotency-Key: <UUID>
POST /api/v1/recurrences/<recurrenceId>/occurrences/<AAAA-MM-DD>/charge-confirmation  Idempotency-Key: <UUID>
```

Teste de geração, previsão/antecipação, confirmação de valores variáveis, alteração/encerramento, persistência e migrações V1–V19:

```powershell
backend\scripts\run-integration-tests.ps1 -Tests RecurrenceGenerationPostgresIT,RecurrencePostgresIT,VariableChargeConfirmationPostgresIT,RecurrenceChangePostgresIT,ExpensePostgresIT,FlywayPostgresIT
```

Diagnóstico seguro: consulte contagens/estados em `recurrence_generation_jobs` e `recurrence_occurrences`; `FAILED` inclui somente `last_error_code`, nunca dados financeiros. Não altere manualmente jobs concluídos. Falhas transitórias ficam elegíveis após 30 segundos; `PROCESSING` com lease vencido é retomado por outro worker.

## Estimar, confirmar e quitar (H04.4)

Uma recorrência de **valor variável** produz três valores distintos, e a interface nunca os mistura:

| Conceito | O que é | Onde aparece |
|---|---|---|
| Estimativa | Valor previsto para uma cobrança ainda não confirmada. Não é cobrança nem pagamento. | Previsões, lançamento “valor estimado a confirmar” e `amount` com `chargeConfirmed=false`. |
| Valor confirmado | Valor que a fatura realmente cobrou. Substitui a estimativa; a estimativa anterior fica em `chargeConfirmation.estimatedAmount` e no evento `CHARGE_CONFIRMED`. | `amount` com `chargeConfirmed=true` e `chargeConfirmation` (autor e instante). |
| Pagamento | Quitação, com valor pago (juros ou desconto), data e pagador. | `paidAmount`, `paymentDate`, `paidByUserId` e o evento `EXPENSE_PAID`. |

Como confirmar:

- Em `/despesas`, um lançamento pendente com valor estimado mostra **Valor estimado atual** e o botão **Confirmar valor da cobrança**. Confirmar não quita: a despesa continua pendente.
- Em `/recorrencias`, uma previsão estimada tem o mesmo botão. Ele reutiliza a materialização da H04.3 pela identidade `(recorrência, data prevista)` e confirma a despesa na mesma transação, sem duplicá-la.
- **Quitar despesa** numa cobrança ainda estimada exige o campo **Valor confirmado da cobrança**; confirmação e quitação são gravadas juntas ou nada é gravado. A API responde `409 CHARGE_CONFIRMATION_REQUIRED` se o campo faltar.
- O lote não aceita cobrança estimada (`AMOUNT_UNCONFIRMED`); a interface não permite selecioná-la. O lote inteiro é recusado sem efeitos parciais.

Regras aplicadas (RF-REC-10/12/13, RF-DES-04/06, D20):

- Só confirmam-se despesas `PENDING`, de origem recorrente, ainda não confirmadas, por administrador ou convidado ativo do mesmo espaço. Confirmar duas vezes retorna `409 CHARGE_ALREADY_CONFIRMED`; paga, cancelada ou versão desatualizada retornam `409 EXPENSE_STATE_CONFLICT`. A mesma `Idempotency-Key` com o mesmo conteúdo reproduz o resultado; com outro conteúdo, retorna `409 IDEMPOTENCY_CONFLICT`.
- O valor é validado no backend: decimal com até duas casas, entre `0.01` e `99999999.99`.
- **Efeito sobre ocorrências futuras:** a estimativa de uma ocorrência é o valor confirmado mais recente com data prevista anterior à dela; sem confirmação anterior, vale a estimativa inicial da definição. Ao confirmar, somente as ocorrências posteriores, pendentes e ainda não confirmadas da mesma recorrência são atualizadas (evento `ESTIMATE_UPDATED`, com a despesa de origem). Ocorrências anteriores, pagas, canceladas ou confirmadas não mudam, e o valor da definição da recorrência também não.
- O valor pago nunca vira referência: juros ou desconto ficam somente no pagamento.
- A geração mensal e as previsões usam essa mesma referência e nunca sobrescrevem um valor confirmado.
- A correção genérica não altera o valor de uma estimativa. Um valor já confirmado pode ser corrigido individualmente (D20); se ele for a referência vigente, as estimativas posteriores são recalculadas na mesma transação.
- Confirmação, correção e materialização travam a definição da recorrência antes das despesas, o que evita que confirmações concorrentes produzam referências inconsistentes.

Exemplos autenticados (exigem CSRF):

```text
POST /api/v1/expenses/<id>/charge-confirmation  Idempotency-Key: <UUID>
{"version": 2, "confirmedAmount": "205.40"}

POST /api/v1/expenses/<id>/payment  Idempotency-Key: <UUID>
{"version": 2, "paidAmount": "207.00", "paymentDate": "2026-10-10", "paidByUserId": "<UUID>", "confirmedChargeAmount": "205.40"}
```

Os reflexos em relatórios, fechamentos e notificações serão validados nos épicos E06–E08. O contrato preparado para eles é `chargeConfirmed` e `chargeConfirmation`.

## Alterar e encerrar recorrência (H04.5)

Em `/recorrencias`, cada recorrência ativa mostra **Alterar a partir de um vencimento** (“este e os próximos”) e **Encerrar recorrência**, além de **Programação por período** e **Histórico de alterações**. Administrador e convidado ativos podem usar as duas ações. Há três operações diferentes, e a interface explica qual está em uso:

| Operação | O que muda | Onde |
|---|---|---|
| Correção individual (“somente este lançamento”) | Apenas o lançamento escolhido; a definição e os outros períodos não mudam. | **Corrigir** em `/despesas` (H02). |
| Alteração da definição (“este e os próximos”) | A configuração a partir do período escolhido: descrição, valor/estimativa, frequência, dia de vencimento, categoria e responsável. Períodos anteriores ficam como estão. | `POST /recurrences/{id}/changes` |
| Encerramento | Para de gerar ocorrências depois do último período. Não apaga histórico nem cancela todas as despesas. | `POST /recurrences/{id}/closure` |

O fluxo é sempre **revisar impacto → confirmar**. A prévia não grava nada e mostra, por lançamento, se ele será atualizado, retirado da programação, marcado para revisão ou preservado, e o efeito nas previsões ainda não materializadas. Ao confirmar, o backend recalcula o impacto sob lock: se versão, lançamentos ou previsões mudaram, responde `409 RECURRENCE_IMPACT_CHANGED` sem aplicar nada, e a tela mantém os dados digitados e pede nova revisão.

Efeito por situação do lançamento (a partir do período escolhido):

| Situação | Descrição, categoria, responsável | Valor | Vencimento (dia/frequência) | Saiu da programação ou ficou após o término |
|---|---|---|---|---|
| Pendente, estimado ou fixo | Atualiza | Fixo: novo valor. Estimado: nova base de estimativa | Atualiza | Cancelado com motivo e auditoria |
| Pendente com valor variável confirmado | Atualiza | Preservado | Preservado | Mantido e marcado para revisão |
| Pendente com vencimento corrigido individualmente | Atualiza | Atualiza (se não confirmado) | Preservado | Mantido e marcado para revisão |
| Pago | Nunca reescrito | Nunca reescrito | Nunca reescrito | Mantido e marcado para revisão |
| Cancelado | Nunca reescrito | Nunca reescrito | Nunca reescrito | Nada muda |
| Período anterior ao escolhido | Nada muda | Nada muda | Nada muda | — |

Regras aplicadas:

- O período inicial precisa ser um vencimento da programação atual, entre o mês atual e os 12 seguintes. O tipo de valor (fixo ou estimado) não é editável.
- A identidade de cada ocorrência é o período (mês) da recorrência: mudar o dia ou a frequência move a data do lançamento pendente, sem duplicar. O job de geração lê a definição no momento de gerar; se o mês deixou de ter ocorrência, marca a tarefa como `SKIPPED`.
- Ao mudar a estimativa de uma recorrência variável, ela passa a ser a referência a partir desse período; confirmações posteriores voltam a prevalecer (regra da H04.4).
- O encerramento usa data de corte inclusiva: o último período é o último vencimento em ou antes da data informada. O motivo é obrigatório (até 2.000 caracteres). O término só pode ser antecipado; não há reativação, pausa ou exclusão física.
- Toda alteração grava um evento em `recurrence_change_events` (autor, instante, versão, campos, contagens e motivo) e eventos por lançamento no histórico de despesas (`RECURRENCE_CHANGE_APPLIED` e `RECURRENCE_OCCURRENCE_REMOVED`). Definição, lançamentos e auditoria são gravados numa transação; qualquer falha desfaz tudo.
- `Idempotency-Key` com o mesmo conteúdo reproduz o resultado; com outro conteúdo, `409 IDEMPOTENCY_CONFLICT`. Versão desatualizada retorna `409 RECURRENCE_VERSION_CONFLICT`; recorrência de outro espaço, `404 RECURRENCE_NOT_FOUND`; categoria arquivada, `400 CATEGORY_NOT_SELECTABLE`.

Exemplos autenticados (operações `POST` também exigem CSRF):

```text
POST /api/v1/recurrences/<id>/changes/preview
{"version": 3, "effectiveDueDate": "2026-11-05", "description": "Energia", "amount": "210.00",
 "frequency": "MONTHLY", "dueDay": 20, "categoryId": null, "responsibleUserId": null}

POST /api/v1/recurrences/<id>/changes  Idempotency-Key: <UUID>
{ ...o mesmo corpo revisado..., "impactToken": "<impactToken da prévia>" }

POST /api/v1/recurrences/<id>/closure/preview
{"version": 4, "lastDueDate": "2026-12-31"}

POST /api/v1/recurrences/<id>/closure  Idempotency-Key: <UUID>
{"version": 4, "lastDueDate": "2026-12-31", "reason": "Mudança de endereço", "impactToken": "<impactToken>"}
```

Teste manual:

1. Cadastre uma recorrência variável mensal de R$ 180,00 com primeiro vencimento no dia 5 do mês atual e antecipe os dois meses seguintes em **Previsões e lançamentos**.
2. Confirme o valor do segundo mês (R$ 195,00) e quite o primeiro.
3. Clique **Alterar a partir de um vencimento**, escolha o vencimento do mês seguinte, mude a estimativa para 210,00, o dia para 20 e a descrição, e clique **Revisar impacto**. O painel mostra o lançamento pago preservado, o confirmado com descrição atualizada e “Preservado: estimativa, vencimento”, e as previsões seguintes com 210,00 no dia 20.
4. Abra a mesma recorrência em outra aba, altere e confirme lá; na primeira aba, **Confirmar alteração** mostra o conflito, mantém os dados digitados e pede nova revisão.
5. Clique **Encerrar recorrência**, escolha um último vencimento, informe o motivo e revise. Os lançamentos estimados posteriores aparecem como “Sai da programação”, os pagos/confirmados como “Mantido para revisão”. Confirme: o cartão mostra o encerramento, as previsões param no último período e **Ver histórico** da despesa cancelada mostra o motivo.

Os reflexos em relatórios, fechamentos e notificações serão implementados em E06–E08; o contrato preparado é o histórico `recurrence_change_events`, o `reviewReason` das previsões e os novos eventos de despesa.

## Criar compra parcelada (H05.1)

Em `/compras-parceladas` (link **Compras parceladas** em Despesas), administrador ou convidado ativo informa descrição, **valor total final**, quantidade de parcelas (2 a 360), **vencimento da primeira parcela** e, opcionalmente, categoria e responsável. **Revisar parcelas** pede o cálculo ao backend e mostra total, quantidade, valor regular, última parcela, período de vencimentos, soma e a tabela n/N. **Confirmar e criar N parcelas** envia exatamente os dados revisados; o backend recalcula e revalida tudo ao salvar. Editar qualquer campo depois da revisão descarta a prévia.

Relação compra ↔ parcelas:

- A compra fica em `installment_purchases` (cabeçalho com total, quantidade, primeiro vencimento, categoria, responsável, autor e instante). O cabeçalho **não é despesa** e não entra em nenhum total.
- Todas as N parcelas são criadas na mesma transação como lançamentos pendentes em `expense_entries`, com `origin = INSTALLMENT`, `installment_purchase_id`, `installment_number` (1..N) e `installment_count` (N). Constraints garantem numeração única por compra e 1 ≤ n ≤ N ≤ 360. A compra é finita: não é recorrência e termina na parcela N, sem depender do horizonte de previsões.
- Cada parcela aparece em Despesas como “Parcela n/N · compra parcelada”, com vencimento, quitação e histórico próprios (regras de E02). A correção individual pode mudar descrição, vencimento, categoria, responsável e observações, mas não o valor: o valor da parcela é definido pela compra (mudar total ou quantidade será cancelar pendentes e cadastrar nova compra, H05.3).

Cálculo dos valores (em centavos inteiros, sem ponto flutuante nem arredondamento por parcela): `regular = floor(total / N)`; as parcelas 1..N−1 recebem `regular`; a última recebe `regular + (total − regular × N)`. A soma é sempre exatamente o total. Sem juros, entrada ou tarifas: o total informado já é o valor final.

| Total | N | Parcelas | Ajuste na última |
|---|---|---|---|
| R$ 1.200,00 | 12 | 12 × 100,00 | 0,00 |
| R$ 100,00 | 3 | 33,33 · 33,33 · **33,34** | 0,01 |
| R$ 1.000,00 | 7 | 6 × 142,85 · **142,90** | 0,05 |
| R$ 0,02 | 2 | 0,01 · 0,01 | 0,00 |
| R$ 0,01 | 2 | recusado: não há R$ 0,01 para cada parcela | — |
| R$ 99.999.999,99 | 360 | 359 × 277.777,77 · **277.780,56** | 2,79 |

Limites: total entre R$ 0,01 e R$ 99.999.999,99 com até duas casas; 2 a 360 parcelas; total ≥ N × R$ 0,01.

Calendário: parcelas mensais a partir do primeiro vencimento, reutilizando a regra mensal das recorrências. O dia do primeiro vencimento é o dia de referência de todas as parcelas; em meses curtos usa-se o último dia do mês, sem deslocar as seguintes. Não há ajuste para fim de semana ou feriado.

| Primeiro vencimento | Vencimentos seguintes |
|---|---|
| 31/01/2027 | 28/02/2027, 31/03/2027, 30/04/2027 |
| 30/11/2027 | 30/12/2027, 30/01/2028, 29/02/2028 (bissexto), 30/03/2028 |
| 15/12/2026 | 15/01/2027, 15/02/2027 (virada de ano) |

Não há campo “data da compra”: a compra guarda só o primeiro vencimento, confirmado por Diego em 29/09/2026 (decisão T21). Vencimentos passados são aceitos; parcelas já vencidas aparecem como atrasadas.

Garantias: compra, parcelas e evento `PURCHASE_CREATED` são gravados numa única transação (qualquer falha desfaz tudo). `Idempotency-Key` é obrigatório e vale por espaço e autor: repetir com o mesmo conteúdo devolve a mesma compra (`200`, sem nova parcela nem auditoria), inclusive com pedidos simultâneos; reutilizar a chave com outro conteúdo retorna `409 IDEMPOTENCY_CONFLICT`. Categoria e responsável são validados no backend para o espaço do autor.

```text
POST /api/v1/installment-purchases/preview
{"description": "Sofá", "totalAmount": "100.00", "installmentCount": 3, "firstDueDate": "2027-01-31",
 "categoryId": null, "responsibleUserId": null}
→ 200 {"regularAmount": "33.33", "lastAmount": "33.34", "lastInstallmentAdjustment": "0.01",
       "installmentsSum": "100.00", "lastDueDate": "2027-03-31", "installments": [...]}

POST /api/v1/installment-purchases  Idempotency-Key: <UUID>
{ ...o mesmo corpo revisado... }
→ 201 Location: /api/v1/installment-purchases/<id>   (repetição: 200 com a mesma compra)
```

Erros: `400 INSTALLMENT_VALIDATION` com `field` (`description`, `totalAmount`, `installmentCount`, `firstDueDate`, `responsibleUserId`, `Idempotency-Key`); `400 CATEGORY_NOT_SELECTABLE` (categoria arquivada ou de outro espaço); `403 ACTIVE_SPACE_ACCESS_NOT_FOUND`; `409 IDEMPOTENCY_CONFLICT`.

Teste manual de divisão não exata:

1. Entre, abra **Despesas → Compras parceladas**.
2. Informe “Sofá”, valor total `100,00`, `3` parcelas e primeiro vencimento `31/01/2027`; clique **Revisar parcelas**.
3. Confira: parcelas 1/3 e 2/3 de R$ 33,33 em 31/01/2027 e 28/02/2027, 3/3 de R$ 33,34 em 31/03/2027, soma R$ 100,00 e o aviso “A última parcela tem R$ 0.01 a mais”.
4. Mude a quantidade para 4: a prévia some. Volte para 3, revise de novo e clique **Confirmar e criar 3 parcelas**.
5. Clique **Ver parcelas em Despesas**, busque “Sofá”, informe o período de 01/01/2027 a 31/03/2027 (sem datas a lista mostra só o mês atual) e aplique os filtros: aparecem “Parcela 1/3”, “2/3” e “3/3”, pendentes. Em **Corrigir** de uma delas, o valor é somente leitura.
6. Tente `0,01` com 2 parcelas: a revisão mostra “Valor total: O valor total não permite 2 parcelas de pelo menos R$ 0,01.”

Testes da compra parcelada (Windows; em Linux, `./mvnw verify -Dit.test=InstallmentPurchasePostgresIT,ExpensePostgresIT,FlywayPostgresIT`):

```powershell
backend\scripts\run-integration-tests.ps1 -Tests InstallmentPurchasePostgresIT,ExpensePostgresIT,FlywayPostgresIT
```

A consulta por compra e a quitação de várias parcelas estão na seção seguinte (H05.2).

## Consultar e quitar parcelas (H05.2)

Abaixo do formulário de `/compras-parceladas`, **Compras cadastradas** lista as compras do espaço, das mais recentes para as mais antigas (10 por página), com total, quantidade, barra de parcelas pagas, contagem de pagas, pendentes, atrasadas e canceladas, quanto falta pagar e o próximo vencimento. **Ver parcelas de …** abre a tabela n/N com vencimento, valor, situação (Pendente, Atrasada, Cancelada ou “Paga em data (R$ valor pago)”) e a descrição, categoria e responsável atuais de cada parcela.

- **Progresso sem saldo bancário:** tudo vem da situação das parcelas. “Pagas” soma o valor das parcelas quitadas; “Falta pagar” soma as pendentes (atrasadas incluídas); nada representa saldo de conta ou limite de cartão. Atraso usa a data de hoje no fuso do espaço.
- **Quitar selecionadas:** só parcelas pendentes podem ser marcadas. **Quitar selecionadas (k)** abre data do pagamento, pagador (você por padrão) e a confirmação “Confirmo a quitação integral…”, e chama o mesmo lote atômico de Despesas (`POST /expenses/batch-payment`, T12/D19): cada parcela é quitada pelo seu valor, com versão e `Idempotency-Key`. Se alguma mudou, nenhuma é quitada; a tela recarrega a compra e mantém selecionadas só as que continuam pendentes. Falha de rede mantém a chave, então repetir não quita duas vezes.
- **Continuam valendo os fluxos de Despesas:** cada parcela pode ser quitada individualmente (inclusive com valor pago diferente), desfeita ou cancelada em Despesas, e aparece nos filtros e buscas; a compra reflete o resultado.
- **Sem fatura duplicada:** a tela avisa para não cadastrar a fatura completa do cartão como outra despesa, porque as parcelas já estão em Despesas.

```text
GET /api/v1/installment-purchases?page=0&size=20        (size de 1 a 100)
→ 200 {"items": [{"id": "...", "description": "Sofá", "totalAmount": "100.00", "installmentCount": 3,
        "progress": {"installmentCount": 3, "paidCount": 1, "pendingCount": 2, "overdueCount": 0,
                     "cancelledCount": 0, "paidAmount": "33.33", "pendingAmount": "66.67",
                     "overdueAmount": "0.00", "cancelledAmount": "0.00", "nextDueDate": "2027-02-28"}}],
       "page": 0, "size": 20, "totalItems": 1}

GET /api/v1/installment-purchases/<id>
→ 200 { ...cabeçalho..., "progress": {...}, "installments": [{"number": 1, "count": 3, "amount": "33.33",
        "dueDate": "2027-01-31", "expenseId": "...", "status": "PAID", "version": 1, "overdue": false,
        "paymentDate": "2027-01-30", "paidAmount": "33.33", "description": "Sofá", ...}]}
```

Erros: `404 INSTALLMENT_PURCHASE_NOT_FOUND` (inexistente ou de outro espaço), `400 INSTALLMENT_VALIDATION` (`page`, `size` ou identificador inválido), `403 ACTIVE_SPACE_ACCESS_NOT_FOUND`. A quitação usa os erros do lote de Despesas (`409` rejeita o lote inteiro).

Teste manual:

1. Crie “Sofá”, `100,00` em `3` parcelas a partir de uma data do mês passado (por exemplo, o dia 15). Em **Compras cadastradas** aparecem “0 de 3 pagas · 3 pendentes (1 atrasadas)”, “Falta pagar R$ 100.00” e o próximo vencimento.
2. Clique **Ver parcelas de Sofá**: a parcela 1/3 aparece como Atrasada; nenhuma caixa de seleção existe para parcelas pagas ou canceladas.
3. Marque 1/3 e 2/3, clique **Quitar selecionadas (2)**, confira “no total de R$ 66.66”, marque a confirmação e clique **Confirmar quitação**. Resultado: “2 parcelas quitadas de uma vez.”, “2 de 3 pagas · 1 pendentes” e as duas linhas como “Paga em …”.
4. Em Despesas, filtre o período das parcelas: as duas aparecem pagas pelo mesmo lote, a 3/3 pendente. Desfaça a quitação de uma delas e volte à compra: ela aparece de novo como pendente.
5. Conflito: abra a compra em duas abas, quite a 3/3 numa delas e tente quitá-la na outra. A segunda recebe “Nenhuma parcela foi quitada…” e a seleção é atualizada.

Testes (Windows; em Linux, `./mvnw verify -Dit.test=InstallmentProgressPostgresIT,InstallmentPurchasePostgresIT,ExpensePostgresIT`):

```powershell
backend\scripts\run-integration-tests.ps1 -Tests InstallmentProgressPostgresIT,InstallmentPurchasePostgresIT,ExpensePostgresIT
```

## Ajustar e cancelar parcelas pendentes (H05.3)

No detalhe de uma compra em **Compras cadastradas** ficam **Alterar parcelas pendentes** e **Cancelar selecionadas (k)**. As duas ações sempre mostram primeiro o impacto calculado pelo servidor (parcelas afetadas com “de → para” e parcelas preservadas com o motivo) e só gravam em **Confirmar alteração** / **Confirmar cancelamento**.

- **Alterar (RF-PAR-05/06):** a partir de uma parcela pendente, com alcance **Somente esta parcela** ou **Esta e as próximas pendentes**, muda descrição, categoria, responsável e/ou vencimento. Com “esta e as próximas”, o novo vencimento vale para a parcela escolhida e as pendentes seguintes são recalculadas mês a mês pela diferença de número (regra de calendário da H05.1: meses curtos usam o último dia). Parcelas pagas e canceladas nunca mudam; parcelas que já têm os valores pedidos são preservadas como “já tem esses valores”. Valor e quantidade não mudam por aqui.
- **Cancelar (RF-PAR-07/08):** cancela só as parcelas pendentes selecionadas, com motivo obrigatório (até 2.000 caracteres). Nada é estornado nem desfeito: parcelas pagas continuam pagas. Opcionalmente cria, na mesma transação, uma **nova compra com o restante** (descrição, valor total, quantidade, primeiro vencimento, categoria e responsável), com as regras da H05.1 e o vínculo “substitui a compra …”. É assim que se corrige valor total ou quantidade.
- **Revisão protegida:** a prévia devolve um `impactToken` (hash das parcelas, versões e novos valores). Ao confirmar, o servidor trava a compra e as parcelas, recalcula o impacto e recusa com `409 INSTALLMENT_IMPACT_CHANGED` se algo mudou (outra aba quitou ou corrigiu uma parcela); a tela descarta a revisão e pede para revisar de novo. A confirmação exige `Idempotency-Key`: repetir a mesma requisição devolve o mesmo resultado sem duplicar a alteração nem a nova compra; a mesma chave com outro conteúdo recebe `409 IDEMPOTENCY_CONFLICT`.
- **Histórico:** cada parcela afetada registra “Alterado pela compra parcelada” ou “Cancelado pela compra parcelada” (com o motivo) no histórico de Despesas, ligado ao registro da alteração em `installment_purchase_changes`.

```text
POST /api/v1/installment-purchases/<id>/changes/preview
{"fromNumber": 2, "scope": "THIS_AND_FOLLOWING", "changedFields": ["dueDate"], "dueDate": "2027-02-10"}
→ 200 {"changeType": "CHANGE", "impactToken": "<64 hex>", "affectedAmount": "66.67", "replacement": null,
       "affected": [{"number": 2, "expenseId": "...", "version": 1, "amount": "33.33", "dueDate": "2027-02-28",
                     "changes": [{"field": "dueDate", "from": "2027-02-28", "to": "2027-02-10"}]}, ...],
       "preserved": [{"number": 1, "status": "PAID", "reason": "PAID"}]}

POST /api/v1/installment-purchases/<id>/changes          (Idempotency-Key: <uuid>)
{ ...o mesmo corpo..., "impactToken": "<token da prévia>"}
→ 200 {"changeId": "...", "changeType": "CHANGE", "affectedCount": 2, "preservedCount": 1,
       "purchase": {...compra atualizada...}, "replacement": null, "replayed": false}

POST /api/v1/installment-purchases/<id>/cancellation/preview
{"installmentNumbers": [3], "reason": "Loja renegociou o saldo",
 "replacement": {"description": "Sofá (restante)", "totalAmount": "33.34", "installmentCount": 2,
                 "firstDueDate": "2027-03-10", "categoryId": null, "responsibleUserId": null}}
POST /api/v1/installment-purchases/<id>/cancellation     (Idempotency-Key, corpo + "impactToken")
→ 200 {..., "changeType": "CANCELLATION", "replacement": {...nova compra, "replacesPurchaseId": "<id>"...}}
```

`scope` aceita `THIS` ou `THIS_AND_FOLLOWING`; `changedFields` é um subconjunto de `description`, `categoryId`, `responsibleUserId` e `dueDate` (só os campos listados são aplicados; `categoryId`/`responsibleUserId` nulos removem o vínculo). `replacement` é opcional.

Erros: `400 INSTALLMENT_VALIDATION` (campo inválido, nada a alterar, motivo ausente, parcela inexistente, `Idempotency-Key` ausente), `404 INSTALLMENT_PURCHASE_NOT_FOUND`, `409 INSTALLMENT_NOT_PENDING` (parcela escolhida paga ou cancelada), `409 INSTALLMENT_IMPACT_CHANGED`, `409 IDEMPOTENCY_CONFLICT`, erros de categoria/responsável iguais aos da H05.1 e `403 ACTIVE_SPACE_ACCESS_NOT_FOUND`. Administrador e convidado ativos podem usar as duas ações.

Teste manual (demonstração do E05):

1. Crie “Sofá”, `100,00` em `3` parcelas (a última fica com `33,34`). Abra **Ver parcelas de Sofá**, marque 1/3 e quite-a (H05.2).
2. **Alterar parcelas pendentes** → a partir de `2/3`, **Esta e as próximas pendentes**, **Alterar vencimento** para o dia 10 do mês da 2/3 → **Revisar impacto**. Resultado: “2 parcelas serão alteradas.”, cada uma com “Vencimento: … → …-10” e “Preservadas: 1 (paga, não muda)”. **Confirmar alteração** → “Alteração aplicada a 2 parcelas; 1 preservada.”
3. Marque 3/3 → **Cancelar selecionadas (1)** → motivo “Loja renegociou o saldo” → marque **Criar nova compra com o restante** (os campos vêm preenchidos: valor da selecionada, 2 parcelas, primeiro vencimento dela) → **Revisar cancelamento**. Resultado: “1 parcela será cancelada, somando R$ 33.34.” e o resumo da nova compra. **Confirmar cancelamento** → “1 parcela cancelada; 2 preservadas. Nova compra “Sofá (restante)” criada com 2 parcelas.”
4. Confira: 1/3 continua “Paga em …”, 3/3 aparece Cancelada, a nova compra aparece em **Compras cadastradas** e, no detalhe dela, “substitui o restante de uma compra anterior”, e em Despesas a 2/3 mostra “Alterado pela compra parcelada” e a 3/3 “Cancelado pela compra parcelada” no histórico.
5. Conflito: revise uma alteração numa aba, quite a parcela em outra e confirme na primeira: “As parcelas mudaram desde a revisão…”, sem nada gravado.

Testes (Windows; em Linux, `./mvnw verify -Dit.test=InstallmentAdjustmentPostgresIT,InstallmentProgressPostgresIT,InstallmentPurchasePostgresIT`):

```powershell
backend\scripts\run-integration-tests.ps1 -Tests InstallmentAdjustmentPostgresIT,InstallmentProgressPostgresIT,InstallmentPurchasePostgresIT
```

## Painel por vencimento (H06.1)

Em `/painel` (link **Painel por vencimento** em Despesas), administrador e convidado ativos veem os indicadores de um mês, sempre pela **data de vencimento** (despesa paga sem vencimento entra pela data do pagamento). O mês inicial é o mês atual no fuso do espaço; **‹ Anterior**, **Próximo ›** e **Ir para o mês** trocam o período. Os filtros (descrição, categoria ou “Sem categoria”, responsável ou “Sem responsável”, pagador e situação) valem para **todos** os indicadores, para as pendências anteriores e para a lista abaixo.

- **Previsto:** soma das cobranças das despesas pendentes e pagas do mês; estimativas variáveis ainda não confirmadas entram e aparecem como “Inclui R$ … a confirmar”.
- **Pago:** soma do valor efetivamente pago das quitações ativas. Uma quitação desfeita deixa de contar; a nova quitação conta uma vez.
- **Pendente:** soma das cobranças em aberto (nunca “previsto − pago”). **Atrasado** é a parte do pendente com vencimento anterior a hoje no fuso do espaço.
- **Ajustes:** acréscimos (pago acima da cobrança) e descontos (pago abaixo) das pagas, com o saldo líquido.
- **Pendências de meses anteriores:** pendentes com vencimento antes do mês, com os mesmos filtros (vazias quando o filtro é “Pagas” ou “Canceladas”).
- **Canceladas nunca entram nos totais**, mesmo quando listadas com o filtro “Canceladas”. Previsões de recorrências ainda não geradas não entram (ficam na tela de Recorrências e no planejamento futuro, H06.3); cada ocorrência gerada entra uma vez. De uma compra parcelada entram só as parcelas do mês, nunca o total da compra.

Os totais são somados no PostgreSQL sobre todo o conjunto autorizado do espaço (não sobre a página carregada), em transação somente leitura `REPEATABLE READ`, com o mesmo predicado da lista de despesas. Dinheiro trafega como string decimal e a tela formata sem converter para número, então totais acima de R$ 99.999.999,99 continuam exatos.

```text
GET /api/v1/reports/due-dashboard?month=2026-10&categoryId=<uuid>&status=ACTIVE
→ 200 {"month": "2026-10", "periodStart": "2026-10-01", "periodEnd": "2026-10-31", "dateBasis": "DUE_DATE",
       "today": "2026-10-15", "timeZone": "America/Sao_Paulo",
       "indicators": {"plannedCount": 8, "plannedTotal": "2792.33", "plannedEstimated": "180.00",
                      "paidCount": 5, "paidTotal": "1022.33", "pendingCount": 3, "pendingTotal": "1780.00",
                      "pendingEstimated": "180.00", "overdueCount": 1, "overdueTotal": "1500.00", "overdueEstimated": "0.00",
                      "adjustmentIncrease": "20.00", "adjustmentDiscount": "10.00", "adjustmentNet": "10.00"},
       "previousPending": {"dueBefore": "2026-10-01", "count": 1, "total": "800.00", "estimated": "0.00",
                           "overdueCount": 1, "overdueTotal": "800.00"}}
```

Erros: `400 REPORT_QUERY_INVALID` (mês fora de `AAAA-MM`, busca acima de 200 caracteres, categoria junto com “sem categoria”, responsável junto com “sem responsável”, UUID ou situação inválidos), `401` sem sessão e `403 ACTIVE_SPACE_ACCESS_NOT_FOUND` sem associação ativa. Não há parâmetro de espaço: ele vem da sessão.

Teste manual:

1. Em Despesas, cadastre no mês atual: “Aluguel” 1.500,00 vencendo há alguns dias (fica Atrasada), “Mercado” 100,00 vencendo no fim do mês, e “Internet” 120,00 vencendo no mês; quite a Internet por 110,00. Cadastre “Cartão” 800,00 vencendo no mês anterior.
2. Abra **Painel por vencimento**. Resultado esperado: Previsto R$ 1.720,00 (3 contas), Pago R$ 110,00, Pendente R$ 1.600,00, Atrasado R$ 1.500,00, Ajustes −R$ 10,00 (desconto R$ 10,00) e Pendências de meses anteriores R$ 800,00.
3. Filtre “Situação: Atrasadas” → Previsto R$ 1.500,00 e anteriores R$ 800,00. Filtre uma categoria e confira que todos os cartões e a lista mudam juntos.
4. Desfaça a quitação da Internet: Pago volta a R$ 0,00 e Pendente sobe para R$ 1.720,00. Quite de novo por 120,00: Pago R$ 120,00, sem contar duas vezes. Cancele o Mercado: ele sai do Previsto e do Pendente.
5. Clique em **Próximo ›**: o Cartão e o Aluguel entram em “Pendências de meses anteriores”.

Testes (Windows; em Linux, `./mvnw verify -Dit.test=ReportingPostgresIT`):

```powershell
backend\scripts\run-integration-tests.ps1 -Tests ReportingPostgresIT
```

## Pagamentos do mês (H06.2)

Em `/pagamentos` (links **Pagamentos do mês** em Despesas e no painel) ficam as **quitações ativas cuja data efetiva de pagamento cai no mês**. É outra população, diferente do painel por vencimento: uma conta que vence em setembro e é paga em outubro aparece em setembro no painel e em outubro aqui. A tela e a resposta identificam a base `PAYMENT_DATE`.

- **Indicadores** (sobre toda a seleção, não a página): quantidade de pagamentos, **pago no mês** (valores efetivamente pagos), **cobranças desses pagamentos** (valor original) e **ajustes de quitação** (pago − cobrança de cada quitação), com acréscimos, descontos e líquido. Não há pendente, saldo, receita nem resultado financeiro.
- **Desfazer e quitar de novo:** a quitação desfeita sai da visão; a nova aparece uma vez, na nova data. Canceladas nunca aparecem.
- **Correções:** a linha já mostra valor, data e pagador corrigidos. Corrigir a data para outro mês move o pagamento; a linha diz “Corrigida por … em …: campos” e quantas correções houve desde a quitação ativa. A trilha completa continua no histórico da despesa. Uma correção feita numa quitação que depois foi desfeita não é atribuída à quitação atual.
- **Cada linha** mostra data do pagamento, descrição, origem (avulsa, recorrência ou parcela n/N), categoria, vencimento (ou “Sem vencimento”), cobrança, pago, ajuste, **quem pagou**, **quem registrou e quando** (no fuso do espaço) e se foi quitação **em lote**.
- **Filtros:** descrição, categoria/sem categoria, responsável/sem responsável e pagador (sem filtro de situação). **Ordenação:** data do pagamento (padrão), valor pago ou descrição, crescente ou decrescente; 20 por página.

```text
GET /api/v1/reports/payments?month=2026-10&payerUserId=<uuid>&page=0&size=20&sort=PAYMENT_DATE&direction=ASC
→ 200 {"month": "2026-10", "dateBasis": "PAYMENT_DATE", "timeZone": "America/Sao_Paulo",
       "indicators": {"count": 6, "paidTotal": "1177.33", "chargeTotal": "1162.33", "adjustmentIncrease": "25.00",
                      "adjustmentDiscount": "10.00", "adjustmentNet": "15.00"},
       "content": [{"description": "Academia", "dueDate": "2026-10-01", "chargeAmount": "99.00", "paidAmount": "99.00",
                    "adjustment": "0.00", "paymentDate": "2026-10-01", "payerDisplayName": "Bia",
                    "recordedByDisplayName": "Ana", "batchPayment": false, "correctionCount": 1,
                    "lastCorrection": {"actorDisplayName": "Ana", "correctedAt": "...",
                                       "changedFields": ["paymentDate", "paidByUserId"]}, ...}],
       "page": 0, "size": 20, "totalElements": 6, "totalPages": 1, "sort": "PAYMENT_DATE", "direction": "ASC"}
```

Erros: `400 REPORT_QUERY_INVALID` (mês, página negativa, tamanho fora de 1–100, ordenação desconhecida, filtros incompatíveis), `401` sem sessão e `403 ACTIVE_SPACE_ACCESS_NOT_FOUND`.

Teste manual (continua o do painel):

1. Cadastre “Água” 150,00 vencendo no mês anterior e quite por 155,00 com data de hoje. Em **Pagamentos do mês**: ela aparece neste mês com ajuste +R$ 5,00; no **Painel por vencimento** ela aparece no mês anterior.
2. Quite duas contas juntas em Despesas (**Quitar selecionadas**) escolhendo o outro membro como pagador: as duas linhas dizem “Em lote, por <você>” e “Pago por <outro membro>”.
3. Corrija uma quitação mudando a data para o mês anterior: ela sai deste mês e aparece no anterior com “Corrigida por <você> …: data do pagamento”. Corrija o pagador: o filtro **Pagador** passa a encontrá-la pelo novo pagador.
4. Desfaça uma quitação: ela some da visão e os totais caem. Quite de novo por outro valor: ela volta uma vez, com o novo valor e sem a correção anterior.
5. Escolha um mês sem pagamentos: “Nenhum pagamento neste mês com os filtros escolhidos.” e todos os valores em R$ 0,00.

Testes: os mesmos de H06.1 (`ReportingPostgresIT` cobre as duas visões).

## Planejamento dos próximos meses (H06.3)

Em `/planejamento` (link **Planejamento** em Despesas, no painel e em Pagamentos) fica **quanto está comprometido no mês atual e nos 12 seguintes**, pelo vencimento e no fuso do espaço.

- **O que entra:** despesas já lançadas (avulsas, parcelas e ocorrências de recorrência, pendentes ou pagas) e **previsões** das recorrências ativas cujo período ainda não virou lançamento. Canceladas e o cabeçalho da compra parcelada não entram. Não há receitas, saldo nem orçamento.
- **Sem dupla contagem:** a previsão e o lançamento da mesma ocorrência (recorrência + período) nunca aparecem juntos. Quando a ocorrência é gerada, antecipada, remarcada, paga ou cancelada, o lançamento prevalece com seus dados reais; o período cancelado não volta como previsão.
- **Alteração e encerramento:** as previsões usam o valor, a descrição, a categoria e o responsável vigentes em cada período; depois do encerramento não há previsões. Previsão de categoria arquivada ou de responsável que saiu do espaço aparece sem eles, como a geração gravaria.
- **Indicadores** (horizonte e cada mês, sobre toda a seleção): total planejado; lançamentos × previsões; confirmado × **a confirmar** (estimativas); **já pago** (pelo valor efetivamente pago) e **em aberto** (pendentes + previsões); composição avulsas/parcelas/recorrências.
- **Filtros:** descrição, categoria/sem categoria e responsável/sem responsável, aplicados a lançamentos, previsões e totais. A lista mostra um mês por vez (botão **Ver** na tabela mês a mês), por data, 20 por página.
- **Somente leitura:** abrir o planejamento não gera lançamentos nem grava nada.

```text
GET /api/v1/reports/planning?month=2026-12&categoryId=<uuid>&page=0&size=20
→ 200 {"horizonStart": "2026-10", "horizonEnd": "2027-10", "dateBasis": "DUE_DATE", "today": "2026-10-15",
       "totals": {"count": 39, "plannedTotal": "6900.00", "confirmedTotal": "5220.00", "estimatedTotal": "1680.00",
                  "materializedCount": 11, "materializedTotal": "3770.00", "forecastCount": 28, "forecastTotal": "3130.00",
                  "paidCount": 2, "paidTotal": "980.00", "openCount": 37, "openTotal": "5900.00", ...},
       "months": [{"month": "2026-10", "totals": {...}}, ...13 meses],
       "month": "2026-12", "monthTotals": {...},
       "content": [{"kind": "EXPENSE", "description": "Luz", "date": "2026-12-02", "amount": "210.00", "estimated": true,
                    "status": "PENDING", ...},
                   {"kind": "FORECAST", "recurrenceId": "...", "description": "Internet", "date": "2026-12-05",
                    "amount": "100.00", "estimated": false, "status": "FORECAST", ...}],
       "page": 0, "size": 20, "totalElements": 4, "totalPages": 1}
```

Erros: `400 REPORT_QUERY_INVALID` (mês fora do horizonte, página negativa, tamanho fora de 1–100, filtros incompatíveis), `401` sem sessão e `403 ACTIVE_SPACE_ACCESS_NOT_FOUND`.

Teste manual:

1. Cadastre uma recorrência fixa de R$ 100,00 vencendo no dia 5 a partir do mês que vem. Em **Planejamento**, cada mês do horizonte a partir do próximo mostra R$ 100,00 em **Previsões**.
2. Em **Recorrências**, antecipe o lançamento do próximo mês. No planejamento, esse mês passa a mostrar o valor em **Lançamentos** e não mais em Previsões; o total não muda.
3. Remarque esse lançamento para o mês seguinte em Despesas: ele aparece no mês novo como lançamento, junto com a previsão daquele mês, e o mês antigo fica sem o valor.
4. Cancele o lançamento: o valor some e o período não volta como previsão.
5. Cadastre uma recorrência variável de R$ 180,00: seus valores aparecem **a confirmar** e somam no indicador “A confirmar”.
6. Encerre a recorrência: as previsões depois do último vencimento somem.
7. Filtre por uma categoria: totais, meses e lista mudam juntos.

Testes: `PlanningPostgresIT` (matriz M1–M14 em PostgreSQL real), `PlanningDomainTest`, `PlanningServiceTest`, `planning.component.spec.ts`, smoke E2E e E2E full-stack. Evidência: `docs/evidencias/H06.3.md`.

