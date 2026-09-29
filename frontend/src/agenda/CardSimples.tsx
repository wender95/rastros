import type { CSSProperties, DragEvent, MouseEvent, PointerEvent, TouchEvent } from 'react'
import type { Agendamento } from '../api/tipos'
import { classeStatus, resumoMaterial, rotuloStatusAgenda, rotuloVendedor } from '../componentes/Ui'
import { BLOCOS_POR_DIA } from './blocos'
import { formatarDia } from './regua'

/** Como o card aparece agora: escolhido, recortado, sendo arrastado… */
export interface EstadoDoCard {
  selecionado: boolean
  recortado: boolean
  /** Selecionado um card com cópias, as cópias acendem junto. */
  copiaRealcada: boolean
  emArrasto: boolean
  emPrevia: boolean
  arrastavel: boolean
}

interface Props {
  item: Agendamento
  /** Quantas linhas da grade o card cobre. */
  span: number
  indiceLinha: number
  semanaInicio: string
  /** Topo de cada linha da semana, medido na tela (veja useToposDasLinhas). */
  topos: number[] | undefined
  estado: EstadoDoCard
  podeEditar: boolean
  /** O nome sendo digitado no próprio card; nulo quando não está renomeando. */
  textoEditado: string | null
  aoMudarTexto: (texto: string) => void
  aoSalvarTexto: () => void
  aoCancelarTexto: () => void
  aoIniciarArrasto: (e: DragEvent<HTMLDivElement>) => void
  aoTerminarArrasto: () => void
  aoClicar: (e: MouseEvent<HTMLDivElement>) => void
  aoTocar: (e: TouchEvent<HTMLDivElement>) => void
  aoAbrirMenu: (e: MouseEvent<HTMLDivElement>) => void
  aoPuxarCanto: (e: PointerEvent<HTMLSpanElement>) => void
}

/** Um carro na agenda: só o nome no card; o resto aparece ao passar o mouse. */
export default function CardSimples({
  item,
  span,
  indiceLinha,
  semanaInicio,
  topos,
  estado,
  podeEditar,
  textoEditado,
  aoMudarTexto,
  aoSalvarTexto,
  aoCancelarTexto,
  aoIniciarArrasto,
  aoTerminarArrasto,
  aoClicar,
  aoTocar,
  aoAbrirMenu,
  aoPuxarCanto,
}: Props) {
  const editandoEste = textoEditado !== null
  const bloqueio = item.tipo === 'INDISPONIVEL'
  const veioDeAntes = item.data < semanaInicio
  /** O código do vendedor vai na barra colorida da esquerda (bloqueio não tem vendedor). */
  const vendedor = bloqueio ? null : item.vendedorCodigo

  return (
    <div
      data-agendamento={item.id}
      // A barra da esquerda alarga conforme o código do vendedor (1 letra, 2 letras...).
      style={vendedor ? ({ '--sigla': vendedor.length } as CSSProperties) : undefined}
      className={[
        'card-simples',
        bloqueio ? 'card-indisponivel' : classeStatus(item.status, item.etiquetaId),
        estado.selecionado && 'selecionado',
        estado.recortado && 'recortado',
        estado.copiaRealcada && 'copia-realcada',
        estado.emArrasto && 'em-arrasto',
        estado.emPrevia && 'em-previa',
        span >= 2 && 'card-alto',
        vendedor && 'com-vendedor',
      ]
        .filter(Boolean)
        .join(' ')}
      // Nesta agenda não se fala em relógio: o dia tem 5 espaços de trabalho.
      title={[
        item.descricao,
        `${rotuloStatusAgenda(item.status, item.etiquetaId)} · ${span} de ${BLOCOS_POR_DIA} espaços do dia${
          veioDeAntes ? ` (começou em ${formatarDia(item.data)})` : ''
        }`,
        item.vendedorCodigo ? `Vendedor: ${rotuloVendedor(item.vendedorCodigo)}` : 'Sem vendedor',
        item.atribuidos?.length ? `Noturno: ${item.atribuidos.map((a) => a.nome).join(', ')}` : '',
        item.material
          ? [`OS ${item.material.numeroOsErp}`, item.material.cliente, item.material.servico, resumoMaterial(item.material)]
              .filter(Boolean)
              .join(' · ')
          : 'Sem OS vinculada',
        (item.partes ?? 1) > 1 ? `Mesmo serviço em ${item.partes} lugares (cópias): editar um muda todos.` : '',
      ]
        .filter(Boolean)
        .join('\n')}
      // O arrasto nasce na alça (a barra da esquerda), mas a imagem que acompanha o mouse
      // é o card inteiro.
      onDragStart={(e) => {
        e.dataTransfer.setDragImage(e.currentTarget, 12, 10)
        aoIniciarArrasto(e)
      }}
      onDragEnd={aoTerminarArrasto}
      // Shift não pode virar seleção de texto na página.
      onMouseDown={(e) => {
        if (e.shiftKey) e.preventDefault()
      }}
      onClick={aoClicar}
      onTouchStart={aoTocar}
      onContextMenu={aoAbrirMenu}
    >
      {/* A divisa dos dias, desenhada por dentro do card: mesmo atravessando
          a virada do dia, a linha da grade continua aparecendo. */}
      {Array.from({ length: span - 1 }, (_, i) => i)
        .filter((i) => (indiceLinha + i) % BLOCOS_POR_DIA === BLOCOS_POR_DIA - 1)
        .map((i) => {
          // 3px da própria linha e 2px do recuo e da borda do card.
          const topo =
            topos && topos[indiceLinha + i + 1] !== undefined
              ? `${topos[indiceLinha + i + 1] - topos[indiceLinha] - 5}px`
              : `calc(var(--altura-linha) * ${i + 1} - 5px)`
          return <span key={i} className="linha-dia" aria-hidden="true" style={{ top: topo }} />
        })}

      {vendedor && (
        <span className="vendedor-na-barra" aria-hidden="true">
          {vendedor}
        </span>
      )}

      {/* A barra da esquerda é a alça de mover (mãozinha); o resto do card escolhe células. */}
      {estado.arrastavel && (
        <span
          className="alca-arrastar"
          draggable
          title="Segure aqui para mover (com vários selecionados, move todos)"
          aria-hidden="true"
        />
      )}

      {editandoEste ? (
        <input
          className="campo-celula"
          autoFocus
          value={textoEditado}
          onChange={(e) => aoMudarTexto(e.target.value)}
          onBlur={aoSalvarTexto}
          onKeyDown={(e) => {
            if (e.key === 'Enter') aoSalvarTexto()
            if (e.key === 'Escape') aoCancelarTexto()
          }}
          onClick={(e) => e.stopPropagation()}
        />
      ) : (
        <>
          <span className="nome">
            {veioDeAntes && <span className="veio-de-antes">↑ </span>}
            {item.descricao}
          </span>
          {/* Serviço de mais de um espaço: um trilho tracejado liga o começo ao fim e o
              nome se repete embaixo, para o meio da grade não parecer outro carro. */}
          {span >= 2 && (
            <>
              <span className="trilho" aria-hidden="true" />
              <span className="nome nome-fim">↳ {item.descricao}</span>
            </>
          )}
        </>
      )}

      {/* Alça do canto, como a da planilha: puxe para baixo e o serviço é replicado nos
          espaços de baixo; puxe de volta e ele encolhe. */}
      {podeEditar && !editandoEste && (
        <span
          className="punho-canto"
          title="Arraste para baixo para replicar o serviço nos espaços de baixo"
          onPointerDown={aoPuxarCanto}
          onClick={(e) => e.stopPropagation()}
          onDragStart={(e) => {
            e.preventDefault()
            e.stopPropagation()
          }}
        >
          +
        </span>
      )}
    </div>
  )
}
