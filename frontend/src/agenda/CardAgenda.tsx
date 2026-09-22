import { bloqueioDesde, diasAlcancados } from '../api/faixas'
import type { Agendamento, FaixaHoraria } from '../api/tipos'
import { formatarHoras, resumoMaterial, rotuloVendedor } from '../componentes/Ui'
import { formatarDia } from './regua'

interface Props {
  item: Agendamento
  faixas: FaixaHoraria[]
  /** Linha da grade onde o card começa e quantas ele cobre. */
  indiceLinha: number
  span: number
  /** Topo e altura reais das linhas, para a tarja do almoço cair na linha certa. */
  geometria: { topo: number; altura: number }[]
  /** Linha da semana que o card atravessa sem ocupar: almoco ou sexta depois das 17h. */
  pausaNaLinha: (linha: number) => boolean
  podeEditar: boolean
  selecionado: boolean
  /** Recortado com Ctrl+X, esperando o Ctrl+V. */
  recortado: boolean
  emArrasto: boolean
  /** Horas mostradas enquanto a borda está sendo puxada, ou nulo. */
  horasEmPrevia: number | null
  destacarSemOs: boolean
  arrastavel: boolean
  /** Começou numa semana anterior: a data em que começou, ou nulo. */
  comecouEm: string | null
  aoComecarArraste: (e: React.DragEvent) => void
  aoTerminarArraste: () => void
  aoClicar: () => void
  aoTocar: (e: React.TouchEvent) => void
  aoPuxarBorda: (e: React.PointerEvent<HTMLElement>) => void
  aoPuxarTopo: (e: React.PointerEvent<HTMLElement>) => void
}

/**
 * Um carro na grade: nome em destaque e, abaixo, quem vendeu e qual OS. É um bloco só do
 * início ao fim — o almoço aparece como uma tarja por dentro, sem partir o serviço.
 */
export default function CardAgenda({
  item,
  faixas,
  indiceLinha,
  span,
  geometria,
  pausaNaLinha,
  podeEditar,
  selecionado,
  recortado,
  emArrasto,
  horasEmPrevia,
  destacarSemOs,
  arrastavel,
  comecouEm,
  aoComecarArraste,
  aoTerminarArraste,
  aoClicar,
  aoTocar,
  aoPuxarBorda,
  aoPuxarTopo,
}: Props) {
  const bloqueio = item.tipo === 'INDISPONIVEL'
  const semOs = !item.material
  const emPrevia = horasEmPrevia !== null
  const diasExtras = diasAlcancados(faixas, item.slotInicio, item.horasEstimadas, bloqueioDesde(faixas, item.data))

  // Onde caem os almoços dentro deste bloco. Com a medida real das linhas; sem ela, a
  // proporção, que é aproximada mas nunca fica fora do card.
  const base = geometria[indiceLinha]
  const pausas: { topo: string; altura: string }[] = []
  for (let k = 0; k < span; k++) {
    if (!pausaNaLinha(indiceLinha + k)) continue
    const linha = geometria[indiceLinha + k]
    pausas.push(
      base && linha
        ? { topo: `${linha.topo - base.topo - 3}px`, altura: `${linha.altura}px` }
        : { topo: `${(k / span) * 100}%`, altura: `${(1 / span) * 100}%` },
    )
  }

  const classes = [
    'card-agenda',
    bloqueio ? 'card-indisponivel' : `ag-${item.status.toLowerCase()}`,
    destacarSemOs && semOs && !bloqueio && 'sem-os',
    podeEditar && 'arrastavel',
    emArrasto && 'em-arrasto',
    emPrevia && 'em-redimensionamento',
    selecionado && 'card-selecionado',
    recortado && 'card-recortado',
  ]
    .filter(Boolean)
    .join(' ')

  const dica = bloqueio
    ? `${item.descricao} — ${item.horarioInicio} às ${item.horarioFim}`
    : [
        item.descricao,
        `${item.horarioInicio} às ${item.horarioFim} (${formatarHoras(item.horasEstimadas)})`,
        item.vendedorCodigo ? `Vendedor: ${rotuloVendedor(item.vendedorCodigo)}` : 'Sem vendedor',
        item.material ? `OS ${item.material.numeroOsErp} — ${resumoMaterial(item.material)}` : 'Sem OS vinculada',
      ].join('\n')

  return (
    <div
      data-agendamento={item.id}
      draggable={arrastavel}
      className={classes}
      onDragStart={aoComecarArraste}
      onDragEnd={aoTerminarArraste}
      onClick={aoClicar}
      onTouchStart={aoTocar}
      title={dica}
    >
      {pausas.map((p, i) => (
        <span
          key={i}
          className="pausa-almoco"
          style={{ top: p.topo, height: p.altura }}
          title="Almoço ou sexta depois das 17h — não conta como hora trabalhada"
        />
      ))}

      {/* Borda de cima: muda o início, o fim fica. Some no pedaço que veio da semana
          anterior, porque o começo de verdade não está nesta tela. */}
      {podeEditar && !comecouEm && (
        <span
          className="punho-redimensionar punho-topo"
          title="Puxe até a linha onde o serviço deve começar — o fim continua no mesmo lugar"
          onPointerDown={aoPuxarTopo}
          onTouchStart={(e) => e.stopPropagation()}
          onDragStart={(e) => {
            e.preventDefault()
            e.stopPropagation()
          }}
          onClick={(e) => e.stopPropagation()}
        />
      )}

      {comecouEm && <div className="card-continua">↑ desde {formatarDia(comecouEm)}</div>}
      <div className="card-nome">{item.descricao}</div>
      <div className="card-rodape">
        {bloqueio ? (
          <span className="rodape-texto">indisponível · {formatarHoras(item.horasEstimadas)}</span>
        ) : emPrevia ? (
          <span className="rodape-texto destaque">{formatarHoras(horasEmPrevia)}</span>
        ) : (
          <span className="rodape-texto">
            {item.vendedorCodigo ? rotuloVendedor(item.vendedorCodigo) : '—'}
            {' · '}
            {item.material ? (
              <>
                <i className={`ponto-material ${item.material.pronto ? 'pronto' : 'producao'}`} />
                OS {item.material.numeroOsErp}
              </>
            ) : (
              'sem OS'
            )}
          </span>
        )}
        {!emPrevia && diasExtras > 0 && <span className="marcador">+{diasExtras}d</span>}
      </div>

      {/* A borda mede as horas a partir do inicio visivel: num servico que veio da semana
          anterior a conta sairia errada, entao ali a duracao se ajusta pela edicao. */}
      {podeEditar && !comecouEm && (
        <span
          className="punho-redimensionar"
          title="Puxe até a linha onde o serviço deve terminar — pode avançar para os dias seguintes"
          onPointerDown={aoPuxarBorda}
          onTouchStart={(e) => e.stopPropagation()} // puxar a borda não é arrastar o card
          onDragStart={(e) => {
            e.preventDefault()
            e.stopPropagation()
          }}
          onClick={(e) => e.stopPropagation()}
        />
      )}
    </div>
  )
}
