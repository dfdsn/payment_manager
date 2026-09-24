# PRD — Gerenciador pessoal de contas e despesas

**Projeto:** account_Manager  
**Responsável pelo produto:** Diego  
**Versão:** 2.0  
**Data:** 22/09/2026  
**Natureza:** aplicação de uso pessoal, sem comercialização prevista  
**Status:** decisões funcionais consolidadas; pendências de especificação identificadas na seção 17.

## 1. Objetivo e autoridade deste documento

Este PRD consolida a revisão do documento original e as decisões aprovadas por Diego na entrevista de definição do produto. Orienta a criação de histórias, arquitetura, implementação e testes. Não afirma que o sistema já foi implementado ou que os critérios de aceite já foram executados.

Esta versão substitui as definições conflitantes da versão 1.0. Os requisitos receberam novos identificadores organizados por módulo; os antigos RF1–RF31 não devem ser usados como contrato de implementação desta versão. Nenhuma referência a pacotes P0/P1, códigos morfológicos ou documentos de brainstorming externos é necessária para compreender o escopo.

As regras descritas como requisitos são decisões aprovadas. Itens da seção 17 são pendências explícitas: não representam escolhas silenciosamente aprovadas. A stack e os provedores externos serão definidos na especificação técnica.

## 2. Visão do produto

Permitir que Diego e um convidado organizem despesas domésticas, saibam o que falta pagar, acompanhem pagamentos e planejem os próximos meses. O sistema deve reduzir o preenchimento manual por meio de recorrências, parcelamentos e cadastro assistido por imagem, mantendo confirmação humana sobre os dados financeiros.

A aplicação será web responsiva e instalável como PWA, acessível pelo computador e pelo celular, hospedada em uma VPS da Hostinger. O uso exige internet. O administrador recebe resumos de vencimentos no WhatsApp; o convidado recebe avisos somente dentro da aplicação.

### 2.1 Resultados esperados

- Registrar uma despesa já paga ou uma conta a pagar com poucos campos.
- Gerar cobranças recorrentes sem recadastro mensal.
- Consultar o mês atual, pendências anteriores e previsões dos próximos 12 meses.
- Distinguir valores de cobrança, estimativas e valores efetivamente pagos.
- Compartilhar integralmente os dados com um segundo usuário.
- Receber lembretes corretos, agrupados e sem envios acumulados.
- Revisar o mês, preservar versões do fechamento e exportar os dados.
- Fotografar uma etiqueta, cobrança ou comprovante e revisar sugestões da IA antes de salvar.
- Recuperar dados e anexos em caso de falha.

Não há metas comerciais de aquisição, retenção ou receita. O sucesso será avaliado pelo funcionamento dos fluxos pessoais e pelos critérios de aceite deste documento.

## 3. Escopo

### 3.1 Incluído no MVP

| Área | Entrega |
|---|---|
| Acesso | Email e senha, confirmação de email, recuperação de senha e encerramento de sessões. |
| Espaço familiar | Uso individual ou com um convidado; máximo de dois membros ativos. |
| Despesas | Avulsas, recorrentes e parceladas; pendentes ou pagas. |
| Recorrências | Mensal, bimestral, trimestral, semestral e anual; valor fixo ou variável estimado. |
| Previsão | Mês atual e próximos 12 meses, distinguindo estimativas de valores confirmados. |
| Quitação | Total, com data, valor efetivo e pagador; reversão com histórico. |
| Organização | Categorias, responsável opcional, busca e filtros. |
| Anexos | PDF, JPG e PNG opcionais; até cinco arquivos de até 10 MB por lançamento. |
| Alertas | Resumos WhatsApp para administrador e notificações internas; regras temporais detalhadas na seção 10. |
| Fechamento | Simbólico, sem bloqueio, com versões preservadas. |
| Relatórios | Dashboard por vencimento, visão por pagamento e exportação CSV. |
| Celular | Web responsiva e PWA; foto ou seleção de imagem. |
| IA | Uma despesa/conta por imagem, revisão obrigatória, cota diária compartilhada. |
| Operação | VPS Hostinger, HTTPS, backups externos diários e reinício dos serviços. |

### 3.2 Fora do MVP

- Receitas, saldo bancário, saldo disponível, resultado financeiro e movimentação de dinheiro.
- Integração bancária, conciliação automática e importação de extratos ou arquivos financeiros.
- Pagamento parcial com saldo remanescente, divisão financeira entre membros e reembolsos.
- Gestão de cartões, limite, fechamento e cálculo automático de faturas.
- Múltiplas moedas e conversão cambial automática.
- Cadastro público livre, comercialização, assinaturas e múltiplos espaços por usuário.
- Despesas privadas e permissões por categoria ou conta.
- Subcategorias, metas e orçamento com alertas de estouro.
- Recorrências semanais, quinzenais ou com intervalos personalizados.
- Ajuste automático de vencimentos por feriados ou dias não úteis.
- Edição offline, sincronização offline, aplicativos nativos e notificações push.
- Email como canal de backup para lembretes de vencimento. Email transacional de acesso e convite permanece incluído.
- Configuração separada de horas silenciosas.
- Quitação pelo WhatsApp e cadastro financeiro sem confirmação humana.
- Leitura de vários produtos de um cupom com desmembramento automático em despesas.
- Relatórios PDF, API pública e cálculo de juros de financiamento.

## 4. Conceitos e dados do domínio

| Conceito | Definição |
|---|---|
| Espaço familiar | Ambiente compartilhado pelo administrador e, opcionalmente, um convidado. |
| Conta recorrente | Configuração que define descrição, frequência, primeiro vencimento, regra de valor, categoria, responsável opcional e eventual término. Não é somada diretamente como despesa. |
| Lançamento | Despesa concreta de um período, avulsa, gerada por recorrência ou parcela de uma compra. Alimenta totais e quitações. |
| Previsão | Projeção de uma ocorrência futura ainda não materializada como lançamento. Não pode ser somada novamente quando o lançamento existir. |
| Parcelamento | Compra com valor total e quantidade finita de parcelas mensais. Não é recorrência indefinida. |
| Quitação | Registro de pagamento total no lançamento, com valor, data e pagador. Não exige entidade de obrigação independente. |
| Responsável | Membro encarregado de acompanhar a conta; não determina acesso nem destinatário do WhatsApp. |
| Pagador | Membro que efetivamente pagou; pode ser diferente do autor do registro. |
| Autor | Usuário que realizou uma ação, registrado automaticamente. |
| Valor estimado | Valor ainda a confirmar de uma cobrança variável. Não é um estado de pagamento. |
| Fechamento | Retrato versionado dos dados do mês no instante em que foi solicitado. |

### 4.1 Situações e valores

Um lançamento ativo é **pendente** ou **pago**. **Atrasado** é uma condição calculada: pendente e com vencimento anterior à data atual no fuso do espaço. No próprio dia do vencimento ainda não é atrasado. **Cancelado** é uma situação que o exclui dos totais ativos e dos alertas, preservando o histórico.

O valor da cobrança e o valor efetivamente pago são distintos. A confirmação de valor variável transforma uma estimativa em cobrança confirmada; ela não quita o lançamento. Anexar imagem, abrir notificação e dispensar aviso também não quitam.

### 4.2 Campos principais do lançamento

| Campo | Regra |
|---|---|
| Descrição | Identifica a despesa. |
| Origem | Avulsa, recorrência ou parcela; referência preservada quando aplicável. |
| Valor da cobrança | Em BRL, duas casas decimais; identificado como estimado ou confirmado. |
| Vencimento | Obrigatório para pendentes; referência principal dos totais mensais. |
| Situação | Pendente, pago ou cancelado; atraso derivado. |
| Categoria | Opcional; ausência exibida como “Sem categoria”. |
| Responsável | Opcional; membro ativo do espaço. |
| Observação | Opcional. |
| Quitação | Quando pago: data do pagamento, valor efetivo, pagador e autor do registro. |
| Anexos | Opcionais, sujeitos aos limites. |
| Histórico | Criação e alterações relevantes, autores, datas e motivos quando exigidos. |

Despesas cadastradas já pagas podem não informar vencimento. Nesse caso, a data do pagamento é a referência mensal. Alterações posteriores nessa referência devem seguir as regras de auditoria e fechamento.

## 5. Identidade, espaço familiar e permissões

### 5.1 Requisitos de acesso

- **RF-ACC-01:** permitir configuração inicial protegida para criar o administrador e seu espaço; após concluída, desabilitar criação pública de contas.
- **RF-ACC-02:** autenticar por email e senha, com confirmação do email.
- **RF-ACC-03:** permitir recuperação de senha por link de uso único e validade limitada, parametrizada na especificação técnica.
- **RF-ACC-04:** permitir encerrar as sessões do próprio usuário em todos os dispositivos.
- **RF-ACC-05:** administrador pode convidar um segundo usuário por email. Convite vale sete dias, é vinculado ao email confirmado e não admite ultrapassar dois membros ativos.
- **RF-ACC-06:** permitir revogar ou reenviar convite; reenvio invalida o link anterior.
- **RF-ACC-07:** cada usuário pertence a apenas um espaço familiar por vez. O sistema pode ser usado sozinho enquanto não houver convidado.

### 5.2 Matriz de permissões

| Ação | Administrador | Convidado |
|---|---|---|
| Consultar todas as despesas, histórico e anexos | Sim | Sim |
| Criar/editar despesas, recorrências e parcelamentos | Sim | Sim |
| Quitar, desfazer quitação e cancelar | Sim | Sim |
| Gerenciar categorias e anexos | Sim | Sim |
| Usar cadastro por IA | Sim | Sim |
| Fechar mês e gerar nova versão | Sim | Sim |
| Consultar relatórios e exportar CSV | Sim | Sim |
| Convidar/remover membro | Sim | Não |
| Alterar configurações gerais, horários, número receptor e cota de IA | Sim | Não |
| Gerenciar preferências pessoais disponíveis | Sim | Sim |
| Receber WhatsApp | Sim, com consentimento | Não |

Preferências pessoais não podem habilitar WhatsApp para o convidado. Não há dados financeiros privados entre os membros. Responsável, pagador e autor são informações diferentes; atribuir responsabilidade não restringe a edição.

### 5.3 Saída e transferência

- **RF-ACC-08:** convidado pode sair; administrador pode removê-lo. A revogação de acesso inclui sessões abertas e acesso aos anexos.
- **RF-ACC-09:** despesas e pagamentos de quem saiu permanecem, preservando sua identificação histórica. Contas e lançamentos sob sua responsabilidade ficam sem responsável, com aviso ao administrador.
- **RF-ACC-10:** administrador com outro membro deve transferir a administração antes de sair. O novo administrador precisa ativar seu próprio WhatsApp; consentimento e número não são transferidos automaticamente.
- **RF-ACC-11:** com uma vaga, permitir novo convite. O convite deve informar que o novo membro terá acesso a todo o histórico do espaço.

Saída de membro não equivale a exclusão definitiva de conta ou de dados. Encerramento do espaço e apagamento pessoal são tratados como pendência na seção 17.

## 6. Despesas, quitação, cancelamento e concorrência

- **RF-DES-01:** cadastrar despesa avulsa já paga ou pendente. Cadastro rápido solicita descrição, valor, situação e a data aplicável; campos complementares não devem dificultar o fluxo.
- **RF-DES-02:** marcar um lançamento pendente como pago, sugerindo valor da cobrança, data atual e usuário atual como pagador, permitindo correção antes de confirmar.
- **RF-DES-03:** permitir valor pago diferente da cobrança. A ação sempre representa quitação integral: valor menor pode ser desconto, nunca saldo parcial implícito. Preservar ambos os valores e permitir observação.
- **RF-DES-04:** se a cobrança ainda for estimada, confirmar seu valor na quitação. Juros ou desconto na quitação não alteram automaticamente o valor base da recorrência.
- **RF-DES-05:** registrar automaticamente o autor da quitação; permitir indicar o outro membro como pagador.
- **RF-DES-06:** permitir selecionar lançamentos para quitação em grupo com data e pagador comuns. Usar o valor confirmado de cada cobrança; estimativas devem ser confirmadas e diferenças ajustadas individualmente antes dessa operação.
- **RF-DES-07:** desfazer quitação exige motivo, preserva os dados anteriores no histórico, retorna o lançamento a pendente e recalcula atraso e totais. Reativa lembretes apenas no próximo horário elegível. Mantém anexos.
- **RF-DES-08:** cancelar pendente exige motivo e registra autor e data. Para cancelar pago, primeiro desfazer a quitação. Não há exclusão definitiva de lançamentos pela interface no MVP.
- **RF-DES-09:** cancelamento exclui de totais ativos e alertas; preserva histórico/anexos e impede recriação automática da mesma ocorrência. Cancelar uma ocorrência não encerra a recorrência.
- **RF-DES-10:** impedir duplicação por repetição da mesma solicitação, inclusive após perda da resposta ou duplo clique.
- **RF-DES-11:** em edição simultânea, preservar a primeira alteração salva. A segunda deve receber conflito e revisar os dados atuais antes de tentar novamente; nunca sobrescrever silenciosamente.
- **RF-DES-12:** duas tentativas concorrentes de quitação geram uma única quitação. A segunda informa que já foi paga, sem substituir pagador, data ou valor. Edição posterior a cancelamento deve ser impedida.

Reembolso real não é reversão de erro de quitação e permanece fora do MVP. Alterações em meses fechados mantêm versões antigas e sinalizam mudanças posteriores.

### 6.1 Cartão de crédito

Compras no cartão são lançadas individualmente, com vencimento correspondente ao pagamento na fatura. Parcelas aparecem em seus respectivos meses. É possível quitar os lançamentos selecionados em grupo. A fatura completa não deve ser registrada como outra despesa quando suas compras já estiverem cadastradas: isso duplicaria os totais. O sistema não gerencia cartões nem calcula automaticamente faturas.

## 7. Recorrências e previsões

### 7.1 Cadastro e calendário

- **RF-REC-01:** cadastrar descrição, valor fixo ou estimativa inicial, frequência, primeiro vencimento, categoria opcional e responsável opcional.
- **RF-REC-02:** suportar frequências mensal, bimestral, trimestral, semestral e anual, calculadas a partir do primeiro vencimento. Não criar períodos anteriores automaticamente.
- **RF-REC-03:** quando o dia original não existir, usar o último dia do mês, preservando o dia de referência para os seguintes. Exemplo: dia 31 resulta em 28/29 em fevereiro e volta a 31 em março.
- **RF-REC-04:** finais de semana e feriados não deslocam vencimento automaticamente. Permitir ajuste manual da ocorrência.
- **RF-REC-05:** lançamentos herdam categoria e responsável da recorrência, sem retirar a possibilidade de ajuste conforme o alcance escolhido.

### 7.2 Geração e horizonte

- **RF-REC-06:** gerar lançamentos com vencimento no mês no início desse mês. Para recorrência cadastrada durante o mês, disponibilizar a ocorrência aplicável após confirmação do cadastro.
- **RF-REC-07:** antecipar a geração quando necessária para o primeiro lembrete, confirmação de valor ou pagamento antecipado. Conta que vence em 03/10 precisa estar materializada até 28/09, quando inicia a janela de cinco dias.
- **RF-REC-08:** mostrar previsões dos próximos 12 meses além do mês atual. Quando houver lançamento real, ele substitui a previsão da mesma ocorrência; não somar os dois.
- **RF-REC-09:** cada ocorrência possui identidade estável independente de mudanças de vencimento, evitando que uma edição crie outra despesa. Geração repetida deve ser segura e respeitar ocorrências canceladas.

### 7.3 Valores variáveis

- **RF-REC-10:** primeira estimativa informada pelo usuário. Próximos valores usam o último valor de cobrança confirmado aplicável; na ausência dele, a estimativa inicial.
- **RF-REC-11:** identificar estimativas como “a confirmar” em lançamentos, previsões, totais e mensagens.
- **RF-REC-12:** novo valor confirmado atualiza apenas estimativas futuras. Preservar períodos anteriores ainda estimados e valores futuros já confirmados manualmente.
- **RF-REC-13:** escolher a referência por ordem de vencimento, não pela data de edição. Para cada estimativa, considerar confirmação anterior aplicável na sequência da recorrência; não propagar uma confirmação de período posterior para trás.

Exemplo: setembro confirmado em R$ 195 atualiza as próximas estimativas que usavam R$ 180. Corrigir janeiro posteriormente não substitui setembro como referência. O valor efetivo pago, acrescido de juros, não vira automaticamente estimativa da próxima cobrança.

### 7.4 Alteração e encerramento

- **RF-REC-14:** oferecer “somente este lançamento” e “este e os próximos”. A segunda opção altera a configuração a partir do período escolhido; mostrar o alcance antes de confirmar.
- **RF-REC-15:** alterações em grupo preservam lançamentos pagos e valores variáveis já confirmados. Correções desses registros são individuais. Não modificar o histórico anterior por um reajuste futuro.
- **RF-REC-16:** encerrar mediante escolha do último período de cobrança. Preservar histórico e pendências até esse período; remover previsões posteriores e impedir novas gerações além do término.
- **RF-REC-17:** lançamentos posteriores ao término que já estejam pagos ou com valor confirmado permanecem e são sinalizados para revisão individual. Ocorrências apenas estimadas posteriores devem sair da programação ativa, preservando o registro das alterações quando já materializadas.

Encerrar uma recorrência não quita pendências e não interrompe os lembretes dos lançamentos que continuam devidos.

## 8. Parcelamentos

- **RF-PAR-01:** cadastrar descrição, valor total final, quantidade de parcelas e primeiro vencimento, além de categoria e responsável opcionais.
- **RF-PAR-02:** gerar parcelas mensais identificadas por posição e total, como 1/10. Cada parcela tem vencimento, quitação e alertas próprios. Aplicar a regra de meses curtos.
- **RF-PAR-03:** dividir o total com precisão de centavos; ajustar a diferença na última parcela. A soma deve ser exatamente o valor total informado. Não calcular juros de financiamento.
- **RF-PAR-04:** considerar apenas parcelas nos totais; o cabeçalho da compra não representa despesa adicional. O parcelamento termina na última parcela.
- **RF-PAR-05:** permitir alterar descrição, categoria ou responsável somente na parcela ou nela e nas próximas pendentes. Permitir ajustar vencimento individual ou recalcular os próximos pendentes.
- **RF-PAR-06:** mostrar parcelas afetadas antes de confirmar ações em grupo; preservar pagas.
- **RF-PAR-07:** para corrigir valor total ou quantidade, cancelar parcelas pendentes e cadastrar novo parcelamento com o valor restante desejado. Não recalcular pagamentos históricos.
- **RF-PAR-08:** cancelamento da compra cancela as parcelas pendentes selecionadas, com motivo e histórico, sem desfazer pagamentos ou criar reembolsos automaticamente.

Parcelas além do horizonte de previsão continuam pertencendo à compra e serão visíveis ao consultar seus períodos; o horizonte de 12 meses não limita a duração do parcelamento. Os limites de quantidade e valor devem ser definidos na especificação.

## 9. Categorias, anexos e consulta

### 9.1 Categorias

- **RF-ORG-01:** disponibilizar Moradia, Alimentação, Transporte, Saúde, Educação, Lazer e Outros como categorias iniciais.
- **RF-ORG-02:** ambos os membros podem criar, renomear e arquivar categorias. Não há subcategorias no MVP.
- **RF-ORG-03:** preenchimento é opcional; ausência aparece como “Sem categoria”.
- **RF-ORG-04:** arquivamento impede novas seleções e preserva registros anteriores. Se usada em recorrência ativa, solicitar substituição ou “Sem categoria” para próximas gerações.

### 9.2 Anexos

- **RF-ANX-01:** permitir PDF, JPG e PNG, até cinco arquivos por lançamento, cada um de até 10 MB. Anexos são opcionais em qualquer situação.
- **RF-ANX-02:** ambos os membros podem adicionar, visualizar, baixar e remover anexos; registrar autor e momento da remoção.
- **RF-ANX-03:** anexar não quita; desfazer quitação não remove; cancelar lançamento preserva anexos.
- **RF-ANX-04:** acesso autenticado restrito aos membros autorizados; nenhum endereço público permanente que permita contornar a revogação de acesso.

### 9.3 Consulta

- **RF-CON-01:** abrir o mês atual por vencimento como visão principal.
- **RF-CON-02:** pesquisar descrição e filtrar período, categoria, responsável, pagador e situação. Atrasadas são subconjunto de pendentes, sem dupla contagem.
- **RF-CON-03:** ordenar por vencimento, valor ou descrição. Canceladas ficam ocultas por padrão.
- **RF-CON-04:** disponibilizar histórico de criação, alterações, quitação, reversão e cancelamento, com autor e data.
- **RF-CON-05:** totais e CSV respeitam os filtros. Mesmo se cancelados forem listados, não integram totais financeiros ativos.

## 10. Alertas e WhatsApp

### 10.1 Destinatários e configuração

- **RF-ALT-01:** WhatsApp exclusivamente para o administrador, independentemente de responsável ou pagador da conta. Convidado recebe avisos somente dentro da aplicação; ambos podem consultar avisos internos.
- **RF-ALT-02:** exigir ativação e consentimento explícito, com data/hora. Desativar interrompe novos envios e tentativas pendentes.
- **RF-ALT-03:** usar número remetente dedicado, a ser adquirido/configurado por Diego, e seu número pessoal como receptor inicial. Receptor é configurável, não fixo no código.
- **RF-ALT-04:** ativação operacional depende de configuração do provedor/remetente e envio de teste. Troca de administrador requer consentimento próprio do novo administrador.
- **RF-ALT-05:** horários padrão 09:00 e 18:00, ajustáveis pelo administrador, com segundo posterior ao primeiro. Usar fuso do espaço, inicialmente America/Sao_Paulo, para datas, horários e limites diários.

### 10.2 Frequência por lançamento pendente

| Distância do vencimento | Inclusão no resumo |
|---|---|
| Mais de cinco dias antes | Não incluir. |
| De cinco a dois dias antes | Primeiro horário do dia. |
| Um dia antes | Primeiro e segundo horários. |
| No vencimento | Primeiro e segundo horários. |
| Após vencimento | Primeiro horário, todos os dias enquanto pendente. |
| Pago ou cancelado | Não incluir. |

- **RF-ALT-06:** contas criadas dentro da janela entram no próximo horário elegível; não enviar avisos retroativos acumulados.
- **RF-ALT-07:** abrir, ler ou dispensar notificação não interrompe a programação. Quitação/cancelamento retiram a conta dos próximos resumos; reversão da quitação retoma no próximo horário aplicável.

### 10.3 Resumos agrupados

- **RF-ALT-08:** gerar no máximo dois resumos lógicos diários por espaço, um por horário, e nenhum quando não houver contas elegíveis. Tentativas técnicas do mesmo resumo não são novos resumos.
- **RF-ALT-09:** primeiro resumo contém atrasadas e vencimentos de hoje até cinco dias à frente; segundo contém apenas amanhã e hoje. A quantidade de contas não multiplica mensagens.
- **RF-ALT-10:** apresentar quantidade e total de todas as contas elegíveis, sinalizando estimativas; detalhar até cinco por descrição, valor e vencimento. Priorizar atrasadas e depois vencimentos mais próximos.
- **RF-ALT-11:** excedente aparece como “e mais X contas”, com link autenticado à lista completa. Não dividir em várias mensagens. O limite de cinco itens está sujeito à validação do formato do provedor, preservando o resumo único.
- **RF-ALT-12:** quitar exclusivamente dentro da aplicação, nunca automaticamente por interação no WhatsApp.

### 10.4 Revalidação, alterações e falhas

- **RF-ALT-13:** revalidar situação, vencimento, destinatário e consentimento imediatamente antes de cada tentativa. Retirar pagos/cancelados e não enviar resumo vazio.
- **RF-ALT-14:** mudança de vencimento invalida programação antiga e recalcula elegibilidade. Não gera mensagem imediata nem repete resumo já enviado naquele horário. Uma nova data fora da janela retira a conta; data vencida a inclui no próximo resumo diário.
- **RF-ALT-15:** identificar estavelmente cada resumo por espaço, data local, horário lógico e canal, evitando duplicação interna. Distinguir resumo, tentativa, aceite do provedor e confirmação de entrega.
- **RF-ALT-16:** registrar tentativas, resultado e motivo. Não tratar aceite como entrega confirmada. Em resposta incerta, consultar/reconciliar conforme capacidades do provedor antes de reenviar; não prometer entrega exatamente uma vez se o provedor não a garantir.
- **RF-ALT-17:** em falha temporária, tentar novamente com espaçamento progressivo dentro de até uma hora do horário programado. Encerrar antes se chegar o próximo resumo, houver desativação ou revogação do acesso. Não há configuração adicional de horas silenciosas.
- **RF-ALT-18:** vencida a janela, não acumular envio. No próximo horário, gerar resumo atual. Indicar falha ao administrador no aplicativo, mantendo o aviso interno disponível.
- **RF-ALT-19:** erro permanente do destinatário/canal suspende WhatsApp e orienta correção. Indisponibilidade geral do provedor não deve ser confundida com número inválido.
- **RF-ALT-20:** indisponibilidade de WhatsApp não reduz compartilhamento, despesas, fechamento ou demais funcionalidades. Email não é contingência de vencimentos.

A janela limita tentativas iniciadas pelo sistema, não o instante final de entrega pelo provedor. Uma mensagem já aceita externamente pode chegar depois da quitação; o sistema garante a revalidação antes do envio, não o recolhimento de mensagens aceitas. A especificação deve tratar esse limite e as respostas incertas.

## 11. Dashboard, fechamento e exportação

### 11.1 Visões e cálculo

- **RF-REL-01:** visão principal agrupa lançamentos por mês de vencimento; para pago sem vencimento, usa a referência de pagamento definida no cadastro.
- **RF-REL-02:** visão de pagamentos agrupa quitações ativas pela data efetiva. Conta vencida em setembro e paga em outubro pertence a setembro na primeira visão e aparece em outubro na segunda.
- **RF-REL-03:** distinguir pendências anteriores do mês selecionado, sem transferi-las nem somá-las duas vezes.
- **RF-REL-04:** previsões incluem somente ocorrências sem lançamento equivalente. Exibir separadamente a parcela estimada dos totais futuros.

Para um conjunto de lançamentos ativos selecionados por vencimento:

| Indicador | Cálculo |
|---|---|
| Total previsto | Soma dos valores das cobranças, confirmados e estimados, das contas selecionadas. |
| Total pago | Soma dos valores efetivamente pagos das contas selecionadas com quitação ativa. |
| Total pendente | Soma dos valores das contas selecionadas ainda pendentes, incluindo estimativas sinalizadas. |
| Ajustes de quitação | Soma de valor pago menos valor da cobrança, somente das quitadas; distinguir acréscimos e descontos. |

O pendente nunca é calculado simplesmente por previsto menos pago. Exemplo: cobrança de R$ 150 quitada por R$ 155 tem pendente R$ 0 e acréscimo R$ 5. Todos os indicadores devem explicitar sua base temporal; a visão por pagamento não deve ser apresentada como se fosse a mesma população da visão por vencimento. Cancelados e quitações desfeitas não compõem valores ativos. Não exibir saldo disponível ou resultado financeiro.

### 11.2 Fechamento mensal

- **RF-FEC-01:** ambos podem fechar simbolicamente um mês, salvando resumo por vencimento com totais, categorias, estimativas e pendências, autor e instante.
- **RF-FEC-02:** permitir fechamento com pendências, mediante aviso. Não quitar, ocultar pendências nem suspender lembretes.
- **RF-FEC-03:** permitir edições posteriores sem bloqueio; recalcular dados atuais e indicar que diferem do retrato salvo.
- **RF-FEC-04:** ambos podem gerar nova versão do fechamento. Preservar versões anteriores e permitir consulta do retrato de cada uma.
- **RF-FEC-05:** qualquer alteração que afete os valores ou classificações do resumo deve respeitar sua preservação, inclusive quitação posterior, reversão, cancelamento e mudança de categoria.

### 11.3 CSV

- **RF-CSV-01:** ambos exportam lançamentos por vencimento ou pagamento e pelos filtros da consulta.
- **RF-CSV-02:** incluir descrição, categoria, vencimento, valor da cobrança, indicação de estimativa, situação, valor pago, data de pagamento, responsável e pagador; origem/parcela deve permitir identificar a despesa quando aplicável.
- **RF-CSV-03:** cancelados não entram por padrão; inclusão explícita os identifica sem incorporá-los aos totais ativos.
- **RF-CSV-04:** previsões são exportadas separadamente e identificadas; não misturar como despesas já materializadas.
- **RF-CSV-05:** não incluir arquivos anexos. Formatar para uso no Excel em português, preservando acentos, datas e valores. Especificação técnica define codificação, separador e proteção contra interpretação de texto como fórmula.

## 12. Web responsiva, PWA e conectividade

- **RF-WEB-01:** disponibilizar telas adaptadas a computador e celular, com “Adicionar despesa” acessível na tela principal.
- **RF-WEB-02:** PWA instalável nos dispositivos/navegadores suportados, abrindo pela tela inicial. Não implica aplicativo nativo, push ou edição offline.
- **RF-WEB-03:** permitir captura de foto quando suportada e seleção de imagem como alternativa. Se a câmera não estiver disponível ou autorizada, manter seleção de arquivo e cadastro manual.
- **RF-WEB-04:** consulta e alteração de dados exigem internet. Em desconexão, informar a indisponibilidade; não apresentar dados antigos como atuais.
- **RF-WEB-05:** preservar campos preenchidos enquanto a tela permanecer aberta após falha de conexão e só mostrar sucesso após confirmação do servidor.
- **RF-WEB-06:** repetir solicitação após falha não duplica a operação. Reabertura/fechamento da tela não oferece garantia de rascunho persistente offline.
- **RF-WEB-07:** lembretes e backups executam no servidor mesmo com aplicação fechada ou telefone desligado.

## 13. Cadastro assistido por IA

### 13.1 Fluxo e alcance

- **RF-IA-01:** ambos os membros podem usar “Adicionar por foto”, capturando ou selecionando uma imagem. Interpretar uma despesa ou conta por imagem.
- **RF-IA-02:** análise ocorre apenas ao solicitar “Analisar imagem”. Anexar normalmente, editar campos ou salvar não aciona análise automaticamente.
- **RF-IA-03:** sugerir descrição, valor, categoria existente e, quando identificáveis, vencimento e possibilidade de recorrência. Campos incertos ou ausentes devem ser destacados para revisão, nunca inventados.
- **RF-IA-04:** sempre exigir revisão e confirmação humana antes de criar, atualizar ou quitar. Não criar categorias automaticamente.
- **RF-IA-05:** procurar possíveis lançamentos/recorrências correspondentes e sugerir vínculo; sem fusão ou criação automática baseada apenas em similaridade.

Após revisão, oferecer:

| Escolha | Resultado |
|---|---|
| Atualizar lançamento existente | Usuário seleciona o lançamento e confirma as alterações de valor/vencimento. Aplicar regras normais de edição e concorrência. |
| Criar despesa avulsa | Usuário confirma dados e situação. |
| Criar nova recorrência | Usuário confirma frequência, primeiro vencimento e demais dados; visualiza a previsão antes de salvar. |

- **RF-IA-06:** etiqueta representa uma oferta/preço, não comprova compra nem pagamento. Usuário confirma se comprou, quantidade e valor final aplicáveis.
- **RF-IA-07:** serviço possivelmente recorrente exige confirmação de periodicidade. Fatura não prova pagamento. Comprovante pode sugerir quitação de lançamento ou nova despesa paga, mas exige confirmação de data, valor e pagador.
- **RF-IA-08:** manter alternativa manual para falha, imagem ilegível, resposta ambígua ou cota atingida. Processamento depende de internet.
- **RF-IA-09:** repetição da confirmação por falha de conexão deve produzir uma única alteração financeira.

### 13.2 Cota e controle

- **RF-IA-10:** limite inicial de 20 análises por dia, compartilhado pelo espaço, ajustável pelo administrador; virada diária conforme fuso do espaço.
- **RF-IA-11:** nova solicitação explícita conta como novo uso. Edição manual não conta nem reanalisa.
- **RF-IA-12:** administrador pode consultar quantidade de análises. Limite de volume não é garantia de teto financeiro; custo e limite monetário dependem do provedor escolhido.
- **RF-IA-13:** aplicar controle de cota de forma consistente entre os dois usuários, inclusive solicitações simultâneas. Política de contabilização de falhas fica explicitada na seção 17.

### 13.3 Imagens e privacidade

- **RF-IA-14:** informar antes da análise que a imagem será enviada ao serviço de IA.
- **RF-IA-15:** “Guardar imagem como anexo” desmarcado por padrão. Se selecionado, aplicar limites e permissões de anexos.
- **RF-IA-16:** imagem não guardada, inclusive de cadastro abandonado, tem cópia temporária excluída do sistema em até 24 horas. Temporários não devem entrar no backup de anexos permanentes, evitando estender essa retenção.
- **RF-IA-17:** registrar solicitante e horário da análise, sem incluir a imagem em logs.
- **RF-IA-18:** escolher provedor considerando retenção e tratamento das imagens. Exclusão local não garante exclusão no provedor; comportamento externo deve ser documentado antes da ativação.

## 14. Requisitos não funcionais e operação

### 14.1 Segurança e integridade

- **RNF-SEG-01:** HTTPS para acesso externo, proteção adequada de senhas por hash e sessões seguras. Segredos de integrações permanecem no servidor.
- **RNF-SEG-02:** validar autorização em toda operação e acesso a anexos; remover um membro revoga acesso mesmo que conserve URLs ou sessão aberta.
- **RNF-SEG-03:** preservar trilha das ações financeiras relevantes; sem senhas, tokens ou imagens em logs.
- **RNF-SEG-04:** usar representação decimal exata para valores em BRL; impedir erros de ponto flutuante e preservar soma das parcelas.
- **RNF-SEG-05:** validar uploads no servidor quanto a tipo e tamanho. Conteúdo de imagem é entrada não confiável; saída da IA não executa ações nem contorna validações e permissões.
- **RNF-SEG-06:** impedir operações duplicadas e sobrescritas concorrentes conforme requisitos funcionais. Nenhuma alteração financeira depende somente de validação do navegador.

### 14.2 Desempenho e capacidade pessoal

| ID | Meta |
|---|---|
| RNF-PER-01 | Buscar carregamento das páginas principais em até três segundos no uso habitual, sem monitoramento contínuo de percentis. Documentar ambiente e condições de validação. |
| RNF-PER-02 | Validar busca, filtros e totais com até 10 mil lançamentos por espaço. Esse é um volume de teste, não um limite de exclusão de histórico. |
| RNF-PER-03 | Exportar até 10 mil lançamentos em até 60 segundos no ambiente de validação. |
| RNF-PER-04 | Iniciar processamento de lembretes até cinco minutos após o horário previsto, quando o serviço estiver disponível. Entrega final depende do provedor. |

Não há SLA comercial ou promessa de disponibilidade ininterrupta. Manutenções podem gerar indisponibilidade. Tempos de análise de IA dependem do provedor e precisam de timeout e feedback ao usuário definidos tecnicamente.

### 14.3 Hospedagem e recuperação

- **RNF-OPS-01:** desenvolvimento e testes locais; ambiente de uso em VPS da Hostinger.
- **RNF-OPS-02:** serviços devem reiniciar automaticamente após reinicialização da VPS. Jobs executam sem depender do computador ou do navegador de Diego.
- **RNF-BKP-01:** backup automático diário de dados e anexos permanentes, com cópias dos últimos sete dias, fora da VPS principal.
- **RNF-BKP-02:** validar restauração antes do uso real. Objetivo de perda máxima: até 24 horas de alterações, condicionado ao sucesso da rotina diária; falhas devem ser detectadas e tratadas.
- **RNF-BKP-03:** documentar restauração consistente de dados e arquivos. Não foi aprovado prazo máximo de recuperação; definir procedimento e expectativa na especificação operacional.
- **RNF-OBS-01:** registrar falhas de jobs, backups, integrações, tentativas de mensagens, duplicados suprimidos e uso de IA, sem exigir plataforma de observabilidade comercial.

### 14.4 Usabilidade e acessibilidade

- **RNF-UX-01:** português do Brasil, datas dd/mm/aaaa e valores com duas casas decimais em reais. Não permitir despesas negativas como substituto de receita/reembolso.
- **RNF-UX-02:** formulários essenciais utilizáveis por teclado, campos rotulados, foco visível e mensagens de erro compreensíveis. Situações não devem depender exclusivamente de cor.
- **RNF-UX-03:** estados de carregamento e falha devem ser visíveis, principalmente em salvar, quitar, enviar foto e analisar imagem.
- **RNF-UX-04:** validar instalação e fluxo por foto no celular de uso real do usuário. Não prometer instalação/câmera idênticas em todos os navegadores; manter acesso web e seleção de arquivo.

## 15. Critérios de aceite do MVP

Os cenários abaixo são obrigatórios para liberação para uso diário. “Obrigatório” significa executar e evidenciar, não apenas escrever testes. Usar testes automatizados onde houver risco de consistência e validação manual nos fluxos de dispositivo e integrações reais.

| ID | Cenário | Resultado esperado |
|---|---|---|
| CA-01 | Configuração inicial, login e convite | Cadastro público fechado após configuração; somente email convidado confirmado aceita; expiração e revogação funcionam. |
| CA-02 | Uso pelos dois perfis | Ambos manipulam despesas; convidado não altera configurações gerais nem recebe WhatsApp. |
| CA-03 | Saída/remoção e transferência | Acesso revogado, histórico mantido, responsabilidades liberadas e novo consentimento exigido. |
| CA-04 | Despesa paga e pendente | Datas corretas, atraso calculado, quitação registrada e avisos retirados. |
| CA-05 | Juros e desconto | Cobrança R$ 150 paga por R$ 155 resulta em pendente zero e ajuste +R$ 5; desconto também não deixa saldo. |
| CA-06 | Reversão e cancelamento | Motivo, autor e histórico preservados; totais e próximos alertas coerentes; cancelada não é recriada. |
| CA-07 | Concorrência e repetição | Edição conflitante é sinalizada; duplo clique/resposta perdida não duplica despesa ou quitação. |
| CA-08 | Recorrências e calendário | Cinco frequências funcionam; meses curtos preservam dia-base; não deslocar por fim de semana. |
| CA-09 | Previsão e materialização | Horizonte de 12 meses sem dupla contagem; conta do início do mês gera antes do primeiro lembrete. |
| CA-10 | Estimativas variáveis | Confirmação atualiza futuras estimadas, preserva confirmadas e anteriores; correção antiga não substitui referência mais recente. |
| CA-11 | Alteração/encerramento de recorrência | Alcance correto; histórico/pagas preservados; término retira previsões posteriores e sinaliza confirmadas. |
| CA-12 | Parcelamento | Soma das parcelas igual ao total; centavos na última; alterações/cancelamento preservam pagas. |
| CA-13 | Totais e datas | Conta vencida em setembro e paga em outubro aparece corretamente nas duas visões; anteriores não são duplicadas. |
| CA-14 | Categorias e anexos | Arquivar preserva histórico; limites e acesso aos arquivos respeitados; anexar não quita. |
| CA-15 | Busca e CSV | Filtros/totais coerentes, cancelados opcionais, previsões separadas e arquivo legível no Excel. |
| CA-16 | Fechamento e correção | Retrato salvo permanece; alteração posterior sinalizada; nova versão preserva anterior. |
| CA-17 | Calendário dos alertas | Dias -5 a -2 uma vez; -1 e 0 duas vezes; atrasadas uma vez; pagos/cancelados excluídos. |
| CA-18 | Agrupamento | Até dois resumos lógicos, cinco detalhes e excedente; nenhum envio vazio; convidado somente in-app. |
| CA-19 | Mudança de vencimento e quitação antes de enviar | Revalidação retira contas inelegíveis; sem mensagem extra imediata. |
| CA-20 | Falha e retomada de WhatsApp | Janela de até uma hora, sem acúmulo; distinguir aceite/entrega; falha visível e aplicativo funcional. |
| CA-21 | Integração real WhatsApp | Envio de teste ao número configurado e evidência de entrega quando fornecida pelo provedor. |
| CA-22 | PWA e câmera | Abrir pela tela inicial, capturar/selecionar imagem e revisar no celular de uso real. |
| CA-23 | Etiqueta legível | IA sugere descrição/preço/categoria, sem cadastro ou pagamento automático. |
| CA-24 | Imagem ilegível/ambígua | Campos incertos destacados, correção manual disponível, sem valores inventados. |
| CA-25 | Fatura recorrente existente | Usuário pode vincular ao lançamento; nenhuma recorrência duplicada automaticamente. |
| CA-26 | Fatura e comprovante | Fatura não quita; comprovante exige revisão e confirmação; confirmação repetida não duplica. |
| CA-27 | Cota/falha de IA | Compartilhamento das 20 análises, controle concorrente e cadastro manual disponível. |
| CA-28 | Retenção da imagem | Sem escolha de anexo, temporário apagado até 24h, inclusive abandono; imagem não consta de logs/backup permanente. |
| CA-29 | Desconexão | Sem falso sucesso; campos preservados com tela aberta; nova tentativa não duplica. |
| CA-30 | Capacidade | Validar 10 mil lançamentos, consulta habitual e CSV dentro das metas documentadas. |
| CA-31 | Backup e restauração | Restaurar dados e anexos de cópia externa antes do uso real e registrar resultado. |
| CA-32 | VPS e reinício | Aplicação HTTPS e jobs retomam após reinício; jobs não dependem de navegador aberto. |

A aplicação pode ser testada antes da ativação de integrações externas, mas a liberação completa com WhatsApp e IA exige validar seus respectivos critérios reais.

## 16. Sequência recomendada de implementação

Esta sequência organiza dependências; não reduz o escopo aprovado nem impõe arquitetura ou divisão em microserviços.

1. Identidade, espaço familiar, permissões e configuração inicial.
2. Despesas, quitação, reversão, cancelamento e histórico; precisão monetária e concorrência.
3. Categorias, responsáveis, anexos e consultas.
4. Recorrências, estimativas, previsões e parcelamentos.
5. Dashboard, CSV e fechamento versionado.
6. Motor de resumos, avisos internos e integração WhatsApp.
7. Experiência responsiva/PWA e cadastro assistido por imagem.
8. Validação integrada, implantação na VPS e restauração de backup.

Segurança, backup e testes de consistência devem ser considerados desde as primeiras etapas, não adicionados apenas ao final.

## 17. Pendências explícitas para especificação e fechamento

### 17.1 Escolhas técnicas ainda não definidas

| Tema | Definição necessária |
|---|---|
| Stack | Backend, frontend, banco, bibliotecas e arquitetura. Não presumir uma stack apenas pelo histórico profissional do usuário. |
| VPS | Plano/dimensionamento, sistema operacional, domínio, certificados, implantação e monitoramento básico. |
| WhatsApp | Provedor, cadastro do remetente, formato/templates, custos, confirmação de entrega, correlação, resposta incerta e limites externos. |
| Email | Serviço de envio de convite, confirmação e recuperação; validade dos tokens exceto convite, já fixado em sete dias. |
| IA | Provedor/modelo, custo, timeout, limite financeiro, formatos/tamanho de imagem e política de retenção externa. |
| Backups | Destino externo, proteção de acesso, agendamento, alerta de falha e procedimento de recuperação. |
| PWA | Navegadores/dispositivos suportados, instalação e estratégia de cache sem edição offline e sem exposição de dados privados. |
| Limites | Tamanho de campos, quantidade máxima de parcelas, faixa monetária e paginação; definir sem comprometer as metas de volume. |
| Operações em lote | Transação e apresentação de conflitos parciais em quitação/alterações coletivas; nenhuma atualização silenciosa ou duplicação. |

### 17.2 Regras residuais que não devem ser inventadas pelo desenvolvimento

- **Privacidade e encerramento:** o PRD original previa exportação de dados pessoais e solicitação de apagamento. O CSV financeiro não substitui automaticamente essa função. Definir fluxo para excluir conta, encerrar espaço sem sucessor, tratamento do histórico compartilhado, anexos e cópias de backup. A entrevista aprovou saída/remoção de membro, não todas essas regras. Resolver antes de liberar essas ações.
- **Valores zero:** despesas negativas estão fora do escopo; decidir se zero é permitido em alguma situação. Não usar zero silenciosamente para representar valor desconhecido.
- **Cota de IA em falha:** definir quais solicitações malsucedidas consomem cota e como evitar cobrança de uso duplicada por repetição técnica. A decisão aprovada conta novas análises explícitas, mas não detalhou erros do provedor.
- **Restauração de cancelamento:** não foi aprovada ação de reativar lançamento cancelado. Não implementar restauração automática nem recriar a ocorrência cancelada; eventual funcionalidade precisa de decisão própria.
- **Edição de recorrência:** telas devem tornar explícito quais campos confirmados/pagos são preservados e quais metadados podem mudar. Nenhuma atualização em grupo pode contornar a proteção de valores já confirmados aprovada.

Essas pendências não invalidam os fluxos aprovados. Devem ser resolvidas antes da história afetada, sem reabrir decisões já consolidadas nem introduzir funcionalidades novas implicitamente.

## 18. Mudanças principais em relação à versão 1.0

- Definido uso pessoal, sem cadastro público e sem métricas comerciais.
- Substituída a contradição de pagamentos por quitação simples no lançamento, sem FSM de obrigação independente nem pagamento parcial.
- Detalhadas recorrências, estimativas, previsão de 12 meses, encerramento e geração antecipada.
- Incluído parcelamento simples e controle individual de compras no cartão.
- Definidos dois membros com visibilidade integral, responsável/pagador/autor separados e regras de saída.
- Restringido WhatsApp ao administrador e adotados resumos agrupados, calendário de frequência, horários e contingência in-app.
- Retirados offline-first, horas silenciosas separadas e fallback que reduziria outras funcionalidades.
- Definidos totais por vencimento e pagamento, ajustes de quitação, fechamento versionado e CSV.
- Incluídos PWA e cadastro assistido por IA com revisão, cota e retenção temporária.
- Confirmados BRL, despesas apenas, VPS Hostinger, metas pessoais e backup externo diário.
- Criados critérios de aceite e identificadas pendências restantes sem atribuir a elas aprovação inexistente.

---

**Fim do PRD v2.0 — base para histórias de usuário e especificação técnica.**
