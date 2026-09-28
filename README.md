# account_Manager

Gerenciador pessoal de despesas para um administrador e um convidado. A fundação técnica, o E01 e o E02 estão validados. Além dos fluxos manuais, H04.1–H04.3 cadastram recorrências, calculam o calendário, geram com segurança a ocorrência vigente e permitem visualizar/antecipar previsões. H04.4 permite confirmar o valor real de cobranças variáveis (em validação; veja `docs/progresso.md`).

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

O backend começa em `com.malyah.accountmanager`. Cada módulo funcional tem `domain`, `application`, `infrastructure` e `api`. O domínio não depende de Spring/JPA/HTTP; a aplicação não depende de adapters. `ArchitectureTest` torna essas fronteiras executáveis. V1–V14 cobrem identidade, despesas e organização; V15 adiciona definições de recorrência, idempotência e auditoria, sem materializar lançamentos; V16–V17 cobrem geração e antecipação; V18 adiciona a auditoria de confirmação de valores variáveis.

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

Consulte [`docs/progresso.md`](docs/progresso.md) para resultados executados e limites. H04.3 está concluída e H04.4 está em validação (falta o E2E full-stack com Docker). H03.3 permanece em validação independente. A próxima história funcional é H04.5, que não foi iniciada.

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

Teste de geração, previsão/antecipação, confirmação de valores variáveis, persistência e migrações V1–V18:

```powershell
backend\scripts\run-integration-tests.ps1 -Tests RecurrenceGenerationPostgresIT,RecurrencePostgresIT,VariableChargeConfirmationPostgresIT,ExpensePostgresIT,FlywayPostgresIT
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

