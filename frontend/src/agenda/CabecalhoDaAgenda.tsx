import { hojeIso, mesDe, nomeDoMes, outroMes } from '../api/periodos'
import type { Periodo } from '../api/periodos'

/** O que dá para editar na própria agenda, cada um na sua janela. */
export type EdicaoDaAgenda = 'adesivadores' | 'status' | 'vendedores'

interface Props {
  mes: Periodo
  aoTrocarMes: (mes: Periodo) => void
  /** O botão Hoje: volta ao mês atual e desce até o dia de hoje. */
  aoIrParaHoje: () => void
  /** Diretoria e administrador: editar adesivadores, status e vendedores. */
  podeEditarLegenda: boolean
  aoEditar: (qual: EdicaoDaAgenda) => void
  podeEditar: boolean
  aoDesfazer: () => void
}

/** Título, a ajuda dos atalhos, a troca de mês e os botões de edição da agenda. */
export default function CabecalhoDaAgenda({
  mes,
  aoTrocarMes,
  aoIrParaHoje,
  podeEditarLegenda,
  aoEditar,
  podeEditar,
  aoDesfazer,
}: Props) {
  return (
    <div className="cabecalho-pagina cabecalho-simples">
      <div>
        <h1>Agenda dos adesivadores</h1>
        <p>
          O mês inteiro, uma semana abaixo da outra · o dia em 5 espaços de trabalho, como na planilha ·
          dois cliques renomeiam · puxe o <strong>+</strong> do canto para replicar o serviço nos espaços de baixo ·
          com o carro selecionado, <strong>B</strong> programado, <strong>P</strong> pátio,{' '}
          <strong>E</strong> executando, <strong>C</strong> concluído, <strong>N</strong> não veio,{' '}
          <strong>X</strong> externo · clique e arraste para escolher várias células, como no Excel (Shift+clique e
          Ctrl+clique também) · segure na barra da esquerda do card (a mãozinha) para mover o card ou os escolhidos ·
          espaços vazios escolhidos + <strong>N</strong> marcam indisponível · o botão direito abre o menu
        </p>
      </div>
      <div className="acoes-linha">
        <button className="botao botao-secundario" onClick={() => aoTrocarMes(mesDe(outroMes(mes.inicio, -1)))}>
          ‹ Mês
        </button>
        <strong className="mes-atual">{nomeDoMes(mes.inicio)}</strong>
        <button className="botao botao-secundario" onClick={() => aoTrocarMes(mesDe(outroMes(mes.inicio, 1)))}>
          Mês ›
        </button>
        <button
          className="botao botao-secundario"
          onClick={() => {
            aoTrocarMes(mesDe(hojeIso()))
            aoIrParaHoje()
          }}
          title="Ir para o mês atual e descer até o dia de hoje"
        >
          Hoje
        </button>
        {podeEditarLegenda && (
          <button
            className="botao botao-secundario"
            onClick={() => aoEditar('adesivadores')}
            title="Incluir, renomear, reordenar ou remover adesivadores"
          >
            ✎ Adesivadores
          </button>
        )}
        {podeEditarLegenda && (
          <button
            className="botao botao-secundario"
            onClick={() => aoEditar('status')}
            title="Incluir, renomear, mudar a cor ou remover status"
          >
            ✎ Status
          </button>
        )}
        {podeEditarLegenda && (
          <button
            className="botao botao-secundario"
            onClick={() => aoEditar('vendedores')}
            title="Incluir, renomear ou remover vendedores"
          >
            ✎ Vendedores
          </button>
        )}
        {podeEditar && (
          <button className="botao botao-secundario" onClick={aoDesfazer} title="Desfazer a última alteração (Ctrl+Z)">
            ↶ Desfazer
          </button>
        )}
      </div>
    </div>
  )
}
