import type { PerfilNome, SecaoDoSistema, Usuario } from '../api/tipos'
import { setoresDe } from './setorAtivo'

/**
 * O que cada pessoa vê. É o espelho, na tela, das regras do servidor (SecurityConfig):
 * aqui só se decide o menu e as rotas — quem barra de verdade é a API.
 *
 * A maior parte vem das **seções** da pessoa: o perfil traz as dele prontas e o cadastro
 * acrescenta ou tira (Administração → Usuários). O servidor manda a lista pronta no login.
 */
export type Area =
  | 'painel'
  /** A agenda do dia de cada adesivador, para a TV. */
  | 'tvAdesivadores'
  /** As OS disponíveis para o Acabamento, para a TV. */
  | 'tvAcabamento'
  | 'agenda'
  /** Mexer na agenda: criar, mover, mudar status, vendedor, OS. */
  | 'agendaEditar'
  /** A coluna do próprio adesivador, dia a dia (quem tem coluna na agenda). */
  | 'minhaAgenda'
  /** Adesivadores, status e vendedores da agenda. */
  | 'colunasAgenda'
  | 'produtividade'
  /** Botão direito no card do painel: iniciar, pausar e concluir por um adesivador. */
  | 'painelAcoes'
  | 'relatorio'
  | 'consulta'
  /** Pátio e Prateleira: mandar a OS para o Financeiro. */
  | 'patioPrateleira'
  | 'criarOs'
  | 'movimentar'
  | 'usuarios'
  | 'administracao'

/** A seção que libera cada área. */
const SECAO_DA_AREA: Partial<Record<Area, SecaoDoSistema>> = {
  painel: 'PAINEL',
  tvAdesivadores: 'PAINEL_ADESIVADORES',
  tvAcabamento: 'PAINEL_ACABAMENTO',
  agenda: 'AGENDA_VER',
  agendaEditar: 'AGENDA_EDITAR',
  colunasAgenda: 'AGENDA_LEGENDA',
  patioPrateleira: 'PATIO_PRATELEIRA',
  consulta: 'CONSULTA',
  criarOs: 'CRIAR_OS',
  produtividade: 'PRODUTIVIDADE',
  relatorio: 'RELATORIO',
  painelAcoes: 'PAINEL_ACOES',
}

/** O que continua sendo do perfil, e não se libera por seção. */
const DO_PERFIL: Record<PerfilNome, Area[]> = {
  ADMIN: ['usuarios', 'administracao'],
  DIRETORIA: [],
  VENDEDOR: [],
  // O Financeiro também é setor: recebe as OS e é o único que conclui o fluxo.
  FINANCEIRO: ['movimentar', 'usuarios'],
  // Setores produtivos: receber, devolver e despachar.
  OPERACIONAL: ['movimentar'],
  PERSONALIZADO: [],
}

/** Adesivador da Frota com coluna na agenda: trabalha só pela Minha agenda. */
/** Adesivador só da Frota: trabalha pela Minha agenda. Com outro setor junto, tem Meu setor também. */
const soAgenda = (u: Usuario) =>
  u.perfil === 'OPERACIONAL' && !!u.temAgenda && setoresDe(u).every((s) => s.nome === 'FROTA')

export const pode = (usuario: Usuario | null | undefined, area: Area) => {
  if (!usuario) return false
  if (area === 'minhaAgenda') return !!usuario.temAgenda
  // A Frota não vê "Meu setor" (todas as OS do setor): cada um vê só a própria agenda e
  // inicia e conclui os projetos dele por lá — a OS acompanha sozinha.
  if (area === 'movimentar' && soAgenda(usuario)) return false
  const secao = SECAO_DA_AREA[area]
  if (secao) return (usuario.secoes ?? []).includes(secao)
  return DO_PERFIL[usuario.perfil]?.includes(area) ?? false
}

/** Primeira tela depois do login: a primeira que a pessoa pode ver, nesta ordem. */
export function telaInicial(usuario: Usuario): string {
  if (soAgenda(usuario)) return '/minha-agenda'
  if (pode(usuario, 'movimentar')) return '/movimentar'
  if (usuario.perfil === 'ADMIN') return '/admin'
  const telas: [Area, string][] = [
    ['painel', '/painel'],
    ['tvAdesivadores', '/tv/adesivadores'],
    ['tvAcabamento', '/tv/acabamento'],
    ['agenda', '/agenda'],
    ['consulta', '/consulta'],
    ['patioPrateleira', '/patio-prateleira'],
    ['relatorio', '/relatorio-semanal'],
    ['produtividade', '/produtividade'],
    ['criarOs', '/ordens/nova'],
    ['minhaAgenda', '/minha-agenda'],
    ['usuarios', '/admin'],
  ]
  return telas.find(([area]) => pode(usuario, area))?.[1] ?? '/sem-acesso'
}

const ROTULO_PERFIL: Record<PerfilNome, string> = {
  ADMIN: 'Administrador',
  DIRETORIA: 'Diretoria',
  VENDEDOR: 'Comercial',
  FINANCEIRO: 'Financeiro',
  OPERACIONAL: 'Operacional',
  PERSONALIZADO: 'Personalizado',
}

export const rotuloPerfil = (perfil: PerfilNome) => ROTULO_PERFIL[perfil] ?? perfil

/** As seções, na ordem do cadastro, com o nome que a tela mostra. */
export const SECOES: { secao: SecaoDoSistema; rotulo: string; detalhe: string }[] = [
  { secao: 'PAINEL', rotulo: 'Painel', detalhe: 'o painel operacional completo' },
  { secao: 'PAINEL_ADESIVADORES', rotulo: 'TV · Adesivadores', detalhe: 'a agenda do dia de cada adesivador, em tela cheia' },
  { secao: 'PAINEL_ACABAMENTO', rotulo: 'TV · Acabamento', detalhe: 'as OS disponíveis para o Acabamento, em tela cheia' },
  { secao: 'AGENDA_VER', rotulo: 'Agenda (ver)', detalhe: 'ver a agenda dos adesivadores' },
  { secao: 'AGENDA_EDITAR', rotulo: 'Agenda (mexer)', detalhe: 'criar, mover, mudar status, vendedor e OS dos cards' },
  { secao: 'AGENDA_LEGENDA', rotulo: 'Agenda: adesivadores, status e vendedores', detalhe: 'editar as colunas e a legenda' },
  { secao: 'PATIO_PRATELEIRA', rotulo: 'Pátio e prateleira', detalhe: 'mandar a OS para o Financeiro' },
  { secao: 'CONSULTA', rotulo: 'Consultar OS', detalhe: 'ver as OS e os fluxos de todos os setores' },
  { secao: 'CRIAR_OS', rotulo: 'OS manual', detalhe: 'abrir OS, acrescentar fluxo e cancelar' },
  { secao: 'PRODUTIVIDADE', rotulo: 'Produtividade', detalhe: 'os gráficos de produtividade' },
  { secao: 'RELATORIO', rotulo: 'Relatório', detalhe: 'o relatório dos adesivadores e o lançamento do score' },
  {
    secao: 'PAINEL_ACOES',
    rotulo: 'Agir pelo painel',
    detalhe: 'botão direito no card do painel: iniciar, pausar e concluir no lugar do adesivador',
  },
]
