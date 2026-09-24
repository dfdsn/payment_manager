# Evidência técnica — PREP-01

Data: 23–24/09/2026. Escopo: compatibilidade mínima da fundação, sem funcionalidades do produto.

## Combinação fixada

| Componente | Versão fixada/efetiva | Forma de verificação |
|---|---:|---|
| Java | 21 como `release`, build e runtime | `pom.xml`, CI e Dockerfiles. O host local tinha apenas Temurin 23.0.1, portanto a execução no mesmo major ainda depende da CI/instalação do JDK 21. |
| Spring Boot / Framework | 4.1.1 / 7.0.9 | Parent/BOM e árvore efetiva do Maven. |
| Maven Wrapper | 3.9.16 | `.mvn/wrapper/maven-wrapper.properties`. |
| JUnit Jupiter | 6.0.3 | Árvore efetiva do Maven. Não foi forçado fora do BOM. |
| Flyway / PostgreSQL JDBC | 12.4.0 / 42.7.13 | Árvore efetiva do Maven. |
| JaCoCo / PIT / plugin JUnit | 0.8.15 / 1.20.5 / 1.2.3 | `pom.xml` e execução dos gates. |
| ArchUnit / Testcontainers | 1.4.2 / 2.0.5 | `pom.xml`; ArchUnit e Testcontainers executados com sucesso. |
| PostgreSQL | 17.6-alpine | Dois testes reais executados com `postgres:17.6-alpine`; Flyway aplicou V1 e V2. |
| Angular Core/CLI/build | 21.2.24 | lockfile, build e testes. |
| Material/CDK | 21.2.14 | lockfile e build. É o patch publicado da família 21, sem misturar majors. |
| Node / TypeScript / RxJS | 24.18.0 / 5.9.3 / 7.8.2 | host, lockfile, build e testes. |
| Vitest / Playwright | 4.1.11 / 1.63.0 | lockfile e execução local. |
| npm | 11.16.0 | Executável usado nos gates e campo `packageManager`. |

## Resultados executados

| Comando/verificação | Resultado observado |
|---|---|
| `mvn test` | Aprovado: 8 testes, 0 falhas/erros; inclui contexto Spring e 3 regras ArchUnit. Executado com JDK 23 compilando para Java 21. |
| `mvn verify -DskipITs` | Aprovado: unitários/arquitetura e gate JaCoCo. Domínio e aplicação ficaram com 7/7 linhas e 4/4 branches cobertos. |
| `mvn -Pmutation verify -DskipITs` | A primeira execução reprovou corretamente o gate de cobertura PIT (71%); após tornar a colaboração domínio/aplicação exercitável, aprovou com 4/4 mutações eliminadas e 100% de cobertura do código mutado. Os limites permaneceram 70% mutação e 80% cobertura. |
| `mvn -o dependency:tree ...` | Confirmou Spring 7.0.9, JUnit Jupiter 6.0.3, Flyway 12.4.0 e PostgreSQL JDBC 42.7.13. O modo online encontrou a CA corporativa não confiada pelo JDK; nenhuma validação TLS foi desativada. |
| `npm audit --audit-level=moderate` | Aprovado após atualizar Playwright/Vitest: 0 vulnerabilidades conhecidas. |
| `npm run test:ci` | Aprovado: 2 testes de componente. |
| `npm run build` | Aprovado: bundle de produção gerado. |
| `npm run e2e:smoke` | A asserção Playwright passou: 1 teste no Chrome, 2,8 s. No Windows, o processo filho do `ng serve` não encerrou e o comando precisou ser interrompido; a CI Linux deverá confirmar o ciclo completo. |
| `backend/scripts/run-integration-tests.ps1 -Tests InitialSetupPostgresIT,FlywayPostgresIT` | Aprovado em 24/09/2026: 25 unitários e 2 ITs; 0 ignorados, 0 falhas, 0 erros; `BUILD SUCCESS`. PostgreSQL 17.6 iniciou via `postgres:17.6-alpine` e Flyway aplicou V1/V2. |
| `docker version` / `docker info` / `docker context show` | Fora do sandbox: cliente Windows 28.5.1 em `desktop-linux`, servidor Linux/amd64 Docker Desktop 28.5.1 sobre WSL2. Dentro do sandbox, leitura de `.docker/config.json` e named pipe foram negadas. O impedimento anterior era isolamento do Codex, não indisponibilidade do Docker Desktop. |
| `docker compose ... config --quiet` | Aprovado para `compose.dev.yml`, `compose.full-local.yml` e `compose.prod.yml`; validação sintática não inicia containers. |
| `mvnw.cmd -version` | Aprovado: baixou Maven 3.9.16 e verificou o SHA-256 fixado no wrapper. |
| Parsers dos scripts | `update.sh` e `docker-entrypoint.sh` aprovados por `bash -n`; `download-backup.ps1` aprovado pelo parser PowerShell. |

## Verificações não executadas

- Build/test/runtime com JDK 21 no mesmo ambiente: configurado na CI e nos containers, mas o host contém somente JDK 23.
- Workflows GitHub e proteção de branch: os arquivos existem, porém o diretório recebido não contém `.git` nem remoto.
- Build das imagens e execução completa das composições em containers: ainda não executados nesta preparação.

Consequentemente, P00 e PREP-01 permanecem **em validação** apenas quanto a JDK 21/CI real e execução completa dos ambientes. A combinação Flyway/PostgreSQL/Testcontainers está comprovada; para encerramento integral, repetir os gates em JDK 21 e numa CI de PR real.
