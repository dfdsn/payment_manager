# Registro de decisões e pendências — account_Manager

Versão 1.0 • 23/09/2026 • Responsável pelo produto: Diego.

Este registro acompanha o PRD v2.0, os épicos e a especificação técnica. **Aprovado** significa decisão de projeto; não significa implementado, conta contratada ou integração validada. As decisões abaixo consolidam a entrevista e complementam os documentos anteriores. Uma mudança posterior deve preservar a decisão anterior no histórico, indicar motivo, impacto e aprovação quando alterar produto, custo ou privacidade.

## Decisões aprovadas

| ID | Decisão | Consequência para desenvolvimento |
|---|---|---|
| D01 | Aplicação pessoal, até dois membros, somente despesas. | Não adicionar receitas, assinatura comercial ou estrutura para múltiplos clientes ao MVP. |
| D02 | Registrar despesas pagas e a pagar, recorrências, previsões e parcelamentos conforme PRD. | Preservar distinção entre estimativa, cobrança e pagamento. IA não elimina revisão humana. |
| D03 | Monólito modular Java/Spring Boot, Maven, Angular/TypeScript/Material. | Módulos e camadas com dependências verificáveis; versões exatas sujeitas a P00. |
| D04 | PostgreSQL em Docker e migrações Flyway. | Sem H2 para validar comportamento de persistência; sem atualização automática de esquema em produção. |
| D05 | Um repositório GitHub, `main`, branches por história e PR. | Sem branch `develop`; documentação acompanha cada entrega. Proteções reais dependem da configuração do repositório. |
| D06 | Build fora da VPS e duas imagens privadas no Docker Hub. | Backend e frontend publicados pela CI; servidor executa Compose com imagens. Resolver P01 antes da publicação. |
| D07 | Release por tag semântica sobre `main`, imagens identificáveis e imutáveis. | Registrar commit, tags e digests do par de imagens; não usar `latest` para selecionar produção. |
| D08 | VPS Hostinger KVM 2, domínio `contas.malyah.tech`. | Mesma origem para frontend e `/api/v1`; confirmar recursos livres e HTTPS em P02/P07. |
| D09 | Sessão no servidor, persistida via JDBC, cookie seguro. | Sem JWT/Redis; expiração por 7 dias de inatividade e limite absoluto de 30 dias; reset de senha revoga sessões. |
| D10 | Confirmação de email 24h, recuperação 30min, convite 7 dias. | Tokens de uso único, armazenados como hash; reenvio explícito invalida o token anterior. |
| D11 | Criação inicial do administrador protegida por segredo temporário e bloqueio persistente. | Operação atômica de uso único; reiniciar ou apagar variável não reabre cadastro inicial. |
| D12 | Gmail dedicado para email transacional. | SMTP com credenciais externas ao código; validar senha de app/2FA e entrega em P04. |
| D13 | WhatsApp oficial Meta Cloud API, número dedicado; administrador recebe no número pessoal. | Convidado recebe somente notificações internas. Credenciamento, templates e custo são P03. |
| D14 | Lembretes agrupados segundo calendário do PRD. | Começam cinco dias antes; frequência intensificada na véspera/no dia e comportamento posterior conforme PRD. Revalidar quitação antes do envio, respeitar janelas e não acumular avisos antigos. |
| D15 | Jobs e controle de execução no PostgreSQL, sem broker. | Reservas com prazo, deduplicação e retomada; chamadas externas fora de transação longa. |
| D16 | Concorrência otimista, idempotência, constraints e auditoria. | Repetição não duplica efeitos; conflito retorna resposta explícita. Idempotência não substitui autorização. |
| D17 | Valores decimais exatos, datas financeiras locais e instantes técnicos UTC. | BigDecimal/NUMERIC; API envia dinheiro como string. Fuso do espaço determina calendário de negócio. |
| D18 | Valor positivo, até R$ 99.999.999,99; parcelas de 2 a 360, cada uma com pelo menos R$ 0,01. | Zero não representa cobrança/pagamento: isenção integral é cancelamento com motivo. Centavos restantes na última parcela. Descrição 200, observação 2.000 e categoria 60 caracteres. Esta decisão complementa/substitui permissões anteriores de valor zero. |
| D19 | Operações em lote são atômicas. | Qualquer item inválido, sem permissão ou em conflito impede o lote inteiro; informar os motivos sem aplicar parcialmente. |
| D20 | Alteração de recorrência protege confirmações e histórico. | Metadados podem alcançar pendentes confirmados; valor variável confirmado e vencimento confirmado exigem correção individual. Pagos/cancelados não são reescritos. |
| D21 | Anexos em volume privado com metadados no banco. | Backend autoriza acesso; arquivos temporários de IA por até 24h e fora do backup. Anexação permanente exige ação explícita. |
| D22 | IA inicialmente com Groq, buscando uso gratuito. | Modelo multimodal e condições reais em P05. Sem troca automática para serviço pago; falha mantém cadastro manual disponível. |
| D23 | Cota de IA compartilhada de 20 análises/dia. | Reservar antes de chamar; entrada rejeitada antes da chamada não consome; análise válida ou ilegível consome; resultado incerto conta se não houver reconciliação confiável. Repetição técnica não duplica consumo. |
| D24 | PWA online, ambos os celulares Android; desktop Windows/Chrome/Edge. | Instalação e câmera precisam de validação em aparelhos reais. Sem cadastro financeiro offline no MVP. |
| D25 | API `/api/v1`, DTOs e OpenAPI; contratos Java entre módulos. | Não fazer HTTP interno. Um módulo não usa repositório JPA de outro; consultas de relatório podem usar projeções de leitura documentadas. |
| D26 | JaCoCo e PIT são gates de negócio. | Domínio/aplicação: linhas ≥80%, branches ≥70%, mutação ≥70%. Não reduzir limites ou excluir regras difíceis para passar. |
| D27 | Testes JUnit/Mockito, Testcontainers PostgreSQL e ArchUnit; frontend com componentes e E2E. | Smoke crítico no PR, suíte completa na release; validar compatibilidade real em P00. Teste simulado não comprova integração real. |
| D28 | Migração explícita em container temporário antes de iniciar a nova versão. | Backup antes, interrupção em falha, runtime sem migrar automaticamente. Rollback de imagem não reverte banco. |
| D29 | Backup diário preparado na VPS e baixado pelo computador Windows pessoal. | Agendamento PowerShell, transferência autenticada e criptografia; substitui a proposta anterior de armazenamento externo em nuvem. PC desligado impede a cópia externa: RPO depende do último download válido. |
| D30 | Backup inclui banco e anexos permanentes, retenção de sete dias e preservação do último válido. | Hash, promoção atômica, logs e chave fora da VPS; testar restauração. Não considerar backup apenas por existir arquivo. Detalhes em P06. |
| D31 | README detalhado para execução, CI, deploy e recuperação. | Comandos devem corresponder ao repositório e ser testados; seção 16 da especificação é o checklist obrigatório. |
| D32 | Desenvolvimento incremental por história, progresso e evidências registrados. | Codex resolve escolhas técnicas rotineiras; lacunas de negócio, custo e mudanças de escopo exigem decisão explícita. |
| D33 | Exportação completa e exclusão administrativa documentadas, sem nova tela no MVP. | CSV de despesas não equivale a exportação completa. Definir execução e preservação/anonimização de histórico em P09 antes de ação destrutiva. |
| D34 | Operação simples compatível com uso pessoal. | Sem alta disponibilidade ou garantia de zero downtime; medir capacidade e manter backup/restauração como critérios de aceite. |

## Pendências reais e critérios para encerramento

P01–P09 estão **abertas**. P00 foi encerrada pelas evidências locais e de CI descritas abaixo. Uma pendência bloqueia apenas a etapa indicada, não todo o projeto. Responsável técnico pode escolher parâmetros internos reversíveis; decisões de custo, acesso a contas e alteração funcional pertencem a Diego.

| ID | Pendência / responsável | Evidência para encerrar | Etapa afetada |
|---|---|---|---|
| P01 | Plano Docker Hub para dois repositórios privados — Diego + desenvolvimento. | Verificar plano/limites da conta, criar os dois repositórios e testar publicação/leitura com credenciais de menor privilégio. Se houver custo adicional, aprovação antes de contratar. | Publicação de imagens privadas; não bloqueia desenvolvimento local. |
| P02 | DNS, certificado e renovação HTTPS — desenvolvimento + acesso de Diego. | Escolher cliente ACME; validar DNS, cadeia TLS, renovação e reload; somente portas necessárias expostas. | PWA/câmera e produção real. |
| P03 | Meta WhatsApp — Diego + desenvolvimento. | Habilitação da conta/número, versão suportada da API, token, webhook autenticado, template aprovado e custo conhecido; envio entregue ao administrador sem mensagem ao convidado; teste de falha/repetição. | Aceite do WhatsApp real. |
| P04 | Gmail dedicado — Diego + desenvolvimento. Confirmação e recuperação reais verificadas na H01.2; convite ainda pendente. | Conta criada, 2FA/senha de app elegível, SMTP autenticado; confirmação, convite e recuperação entregues de verdade; documentar limites e falhas. | Aceite de email real; resta validar o convite na H01.3. |
| P05 | Groq multimodal — desenvolvimento + conta de Diego. | Modelo ativo que aceite imagem, qualidade em etiquetas/serviços, cota real, tamanho de entrada, retenção/ZDR verificados na conta e fallback manual exercitado. Não fixar modelo descontinuado. | Aceite da análise real por IA. |
| P06 | Ferramentas e agenda do backup Windows — desenvolvimento + Diego. | Escolher criptografia, horário/fuso e pasta local; agendamento e SSH restrito; testar PC desligado, retomada, integridade, retenção e restauração completa com chave externa. | Aceite de backup externo e recuperação. |
| P07 | Recursos e configuração da VPS — desenvolvimento. | Inventário de CPU/RAM/disco/arquitetura/serviços; limites dos containers, heap, pool, logs e alertas ajustados por medição. | Dimensionamento e aceite operacional. |
| P08 | Parâmetros técnicos operacionais — desenvolvimento. | Fixar e testar limites de paginação/upload/rate limit, timeout, backoff, lease, retenção de idempotência e resolução de reserva incerta da IA, respeitando limites já aprovados no PRD. Documentar valores e motivos. | Cada história que depender do parâmetro; não criar defaults silenciosos de negócio. |
| P09 | Procedimentos de exportação completa e exclusão — desenvolvimento + Diego. | Definir conteúdo/formato da exportação, verificação de identidade, efeito sobre histórico compartilhado e anexos, backups/retenção e reaplicação após restore; ensaio com dados fictícios. Aprovar semântica antes de apagar dados reais. | Procedimentos administrativos e aceite final. |

A tabela não solicita senhas, tokens, chaves ou documentos pessoais no chat. Configurar segredos nos locais apropriados durante a implementação.

### Pendência encerrada

| ID | Encerramento | Evidência |
|---|---|---|
| P00 | Compatibilidade e patches da stack. | GitHub Actions `36038781176` no commit `737f46a` aprovou Java 21, Spring/JUnit, Testcontainers PostgreSQL 17.6, JaCoCo, PIT, ArchUnit, Node/Angular/Material/Vitest/Playwright. A repetição local da H01.2 aprovou 41 unitários, 4 ITs, V1–V3, JaCoCo, PIT 90%, 11 testes frontend, build e E2E full-stack. |

## Registros técnicos da preparação

| ID | Escolha reversível | Estado e evidência |
|---|---|---|
| T01 | Java 21, Boot 4.1.1, Maven 3.9.16, JaCoCo 0.8.15, PIT 1.20.5/plugin JUnit 1.2.3, ArchUnit 1.4.2 e Testcontainers 2.0.5. Dependências Boot permanecem no BOM; módulos Flyway e Session JDBC usam os starters do Boot 4 para incluir auto-configuração. | Verificada localmente e na CI Temurin 21. PostgreSQL 17.6/V1–V3, 41 unitários, 4 ITs, JaCoCo e PIT 90% aprovados. P00 encerrada. |
| T02 | Angular/Core/CLI 21.2.24, Material/CDK 21.2.14, Node 24.18.0, TypeScript 5.9.3, Vitest 4.1.11 e Playwright 1.63.0. | CI Linux e execução Windows aprovaram audit, testes, build e smoke. O E2E full-stack H01.2 passou em Chrome; o webServer do smoke interceptado ainda exige interrupção manual depois da asserção verde no Windows. |
| T03 | Release por tag SemVer publica dois artefatos privados sem `latest`; produção usa `image:` e migration separada. | Configuração preparada, não publicada nem implantada. P01, P02 e P07 permanecem abertas. |
| T04 | H01.1 usa segredo temporário em `X-Setup-Secret`, comparação por digest em tempo constante, BCrypt com custo 12 e senha de 12 caracteres a 72 bytes UTF-8 contendo letra e número. Uma linha singleton é bloqueada com `SELECT ... FOR UPDATE`; usuário, espaço, papel e fechamento são gravados na mesma transação. O contexto protegido `/identity/me` resolve a associação ativa pelo email normalizado do principal. | Escolha implementada e verificada por unitários/HTTP/PIT e `InitialSetupPostgresIT` real com PostgreSQL 17.6: concorrência, defaults, contexto persistido e reinício aprovados. O endpoint de contexto permanece inacessível sem autenticação; criação/rotação da sessão é H01.2. Em produção o segredo é montado como Docker secret; após o setup, seu arquivo-fonte fica vazio para manter o contrato do Compose sem disponibilizar segredo ao novo processo. |
| T05 | H01.2 usa sessão Spring JDBC; inatividade de 7 dias e duração absoluta de 30 dias em metadados persistidos; tokens aleatórios de 32 bytes armazenados como SHA-256; confirmação 24h e reset 30min; reenvio revoga o anterior. O SMTP é executado depois da transação para não manter lock externo, e respostas de solicitação são genéricas. | Verificada por unitários/HTTP, `AccountAccessPostgresIT`, E2E full-stack e Gmail real. Confirmação e recuperação foram entregues e consumidas; reset consumiu o token, alterou BCrypt e removeu sessões na mesma transação. P04 permanece aberta somente para o convite de H01.3. P08 segue aberto para rate limiting, sem default silencioso. |

## Como registrar nova decisão

Adicionar ID, data, contexto, opções, escolha, responsável, status, requisitos/histórias afetados e evidência. Marcar a anterior como substituída, sem apagar o histórico. Fechar uma pendência somente com a evidência indicada; aprovação verbal de intenção não comprova entrega técnica.

## Histórico

| Data | Alteração |
|---|---|
| 23/09/2026 | Consolidação da entrevista técnica em D01–D34; P00–P09 explicitadas; nenhuma implantação ou integração executada. |
| 23/09/2026 | PREP registrou T01–T03 e evidência parcial de compatibilidade. P00 não foi encerrada porque Docker/Testcontainers, JDK 21 e CI real não puderam ser executados. |
| 23/09/2026 | H01.1 registrou T04. A escolha foi verificada nas camadas de domínio, aplicação e HTTP; naquele momento o sandbox não acessou o daemon. Em 24/09 a execução autorizada comprovou PostgreSQL/concorrência e atribuiu corretamente o bloqueio ao isolamento. |
| 24/09/2026 | Revisão de H01.1 completou o contrato protegido de contexto autenticado. Os gates foram reexecutados sem reduzir limites: 25 testes, JaCoCo 100% linhas/94,23% branches e PIT 90,32%. |
| 24/09/2026 | Testcontainers/PostgreSQL 17.6 aprovados fora do sandbox: 2 ITs, sem skip/falha/erro. O bloqueio anterior foi atribuído corretamente ao isolamento do Codex; T01/T04 atualizadas sem encerrar P00, que ainda depende de JDK 21/CI. |
| 24/09/2026 | A CI `36038781176` aprovou a stack no JDK 21/Node 24.18 e a repetição local full-stack aprovou Flyway/Spring Session JDBC. P00 encerrada; T01/T02 atualizadas. |
| 24/09/2026 | H01.2 registrou T05. Tokens, sessão JDBC, expiração e revogação foram validados localmente; P04 continua aberta porque Mailpit não comprova entrega Gmail. |
| 24/09/2026 | Gmail real validado com SMTP autenticado e STARTTLS: confirmação e recuperação chegaram à caixa externa, os links foram consumidos e o reset encerrou sem sessão residual. H01.2 concluída; P04 continua parcialmente aberta somente para o convite de H01.3. A inspeção TLS local do Avast exigiu importar sua CA pública apenas na imagem descartável de teste, sem desabilitar validação. |
