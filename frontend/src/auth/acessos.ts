import type { PerfilNome, Usuario } from '../api/tipos'

/**
 * O que cada perfil vê. É o espelho, na tela, das regras do servidor (SecurityConfig):
 * aqui só se decide o menu e as rotas — quem barra de verdade é a API.
 */
export type Area =
  | 'painel'
  | 'agenda'
  /** A coluna do próprio adesivador, dia a dia (quem tem coluna na agenda). */
  | 'minhaAgenda'
  /** Modo edição dos adesivadores da agenda: adicionar, remover, renomear, reordenar. */
  | 'colunasAgenda'
  | 'produtividade'
  | 'relatorio'
  | 'consulta'
  /** Pátio e Prateleira: só Comercial, Diretoria e Admin mandam a OS para o Financeiro. */
  | 'patioPrateleira'
  | 'criarOs'
  | 'movimentar'
  | 'usuarios'
  | 'administracao'

const AREAS: Record<PerfilNome, Area[]> = {
  ADMIN: ['painel', 'agenda', 'colunasAgenda', 'patioPrateleira', 'produtividade', 'relatorio', 'consulta', 'criarOs', 'usuarios', 'administracao'],
  DIRETORIA: ['painel', 'agenda', 'colunasAgenda', 'patioPrateleira', 'produtividade', 'relatorio', 'consulta', 'criarOs'],
  VENDEDOR: ['painel', 'agenda', 'patioPrateleira', 'consulta', 'criarOs'],
  // O Financeiro também é setor: recebe as OS e é o único que conclui o fluxo.
  FINANCEIRO: ['movimentar', 'painel', 'usuarios'],
  // Setores produtivos: só receber, devolver e despachar.
  OPERACIONAL: ['movimentar'],
}

/** Adesivador da Frota com coluna na agenda: trabalha só pela Minha agenda. */
const soAgenda = (u: Usuario) => u.perfil === 'OPERACIONAL' && u.setor === 'FROTA' && !!u.temAgenda

export const pode = (usuario: Usuario | null | undefined, area: Area) => {
  if (!usuario) return false
  if (area === 'minhaAgenda') return !!usuario.temAgenda
  // A Frota não vê "Meu setor" (todas as OS do setor): cada um vê só a própria agenda e
  // recebe, entrega no Pátio ou devolve as OS dos carros dele por lá.
  if (area === 'movimentar' && soAgenda(usuario)) return false
  return AREAS[usuario.perfil].includes(area)
}

/** Primeira tela depois do login. */
export function telaInicial(usuario: Usuario): string {
  if (soAgenda(usuario)) return '/minha-agenda'
  if (pode(usuario, 'movimentar')) return '/movimentar'
  if (usuario.perfil === 'ADMIN') return '/admin'
  return '/painel'
}

const ROTULO_PERFIL: Record<PerfilNome, string> = {
  ADMIN: 'Administrador',
  DIRETORIA: 'Diretoria',
  VENDEDOR: 'Comercial',
  FINANCEIRO: 'Financeiro',
  OPERACIONAL: 'Operacional',
}

export const rotuloPerfil = (perfil: PerfilNome) => ROTULO_PERFIL[perfil] ?? perfil
