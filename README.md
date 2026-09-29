# account_Manager

Gerenciador pessoal de despesas para um administrador e um convidado. A fundação técnica, o E01, o E02 e o E04 estão validados. Além dos fluxos manuais, H04.1–H04.3 cadastram recorrências, calculam o calendário, geram com segurança a ocorrência vigente e permitem visualizar/antecipar previsões. H04.4 permite confirmar o valor real de cobranças variáveis e H04.5 permite alterar “este e os próximos” e encerrar recorrências (veja `docs/progresso.md`). H05.1 cadastra compras parceladas, com cada parcela lançada em Despesas, H05.2 mostra o progresso de cada compra e quita as parcelas selecionadas, e H05.3 altera ou cancela parcelas pendentes preservando as pagas. H06.1 mostra o painel do mês por vencimento (previsto, pago, pendente, atrasado e ajustes) H06.2 mostra os pagamentos do mês pela data efetiva, com pagador, autor e correções, H06.3 mostra o planejamento do mês atual e dos 12 seguintes, somando lançamentos e previsões sem contar duas vezes, e H06.4 exporta em CSV, para o Excel, a seleção de Despesas e, em arquivo separado, as previsões. H07.1 fecha o mês guardando um retrato imutável do resumo por vencimento, sem bloquear correções, H07.2 sinaliza quando os dados atuais passam a diferir do retrato salvo e H07.3 gera novas versões do fechamento, preservando e permitindo consultar as anteriores.

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
- WhatsApp: adapter real da Meta Cloud API (H08.4), **desligado por padrão** (`META_WHATSAPP_ENABLED=false`, provedor `PROVIDER_DISABLED`). Não existe adapter falso no código de produção; os testes usam um servidor HTTP local identificado como simulado (`FakeMetaServer`), que só existe no classpath de teste. Configuração e validação real: seção “Enviar e acompanhar pelo WhatsApp (H08.4)”.
- IA: nenhum adapter falso foi criado; fica indisponível até o E10.
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

## Exportar CSV (H06.4)

Em **Despesas**, o botão **Exportar CSV** baixa **todos** os lançamentos da seleção exibida (filtros aplicados, base do período e ordem), não só a página. Em **Planejamento**, **Exportar previsões (CSV)** baixa, em arquivo separado, as previsões de recorrência ainda não lançadas no horizonte, com os filtros do planejamento. Esta exportação não substitui a exportação administrativa completa de dados e anexos (procedimento 10).

- **O que entra:** a mesma população da lista. Canceladas só com a situação “Canceladas” ou “Todas”, identificadas na coluna Situação. Previsões nunca entram no arquivo de despesas. Anexos, observações e dados internos não entram.
- **Colunas (despesas):** Descrição; Categoria; Vencimento; Valor da cobrança; Estimativa (Sim/Não); Situação (Pendente, Atrasada, Paga, Cancelada); Valor pago; Data do pagamento; Responsável; Pagador; Origem (Avulsa, Recorrência, Parcela); Parcela (“2 de 3”); ID do lançamento.
- **Colunas (previsões):** Descrição; Categoria; Vencimento previsto; Valor previsto; Estimativa; Responsável; Tipo (“Previsão de recorrência”); ID da recorrência.
- **Formato para Excel em português:** UTF-8 com BOM (acentos corretos), separador `;`, datas `dd/mm/aaaa`, valores com vírgula decimal e sem separador de milhar (o Excel lê como número), textos entre aspas. Texto que começa com `=`, `+`, `-` ou `@` ganha um apóstrofo (`'=...`) para o Excel não executá-lo como fórmula; valores e datas não são alterados.
- **Limite:** até **10.000 linhas** por arquivo. Acima disso a exportação é recusada com a quantidade encontrada (“A seleção tem 12.345 registros e a exportação aceita até 10.000…”); reduza o período ou aplique filtros e exporte em partes. Nunca sai um arquivo cortado.
- **Seleção vazia:** a tela avisa “Nenhum lançamento com os filtros atuais; nenhum arquivo foi baixado.”
- **Momento do arquivo:** cada arquivo é o retrato do instante do pedido (uma única leitura consistente); “Atrasada” usa o dia atual no fuso do espaço.

```text
GET /api/v1/reports/expenses/export?dateFrom=2026-10-01&dateTo=2026-10-31&dateBasis=DUE_DATE&status=ALL&sort=REFERENCE_DATE&direction=ASC
→ 200 text/csv;charset=UTF-8
  Content-Disposition: attachment; filename="despesas_vencimento_2026-10-01_a_2026-10-31.csv"
  X-Export-Rows: 10
  Cache-Control: no-store
  "Descrição";"Categoria";"Vencimento";"Valor da cobrança";"Estimativa";"Situação";"Valor pago";...
  "Aluguel; sala ""B""";"Casa";05/10/2026;1500,00;Não;Atrasada;;;"Bia";;Avulsa;;<id>
  "Sofá";;25/10/2026;33,33;Não;Pendente;;;;;Parcela;1 de 3;<id>

GET /api/v1/reports/planning/export?categoryId=<uuid>
→ 200 text/csv, filename="previsoes_2026-10_a_2027-10.csv"
```

Parâmetros: os mesmos de `GET /api/v1/expenses` sem `page`/`size` (padrão: mês atual por vencimento, ativas, data crescente) e, para previsões, os do planejamento. Erros: `422 EXPORT_LIMIT_EXCEEDED`, `400 REPORT_QUERY_INVALID`, `401` sem sessão e `403 ACTIVE_SPACE_ACCESS_NOT_FOUND`.

Teste manual (inclui a conferência no Excel, não executada no container de desenvolvimento):

1. Em Despesas, cadastre no mês atual “Luz; água "casa"” 150,00, “=1+1” 0,01, “Café” 12,50 e “Revisão” 30,00; quite o Café e cancele a Revisão. Clique **Exportar CSV**: baixa `despesas_vencimento_<início>_a_<fim>.csv` com 3 linhas (a cancelada não entra) e a tela diz quantos registros foram baixados.
2. Abra o arquivo no Excel em português com duplo clique: acentos corretos, uma coluna por campo, “Luz; água "casa"” inteira numa célula, `=1+1` como texto `'=1+1` (sem calcular 2), datas como datas e valores como números (some a coluna Valor da cobrança e confira com a lista).
3. Mude a Situação para **Todas**, aplique e exporte de novo: a cancelada aparece como “Cancelada”.
4. Troque a base para **Data de pagamento**: o arquivo passa a se chamar `despesas_pagamento_…` e traz só as pagas no período.
5. Digite outra busca sem aplicar e exporte: o arquivo segue a seleção que está na tela.
6. Em Planejamento, com uma recorrência cadastrada, clique **Exportar previsões (CSV)**: cada linha é “Previsão de recorrência”; o mês já lançado não aparece.

Testes: `ExportPostgresIT` (matriz C1–C13 em PostgreSQL real, 10.000 linhas e concorrência), `CsvDocumentTest`, `ExportServiceTest`, `ReportExportHttpTest`, `csv-export-button.component.spec.ts`, smoke E2E com download e E2E full-stack com download real. Evidência: `docs/evidencias/H06.4.md`.


## Fechamento do mês (E07)

Em **Fechamento** (`/fechamento`, link no Painel) um membro guarda o **retrato do mês revisado**. Fechar é simbólico: não quita, não cancela, não esconde contas e não mexe em lembretes; qualquer correção continua permitida depois.

### Fechar mês com resumo (H07.1)

- **Quem e quando:** administrador e convidado; o mês atual (no fuso do espaço) ou meses anteriores. Mês futuro não pode ser fechado. Cada mês tem um único fechamento; atualizar o retrato é gerar nova versão (H07.3).
- **Base temporal:** vencimento, igual ao Painel (paga sem vencimento entra pela data do pagamento), sem filtros. Quitação feita em outro mês não move a conta; a visão por pagamento não faz parte do fechamento.
- **O que é salvo:** totais do painel (previsto, parte a confirmar, pago, pendente, atrasado na data do fechamento, ajustes), totais por categoria com o nome do momento (“Sem categoria” por último), pendências e cada lançamento com descrição, origem/parcela, vencimento, situação, cobrança, estimativa e valor pago; autor, instante, data local e fuso. Canceladas, previsões ainda não geradas e o total de compras parceladas não entram; pendências de meses anteriores ficam no fechamento do mês delas.
- **Pendências:** se o mês tem contas pendentes, a confirmação mostra quantas e quanto somam e exige marcar “Estou ciente das pendências e quero fechar mesmo assim.”. Mês sem lançamentos pode ser fechado e fica com o retrato zerado.
- **Retrato imutável:** a consulta lê só o que foi gravado; renomear categoria, corrigir descrição ou quitar depois não muda o retrato. O banco recusa alterar ou apagar versões, linhas, categorias e eventos do fechamento.
- **Repetição e concorrência:** a tela envia uma `Idempotency-Key` e a reaproveita se a conexão cair; a mesma chave devolve o mesmo fechamento. Dois membros fechando ao mesmo tempo geram um único fechamento; o outro vê “Este mês já foi fechado” e a tela recarrega o retrato salvo.

```text
GET  /api/v1/reports/closings/2026-10
→ 200 { month, periodStart, periodEnd, dateBasis: "DUE_DATE", today, timeZone, closable,
        saved: null | { version: 1, authorDisplayName, closedAt, businessDate, indicators, categories, lines, ... },
        current: { indicators, categories, lines, ... } }

POST /api/v1/reports/closings/2026-10   Idempotency-Key: <uuid>   X-XSRF-TOKEN: <token>
     { "acknowledgePending": true }
→ 201 (200 na repetição da mesma chave)
```

Erros: `422 CLOSING_PENDING_CONFIRMATION_REQUIRED` (pendências sem confirmação; nada é gravado), `422 CLOSING_MONTH_NOT_ALLOWED` (mês futuro), `409 MONTH_ALREADY_CLOSED`, `409 IDEMPOTENCY_CONFLICT`, `400 REPORT_QUERY_INVALID`, `401` sem sessão e `403 ACTIVE_SPACE_ACCESS_NOT_FOUND`.

Teste manual:

1. No mês atual, cadastre “Aluguel” 1.500,00 pendente com vencimento já passado, “Luz” como recorrência variável antecipada e “Telefone” 120,00 quitado por 110,00. Confira os números no Painel.
2. Abra **Fechamento**: aparece “Mês não fechado” e os dados atuais com os mesmos totais do Painel. Clique **Fechar mês…**: o aviso diz quantas contas estão pendentes e o botão só habilita depois de marcar a caixa.
3. Confirme: aparece “Mês fechado · versão 1”, com seu nome e o horário. Volte ao Painel: as pendentes continuam pendentes.
4. Renomeie a categoria e corrija a descrição do Aluguel; o retrato salvo continua com os nomes antigos.
5. Tente o mês seguinte: a tela informa que só é possível fechar o mês atual ou anteriores.

Testes: `MonthClosingPostgresIT` (matriz C1–C14 em PostgreSQL real, com concorrência, rollback e alteração simultânea), `ClosingSummaryTest`, `MonthClosingServiceTest`, `MonthClosingHttpTest`, `TransactionalMonthClosingUseCaseTest`, `month-closing.component.spec.ts`, smoke E2E e E2E full-stack. Evidência: `docs/evidencias/H07.1.md`.

### Sinalizar alterações posteriores (H07.2)

- **Situação do mês:** *Mês não fechado*, *Atualizado* (os dados atuais têm os mesmos valores e classificações do retrato) ou *Alterado depois do fechamento*. O servidor calcula a situação a cada consulta comparando o retrato salvo com os dados atuais; nada é marcado nem gravado, então nenhuma alteração se perde, mesmo feita durante o fechamento.
- **O que conta:** inclusão no mês (nova despesa, parcela, ocorrência, data movida para o mês), saída do mês (cancelamento, data movida para outro mês) e mudança de vencimento, situação (quitação, reversão), cobrança, estimativa/confirmação, valor pago ou categoria. Mudança de vencimento, ou da data do pagamento de conta sem vencimento, entre meses aparece nos dois meses fechados.
- **O que não conta:** descrição, nome da categoria, observações, responsável, pagador, data do pagamento de conta com vencimento e o passar do tempo (o atraso do retrato é o da data do fechamento; o atual aparece nos dados atuais). Quitar e reverter em seguida não deixa diferença.
- **Tela:** a lista “O que mudou depois do fechamento” mostra cada conta com o valor salvo e o atual de cada campo; “Retrato salvo (versão N)” e “Dados atuais do mês” ficam lado a lado (um abaixo do outro no celular). “Fechamentos de AAAA” lista os meses fechados do ano com versão, autor, instante e situação; clique para abrir.

```text
GET /api/v1/reports/closings/2026-10
→ 200 { ..., status: "OUTDATED", changes: [ { kind: "CHANGED", expenseId, fields: ["SITUATION", "PAID_AMOUNT"],
                                              saved: {...}, current: {...} } ], saved: {...}, current: {...} }
GET /api/v1/reports/closings?year=2026
→ 200 { year: 2026, closings: [ { month: "2026-10", version: 1, authorDisplayName, closedAt, status: "OUTDATED" } ] }
```

Teste manual (depois do teste da H07.1):

1. Com o mês fechado, abra **Fechamento**: aparece “Atualizado”.
2. Em **Despesas**, quite o Aluguel e corrija o valor da Luz. Volte ao **Fechamento**: aparece “Alterado depois do fechamento: 2 diferença(s)”, com “Situação: Pendente → Paga” e “Cobrança: … → …”. O retrato salvo continua com os valores antigos; os dados atuais, ao lado, mostram os novos.
3. Renomeie uma categoria ou corrija só a descrição de uma conta: a situação não muda por isso.
4. Mude o vencimento de uma conta de um mês fechado para outro mês fechado: os dois aparecem como alterados em “Fechamentos de AAAA”.

Testes: `MonthClosingChangesPostgresIT` (matriz D1–D17 em PostgreSQL real, com concorrência), `ClosingComparisonTest`, `MonthClosingServiceTest`, `MonthClosingHttpTest`, `month-closing.component.spec.ts`, smoke E2E e E2E full-stack (inclusão depois do fechamento). Evidência: `docs/evidencias/H07.2.md`.

### Gerar e consultar versões (H07.3)

- **Quem e quando:** administrador e convidado, em mês já fechado. Não há motivo obrigatório. A nova versão guarda os dados atuais com as mesmas regras do fechamento e passa a ser a **vigente**; as anteriores continuam salvas, imutáveis e consultáveis (não há exclusão). Gerar versão não bloqueia correções.
- **Sem diferenças:** permitido; a tela avisa que a nova versão só atualizará nomes, descrições e a data usada para o atraso.
- **Pendências:** mesma confirmação do fechamento.
- **Concorrência e repetição:** o pedido leva a versão vigente que você revisou (`expectedVersion`). Se outro membro gerou uma versão antes, nada é gravado, aparece “Outra versão foi gerada enquanto você revisava” e a tela recarrega. A mesma `Idempotency-Key` devolve a versão já gerada. Versão, troca da vigente, evento `VERSION_GENERATED` e chave são gravados juntos; uma falha mantém a versão anterior vigente.
- **Sinalização:** depois da nova versão, a comparação da H07.2 usa a vigente; só continua sinalizado o que mudou depois dela.
- **Tela:** botão **Gerar nova versão…**, lista “Versões de <mês>” (autor, instante, totais, “Vigente”) e **Ver retrato** para abrir uma versão anterior exatamente como foi salva. Não há comparação visual entre versões nem exportação nova.

```text
POST /api/v1/reports/closings/2026-10/versions   Idempotency-Key: <uuid>   X-XSRF-TOKEN: <token>
     { "expectedVersion": 1, "acknowledgePending": true }
→ 201 Location: /api/v1/reports/closings/2026-10/versions/2   (200 na repetição da mesma chave)
GET  /api/v1/reports/closings/2026-10/versions      → 200 { month, currentVersion: 2, versions: [ { version: 1, ..., current: false }, ... ] }
GET  /api/v1/reports/closings/2026-10/versions/1    → 200 { month, currentVersion: 2, current: false, snapshot: {...} }
```

Erros: `409 MONTH_NOT_CLOSED`, `409 CLOSING_VERSION_CONFLICT`, `409 IDEMPOTENCY_CONFLICT`, `422 CLOSING_PENDING_CONFIRMATION_REQUIRED`, `404 CLOSING_VERSION_NOT_FOUND`, `400 REPORT_QUERY_INVALID`, `401` e `403`.

Teste manual (continuação da H07.2):

1. Com o mês “Alterado depois do fechamento”, clique **Gerar nova versão…**, marque a caixa de pendências se aparecer e confirme **Gerar versão 2**.
2. Aparece “versão 2 (vigente)” e “Atualizado”; em “Versões de <mês>”, a versão 1 tem os totais originais e a 2 os novos.
3. Clique **Ver retrato** na versão 1: o retrato mostra os valores do primeiro fechamento (por exemplo, o Aluguel ainda pendente).
4. Em outra aba com a versão 1 ainda na tela, tente gerar outra versão: aparece o aviso de que outra versão foi gerada e a tela recarrega.

Testes: `MonthClosingVersionsPostgresIT` (matriz V1–V13 em PostgreSQL real, com concorrência, repetição e falha), regressões `MonthClosingPostgresIT` e `MonthClosingChangesPostgresIT`, `MonthClosingServiceTest`, `MonthClosingHttpTest`, `month-closing.component.spec.ts`, smoke E2E e E2E full-stack (versão 2 e consulta da versão 1). Evidência: `docs/evidencias/H07.3.md`.

## Lembretes e WhatsApp (E08)

O E08 está em andamento: H08.1, H08.2 e H08.3 estão entregues; H08.4 (envio real pelo WhatsApp) está implementada e **em validação** (falta a P03 e um envio real autorizado); H08.5 (retentativas, reconciliação e retomada) não existe nesta versão.

### Configurar canal, consentimento e horários (H08.1)

Tela **Lembretes e WhatsApp** (`/lembretes`, link em **Membros e acesso** e na barra de Despesas).

- **Quem:** só o administrador altera horários, número, consentimento e ativação. O convidado vê a explicação, os horários e se o canal está configurado, sem controles, sem o número completo (só o final) e sem autor/data do consentimento. O convidado nunca recebe WhatsApp; responsável ou pagador de uma despesa não mudam o destinatário.
- **Horários:** padrão 09:00 e 18:00, `HH:mm`, no fuso do espaço (`America/Sao_Paulo`). O segundo precisa ser posterior ao primeiro. Valem para os resumos do espaço (H08.2).
- **Número:** celular brasileiro; a entrada aceita espaços, parênteses, hífen e `+55` opcional e é guardada em E.164 (`+5511987654321`). Trocar o número revoga o consentimento anterior e desativa o canal.
- **Consentimento:** explícito. O administrador lê o texto (versão `WHATSAPP-RESUMOS-V1`), confirma o número e marca o aceite; o servidor grava autor, número, versão do texto e instante UTC. Informar número ou ativar nunca cria consentimento. A revogação desativa o canal na mesma operação e o histórico fica guardado.
- **Ativar:** exige número e consentimento ativo do administrador atual. Desativar mantém número e consentimento.
- **Canal x provedor:** a tela mostra separadamente o que foi configurado e a disponibilidade do envio. Enquanto a Meta não estiver configurada no servidor, o provedor responde `PROVIDER_DISABLED` ou `PROVIDER_NOT_CONFIGURED` e o estado efetivo de um canal ativo é `PROVIDER_UNAVAILABLE`. Não existe tela nem campo de token; as credenciais da Meta ficam só na configuração do backend (H08.4).
- **Transferência de administração:** na mesma transação da transferência, o consentimento é revogado (`ADMINISTRATION_TRANSFERRED`), o número é apagado e o canal desativado; os horários ficam. O novo administrador informa o próprio número e consente.
- **Concorrência, repetição e auditoria:** toda alteração leva `expectedVersion` e `Idempotency-Key`; versão diferente dá `409 NOTIFICATION_SETTINGS_VERSION_CONFLICT` e a tela recarrega. Os eventos (horários, número mascarado, consentimento, ativação) aparecem para o administrador em “Alterações recentes”. Logs não contêm número.

```text
GET  /api/v1/notifications/settings                              → 200 { canManage, timeZone, version, schedule, whatsapp: { state, provider, consent, ... } }
PUT  /api/v1/notifications/settings/schedule                     { "expectedVersion": 0, "firstTime": "08:30", "secondTime": "20:00" }
PUT  /api/v1/notifications/settings/whatsapp/recipient           { "expectedVersion": 1, "phone": "(11) 98765-4321" }
POST /api/v1/notifications/settings/whatsapp/consent             { "expectedVersion": 2, "phone": "(11) 98765-4321", "accepted": true }
PUT  /api/v1/notifications/settings/whatsapp/channel             { "expectedVersion": 3, "enabled": true }
POST /api/v1/notifications/settings/whatsapp/consent/revocation  { "expectedVersion": 4 }
GET  /api/v1/notifications/settings/events                       → 200 { items: [...] } (só o administrador)
```

Todas as escritas exigem `Idempotency-Key` e `X-XSRF-TOKEN`. Erros: `400 WHATSAPP_RECIPIENT_INVALID`, `400 REMINDER_SCHEDULE_INVALID_FORMAT`, `400 REMINDER_SETTINGS_INVALID`, `422 REMINDER_SCHEDULE_INVALID`, `422 WHATSAPP_RECIPIENT_REQUIRED`, `422 WHATSAPP_CONSENT_REQUIRED`, `409 WHATSAPP_RECIPIENT_MISMATCH`, `409 WHATSAPP_CONSENT_NOT_ACTIVE`, `409 NOTIFICATION_SETTINGS_VERSION_CONFLICT`, `409 IDEMPOTENCY_CONFLICT`, `403 NOTIFICATION_ADMINISTRATOR_REQUIRED`, `401` e `403`.

Teste manual (use um número fictício, por exemplo `(11) 98765-4321`):

1. Como administrador, abra **Lembretes e WhatsApp**: 09:00/18:00, número “Não cadastrado”.
2. Altere para 08:30 e 20:00 e salve; tente 20:00 e 08:30: aparece o erro do segundo horário.
3. Informe o número e salve; clique **Autorizar recebimento…**, leia o texto, marque “Li e autorizo o envio para este número.” e clique **Registrar consentimento**. Clique **Ativar canal**: o estado diz “Canal configurado e ativo, mas o envio real ainda não está disponível”.
4. Entre como convidado: horários visíveis, “terminado em 4321”, nenhum botão de alteração.
5. Em **Membros e acesso**, transfira a administração ao convidado. Na tela de lembretes do novo administrador: número e consentimento vazios, horários 08:30/20:00 mantidos, evento “Consentimento revogado” em “Alterações recentes”.

Testes: `ReminderSettingsPostgresIT` (matriz C1–C16 em PostgreSQL real, com transferência, concorrência e repetição), `ReminderSettingsDomainTest`, `ReminderSettingsServiceTest`, `ReminderSettingsHttpTest`, `MembershipManagementServiceTest`, `reminder-settings.component.spec.ts`, smoke E2E e E2E full-stack. Evidência: `docs/evidencias/H08.1.md`.

### Calcular elegibilidade e resumo (H08.2)

O backend decide, no fuso do espaço, quais contas cada horário menciona e grava **um resumo lógico por espaço, data e horário**. Nada é enviado nesta versão: o resumo fica disponível no aplicativo (pelo link) e o registro do WhatsApp só é marcado como previsto quando o canal está pronto (H08.4 fará o envio).

**Calendário** (distância até o vencimento, como no PRD 10.2):

| Vencimento em relação a hoje | Primeiro horário | Segundo horário |
|---|---|---|
| Seis dias ou mais à frente | não | não |
| De cinco a dois dias à frente | sim | não |
| Amanhã | sim | sim |
| Hoje | sim | sim |
| Vencida (pendente) | sim, todos os dias | não |
| Paga ou cancelada | não | não |

Exemplo com hoje = 05/10/2026 e horários 09:00/18:00: contas pendentes vencendo em 04/10, 05/10, 06/10, 07/10, 10/10 e 11/10. O resumo das 09:00 traz 04/10 (atrasada), 05/10, 06/10, 07/10 e 10/10; o das 18:00 traz 05/10 e 06/10; 11/10 só entra no primeiro horário de 06/10.

**Composição:** quantidade e total exato de **todas** as elegíveis, quantidade e total das estimativas e quantidade de atrasadas; até cinco detalhes (descrição, parcela n/N, valor, vencimento, “estimada”), atrasadas primeiro, depois o vencimento mais próximo, empate pela descrição; excedente “e mais X contas”; link `APP_BASE_URL/lembretes/resumos/{id}` para a lista completa (exige sessão; outro espaço recebe `404`). Sem conta elegível, nenhum resumo é gerado.

**Execução:** o job `ReminderSummaryJob` roda a cada minuto (`app.jobs.reminders.fixed-delay-ms`, padrão 60000; desligue com `APP_JOBS_REMINDERS_ENABLED=false`). Um horário é processado do instante programado até uma hora depois ou até o próximo horário; perdido esse prazo, fica registrado como `MISSED` e não é enviado atrasado. Cada horário é registrado uma vez (`GENERATED`, `EMPTY` ou `MISSED`), mesmo com reexecução ou dois processos. O conteúdo é lido no momento do processamento: quitação, cancelamento e mudança de vencimento valem a partir do próximo horário, e um horário já processado não é refeito.

**Recorrências:** antes de montar um horário, as ocorrências que vencem no alcance dele são materializadas pela mesma fila e pelo mesmo materializador da H04.2, mesmo quando são do mês seguinte (vence 03/10, gerada até 28/09). Se a geração falhar ou estiver desligada, a ocorrência aparece uma vez como previsão. Parcelas aparecem cada uma como n/N.

**Canais:** o registro `IN_APP` existe sempre (os dois membros). O `WHATSAPP` fica `PLANNED` (destinatário: o administrador que consentiu) só com número, consentimento do administrador atual, canal ativo e provedor disponível; senão fica `SKIPPED` com o motivo (`RECIPIENT_REQUIRED`, `CONSENT_REQUIRED`, `DISABLED`, `PROVIDER_UNAVAILABLE`). Nesta versão o motivo é sempre um desses, porque o provedor está indisponível.

```text
GET /api/v1/notifications/reminders/preview?date=2026-10-05&slot=FIRST  → 200 { date, slot, scheduledTime, today, summary: {count, total, items, text, channels} | null }
GET /api/v1/notifications/reminders/summaries/{id}                      → 200 { id, link, generatedAt, items, ... }  (404 fora do espaço)
```

Erros: `400 REMINDER_QUERY_INVALID` (data fora de hoje…+60 dias, formato, horário), `404 REMINDER_SUMMARY_NOT_FOUND`, `401` e `403`.

**Contratos preparados para as próximas histórias (não implementadas):** H08.3 lê `reminder_summaries`/`reminder_summary_items` e o canal `IN_APP` para listar os avisos; H08.4 envia os registros `WHATSAPP` com `PLANNED` usando `ReminderSummaryText` (mensagem única), revalida consentimento e conteúdo antes de cada tentativa e substitui `UnavailableWhatsAppProvider` pelo adapter da Meta (feito na H08.4: `MetaWhatsAppProvider`, com a mensagem em `WhatsAppSummaryTemplate`); H08.5 usa a identidade espaço + data + horário + canal e a janela de `ReminderWindow` para não acumular tentativas. Nenhuma tabela de tentativa, aceite ou entrega foi criada.

Teste manual com datas controladas (não depende do relógio do servidor):

1. Em **Despesas**, cadastre pendentes com vencimento hoje, amanhã, daqui a dois dias, daqui a seis dias e uma vencida ontem.
2. Em **Lembretes e WhatsApp**, abra **Prévia dos resumos**: o primeiro horário de hoje lista a vencida (Atrasada), hoje, amanhã e daqui a dois dias; não lista a de seis dias.
3. Escolha **Segundo** e **Simular**: só hoje e amanhã.
4. Escolha a data de daqui a um dia e o **Primeiro**: a de seis dias agora está a cinco e entra; a de hoje aparece como atrasada.
5. Quite uma delas e simule de novo: ela sai. Com mais de cinco contas aparece “e mais X contas” e a lista completa abaixo.

Os resumos gravados pelo job (com relógio real) abrem pelo link `/lembretes/resumos/{id}` e pela tela **Avisos** (H08.3).

Testes: `ReminderSummaryPostgresIT` (matriz E1–E16 em PostgreSQL real, com relógio controlado, materialização concorrente e isolamento), `ReminderSummaryDomainTest`, `ReminderSummaryServiceTest`, `ReminderSummaryHttpTest`, `reminder-summary.component.spec.ts`, smoke E2E e E2E full-stack (prévia de uma conta real em dois horários e em outra data). Evidência: `docs/evidencias/H08.2.md`.

### Avisos no aplicativo (H08.3)

Tela **Avisos** (`/avisos`, link em Despesas, Lembretes e na página do resumo). Não há configuração nova: os avisos seguem os horários da H08.1 e o job da H08.2. Nenhum push ou email é enviado.

- **Quem recebe:** cada resumo gerado vira um aviso para cada membro ativo no momento da geração (administrador e convidado). O convidado recebe lembretes só por aqui. Um membro removido deixa de acessar (`403`) e não recebe avisos novos.
- **Falhas do WhatsApp:** quando o canal está ativado com consentimento, mas o resumo não pôde seguir pelo WhatsApp, o administrador recebe um aviso “WhatsApp não enviado” (marcado “Somente administrador”). A mensagem vem de um catálogo fixo (`PROVIDER_UNAVAILABLE`, `PROVIDER_REJECTED`, `DELIVERY_FAILED`, `RECIPIENT_INVALID`, `RESULT_UNCERTAIN`), nunca da resposta do provedor. O convidado não recebe nem vê esses avisos; depois de uma transferência, o antigo administrador também deixa de vê-los. Com a H08.4 todos os motivos passam a ocorrer, além de `NOT_SENT_IN_WINDOW` (o horário terminou antes do envio).
- **Ler e dispensar:** estados individuais. “Marcar como lido” e “Dispensar” mudam só o aviso de quem clicou; dispensar também marca como lido e move o aviso para **Dispensados**. Repetir não muda o primeiro instante. Nada disso paga, cancela ou altera contas, nem interrompe os próximos lembretes.
- **Histórico × atual:** o aviso e o resumo mostram as contas como estavam no horário. Na página do resumo, cada conta que mudou mostra a situação atual (“Agora: paga”, “Agora: cancelada”, “Agora vence em …”) e o link **Abrir conta** (`/despesas?despesa={id}`), que usa a autorização normal das despesas.
- **Ordem e paginação:** mais recentes primeiro; 20 por página, até 100 (`size`). Contagem de não lidos na própria lista.
- **Retenção:** não definida na documentação; nenhum aviso é apagado (registrado em T33 como dúvida para P08/P09).

```text
GET  /api/v1/notifications/inbox?view=ACTIVE|DISMISSED&page=0&size=20  → 200 { items, page, size, totalItems, totalPages, unreadCount, view }
GET  /api/v1/notifications/inbox/unread-count                            → 200 { unreadCount }
POST /api/v1/notifications/inbox/{id}/read                               → 200 aviso (exige X-XSRF-TOKEN)
POST /api/v1/notifications/inbox/{id}/dismiss                            → 200 aviso (exige X-XSRF-TOKEN)
```

Erros: `400 NOTIFICATION_QUERY_INVALID`, `404 NOTIFICATION_NOT_FOUND` (inexistente, de outro membro ou de outro espaço), `403 ACTIVE_SPACE_ACCESS_NOT_FOUND`, `403` sem token CSRF e `401` sem sessão.

Teste manual:

1. Como administrador, cadastre uma despesa pendente vencendo hoje. Em **Lembretes e WhatsApp**, deixe o primeiro horário em `00:00` e ponha o segundo um ou dois minutos à frente (só funciona se o segundo horário de hoje ainda não foi processado, ou seja, antes do horário anterior + 1 h).
2. Espere o job (até um minuto depois do horário). Em **Avisos**, os dois membros veem “Contas a pagar: segundo horário de …”. Com o canal ativado, o administrador também vê “WhatsApp não enviado”.
3. Como administrador, clique **Marcar como lido**; como convidado, o aviso dele continua “Novo”. Como convidado, clique **Dispensar**: some dos ativos e aparece em **Dispensados**.
4. Abra o resumo, clique **Abrir conta**: a despesa continua pendente. Quite-a em Despesas e reabra o resumo: ela aparece com “Agora: paga”, e o próximo horário não a inclui.

Testes: `MemberNotificationPostgresIT` (matriz N1–N14 em PostgreSQL real, com relógio controlado, concorrência, remoção e transferência), `MemberNotificationServiceTest`, `MemberNotificationHttpTest`, `ReminderSummaryServiceTest`, `notification-inbox.component.spec.ts`, `reminder-summary.component.spec.ts`, smoke E2E e E2E full-stack (job real gerando o aviso). O E2E full-stack usa o segundo horário de hoje e precisa rodar antes das 17:55 de São Paulo. Evidência: `docs/evidencias/H08.3.md`.

### Enviar e acompanhar pelo WhatsApp (H08.4)

**Estado: em validação.** O envio, o acompanhamento e o webhook estão implementados e testados contra uma Meta **simulada**. Nenhuma mensagem real foi enviada: faltam a P03 (número dedicado, credenciais, template aprovado, webhook HTTPS público e custo conhecido) e a autorização do Diego para o primeiro envio. Evidência: `docs/evidencias/H08.4.md`.

**Como funciona**

- Só o **administrador ativo** recebe, no número cadastrado e com o consentimento dele para esse número (H08.1). O convidado nunca recebe WhatsApp, nem como teste.
- O job `WhatsAppDeliveryJob` roda a cada 30 s (`app.jobs.whatsapp.fixed-delay-ms`, desligável com `app.jobs.whatsapp.enabled=false`) e pega os resumos com canal `WHATSAPP` previsto. Para cada um: (1) numa transação curta, bloqueia a configuração do espaço e **revalida** administrador, consentimento, número, ativação, provedor, janela do horário e o conteúdo (as contas do resumo que continuam pendentes e elegíveis; nada novo entra); (2) chama a Meta **fora de transação**; (3) grava o resultado noutra transação curta.
- **Uma entrega lógica por resumo** (espaço + data + horário + canal): índice único em `whatsapp_deliveries.summary_id`. Tentativas ficam em `whatsapp_attempts`; confirmações do webhook em `whatsapp_status_events` (uma por mensagem e situação).
- **Aceite não é entrega.** Situações: `ATTEMPTING` (tentativa gravada), `ACCEPTED` (Meta aceitou e devolveu o id; **ainda não entregue**), `SENT`, `DELIVERED`, `READ` (confirmações do webhook), `FAILED`, `REJECTED`, `UNCERTAIN` (sem prova de aceite nem de recusa) e `SKIPPED` (bloqueado na revalidação, com o motivo).
- **Nada é reenviado automaticamente.** Timeout depois de enviar, erro 5xx, resposta 200 sem id e tentativa interrompida (mais de 10 min em `ATTEMPTING`) viram `UNCERTAIN`, com aviso ao administrador. Retentativa e reconciliação são da H08.5. Não prometemos entrega exatamente uma vez.
- Falhas viram aviso interno só para o administrador (H08.3): `PROVIDER_REJECTED`, `RECIPIENT_INVALID`, `PROVIDER_UNAVAILABLE`, `DELIVERY_FAILED`, `RESULT_UNCERTAIN`, `NOT_SENT_IN_WINDOW`. O aviso interno do resumo continua para os dois membros e o resto do aplicativo não depende da Meta.
- Pagar é só no aplicativo: o webhook nunca altera despesas; mensagens recebidas (texto do usuário) são ignoradas e não são gravadas.
- **Acompanhamento:** a página do resumo (`/lembretes/resumos/{id}`) mostra ao administrador a situação, o número mascarado, a quantidade enviada e os instantes de tentativa, aceite, envio, entrega, leitura ou falha (`GET /api/v1/notifications/reminders/summaries/{id}/whatsapp`; o convidado recebe `403`).
- **Mensagem de teste:** em **Lembretes e WhatsApp**, com consentimento ativo e provedor configurado, o administrador pode enviar o template de teste (sem dados de contas) ao próprio número (`POST /api/v1/notifications/settings/whatsapp/test-message` com `Idempotency-Key`; repetir a chave não reenvia; um teste por minuto).

**Classificação da resposta da Meta** (códigos a conferir na P03): 2xx com `messages[0].id` → `ACCEPTED`; 2xx sem id → `UNCERTAIN`; conexão recusada, DNS ou timeout de conexão → `FAILED` (`PROVIDER_UNAVAILABLE`); timeout depois de enviar ou 5xx → `UNCERTAIN`; 429 ou `130429`/`131048`/`131056`/`80007` → `FAILED` (`PROVIDER_UNAVAILABLE`); 4xx com `131026`/`131030` → `REJECTED` (`RECIPIENT_INVALID`); outro 4xx → `REJECTED` (`PROVIDER_REJECTED`). Só o código numérico é gravado; o texto da Meta nunca.

**Configuração** (backend; nada disso vai para o banco ou para o Git)

| Variável | Uso | Padrão |
|---|---|---|
| `META_WHATSAPP_ENABLED` | Liga o provedor. Só envia com todos os valores abaixo. | `false` |
| `META_WHATSAPP_API_BASE_URL` | Graph API. Em produção precisa ser HTTPS. | `https://graph.facebook.com` |
| `META_WHATSAPP_API_VERSION` | Versão da Graph API (`vNN.N`). Conferir a versão suportada na P03; a documentação consultada usa `v23.0` como exemplo. | vazio |
| `META_WHATSAPP_PHONE_NUMBER_ID` | ID do número dedicado remetente. | vazio |
| `META_WHATSAPP_SUMMARY_TEMPLATE` / `META_WHATSAPP_TEMPLATE_LANGUAGE` | Nome e idioma do template aprovado do resumo. | vazio / `pt_BR` |
| `META_WHATSAPP_TEST_TEMPLATE` / `META_WHATSAPP_TEST_TEMPLATE_LANGUAGE` | Template de teste sem dados financeiros (opcional). | vazio / `pt_BR` |
| `META_WHATSAPP_TOKEN` (`META_WHATSAPP_TOKEN_FILE`) | Token de acesso. **Segredo.** | vazio |
| `META_WHATSAPP_APP_SECRET` (`META_WHATSAPP_APP_SECRET_FILE`) | App secret, usado só para validar a assinatura do webhook. **Segredo.** | vazio |
| `META_WHATSAPP_VERIFY_TOKEN` (`META_WHATSAPP_VERIFY_TOKEN_FILE`) | Token que você define e informa no painel da Meta ao cadastrar o webhook. **Segredo.** | vazio |

Em produção os três segredos ficam em `deploy/secrets/meta_whatsapp_token.txt`, `meta_whatsapp_app_secret.txt` e `meta_whatsapp_verify_token.txt` (Docker secrets montados em `/run/secrets/…`; o `docker-entrypoint.sh` os exporta). Os três arquivos precisam existir para o Compose subir; podem ficar vazios enquanto `META_WHATSAPP_ENABLED=false`. Com o provedor ligado e algum valor faltando, o log mostra `whatsapp_provider_incomplete missing=[…]` (só nomes, nunca valores) e nada é enviado.

**Template do resumo proposto** (categoria utilidade, `pt_BR`; **precisa da aprovação do Diego e da Meta**, e o custo por mensagem depende da categoria e do mercado): corpo com quatro parâmetros de uma linha cada:

```
Contas a pagar em {{1}}.
{{2}}
{{3}}
Lista completa: {{4}}
```

`{{1}}` data e horário (`05/10/2026, 09:00`); `{{2}}` quantidade, total, atrasadas e estimadas (`3 contas, total R$ 1.234,50, 1 atrasada (1 estimada: R$ 90,00)`); `{{3}}` até cinco contas separadas por `; `, descrições cortadas em 40 caracteres, e “e mais X contas”; `{{4}}` o link autenticado do resumo. Uma mensagem por resumo.

**Webhook:** `https://contas.malyah.tech/api/v1/integrations/whatsapp/webhook` (campo `messages`). `GET` responde ao desafio (`hub.mode=subscribe`, `hub.verify_token`, `hub.challenge`) com o token certo, `403` com o errado e `404` com a integração desligada. `POST` valida `X-Hub-Signature-256` (HMAC-SHA256 do corpo bruto com o app secret) **antes** de ler qualquer coisa: sem assinatura válida → `401` e nada gravado. Só situações de mensagens conhecidas do número configurado são aplicadas, uma vez cada, sem voltar atrás (lido não volta para entregue; falha depois de entregue não rebaixa). Se chegar a situação de uma mensagem cujo id ainda não foi gravado enquanto há uma tentativa em andamento, a resposta é `503` para a Meta reenviar; erro de banco → `500` (reenvio deduplicado). É o único caminho sem sessão e sem CSRF; todas as outras mutações continuam exigindo CSRF.

**Testes locais** (sem Meta real): `WhatsAppDeliveryPostgresIT` (matriz W1–W21 em PostgreSQL real com a Meta simulada por `FakeMetaServer`), `WhatsAppDeliveryServiceTest`, `WhatsAppDeliveryDomainTest`, `MetaWhatsAppProviderTest`, `WhatsAppHttpTest`, `WebhookSecurityHttpTest`, `reminder-summary.component.spec.ts`, `reminder-settings.component.spec.ts`, smoke E2E e E2E full-stack (webhook desligado responde `404` sem sessão/CSRF; acompanhamento `403` para o convidado). Nada disso comprova entrega real.

**Validação real (pendente; somente com autorização do Diego)**

1. P03 concluída: conta WhatsApp Business e número dedicado habilitados, versão da API definida, template do resumo e de teste aprovados, custo conhecido.
2. Gravar os três segredos em `deploy/secrets/` (permissão `600`) e as variáveis no `.env` do servidor; `META_WHATSAPP_ENABLED=true`. Subir a versão e conferir no log que não há `whatsapp_provider_incomplete`.
3. No painel da Meta, cadastrar o webhook acima com o mesmo verify token e assinar o campo `messages`. Esperado: verificação aceita (o backend respondeu o desafio).
4. Em **Lembretes e WhatsApp**, como administrador com consentimento, **Enviar mensagem de teste**. Esperado: “Aceito pela Meta. A entrega ainda não foi confirmada.” e, depois do webhook, a entrega na página (ou a mensagem no celular). Registrar em `docs/evidencias/H08.4.md` só horário, situação e final do número; nunca token, id completo da mensagem ou número inteiro.
5. Ativar o canal e aguardar um horário com contas de teste (sem dados financeiros reais). Conferir na página do resumo: `ACCEPTED` → `SENT` → `DELIVERED` (ou `READ`). Conferir que o convidado não recebeu nada.

Diagnóstico: `docker compose logs backend | grep whatsapp_` mostra `whatsapp_delivery summaryId=… status=… errorCode=…`, `whatsapp_webhook applied=… duplicated=… stale=… ignored=…` e `whatsapp_webhook_rejected reason=signature`. Sem entrega confirmada: verifique o webhook no painel da Meta e o `401` por assinatura (app secret errado). `REJECTED` com `132001`: template inexistente ou idioma errado. `PROVIDER_UNAVAILABLE` repetido: Meta fora do ar ou limite; o aplicativo segue funcionando e os avisos internos continuam.

**Pendente para a H08.5:** retentativas com espaçamento dentro da janela, reconciliação de `UNCERTAIN` (a partir de `whatsapp_attempts` e do id da Meta quando houver), suspensão do canal em falha permanente do destinatário e retomada. As tabelas já comportam várias tentativas por entrega (`attempt_number`).
