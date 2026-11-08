/** H09.1: sections reachable from the responsive menu, in the order a member uses them on the phone. */
export interface NavigationItem {
  readonly path: string;
  readonly label: string;
  readonly hint: string;
}

export const NAVIGATION: readonly NavigationItem[] = [
  { path: '/painel', label: 'Painel', hint: 'Vencimentos do mês' },
  { path: '/despesas', label: 'Despesas', hint: 'Cadastrar, quitar e consultar' },
  { path: '/pagamentos', label: 'Pagamentos', hint: 'Quitações do mês' },
  { path: '/planejamento', label: 'Planejamento', hint: 'Próximos 12 meses' },
  { path: '/fechamento', label: 'Fechamento', hint: 'Retrato do mês' },
  { path: '/recorrencias', label: 'Recorrências', hint: 'Contas que se repetem' },
  { path: '/compras-parceladas', label: 'Compras parceladas', hint: 'Parcelas e progresso' },
  { path: '/categorias', label: 'Categorias', hint: 'Organizar despesas' },
  { path: '/avisos', label: 'Avisos', hint: 'Notificações do aplicativo' },
  { path: '/lembretes', label: 'Lembretes', hint: 'Horários e WhatsApp' },
  { path: '/membros', label: 'Membros', hint: 'Convite e papéis' },
  { path: '/entrar', label: 'Minha sessão', hint: 'Conta e sair' },
];

/**
 * Screens of the sign-in, confirmation, recovery, invitation and first-setup flows. They never show the menu,
 * even with a session, because they are reached from emails and links outside the application.
 */
export const PUBLIC_FLOW_PATHS: readonly string[] = [
  '/confirmar-email', '/recuperar-acesso', '/redefinir-senha', '/aceitar-convite', '/configuracao-inicial',
];

export function isPublicFlow(url: string): boolean {
  const path = url.split(/[?#]/)[0];
  return PUBLIC_FLOW_PATHS.some(publicPath => path === publicPath || path.startsWith(`${publicPath}/`));
}
