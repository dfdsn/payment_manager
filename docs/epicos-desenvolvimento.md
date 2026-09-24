# Épicos e histórias — Gerenciador pessoal de contas e despesas

**Projeto:** account_Manager  
**Versão do backlog:** 1.0  
**Data:** 22/09/2026  
**Fonte de verdade:** PRD v2.0, arquivo `prd.md`  
**Status:** planejamento proposto; nenhuma história é considerada implementada.

## 1. Como usar este backlog

Este documento transforma o PRD em entregas de desenvolvimento. Cada épico representa um resultado utilizável, com histórias, dependências, critérios de aceite essenciais e uma demonstração de conclusão. As histórias abrangem interface, comportamento no servidor, persistência e validação quando aplicáveis; não são apenas tarefas de criar tabelas ou endpoints.

O PRD prevalece em caso de dúvida. O backlog não escolhe stack, arquitetura, provedor ou regras ainda não aprovadas. Antes de implementar uma história, resolver somente as pendências que a bloqueiam e detalhar seus cenários técnicos. Os critérios abaixo complementam, sem substituir, os 32 critérios de aceite do PRD.

**Todos os onze épicos pertencem ao MVP.** A ordem é uma recomendação de implementação, não uma divisão entre MVP e pós-MVP. Segurança, experiência responsiva, precisão monetária e consistência devem ser aplicadas desde a primeira entrega. O épico operacional começa cedo e termina com a liberação integrada.

## 2. Mapa dos épicos

| Ordem | Épico | Resultado entregue | Dependências principais |
|---|---|---|---|
| 1 | E01 — Acessar e compartilhar o espaço familiar | Administrador e convidado entram e operam com acesso controlado. | Preparação técnica mínima. |
| 2 | E02 — Registrar e quitar despesas com segurança | Despesas avulsas pagas/pendentes, correções e histórico confiável. | E01. |
| 3 | E03 — Organizar e localizar despesas | Categorias, anexos, responsáveis, busca e filtros. | E01 e E02. |
| 4 | E04 — Automatizar contas recorrentes e previsões | Geração automática, valores variáveis e planejamento de 12 meses. | E02 e E03. |
| 5 | E05 — Controlar compras parceladas | Parcelas mensais com soma exata e quitação individual. | E02/E03 e regra de calendário H04.1. |
| 6 | E06 — Acompanhar totais e exportar dados | Dashboard coerente por vencimento/pagamento e CSV. | E02 a E05 para cobertura completa. |
| 7 | E07 — Revisar e fechar o mês | Resumos versionados sem impedir correções. | E06. |
| 8 | E08 — Receber lembretes agrupados | Avisos in-app e WhatsApp ao administrador, com falhas controladas. | E01, E02, E04 e E05; provedor para envio real. |
| 9 | E09 — Usar no celular como PWA | Instalação e câmera/seleção de imagem no dispositivo real. | E01–E03; responsividade começa antes. |
| 10 | E10 — Cadastrar despesas por imagem com IA | Sugestões revisáveis, vínculo ao existente, cota e retenção. | E02, E03, E04 e E09; provedor de IA. |
| 11 | E11 — Operar e recuperar o sistema na VPS | HTTPS, jobs, backups e aceite integrado. | Começa na preparação; conclusão depende de E01–E10. |

### 2.1 Marcos para demonstrar progresso

| Marco | Momento | Demonstração |
|---|---|---|
| M1 — Controle manual | E01–E03 | Dois perfis registram, quitam, corrigem e consultam despesas com anexos. |
| M2 — Planejamento | E04–E05 | Recorrências e parcelas aparecem sem duplicação, com estimativas e histórico preservado. |
| M3 — Revisão financeira | E06–E07 | Totais corretos, CSV e fechamento versionado. |
| M4 — Lembretes | E08 | Resumo real entregue ao administrador, com aviso interno ao convidado. |
| M5 — Uso pelo celular e IA | E09–E10 | Foto interpretada, revisada e salva pela PWA sem duplicação. |
| M6 — Uso diário | E11 concluído | VPS operacional, restauração comprovada e aceite completo. |

Marcos intermediários permitem testes controlados; não equivalem à liberação completa do MVP. Pode-se antecipar a preparação de email, WhatsApp, IA e VPS para evitar bloqueios externos, sem implementar as regras funcionais fora de ordem.

## 3. Preparação e regras de conclusão

### 3.1 Preparação técnica mínima — antes de H01.1

Registrar stack, arquitetura, banco, estrutura do projeto, execução local, configuração de ambiente, migrações e modo de executar verificações. Proteger segredos e definir como controlar a configuração inicial do administrador. Não criar infraestrutura extensa sem necessidade demonstrada para dois usuários.

Essa é uma atividade habilitadora, não um décimo segundo épico de produto. Não existe autorização implícita para escolher uma stack apenas por Diego trabalhar com Java. As escolhas devem constar na especificação técnica.

### 3.2 Definição de pronto para iniciar uma história

- Requisitos do PRD e histórias predecessoras identificados.
- Pendências que afetam o resultado resolvidas ou separadas como bloqueio explícito.
- Entrada, saída, permissões, erros relevantes e demonstração definidos.
- Para integração externa, distinguir validação local simulada de validação real.

### 3.3 Definição de concluído

- Fluxo demonstrável pelo usuário, com persistência e autorização no servidor.
- Critérios da história atendidos e evidências registradas.
- Testes significativos para valores, calendários, concorrência e integrações conforme o risco; não exigir testes repetitivos de detalhes triviais.
- Sem segredos no código/logs e sem sobrescrita ou duplicação silenciosa.
- Interface responsiva, mensagens de erro e comportamento de desconexão aplicáveis funcionando.
- Requisitos e decisões técnicas documentados; nenhuma pendência funcional encoberta por um valor arbitrário.

**Integrações posteriores:** E02 pode concluir sua quitação sem WhatsApp existente; a regra “quitação retira a conta do resumo” será validada em E08 e novamente no aceite final. O mesmo vale para categorias referenciadas por recorrências, remoção de membro com anexos e fechamento afetado por IA. Isso evita dependências circulares sem dispensar a integração posterior.

## 4. E01 — Acessar e compartilhar o espaço familiar

**Objetivo:** como administrador, quero configurar meu espaço e compartilhar com uma pessoa, mantendo o controle de acesso.

**Cobertura:** RF-ACC-01 a RF-ACC-11; matriz de permissões do PRD; segurança e sessões.  
**Aceite relacionado:** CA-01, CA-02 e CA-03.  
**Dependência:** preparação técnica mínima.  
**Bloqueios:** serviço de email, validade de tokens de confirmação/recuperação e proteção do primeiro acesso. Exclusão definitiva de conta é pendência do PRD e não se confunde com saída.

### H01.1 — Configurar administrador e espaço

Como Diego, quero criar meu acesso inicial para começar a usar o sistema sozinho.

- Criar administrador e espaço em fluxo inicial protegido.
- Impedir novo cadastro público após concluir; impedir dois administradores iniciais por solicitações concorrentes.
- Aplicar BRL, português do Brasil e fuso inicial America/Sao_Paulo.
- Disponibilizar estrutura de navegação responsiva e contexto do usuário autenticado.

### H01.2 — Entrar, confirmar email e recuperar acesso

Como usuário, quero autenticar e recuperar minha senha para acessar meus dados com segurança.

- Confirmar email e autenticar conforme RF-ACC-02.
- Recuperação por link válido uma única vez e por prazo definido tecnicamente.
- Permitir encerrar todas as próprias sessões; links inválidos/expirados não concedem acesso.
- Validar o envio real de email, além dos testes locais do fluxo.

### H01.3 — Convidar e aceitar o segundo membro

Como administrador, quero convidar uma pessoa para compartilhar todas as despesas.

- Convite vinculado ao email confirmado, expira em sete dias.
- Revogar/reemitir invalida o anterior; impedir terceiro membro e múltiplos espaços por usuário.
- Informar no convite que todo o histórico será compartilhado.
- Aceitação repetida não duplica vínculo.

### H01.4 — Aplicar papéis e gerenciar saída

Como membro, quero acessar o que meu papel permite e poder sair sem apagar o histórico.

- Convidado não gerencia membros/configurações gerais; ambos manipulam despesas conforme funcionalidades disponíveis.
- Saída/remoção revoga acesso em sessões abertas; preservar autoria histórica.
- Transferir administração antes da saída do administrador com outro membro; liberar vaga para novo convite.
- Limpeza de responsabilidades, anexos e consentimento de WhatsApp é exercitada novamente em E03, E04 e E08.

**Demonstração de conclusão:** administrador configura o espaço, convidado aceita, tentativas sem permissão falham, saída revoga sessão e permite novo convite. Acesso a recursos introduzidos depois deve herdar a mesma autorização.

## 5. E02 — Registrar e quitar despesas com segurança

**Objetivo:** como membro, quero saber o que está pendente e registrar pagamentos sem perder o histórico.

**Cobertura:** RF-DES-01 a RF-DES-12; conceitos e campos do PRD; RF-WEB-04 a RF-WEB-06 no fluxo de despesas.  
**Aceite relacionado:** CA-04 a CA-07 e CA-29.  
**Dependência:** E01.  
**Bloqueios:** política de valor zero e limites monetários; comportamento transacional da quitação em lote antes de H02.5.

### H02.1 — Cadastrar e listar uma despesa avulsa

Como membro, quero registrar uma conta pendente ou uma despesa já paga.

- Solicitar descrição, valor, situação e datas necessárias; pendente exige vencimento.
- Pago sem vencimento usa data do pagamento como referência mensal.
- Exibir lista básica e distinguir atraso por data local; vencimento hoje não está atrasado.
- Usar valor decimal exato; não permitir valor negativo como receita.
- Salvar novamente a mesma solicitação após resposta perdida não cria outra despesa.

### H02.2 — Quitar e identificar quem pagou

Como membro, quero marcar uma conta como paga, mesmo com juros ou desconto.

- Sugerir valor da cobrança, data atual e usuário atual; permitir indicar o outro membro como pagador.
- Guardar cobrança, valor efetivo, pagador e autor separadamente.
- Cobrança R$ 150 paga por R$ 155 ou R$ 145 fica totalmente quitada; não há saldo parcial.
- Confirmar valor estimado antes da quitação quando esse tipo existir em E04.
- Tentativas concorrentes não alteram a primeira quitação registrada.

### H02.3 — Corrigir com proteção contra conflito

Como membro, quero corrigir um lançamento sem sobrescrever a alteração de outra pessoa.

- Registrar autoria e alterações relevantes.
- Se a versão mudou, preservar a primeira gravação e solicitar revisão da segunda.
- Não permitir editar lançamento que foi cancelado durante a operação.
- Conexão perdida não gera falso sucesso; preservar campos com tela aberta e permitir tentativa segura.

### H02.4 — Desfazer quitação e cancelar

Como membro, quero corrigir registros incorretos preservando o histórico.

- Reversão exige motivo, guarda quitação desfeita e retorna à situação pendente/atrasada.
- Cancelamento exige motivo; pago precisa ter quitação desfeita antes.
- Cancelados ficam fora dos totais ativos e não são apagados definitivamente.
- Expor histórico no detalhe; futuras integrações preservam anexos e retiram/retomam lembretes conforme PRD.
- Não implementar reembolso nem reativação de cancelado como atalhos.

### H02.5 — Quitar vários lançamentos

Como membro, quero registrar o pagamento de várias contas de uma vez.

- Selecionar lançamentos, data e pagador comuns; usar cobrança confirmada de cada um.
- Estimativas e diferenças de pagamento são tratadas individualmente antes do lote.
- Resolver e comunicar conflitos conforme contrato transacional definido, sem sucesso parcial silencioso.
- Não criar uma despesa adicional representando a fatura de cartão quando suas compras já estão registradas.

**Demonstração de conclusão:** cadastrar, corrigir, quitar com diferença, reverter e cancelar; simular duplo clique e dois usuários editando a mesma conta. O histórico mostra autores distintos de pagadores.

## 6. E03 — Organizar e localizar despesas

**Objetivo:** como membro, quero categorizar, encontrar e comprovar despesas rapidamente.

**Cobertura:** RF-ORG-01 a RF-ORG-04; RF-ANX-01 a RF-ANX-04; RF-CON-01 a RF-CON-05; responsável opcional.  
**Aceite relacionado:** CA-03, CA-14 e parte de CA-15.  
**Dependência:** E01 e E02.

### H03.1 — Gerenciar categorias

Como membro, quero classificar despesas usando categorias comuns ao espaço.

- Criar categorias iniciais do PRD; permitir criar, renomear e arquivar por ambos os membros.
- Campo opcional com “Sem categoria”; não incluir subcategorias.
- Arquivo preserva histórico e impede novas seleções.
- Ao haver recorrência ativa, exigir substituição ou “Sem categoria” para próximas gerações; integração validada em E04.

### H03.2 — Atribuir responsável e consultar histórico

Como membro, quero indicar quem acompanha a conta sem restringir a atuação do outro.

- Responsável opcional, distinto de pagador e autor.
- Ambos continuam editando; retirada do membro limpa responsabilidade e avisa administrador.
- Autorias e pagamentos antigos continuam identificáveis.

### H03.3 — Anexar e acessar documentos

Como membro, quero guardar boleto, fatura ou comprovante junto ao lançamento.

- PDF/JPG/PNG, até cinco arquivos de até 10 MB, validados no servidor.
- Ambos adicionam, consultam, baixam e removem; registrar remoção.
- Cancelamento preserva anexos; reversão não apaga; anexo não quita.
- Membro removido não acessa arquivo mesmo com URL conhecida.

### H03.4 — Buscar e filtrar lançamentos

Como membro, quero localizar despesas por descrição e critérios do meu interesse.

- Mês atual por vencimento como entrada; filtros de período, categoria, responsável, pagador e situação.
- Ordenar por vencimento, valor ou descrição; cancelados ocultos por padrão.
- Atrasadas são subconjunto de pendentes; sem contagem em dobro.
- Manter contrato de filtros compartilhado com os totais/CSV de E06.

**Demonstração de conclusão:** filtrar uma despesa, ver responsável e histórico, anexar um comprovante, arquivar categoria e comprovar bloqueio do arquivo após remoção do convidado.

## 7. E04 — Automatizar contas recorrentes e previsões

**Objetivo:** como membro, quero cadastrar uma conta uma vez e acompanhar suas próximas cobranças.

**Cobertura:** RF-REC-01 a RF-REC-17.  
**Aceite relacionado:** CA-08 a CA-11 e integração com CA-06/CA-14.  
**Dependência:** E02 e E03.  
**Bloqueio localizado:** definir apresentação e alcance de alterações de metadados versus valores confirmados antes de H04.5, conforme seção 17 do PRD.

### H04.1 — Cadastrar recorrência e calcular calendário

Como membro, quero configurar uma cobrança fixa ou variável na frequência correta.

- Cinco frequências aprovadas, primeiro vencimento, valor fixo/estimativa, categoria e responsável opcionais.
- Não gerar histórico anterior ao início.
- Mês curto usa último dia e preserva dia original para próximos meses; fim de semana/feriado não desloca automaticamente.
- Disponibilizar cálculo de calendário para uso em parcelamentos e previsões sem repetir regras divergentes.

### H04.2 — Gerar ocorrências sem duplicação

Como membro, quero receber automaticamente os lançamentos de cada período.

- Geração no início do mês e após cadastro aplicável no mês corrente.
- Identidade da ocorrência não muda com vencimento; execução repetida não duplica.
- Respeitar ocorrência cancelada; nunca recriá-la para compensar ausência nos ativos.
- Prever execução agendada em servidor, integrada à operação de E11.

### H04.3 — Visualizar e antecipar previsões

Como membro, quero planejar os próximos 12 meses e pagar antes quando necessário.

- Exibir mês atual mais próximos 12 meses; separar projeção de lançamento real.
- Materializar antes do primeiro lembrete, ao confirmar valor ou ao pagar antecipadamente.
- Vencimento 03/10 está materializado até 28/09; não depende de E08 estar pronto para testar o calendário.
- Ao materializar, substituir previsão, sem dobrar total.

### H04.4 — Confirmar valores variáveis

Como membro, quero ajustar o valor real da fatura e melhorar estimativas seguintes.

- Exibir “a confirmar”; usar estimativa inicial até existir cobrança confirmada aplicável.
- Propagar referência apenas às estimativas futuras, por ordem de vencimento.
- Preservar valores confirmados e períodos anteriores; correção antiga não sobrepõe referência mais recente.
- Valor pago com juros/desconto não substitui automaticamente a cobrança base.

### H04.5 — Alterar e encerrar recorrência

Como membro, quero aplicar reajustes ou encerrar uma conta sem alterar o passado.

- Oferecer somente este lançamento ou este e próximos, mostrando o alcance.
- Preservar pagas e valores variáveis já confirmados; correções desses valores são individuais.
- Encerrar pelo último período, preservar pendências anteriores e retirar previsões posteriores.
- Futuras já pagas/confirmadas permanecem para revisão; estimadas posteriores saem da programação ativa com histórico.
- Arquivamento de categoria e saída do responsável também afetam futuras gerações corretamente.

**Demonstração de conclusão:** recorrência dia 31 atravessa fevereiro, gera uma só ocorrência, atualiza estimativas, materializa antes do mês e termina sem apagar pendências.

## 8. E05 — Controlar compras parceladas

**Objetivo:** como membro, quero dividir uma compra em parcelas finitas e controlar cada vencimento.

**Cobertura:** RF-PAR-01 a RF-PAR-08; seção de cartão do PRD.  
**Aceite relacionado:** CA-12 e integração com CA-05/CA-13.  
**Dependência:** E02/E03 e calendário H04.1; pode começar antes de concluir todo E04.  
**Bloqueios:** limites de quantidade/valor e apresentação de ações em lote.

### H05.1 — Criar compra e parcelas

Como membro, quero informar total, quantidade e primeiro vencimento uma única vez.

- Gerar parcelas mensais com identificação n/N, categoria e responsável.
- Somar exatamente o total; diferenças de centavos ficam na última parcela.
- Valor informado já inclui juros da compra; não calcular financiamento.
- Fim automático na última parcela; horizonte de previsão não limita duração da compra.

### H05.2 — Consultar e quitar parcelas

Como membro, quero pagar cada parcela ou várias selecionadas sem duplicar a compra.

- Cada parcela participa da lista, dos filtros e da quitação de E02.
- O total da compra não entra novamente nos totais como outra despesa.
- Mostrar progresso de parcelas através de suas situações, sem inventar saldo bancário.
- Não cadastrar fatura completa como duplicata dos lançamentos do cartão.

### H05.3 — Ajustar e cancelar parcelas pendentes

Como membro, quero corrigir as parcelas futuras preservando o que já paguei.

- Mostrar seleção afetada; alterar metadados ou vencimentos conforme alcance aprovado.
- Preservar parcelas pagas.
- Corrigir total/quantidade exige cancelar pendentes e criar parcelamento restante.
- Cancelamento não gera reembolso nem reversão automática.

**Demonstração de conclusão:** compra com divisão não exata soma corretamente, uma parcela é paga e as restantes são alteradas/canceladas sem afetar a paga.

## 9. E06 — Acompanhar totais e exportar dados

**Objetivo:** como membro, quero entender minhas despesas por mês e analisar os dados fora do sistema.

**Cobertura:** RF-REL-01 a RF-REL-04; RF-CSV-01 a RF-CSV-05; integração de RF-CON-05.  
**Aceite relacionado:** CA-13, CA-15 e CA-30.  
**Dependência:** pode iniciar com E02/E03; conclusão com avulsas, recorrências e parcelas de E04/E05.

### H06.1 — Consultar dashboard por vencimento

Como membro, quero saber quanto foi previsto, quitado e ainda está pendente no mês.

- Aplicar as fórmulas do PRD a lançamentos ativos filtrados.
- Mostrar parcela estimada dos valores; separar pendências anteriores.
- Total pendente vem de contas abertas, nunca simplesmente previsto menos pago.
- Cancelados continuam fora dos totais mesmo se exibidos na lista.

### H06.2 — Consultar pagamentos e ajustes

Como membro, quero saber quando efetivamente paguei e quais diferenças houve.

- Visão separada pela data de quitação ativa; reversões retiradas.
- Vencimento setembro/pagamento outubro aparece nos meses corretos de cada visão.
- Cobrança R$ 150 paga R$ 155: pendente zero, ajuste +R$ 5, pagamento R$ 155.
- Identificar claramente base temporal; não exibir receitas, saldo ou resultado financeiro.

### H06.3 — Consultar planejamento futuro integrado

Como membro, quero ver compromissos futuros sem duplicar projeções e lançamentos.

- Integrar recorrências, parcelas e avulsas futuras no horizonte aprovado.
- Diferenciar confirmado/estimado; ocorrência materializada substitui projeção.
- Encerramento e cancelamento refletem no planejamento sem apagar histórico real.

### H06.4 — Exportar CSV filtrado

Como membro, quero exportar a seleção atual para abrir no Excel.

- Respeitar base temporal e filtros; colunas e cancelados conforme PRD.
- Previsões em exportação separada; não incluir arquivos anexos.
- Preservar acentos, datas e valores; proteger textos contra interpretação como fórmula.
- Validar com 10 mil lançamentos e alvo de até 60 segundos nas condições documentadas.

**Demonstração de conclusão:** comparar manualmente um conjunto com estimativas, juros, desconto, atraso, cancelamento e pagamento em outro mês com os dois dashboards e o CSV.

## 10. E07 — Revisar e fechar o mês

**Objetivo:** como membro, quero guardar o retrato do mês revisado sem impedir correções futuras.

**Cobertura:** RF-FEC-01 a RF-FEC-05.  
**Aceite relacionado:** CA-16; integração com CA-06 e CA-13.  
**Dependência:** E06.

### H07.1 — Fechar mês com resumo

Como membro, quero registrar que revisei um mês e guardar seus números.

- Salvar totais por vencimento, categorias, estimativas, pendências, autor e instante.
- Permitir fechamento com pendências mediante aviso.
- Não quitar, esconder conta ou interromper lembrete por causa do fechamento.

### H07.2 — Sinalizar alterações posteriores

Como membro, quero distinguir o retrato salvo dos dados atuais.

- Edição, quitação, reversão, cancelamento ou mudança de categoria não reescrevem o retrato.
- Indicar alteração posterior e manter consulta dos dados atuais.
- Testar mudança que afeta o mês após ele já ter sido fechado.

### H07.3 — Gerar e consultar versões

Como membro, quero atualizar o fechamento e consultar versões anteriores.

- Ambos podem gerar nova versão; preservar autor/data e conteúdo de todas.
- Navegação permite distinguir versões e estado atual.
- Nova versão não impõe bloqueio de edição.

**Demonstração de conclusão:** fechar com pendência, quitar/corrigir depois e gerar nova versão; a primeira mantém seus valores originais.

## 11. E08 — Receber lembretes agrupados

**Objetivo:** como administrador, quero receber resumos no WhatsApp; como convidado, quero consultar os avisos no aplicativo.

**Cobertura:** RF-ALT-01 a RF-ALT-20; RF-WEB-07 quanto aos jobs de alertas.  
**Aceite relacionado:** CA-17 a CA-21; partes de CA-02, CA-03, CA-04 e CA-32.  
**Dependência:** E01/E02/E04/E05 para comportamento completo.  
**Bloqueio externo:** provedor, remetente, formato aprovado, retorno de entrega e política de resposta incerta antes do envio real. Não bloquear o motor de elegibilidade por falta do provedor.

### H08.1 — Configurar canal, consentimento e horários

Como administrador, quero escolher meu número receptor e horários de recebimento.

- Remetente dedicado e receptor configurável; consentimento com data/hora.
- Padrões 09h/18h no fuso do espaço; segundo posterior ao primeiro.
- Convidado não habilita WhatsApp; responsabilidade pela conta não altera destinatário.
- Transferência de administração desativa uso do consentimento anterior e exige novo.

### H08.2 — Calcular elegibilidade e resumo

Como membro, quero que só contas relevantes apareçam nos avisos de cada horário.

- Dias -5 a -2 no primeiro; -1 e 0 nos dois; atrasadas no primeiro.
- Agrupar em no máximo dois resumos lógicos por dia; sem envio vazio.
- Total/quantidade de todas as elegíveis e até cinco detalhes, atrasadas primeiro, com excedente e link autenticado.
- Pagas/canceladas fora; estimativas identificadas; materialização antecipada integrada a E04.

### H08.3 — Disponibilizar notificações internas

Como membro, quero consultar os resumos e saber de problemas de envio dentro do aplicativo.

- Ambos consultam avisos; convidado recebe somente nesse canal.
- Abrir/dispensar não quita nem interrompe próximos lembretes.
- Falha de WhatsApp visível ao administrador, sem retirar funcionalidades.
- Nenhum push ou email de backup é introduzido.

### H08.4 — Enviar e acompanhar WhatsApp real

Como administrador, quero receber o resumo no número configurado e acompanhar seu resultado.

- Envio único lógico por espaço/data/horário/canal; tentativas correlacionadas.
- Distinguir tentativa, aceite e entrega confirmada; registrar motivo de falha.
- Revisar consentimento e dados antes de enviar; quitação somente no aplicativo.
- Validar número configurado com envio real, sem considerar apenas simulação como entrega concluída.

### H08.5 — Revalidar e tratar falhas sem acúmulo

Como administrador, quero evitar mensagens obsoletas após mudanças ou indisponibilidade.

- Mudança de vencimento recalcula elegibilidade sem disparo extra imediato.
- Revalidar em cada tentativa: conta paga/cancelada ou resumo vazio não são enviados.
- Retentar até uma hora do horário original, parando antes do próximo resumo ou desativação; não reiniciar janela a cada tentativa.
- Falhas permanentes suspendem canal; falha geral do provedor não marca número como inválido.
- Reconciliar resposta incerta conforme provedor; não reenviar cegamente nem prometer recolher mensagem já aceita.

**Demonstração de conclusão:** controlar relógio de teste para as janelas, pagar/alterar durante espera e simular falhas; depois confirmar resumo real ao administrador e ausência de WhatsApp ao convidado.

## 12. E09 — Usar no celular como PWA

**Objetivo:** como membro, quero abrir pela tela inicial do celular e capturar imagens com facilidade.

**Cobertura:** RF-WEB-01 a RF-WEB-06, complementando comportamentos básicos já aplicados; RNF-UX-01 a RNF-UX-04.  
**Aceite relacionado:** CA-22 e CA-29.  
**Dependência:** E01–E03; pode ser antecipado após M1.  
**Bloqueio localizado:** dispositivos/navegadores-alvo e estratégia segura de cache.

### H09.1 — Instalar e navegar pelo celular

Como membro, quero abrir o sistema pela tela inicial sem aplicativo nativo.

- Implementar instalação PWA nos dispositivos suportados e navegação responsiva.
- Ação de cadastro rápido acessível; formulários funcionam por teclado, com rótulos e erros compreensíveis.
- Preservar regras de sessão e autorização; não disponibilizar dados financeiros indevidamente em cache.

### H09.2 — Capturar ou selecionar imagem

Como membro, quero usar câmera ou galeria para fornecer uma imagem.

- Solicitar acesso apenas no fluxo adequado; câmera negada não impede seleção de arquivo.
- Permitir visualizar a imagem selecionada antes de prosseguir.
- Reutilizar captura no fluxo de anexo e depois em E10, sem disparar IA ao anexar normalmente.
- Validar no celular real, não apenas em emulação de tela.

### H09.3 — Tratar desconexão de forma clara

Como membro, quero saber quando uma operação não foi salva.

- Sem edição offline ou fila oculta de alterações; consulta exige conexão.
- Preservar preenchimento com tela aberta e só confirmar sucesso após resposta.
- Reenvio não duplica; PWA instalada não implica funcionar sem internet.

**Demonstração de conclusão:** instalar, entrar, cadastrar, anexar foto e experimentar perda de conexão no dispositivo-alvo.

## 13. E10 — Cadastrar despesas por imagem com IA

**Objetivo:** como membro, quero transformar uma foto em dados sugeridos, mantendo a decisão final antes de salvar.

**Cobertura:** RF-IA-01 a RF-IA-18.  
**Aceite relacionado:** CA-23 a CA-28, com CA-22 e CA-07 nas integrações.  
**Dependência:** E02/E03/E04/E09.  
**Bloqueios:** provedor/modelo, limites de entrada, timeout, custo e retenção; política de cota em falhas antes da ativação. Não liberar processamento real sem retenção e cota implementadas.

### H10.1 — Solicitar análise e revisar sugestões

Como membro, quero fotografar uma etiqueta ou conta e revisar o que a IA identificou.

- Análise apenas mediante ação explícita; informar envio ao provedor.
- Uma despesa/conta por imagem; sugerir descrição, valor, categoria existente e dados identificáveis.
- Campos ausentes/ambíguos destacados; não inventar valor/vencimento nem criar categorias.
- Editar sugestão não dispara nova análise; alternativa manual sempre disponível.

### H10.2 — Criar avulsa ou vincular a existente

Como membro, quero aproveitar sugestões sem duplicar uma despesa já registrada.

- Apresentar possíveis correspondências; seleção/confirmacão humana decide vínculo.
- Oferecer atualizar lançamento ou criar avulsa, reaproveitando validações de E02.
- Etiqueta não prova compra: confirmar quantidade/valor final e situação.
- Confirmação repetida produz uma única operação; conflito de edição é apresentado.

### H10.3 — Criar recorrência ou sugerir quitação

Como membro, quero transformar uma cobrança em recorrência ou usar um comprovante para registrar pagamento.

- Nova recorrência exige frequência, primeiro vencimento e revisão de previsão.
- Serviço não é automaticamente recorrente; fatura não é automaticamente paga.
- Comprovante só sugere quitação; usuário confirma lançamento, valor, data e pagador.
- Cobrança recorrente existente pode atualizar ocorrência sem duplicar configuração.

### H10.4 — Controlar cota compartilhada

Como administrador, quero acompanhar e limitar as análises do espaço.

- Padrão de 20/dia, ajustável só pelo administrador; compartilhado com convidado.
- Virada no fuso do espaço e controle consistente em concorrência.
- Nova análise explícita conta; edição manual não conta. Política de erro deve estar definida e testada.
- Cota atingida/falha não impede cadastro manual; apresentar uso ao administrador.

### H10.5 — Controlar retenção e anexação

Como membro, quero escolher se guardo a imagem depois de usá-la.

- “Guardar como anexo” desmarcado; se selecionado, aplicar regras/limites de E03.
- Sem anexação, excluir temporário até 24h, inclusive abandono; temporários fora de backups permanentes.
- Registrar solicitante/data, sem imagem em logs; documentar retenção externa do provedor.
- Saída da IA é entrada não confiável e nunca executa comandos financeiros diretamente.

**Demonstração de conclusão:** etiqueta clara, imagem ambígua, fatura existente e comprovante; usuário revisa antes de salvar. Validar cota concorrente e limpeza de foto abandonada com controle temporal.

## 14. E11 — Operar e recuperar o sistema na VPS

**Objetivo:** como Diego, quero utilizar o sistema com segurança e recuperar minhas informações quando houver falha.

**Cobertura:** RNF-OPS-01/02, RNF-BKP-01 a 03, RNF-OBS-01 e validação integrada dos demais RNFs; RF-WEB-07.  
**Aceite relacionado:** CA-30 a CA-32 e execução final de CA-01 a CA-29.  
**Dependência:** começa com preparação, termina após E01–E10.  
**Bloqueios:** dimensionamento VPS, domínio/HTTPS, destino externo dos backups e procedimento operacional.

### H11.1 — Preparar execução e implantação

Como responsável pelo sistema, quero executar localmente e publicar na VPS Hostinger de forma reproduzível.

- Documentar ambientes, migrações e configuração sem segredos no repositório.
- Configurar HTTPS e serviços com reinício automático.
- Distinguir configuração local de produção; não depender de computador/browser para jobs.
- Iniciar essa história cedo; integração final contempla os serviços dos demais épicos.

### H11.2 — Fazer backup diário externo

Como Diego, quero cópias dos dados e anexos permanentes fora da VPS.

- Rotina diária com retenção das últimas sete cópias diárias/dias conforme PRD.
- Registrar sucesso/falha, restringir acesso ao destino e excluir imagens temporárias da IA.
- Coordenar dados/anexos para restauração consistente.
- Objetivo de perda de até 24h depende de backups funcionando; falhas devem ficar visíveis.

### H11.3 — Restaurar e retomar serviços

Como Diego, quero recuperar o sistema sem descobrir na falha que o backup é inutilizável.

- Executar restauração de dados e anexos antes do uso real e registrar evidência.
- Documentar passos e expectativa de recuperação sem inventar SLA não aprovado.
- Reiniciar VPS e validar aplicação/jobs; mensagens antigas não são disparadas em lote na retomada.

### H11.4 — Validar capacidade e operação

Como responsável pelo sistema, quero comprovar que a capacidade é adequada ao uso pessoal.

- Dataset de 10 mil lançamentos, consulta/filtros/totais e CSV em até 60s nas condições documentadas.
- Buscar páginas em até 3s no uso habitual; documentar limitações se a meta não for atingida.
- Início dos alertas até cinco minutos do horário quando serviço disponível.
- Logs de falhas, uso de IA e tentativas de mensagem sem dados sensíveis; não exigir estrutura comercial.

### H11.5 — Executar aceite integrado do MVP

Como Diego, quero liberar o uso diário só após os cenários essenciais funcionarem juntos.

- Executar e registrar CA-01 a CA-32, reaproveitando evidências válidas e complementando integrações posteriores.
- Incluir email real, WhatsApp real, interpretação por IA, PWA no celular e restauração externa.
- Não considerar mocks como comprovação de entrega externa real.
- Listar pendências remanescentes e bloquear somente fluxos cuja regra esteja indefinida; não declarar o MVP inteiro concluído se critério obrigatório faltar.

**Demonstração de conclusão:** acessar a VPS por HTTPS, receber lembrete, cadastrar por foto, exportar e restaurar cópia externa com evidências do aceite integrado.

## 15. Pendências e momento de resolução

| Pendência do PRD | Momento limite | Tratamento |
|---|---|---|
| Stack e arquitetura | Antes de H01.1 | Registrar especificação técnica; nenhuma escolha presumida neste backlog. |
| Email e tokens | Antes de H01.2/H01.3 | Definir serviço e validade de confirmação/recuperação; convite já vale sete dias. |
| Valor zero/faixas monetárias | Antes de H02.1 | Decisão explícita; zero não representa desconhecido. |
| Transação e conflito em lote | Antes de H02.5 e H05.3 | Definir atomicidade e feedback. |
| Limites de parcela | Antes de H05.1 | Definir faixa suportada e validação. |
| Edição de metadados confirmados | Antes de H04.5 | Resolver alcance de cada campo sem alterar valores protegidos. |
| Provedor WhatsApp/resposta incerta | Antes de H08.4 | Concluir configuração e contrato técnico; H08.2 pode ser testada antes. |
| Dispositivos/cache PWA | Antes de H09.1 | Selecionar alvos de validação e proteção de dados. |
| IA, custos e retenção externa | Antes de análise real de H10.1 | Informar tratamento e validar provedor. |
| Cota de IA em erro | Antes de H10.4/ativação | Definir contabilização sem contornar limite em concorrência. |
| VPS e backup externo | Antes de uso com dados reais | Configurar e demonstrar restauração em E11. |
| Apagamento, exportação pessoal e encerramento sem sucessor | Antes de expor essas ações; revisar antes da liberação | Pendência de produto herdada do PRD; não transformar CSV em exportação pessoal completa nem saída em apagamento. Criar histórias adicionais após decisão. |
| Restauração de cancelado | Somente se aprovada futuramente | Não introduzir botão de restauração nem recriação automática. |

## 16. Requisitos transversais

| Tema | Responsabilidade durante o desenvolvimento |
|---|---|
| Segurança e isolamento | E01 estabelece identidade; todos os épicos validam autorização; E11 valida implantação. |
| Auditoria | E02 define histórico; alterações introduzidas por outros épicos integram a mesma rastreabilidade. |
| Valores exatos | E02 estabelece representação; E04/E05/E06 reutilizam para estimativas, parcelas e totais. |
| Idempotência e concorrência | Em cada ação mutável; não é atividade adiada para o fim. |
| Responsividade e acessibilidade | Toda história com interface; E09 adiciona instalação e validação no celular. |
| Backup e recuperação | Planejamento desde preparação; execução automática antes de dados reais; prova em E11. |
| Observabilidade | Cada integração registra resultados; consolidação operacional em E11. |
| Fuso e datas | Um contrato comum para vencimento, atraso, jobs e cota de IA. |

## 17. Orientação para iniciar o desenvolvimento

Começar pela preparação técnica mínima e depois por **H01.1**. Para cada história, consultar o épico, o PRD e as pendências pertinentes; detalhar critérios em cenários de teste; implementar o fluxo completo; demonstrar a entrega e registrar evidências antes de avançar.

Não é necessário implementar um épico inteiro em uma única solicitação. A unidade recomendada é uma história, ou parte explicitamente delimitada quando envolver integração externa. Não estimamos horas ou sprints porque stack, disponibilidade e fornecedores ainda não foram definidos.

A ordem numérica é o caminho sequencial padrão. Antecipações permitidas por dependências: preparar VPS e provedores desde o início; iniciar E05 após H04.1; executar E09 após E03. Mesmo nesses casos, o aceite integrado permanece obrigatório.

## 18. Matriz de rastreabilidade completa

Cada identificador abaixo existe no PRD v2.0. A coluna de responsabilidade indica o épico que entrega a capacidade; requisitos transversais também são exigidos nas histórias afetadas.

| Requisitos do PRD | Responsabilidade |
|---|---|
| RF-ACC-01, RF-ACC-02, RF-ACC-03, RF-ACC-04, RF-ACC-05, RF-ACC-06, RF-ACC-07, RF-ACC-08, RF-ACC-09, RF-ACC-10, RF-ACC-11 | E01 |
| RF-DES-01, RF-DES-02, RF-DES-03, RF-DES-04, RF-DES-05, RF-DES-06, RF-DES-07, RF-DES-08, RF-DES-09, RF-DES-10, RF-DES-11, RF-DES-12 | E02 |
| RF-REC-01, RF-REC-02, RF-REC-03, RF-REC-04, RF-REC-05, RF-REC-06, RF-REC-07, RF-REC-08, RF-REC-09, RF-REC-10, RF-REC-11, RF-REC-12, RF-REC-13, RF-REC-14, RF-REC-15, RF-REC-16, RF-REC-17 | E04 |
| RF-PAR-01, RF-PAR-02, RF-PAR-03, RF-PAR-04, RF-PAR-05, RF-PAR-06, RF-PAR-07, RF-PAR-08 | E05 |
| RF-ORG-01, RF-ORG-02, RF-ORG-03, RF-ORG-04 | E03 |
| RF-ANX-01, RF-ANX-02, RF-ANX-03, RF-ANX-04 | E03 |
| RF-CON-01, RF-CON-02, RF-CON-03, RF-CON-04, RF-CON-05 | E03, com totais/CSV integrados em E06 |
| RF-ALT-01, RF-ALT-02, RF-ALT-03, RF-ALT-04, RF-ALT-05, RF-ALT-06, RF-ALT-07, RF-ALT-08, RF-ALT-09, RF-ALT-10, RF-ALT-11, RF-ALT-12, RF-ALT-13, RF-ALT-14, RF-ALT-15, RF-ALT-16, RF-ALT-17, RF-ALT-18, RF-ALT-19, RF-ALT-20 | E08 |
| RF-REL-01, RF-REL-02, RF-REL-03, RF-REL-04 | E06 |
| RF-FEC-01, RF-FEC-02, RF-FEC-03, RF-FEC-04, RF-FEC-05 | E07 |
| RF-CSV-01, RF-CSV-02, RF-CSV-03, RF-CSV-04, RF-CSV-05 | E06 |
| RF-WEB-01, RF-WEB-02, RF-WEB-03, RF-WEB-04, RF-WEB-05, RF-WEB-06, RF-WEB-07 | E09, com conexão em E02 e jobs em E08/E11 |
| RF-IA-01, RF-IA-02, RF-IA-03, RF-IA-04, RF-IA-05, RF-IA-06, RF-IA-07, RF-IA-08, RF-IA-09, RF-IA-10, RF-IA-11, RF-IA-12, RF-IA-13, RF-IA-14, RF-IA-15, RF-IA-16, RF-IA-17, RF-IA-18 | E10 |
| RNF-SEG-01, RNF-SEG-02, RNF-SEG-03, RNF-SEG-04, RNF-SEG-05, RNF-SEG-06 | E01–E11 conforme ação; validação integrada em E11 |
| RNF-PER-01, RNF-PER-02, RNF-PER-03, RNF-PER-04 | E06, E08 e E11 |
| RNF-OPS-01, RNF-OPS-02 | E11 |
| RNF-BKP-01, RNF-BKP-02, RNF-BKP-03 | E11 |
| RNF-OBS-01 | E08/E10 em integração; E11 na operação |
| RNF-UX-01, RNF-UX-02, RNF-UX-03, RNF-UX-04 | Todos os fluxos de interface; E09 na experiência PWA |

### 18.1 Responsabilidade pelos critérios de aceite

Todos também são revisados no aceite integrado H11.5.

| Critério do PRD | Épico responsável pela evidência |
|---|---|
| CA-01 | E01 |
| CA-02 | E01/E08 |
| CA-03 | E01/E03/E04/E08 |
| CA-04 | E02/E08 |
| CA-05 | E02/E06 |
| CA-06 | E02/E04/E07/E08 |
| CA-07 | E02 e mutações de todos os módulos |
| CA-08 | E04 |
| CA-09 | E04/E06/E08 |
| CA-10 | E04/E06 |
| CA-11 | E04 |
| CA-12 | E05 |
| CA-13 | E06 |
| CA-14 | E03/E04 |
| CA-15 | E03/E06 |
| CA-16 | E07 |
| CA-17 | E08 |
| CA-18 | E08 |
| CA-19 | E08 |
| CA-20 | E08 |
| CA-21 | E08 |
| CA-22 | E09/E10 |
| CA-23 | E10 |
| CA-24 | E10 |
| CA-25 | E10 |
| CA-26 | E10/E02 |
| CA-27 | E10 |
| CA-28 | E10/E11 |
| CA-29 | E02/E09 |
| CA-30 | E06/E11 |
| CA-31 | E11 |
| CA-32 | E08/E11 |

---

**Inventário:** 11 épicos, 46 histórias propostas e cobertura dos 140 requisitos identificados e 32 critérios de aceite do PRD v2.0. Pendências de produto continuam explícitas e não contam como histórias aprovadas.
