import type { Agendamento, StatusAgendamento } from '../api/tipos'
import { classeStatus } from '../componentes/Ui'
import type { Vendedor } from '../componentes/Ui'
import type { ItemMenu } from './MenuContexto'
import type { OpcaoDeStatus } from './statusDoCard'
import type { AlvoSolte } from './useArrasteToque'

/** O que o menu do botão direito sabe da tela e o que ele pode pedir a ela. */
export interface ContextoDoMenu {
  podeEditar: boolean
  /** Os cards escolhidos juntos (Shift/Ctrl+clique); vazio ou com um só quando não há grupo. */
  grupo: number[]
  statusDoMenu: OpcaoDeStatus[]
  vendedores: Vendedor[]
  copiado: Agendamento | null
  recortado: boolean

  renomear: (item: Agendamento) => void
  abrir: (item: Agendamento) => void
  /** O card está na coluna Noturno. */
  ehNoturno: (item: Agendamento) => boolean
  /** Abre a escolha dos adesivadores do serviço noturno. */
  atribuir: (item: Agendamento) => void
  mudarStatus: (item: Agendamento, status: StatusAgendamento, etiquetaId: number | null) => void
  mudarStatusDoGrupo: (status: StatusAgendamento, etiquetaId: number | null) => void
  definirVendedor: (item: Agendamento, codigo: string | null) => void
  copiar: (item: Agendamento) => void
  recortar: (item: Agendamento) => void
  excluir: (item: Agendamento) => void
  excluirGrupo: () => void
  novoAqui: (celula: AlvoSolte) => void
  novoComDetalhes: (celula: AlvoSolte) => void
  colarEm: (celula: AlvoSolte) => void
  desfazer: () => void
}

const rotuloComTecla = (s: OpcaoDeStatus) => (s.tecla ? `${s.nome}   (${s.tecla.toUpperCase()})` : s.nome)

/** Botão direito num card do grupo: o menu vale para todos os escolhidos. */
function menuDoGrupo(ctx: ContextoDoMenu): ItemMenu[] {
  const { grupo, podeEditar } = ctx
  return [
    { rotulo: `${grupo.length} cards selecionados` },
    ...ctx.statusDoMenu.map((s, i) => ({
      rotulo: rotuloComTecla(s),
      cor: classeStatus(s.status, s.etiquetaId),
      separadorAntes: i === 0,
      aoEscolher: podeEditar ? () => ctx.mudarStatusDoGrupo(s.status, s.etiquetaId) : undefined,
    })),
    {
      rotulo: `🗑 Excluir os ${grupo.length} (Delete)`,
      separadorAntes: true,
      perigo: true,
      aoEscolher: podeEditar ? ctx.excluirGrupo : undefined,
    },
  ]
}

function menuDoCard(ctx: ContextoDoMenu, item: Agendamento): ItemMenu[] {
  const { podeEditar } = ctx
  const bloqueio = item.tipo === 'INDISPONIVEL'
  return [
    { rotulo: '✎ Renomear (ou dois cliques)', aoEscolher: () => ctx.renomear(item) },
    { rotulo: '⛭ Abrir: OS, vendedor, observação…', aoEscolher: () => ctx.abrir(item) },
    ...(!bloqueio && ctx.ehNoturno(item)
      ? [
          {
            rotulo: item.atribuidos?.length
              ? `🌙 Atribuídos: ${item.atribuidos.map((a) => a.nome).join(', ')}`
              : '🌙 Atribuir adesivadores…',
            aoEscolher: podeEditar ? () => ctx.atribuir(item) : undefined,
          },
        ]
      : []),
    ...(bloqueio
      ? []
      : ctx.statusDoMenu.map((s, i) => {
          const atual =
            s.etiquetaId != null ? item.etiquetaId === s.etiquetaId : !item.etiquetaId && item.status === s.status
          return {
            rotulo: rotuloComTecla(s),
            cor: classeStatus(s.status, s.etiquetaId),
            atual,
            separadorAntes: i === 0,
            aoEscolher: podeEditar && !atual ? () => ctx.mudarStatus(item, s.status, s.etiquetaId) : undefined,
          }
        })),
    ...(bloqueio
      ? []
      : [
          ...ctx.vendedores
            .filter((v) => v.ativo)
            .map((v, i) => ({
              rotulo: `👤 ${v.nome}`,
              atual: item.vendedorCodigo === v.codigo,
              separadorAntes: i === 0,
              aoEscolher:
                podeEditar && item.vendedorCodigo !== v.codigo ? () => ctx.definirVendedor(item, v.codigo) : undefined,
            })),
          {
            rotulo: '👤 Sem vendedor',
            atual: !item.vendedorCodigo,
            aoEscolher: podeEditar && item.vendedorCodigo ? () => ctx.definirVendedor(item, null) : undefined,
          },
        ]),
    {
      rotulo: 'Copiar para outro espaço (Ctrl+C)',
      separadorAntes: true,
      aoEscolher: podeEditar ? () => ctx.copiar(item) : undefined,
    },
    { rotulo: '✂ Recortar (Ctrl+X)', aoEscolher: podeEditar ? () => ctx.recortar(item) : undefined },
    {
      rotulo: '🗑 Excluir (Delete)',
      separadorAntes: true,
      perigo: true,
      aoEscolher: podeEditar ? () => ctx.excluir(item) : undefined,
    },
  ]
}

function menuDoEspacoLivre(ctx: ContextoDoMenu, celula: AlvoSolte): ItemMenu[] {
  const { podeEditar, copiado, recortado } = ctx
  return [
    { rotulo: '＋ Novo serviço aqui', aoEscolher: podeEditar ? () => ctx.novoAqui(celula) : undefined },
    {
      rotulo: '⛭ Novo serviço com OS e vendedor…',
      aoEscolher: podeEditar ? () => ctx.novoComDetalhes(celula) : undefined,
    },
    {
      rotulo: copiado
        ? recortado
          ? `📋 Mover "${copiado.descricao}" para cá`
          : `📋 Continuar "${copiado.descricao}" aqui`
        : '📋 Colar',
      separadorAntes: true,
      aoEscolher: podeEditar && copiado ? () => ctx.colarEm(celula) : undefined,
    },
    { rotulo: '↶ Desfazer (Ctrl+Z)', separadorAntes: true, aoEscolher: podeEditar ? ctx.desfazer : undefined },
  ]
}

/** Os itens do menu do botão direito: de um card, do grupo escolhido ou de um espaço livre. */
export function itensDoMenu(ctx: ContextoDoMenu, alvo: { item?: Agendamento; celula?: AlvoSolte }): ItemMenu[] {
  if (alvo.item) {
    return ctx.grupo.length > 1 && ctx.grupo.includes(alvo.item.id) ? menuDoGrupo(ctx) : menuDoCard(ctx, alvo.item)
  }
  return menuDoEspacoLivre(ctx, alvo.celula!)
}
