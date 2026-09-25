# account_Manager

Gerenciador pessoal de despesas para um administrador e um convidado. A fundação técnica PREP-01 a PREP-04 e o épico E01 (H01.1–H01.4) estão validados. Confirmação, recuperação e convite também foram entregues e consumidos por Gmail real. Despesas ainda não foram iniciadas.

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

O backend começa em `com.malyah.accountmanager`. Cada módulo funcional tem `domain`, `application`, `infrastructure` e `api`. O domínio não depende de Spring/JPA/HTTP; a aplicação não depende de adapters. `ArchitectureTest` torna essas fronteiras executáveis. V1–V3 formam a baseline de identidade/sessão/tokens, V4 adiciona convites e V5 registra encerramento e eventos auditáveis da associação.

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

Resultado esperado: Flyway aplica V1–V5 (ou informa que estão atuais) e o processo termina. Produção nunca executa migration automaticamente no runtime.

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
2. Execute as migrações V1–V5 antes do runtime, conforme os passos anteriores.
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
- `verify`: inclui os `*IT` com PostgreSQL 17 real via Testcontainers e aplica JaCoCo. Eles verificam V1–V5, atomicidade concorrente do setup/aceite/associação, tokens, papéis e revogações persistidos.
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

`e2e:full-stack` pressupõe `compose.full-local.yml` saudável e banco vazio; ele cria o administrador pela UI, lê os links no Mailpit, confirma, testa credencial inválida/válida, convida/reenvia/aceita o segundo membro, transfere a administração nos dois sentidos, executa a saída voluntária, comprova revogação e vaga para novo convite, redefine a senha e rejeita reutilização de tokens. O E2E local usa Chrome instalado. A CI instala Chromium fixado pelo Playwright. Relatórios ficam em `frontend/test-results/` e `frontend/playwright-report/`. Instalação PWA/câmera em Android não é simulada e pertence a H09.

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

Consulte [`docs/progresso.md`](docs/progresso.md) para resultados executados e limites. H01.1 a H01.4 e o épico E01 estão concluídos pelas evidências atuais. A próxima história recomendada é **H02.1 — Cadastrar e listar uma despesa avulsa**.
