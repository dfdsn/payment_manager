# Evidência T36 — WhatsApp pela Evolution API

## Estado

**Em validação**, como a H08.4 e a H08.5. Em 27/11/2026 Diego decidiu trocar o provedor do WhatsApp (P03) da Meta Cloud API pela Evolution API, que ele vai hospedar no servidor. As portas e os adaptadores da H08.4/H08.5 foram adaptados, e o comportamento (revalidação, janela, novas tentativas, incerteza, suspensão, webhook sem regressão) foi mantido. Tudo foi testado com PostgreSQL real e uma Evolution API **simulada** (`FakeEvolutionServer`, só no classpath de teste). Nenhuma mensagem real foi enviada. Nenhuma imagem foi publicada, nenhum deploy foi feito e nenhum segredo foi pedido ou gravado.

Base: `main` em `13edda6` (merge do PR #15, H08.5).

## Documentação consultada (27/11/2026)

| Tema | Situação | Aplicação |
|---|---|---|
| Webhook da instância (`/webhook/set/<instância>`) | Conferido na documentação da Evolution API v2 (páginas de webhooks) e na issue 1340 do repositório (versão 2.2.3) | Campos `enabled`, `url`, `headers`, `byEvents`, `base64`, `events`. O campo `headers` permite enviar um cabeçalho fixo, usado para o segredo `X-Webhook-Token`. |
| Autenticação dos eventos | A Evolution API **não assina** o corpo. Documenta só cabeçalhos customizados (inclusive um JWT opcional), lista de IPs e HTTPS | Segredo fixo em cabeçalho, comparado em tempo constante. |
| Reentrega do webhook | Documentada: reenvio com espera exponencial; por padrão não reenvia 400, 401, 403, 404 e 422 | O `503` (id ainda não gravado) e o `500` (erro de banco) continuam pedindo reenvio. |
| Envelope dos eventos | Conferido: `event`, `instance`, `data`, `destination`, `date_time`, `sender`, `server_url`, `apikey` | O webhook filtra por `event` e `instance` e usa `date_time` como instante. |
| `messages.update`, `sendText`, `connectionState` | **Não encontrados** nas páginas acessíveis nesta sessão | Implementados pelo formato conhecido da Evolution API v2 (`keyId`/`status` com `SERVER_ACK`, `DELIVERY_ACK`, `READ`, `PLAYED`, `ERROR`; resposta do envio com `key.id`; estado em `instance.state`; número inexistente como 400 com `exists: false`). **Conferir na versão instalada antes do primeiro envio real (P03).** |

## O que mudou

| Antes (Meta, T34/T35) | Agora (Evolution API, T36) |
|---|---|
| Template aprovado com 4 parâmetros | Texto livre (`WhatsAppSummaryMessage`), mesmo conteúdo, uma conta por linha |
| Template de teste opcional (`WHATSAPP_TEST_TEMPLATE_MISSING`) | Texto fixo sem dados financeiros; o código de erro deixou de existir |
| `Authorization: Bearer <token>`, `POST /<versão>/<phone-number-id>/messages` | `apikey: <chave>`, `GET /instance/connectionState/<instância>` e depois `POST /message/sendText/<instância>` |
| Webhook com `GET hub.*` e `X-Hub-Signature-256` (HMAC) | Só `POST`, com `X-Webhook-Token` (segredo fixo, mínimo 16 caracteres) |
| `statuses` do `phone_number_id` | `messages.update` da instância, só `fromMe` |
| Reconciliação de incertos por `biz_opaque_callback_data` | **Não há**: a Evolution API não devolve referência nossa; incerto continua incerto e nunca é reenviado |
| Falha do webhook com código de destinatário suspendia o canal | `ERROR` não traz motivo: avisa, mas nunca suspende |
| `META_WHATSAPP_*` | `EVOLUTION_API_ENABLED`, `EVOLUTION_API_BASE_URL`, `EVOLUTION_API_INSTANCE`, `EVOLUTION_API_KEY(_FILE)`, `EVOLUTION_WEBHOOK_TOKEN(_FILE)` |
| HTTPS obrigatório em produção | HTTPS obrigatório, salvo endereço interno do servidor (`localhost`, loopback ou nome de serviço Docker) |

Sem migração: tabelas, estados, janela, intervalos e suspensão continuam os da H08.4/H08.5. Os códigos gravados em `provider_error_code` cabem nos 16 caracteres existentes (`CONN_CLOSE`, `CONN_CONNECTING`, `STATE_404`, `NOT_ON_WHATSAPP`, códigos HTTP).

## Matrizes revistas

As matrizes W1–W21 (H08.4) e R1–R21 (H08.5) continuam valendo. Os cenários abaixo mudaram de entrada ou de resultado esperado por causa do novo provedor; os demais só trocaram o formato da requisição e da resposta simuladas.

| # | Cenário | Esperado agora | Observado | Teste |
|---|---|---|---|---|
| W1 | Envio elegível | 1 consulta de estado e 1 `sendText` com `apikey`, `number` sem `+` e o texto em linhas; `ACCEPTED` com `key.id` | Conforme | `w1EligibleSummary…` |
| W8 | 401 da Evolution API e 400 com `exists: false` | `REJECTED/PROVIDER_REJECTED` (`401`) e `REJECTED/RECIPIENT_INVALID` (`NOT_ON_WHATSAPP`); texto da resposta nunca gravado | Conforme | `w8Rejections…` |
| W9 | Servidor fora do ar, 429 e **instância desconectada** (`close`) | `RETRY_WAITING` nas três; com `close` nenhum `sendText`; reconectada, a 4ª tentativa é aceita | Conforme (`FAILED, FAILED, FAILED, ACCEPTED`, 2 envios) | `w9UnavailableProvider…` |
| W10 | Timeout, 500 e 2xx sem `key.id` | `UNCERTAIN`, nunca reenviado | Conforme | `w10…` |
| W12 | Segredo ausente, vazio, errado, com espaço ou igual à chave da API | `401`, nada gravado | Conforme | `w12WrongSecretsAreRejected` |
| W14 | Id desconhecido, outra instância, `fromMe: false`, `messages.upsert` recebido, `connection.update`, `PENDING`, JSON inválido | `200`, nada gravado; id ainda não gravado com tentativa em andamento → `503` | Conforme | `w14OnlyKnownMessagesAreCorrelated` |
| W15 | `ERROR` para mensagem aceita | `FAILED/DELIVERY_FAILED`, código `ERROR`, sem suspensão, despesas intactas | Conforme | `w15…` |
| W16 | Webhook desligado; corpo nulo ou acima de 3 MB | `404`; `413` | Conforme | `w16WebhookOffOrOversized…` |
| W19 | Teste com instância `connecting` | Texto fixo `WhatsAppSummaryMessage.TEST`; com a instância desconectada o teste termina `FAILED` sem envio | Conforme | `w19TestMessage…` |
| R6 | 429 e depois instância `connecting` | Mesma entrega, `FAILED, FAILED, ACCEPTED`, 2 envios; o id da tentativa não sai na requisição | Conforme | `r6Transient…` |
| R12 | Queda depois do aceite e antes de gravar | `UNCERTAIN` após 10 min, nunca reenviado; evento posterior do mesmo id é ignorado (`reconciledAt` nulo) | Conforme | `r12ARecoveredLeaseIsNeverResent` |
| R13 | Timeout depois de enviar | Nenhum reenvio na janela; evento tardio ignorado; entrega continua `UNCERTAIN` | Conforme | `r13AnAmbiguousTimeoutIsNotResent` |
| R14 | 400 com `exists: false` | Suspende com orientação, mantém número e consentimento | Conforme | `r14…` |
| R14b | Consulta de estado `404` (instância inexistente) | `REJECTED/PROVIDER_REJECTED` (`STATE_404`), suspende, **nenhum** envio | Conforme | `r14bAPermanentRefusal…` |
| R16 | `ERROR` pelo webhook, repetido e fora de ordem | Aviso `DELIVERY_FAILED` uma vez, canal continua `READY`, sem nova suspensão | Conforme | `r14…` |
| W23/R22 | Envio real | Resumo real ao administrador, nada ao convidado | **Não executado** (P03 e autorização do Diego) | — |

## Verificações executadas (27/11/2026, contêiner de nuvem)

| Verificação | Resultado |
|---|---|
| Unitários/HTTP/ArchUnit (`mvnw verify`, Surefire) | 480, 0 falhas |
| ITs PostgreSQL (`mvnw verify`, Failsafe, Testcontainers `postgres:17.6-alpine`) | 229/229, 0 falhas |
| `WhatsAppDeliveryPostgresIT` (Evolution API simulada) | 30/30 |
| JaCoCo domínio/aplicação (≥80%/≥70%) | linhas 3778/3835 (98,51%), branches 2127/2260 (94,12%) |
| PIT (≥70%, `-Pmutation verify -DskipITs`) | 1689/1849 (91%), força 95%; limites mantidos, sem exclusões novas |
| Vitest / build Angular | 149/149 / aprovado |
| Smoke E2E | 18/18. H07.1 e H07.3 falhavam também em `main`, porque o fechamento simulado é de setembro e a página abre no mês corrente; o relógio do navegador foi fixado em 29/09/2026 nesses testes. |
| `npm audit --audit-level=moderate` | 0 vulnerabilidades. Avisos publicados depois do último CI de `main` (vite, zod, undici, piscina, glob, minimatch) foram resolvidos com Angular 21.2.26 / `@angular/build` e `@angular/cli` 21.2.31, Vitest 4.1.12 e `overrides` de `glob` 14.0.6 e `minimatch` 11.0.9 (dependências do próprio CLI); o lockfile foi regenerado. |
| Ordem dos testes HTTP | No CI, `AuthenticationHttpTest` reaproveitava o contexto de `InvitationHttpTest`, cujo `csrf()` troca o repositório de CSRF do contexto. `InvitationHttpTest`, `MembershipHttpTest` e `ExpenseHttpTest` agora usam `@DirtiesContext`; conferido nas ordens alfabética e inversa. |
| E2E full-stack | **Não executado.** O cenário só roda antes das 17:55 de São Paulo e a sessão estava depois desse horário; o deslocamento de relógio por dias (`E2E_CLOCK_OFFSET_DAYS`) só existe no PR da H09.1, ainda não integrado. O trecho do webhook foi ajustado (`POST` desligado → `404`, `GET` → `401`). |

## Riscos e limitações

- **API não oficial.** No modo padrão (Baileys), a Evolution API usa o protocolo do WhatsApp Web. Não é a API oficial da Meta, e o número remetente pode ser limitado ou banido pelo WhatsApp. Recomenda-se um número dedicado e poucos envios (são no máximo dois resumos por dia). A Evolution API também tem um canal pela API oficial, mas nele texto livre só sai dentro da janela de 24 h, o que não serve aos resumos sem template.
- **Sem reconciliação de incertos.** A Evolution API não devolve a referência da tentativa nos eventos. Um resultado incerto fica incerto e visível, sem reenvio.
- **Falha pelo webhook sem motivo.** O evento `ERROR` não diz se o problema é do número, então nunca suspende o canal; uma recusa do número só suspende quando vem na resposta do envio.
- **Formato a conferir.** O formato de `sendText`, `connectionState`, `messages.update` e dos erros veio do conhecimento da Evolution API v2 e não de uma página consultada nesta sessão. Conferir na versão instalada (P03).
- **Duas chamadas por envio.** A consulta de estado acrescenta uma chamada local; se ela falhar, nada é enviado e a tentativa volta dentro da janela.

## Para concluir (P03)

1. Subir a Evolution API, criar a instância e conectá-la ao número remetente.
2. Gravar `deploy/secrets/evolution_api_key.txt` e `deploy/secrets/evolution_webhook_token.txt` e preencher o `.env`.
3. Configurar o webhook da instância com `X-Webhook-Token`.
4. Com autorização do Diego, enviar a mensagem de teste e depois um resumo real com contas de teste; registrar aqui só horário, situação e final do número.
