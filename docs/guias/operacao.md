# Guia operacional inicial

Este roteiro prepara PREP-04. Ele não comprova uma implantação: VPS, DNS, TLS, publicação, backup criptografado e restauração não foram executados nesta etapa. Use somente dados fictícios até resolver P01, P02, P06 e P07.

## 1. Preparar e executar localmente

1. Clone o repositório e instale JDK 21, Docker/Compose v2 e Node 24.18.x.
2. Copie `deploy/.env.example` para um `.env` não versionado somente quando precisar validar a composição de produção; substitua placeholders localmente.
3. Inicie `docker compose -f deploy/compose.dev.yml up -d` e confirme `docker compose -f deploy/compose.dev.yml ps`.
4. Execute a migration com `APP_MODE=migrate` conforme o README. O resultado esperado é V1, V2 e V3 aplicadas e encerramento com código zero.
5. Inicie backend e frontend. Valide `/api/v1/actuator/health` e a tela de fundação.

O administrador inicial é criado uma única vez pelo fluxo protegido descrito no README. Defina um segredo temporário fora do Git e remova-o após o sucesso; o bloqueio persistente impede reabertura. Depois do setup, confirme o email e autentique-se conforme o fluxo da H01.2. Mailpit é um capturador local, não um envio real. WhatsApp usa a Meta Cloud API real somente quando configurado (desligado por padrão); IA não tem adapter falso nesta fundação.

## 2. Testes, relatórios e falhas comuns

Execute `backend/mvnw verify`, depois `backend/mvnw -Pmutation verify -DskipITs`. Execute em `frontend`: `npm ci`, `npm audit --audit-level=moderate`, `npm run test:ci`, `npm run build` e `npm run e2e:smoke`.

- Docker/Testcontainers indisponível: confirme `docker version`; não troque PostgreSQL por H2.
- Maven/npm com erro PKIX: instale a CA confiável no sistema/JDK; não desative TLS.
- JaCoCo/PIT abaixo do limite: melhore o teste ou desenho; não diminua o gate.
- Migration falhou: preserve log e banco; não edite migration já aplicada nem use `repair` sem diagnóstico.

Relatórios ficam em `backend/target/site/jacoco`, `backend/target/pit-reports`, `backend/target/*-reports`, `frontend/test-results` e `frontend/playwright-report`.

## 3. GitHub Actions e imagens privadas

Crie o repositório Git, uma branch por história e PR para `main`. Proteja `main`, exija os jobs `backend` e `frontend`, revisão e histórico sem force-push. A CI não recebe segredos reais.

Antes de publicar, resolva P01 e crie dois repositórios privados no Docker Hub. Configure no GitHub os secrets `DOCKERHUB_USERNAME`, `DOCKERHUB_TOKEN`, `DOCKERHUB_BACKEND_REPOSITORY` e `DOCKERHUB_FRONTEND_REPOSITORY`. O token deve ter o menor escopo possível.

Depois dos gates e de um merge em `main`, crie uma tag SemVer, por exemplo `v1.0.0`. `release.yml` reexecuta os gates, publica backend/frontend com a mesma versão e produz um manifesto de commit e digests. Não reutilize a tag nem publique `latest`. Se uma imagem falhar, a release está incompleta; não esconda a falha sobrescrevendo tags.

## 4. VPS, DNS e TLS

Antes da instalação, registre CPU, RAM, disco, arquitetura, versão do Docker e serviços/portas existentes (P07). Instale somente Docker Engine e Compose; a VPS não compila o projeto. Crie `/srv/account-manager/{deploy,secrets,backups}` com proprietário dedicado e permissões mínimas. Copie `compose.prod.yml`, o `.env` local do servidor e o segredo `postgres_password.txt`; aplique `chmod 600` aos segredos.

Exemplo para uma VPS Ubuntu, depois de instalar Docker pelo repositório oficial e confirmar `docker version`/`docker compose version`:

```bash
sudo adduser --system --group --home /srv/account-manager accountmanager
sudo install -d -m 0750 -o accountmanager -g accountmanager /srv/account-manager/deploy
sudo install -d -m 0700 -o accountmanager -g accountmanager /srv/account-manager/secrets
sudo install -d -m 0750 -o accountmanager -g accountmanager /srv/account-manager/backups
sudo install -m 0640 -o accountmanager -g accountmanager compose.prod.yml /srv/account-manager/deploy/compose.prod.yml
sudo install -m 0600 -o accountmanager -g accountmanager .env /srv/account-manager/deploy/.env
sudo install -m 0600 -o accountmanager -g accountmanager postgres_password.txt /srv/account-manager/deploy/secrets/postgres_password.txt
```

Resultado esperado: arquivos de configuração não legíveis por outros usuários e nenhum código-fonte/toolchain de build na VPS. Ajuste o grupo de execução do Docker conforme a política do host; pertencer ao grupo `docker` equivale a privilégio elevado.

Crie um token Docker Hub somente leitura e faça `docker login --username USUARIO`. Use os dois digests do mesmo manifesto em `BACKEND_IMAGE` e `FRONTEND_IMAGE`.

```bash
cd /srv/account-manager/deploy
docker login --username SEU_USUARIO
docker compose --env-file .env -f compose.prod.yml config --quiet
docker compose --env-file .env -f compose.prod.yml pull db backend frontend
```

Forneça o token no prompt, nunca na linha de comando nem no histórico. Resultado esperado: configuração válida e três imagens baixadas, sem build local.

Aponte `contas.malyah.tech` para a VPS. P02 ainda deve escolher e testar o cliente ACME, cadeia, renovação automática e reload do proxy. O Compose atual expõe HTTP 80 apenas como baseline; não use dados reais antes do HTTPS. Banco e backend não devem publicar portas na internet.

## 5. Atualização, migration e saúde

1. Confirme uma cópia externa íntegra e registre seu timestamp.
2. Defina no `.env` os dois digests da mesma release.
3. Faça `docker compose --env-file .env -f compose.prod.yml pull`.
4. Execute `docker compose --env-file .env -f compose.prod.yml --profile migration run --rm migrate` e pare se o código não for zero.
5. Execute `BACKUP_CONFIRMED=yes ./scripts/update.sh .env` ou suba `db backend frontend` explicitamente.
6. Confira `docker compose ... ps`, logs sem dados sensíveis, `/api/v1/actuator/health` internamente e o fluxo público via HTTPS.

O script foi preparado, não testado em VPS. Nunca rode migration e chamadas externas em uma mesma transação longa.

## 6. Falha, rollback e restauração

Falha antes da migration: não altere serviços; corrija imagem/configuração. Falha da migration: não suba o novo backend, preserve o log e investigue a compatibilidade. Rollback de aplicação significa selecionar os digests anteriores; ele não desfaz o schema.

Restauração de banco é uma operação diferente e destrutiva. Requer autorização, janela, cópia de segurança do estado atual e confirmação do ponto de recuperação. Após restaurar, revise sessões, reservas/jobs, exclusões e notificações antigas antes de liberar o tráfego.

## 7. Backup criptografado e download Windows

P06 deve fixar ferramenta, chave externa, horário/fuso e retenção. O processo previsto é: `pg_dump` consistente, incluir anexos/metadados necessários, gerar manifesto/checksums, criptografar na VPS, validar o artefato e remover temporários em texto claro. A chave de descriptografia fica fora da VPS.

Crie um usuário SSH restrito a leitura da pasta de backups. No Agendador de Tarefas do Windows, execute `deploy/scripts/download-backup.ps1` com chave SSH dedicada e pasta local protegida. O script baixa para `.part`, compara SHA-256 remoto/local e só então renomeia. Registre sucesso/falha e alerte quando a última cópia externa ultrapassar o RPO.

Retenção só pode apagar após confirmar integridade e política aprovada. Se o PC estiver desligado, a tarefa deve repetir ao ligar; a perda possível continua sendo desde a última cópia externa válida.

## 8. Ensaio de restauração limpa

Em ambiente isolado e vazio, instale a versão documentada do Docker, recupere a chave externa, valide checksum, descriptografe, restaure PostgreSQL e anexos, então suba exatamente os digests compatíveis. Revise sessões/jobs e execute saúde e fluxos críticos com dados fictícios. Registre duração total, timestamp do backup e diferença até o incidente; essa diferença é a perda de dados possível. Nenhum ensaio foi feito nesta preparação.

## 9. Segredos, logs e provedores

Rotacione um segredo por vez: crie o novo, configure fora do Git, reinicie o consumidor, teste, revogue o antigo e registre sem copiar o valor. Logs devem excluir senhas, tokens, conteúdo financeiro completo, documentos e URLs assinadas.

Para email real, configure `SMTP_HOST=smtp.gmail.com`, `SMTP_PORT=587`, `SMTP_AUTH=true`, `SMTP_STARTTLS=true`, `SMTP_USERNAME` e `SMTP_FROM` fora da imagem. Grave somente a senha de app no arquivo Docker secret `smtp_password` e monte-o em `/run/secrets/smtp_password`; nunca use a senha normal da conta nem registre seu conteúdo. A validação operacional deve enviar confirmação e recuperação para uma caixa real, abrir ambos os links e registrar apenas horários e resultados, nunca os tokens. Até P04 ser executada, Mailpit comprova somente o protocolo SMTP e a captura local.

Para o WhatsApp real (H08.4, **procedimento ainda não testado com a Meta**), crie em `/srv/account-manager/deploy/secrets/` os arquivos `meta_whatsapp_token.txt`, `meta_whatsapp_app_secret.txt` e `meta_whatsapp_verify_token.txt` com `chmod 600` (podem ficar vazios enquanto `META_WHATSAPP_ENABLED=false`). No `.env` do servidor, preencha `META_WHATSAPP_API_VERSION`, `META_WHATSAPP_PHONE_NUMBER_ID`, `META_WHATSAPP_SUMMARY_TEMPLATE`, `META_WHATSAPP_TEST_TEMPLATE` e os idiomas, e só então `META_WHATSAPP_ENABLED=true`. Na inicialização o backend registra `whatsapp_provider_incomplete missing=<nomes>` quando falta algo, sem valores. O webhook público é `https://contas.malyah.tech/api/v1/integrations/whatsapp/webhook`; o proxy precisa encaminhar o corpo sem alteração, porque a assinatura `X-Hub-Signature-256` é calculada sobre os bytes recebidos. Para rotacionar, troque o arquivo, reinicie o backend e atualize o painel da Meta; o *app secret* valida a assinatura de cada evento e o *verify token* só é usado quando a Meta confirma a assinatura do webhook (`GET` com `hub.verify_token`). Indisponibilidade da Meta não bloqueia o aplicativo: o administrador recebe um aviso interno e o resumo continua disponível no app.

Gmail indisponível: mantenha cadastros manuais e sinalize entregas pendentes sem revelar token. Meta indisponível: não envie ao convidado nem acumule disparos obsoletos. Groq indisponível/incerto: não consuma nova cota nem crie despesa automaticamente; permita entrada manual e reconcilie a reserva. P03–P05 exigem testes reais próprios.

## 10. Exportação e exclusão

Não use o CSV financeiro como exportação pessoal completa. P09 precisa definir formato, identidade, anexos, histórico compartilhado, retenção em backups e reaplicação de exclusão após restore. Ensaiar com dados fictícios e obter aprovação antes de implementar ou apagar dados reais. Esta preparação não contém comando destrutivo.
