import { useCallback, useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { horaNoDia, textoDasPausas } from '../agenda/projetos'
import { formatarDia } from '../agenda/regua'
import { api } from '../api/client'
import { hojeIso, mesDe, nomeDoMes, semanaNoMes } from '../api/periodos'
import type { Periodo } from '../api/periodos'
import NavegacaoMes from '../componentes/NavegacaoMes'
import type { BuscaRelatorio, RelatorioSemanal as Relatorio, ServicoDoRelatorio } from '../api/tipos'
import { Aviso, Carregando, ChipStatusAgenda, Vazio, useLegenda } from '../componentes/Ui'

/** Texto do campo -> numero. Aceita vírgula, e vazio significa "sem score". */
function lerScore(texto: string): number | null | undefined {
  const limpo = texto.trim().replace(',', '.')
  if (!limpo) return null
  const n = Number(limpo)
  return Number.isFinite(n) && n >= 0 ? n : undefined
}

const umDecimal = (n: number) => n.toLocaleString('pt-BR', { maximumFractionDigits: 1 })

/**
 * O campo de score de um serviço: salva ao sair dele ou com Enter; Esc desiste. O que foi
 * digitado só existe entre o primeiro toque e o salvamento.
 */
function CampoScore({
  servico,
  aoSalvar,
  aoErrar,
}: {
  servico: ServicoDoRelatorio
  aoSalvar: (valor: number | null) => Promise<unknown>
  aoErrar: (mensagem: string | null) => void
}) {
  const [rascunho, setRascunho] = useState<string | null>(null)
  const [salvando, setSalvando] = useState(false)
  const desistindo = useRef(false)
  return (
    <input
      className="campo-score"
      inputMode="decimal"
      aria-label={`Score de ${servico.descricao}`}
      placeholder="—"
      disabled={salvando}
      value={rascunho ?? (servico.score != null ? umDecimal(servico.score) : '')}
      onChange={(e) => setRascunho(e.target.value)}
      onFocus={(e) => e.currentTarget.select()}
      onBlur={(e) => {
        // Esc desiste do que foi digitado; o blur que ele provoca não pode salvar.
        if (desistindo.current) {
          desistindo.current = false
          return
        }
        // Compara o que está no campo com o que está gravado — e não com o rascunho:
        // digitar e sair na mesma batida não pode perder o lançamento.
        const valor = lerScore(e.target.value)
        if (valor === undefined) {
          aoErrar('Score inválido: escreva um número, como 3 ou 3,5.')
          return
        }
        if (valor === servico.score) {
          setRascunho(null)
          return
        }
        aoErrar(null)
        setSalvando(true)
        aoSalvar(valor)
          .then(() => setRascunho(null))
          .catch((erro) => aoErrar(erro instanceof Error ? erro.message : 'Não foi possível salvar o score.'))
          .finally(() => setSalvando(false))
      }}
      onKeyDown={(e) => {
        if (e.key === 'Enter') e.currentTarget.blur()
        if (e.key === 'Escape') {
          desistindo.current = true
          setRascunho(null)
          e.currentTarget.blur()
        }
      }}
    />
  )
}

/**
 * As larguras das colunas, iguais em todas as tabelas de serviços: um adesivador embaixo do
 * outro, as colunas ficam alinhadas. Só "Carro / serviço" estica, e um nome longo quebra
 * linha em vez de empurrar as outras.
 */
function ColunasDoServico({ busca = false }: { busca?: boolean }) {
  return (
    <colgroup>
      <col className={busca ? 'rel-col-dia-ano' : 'rel-col-dia'} />
      {busca && <col className="rel-col-adesivador" />}
      <col className="rel-col-hora" />
      <col className="rel-col-hora-longa" />
      <col />
      <col className="rel-col-os" />
      <col className="rel-col-status" />
      <col className="rel-col-score" />
    </colgroup>
  )
}

/** As células de um serviço que o relatório e a busca mostram iguais: início ao score. */
function CelulasDoServico({
  s,
  aoSalvar,
  aoErrar,
}: {
  s: ServicoDoRelatorio
  aoSalvar: (valor: number | null) => Promise<unknown>
  aoErrar: (mensagem: string | null) => void
}) {
  return (
    <>
      {/* O horário real, de quando o adesivador iniciou e concluiu - a agenda nova não tem
          relógio. Data junto quando não foi no dia do serviço. */}
      {/* O mouse sobre o horário mostra quem registrou: o adesivador, ou quem agiu pelo painel. */}
      <td className="mono" data-rotulo="Início" title={s.iniciadoPor ? `Iniciado por ${s.iniciadoPor}` : undefined}>
        {s.iniciadoEm ? (
          <span className={s.iniciadoPor ? 'rel-quem' : undefined}>{horaNoDia(s.iniciadoEm, s.data)}</span>
        ) : (
          <span className="rel-vazio">—</span>
        )}
      </td>
      <td className="mono" data-rotulo="Conclusão" title={s.concluidoPor ? `Concluído por ${s.concluidoPor}` : undefined}>
        {s.concluidoEm ? (
          <span className={s.concluidoPor ? 'rel-quem' : undefined}>{horaNoDia(s.concluidoEm, s.data)}</span>
        ) : s.status === 'CONCLUIDO' && s.tipo !== 'INDISPONIVEL' ? (
          // Concluído na agenda, sem o adesivador concluir: não há horário real de conclusão.
          <span
            className="rel-sem-conclusao"
            title="Não foi concluído pelo adesivador: o serviço foi encerrado direto na agenda."
            aria-label="Não foi concluído pelo adesivador: o serviço foi encerrado direto na agenda."
          >
            ?
          </span>
        ) : (
          <span className="rel-vazio">—</span>
        )}
        {/* Teve pausa: o ícone mostra os horários de cada uma ao passar o mouse. */}
        {!!s.pausas?.length && (
          <span className="icone-pausa" title={textoDasPausas(s.pausas, s.data)} aria-label={textoDasPausas(s.pausas, s.data)}>
            ⏸
          </span>
        )}
      </td>
      <td className="celula-titulo">
        <strong>{s.descricao}</strong>
        {s.vendedor && <span className="rel-vendedor"> · {s.vendedor}</span>}
      </td>
      <td className="mono" data-rotulo="OS">
        {s.osId != null ? (
          <Link to={`/ordens/${s.osId}`} className="rel-link">
            {s.numeroOsErp}
          </Link>
        ) : (
          <span className="rel-vazio">—</span>
        )}
      </td>
      <td data-rotulo="Status">
        <ChipStatusAgenda status={s.status} etiquetaId={s.etiquetaId} />
      </td>
      {/* O score se digita aqui, na linha do serviço. */}
      <td className="celula-score" data-rotulo="Score">
        {s.tipo === 'INDISPONIVEL' ? (
          <span className="rel-vazio">—</span>
        ) : (
          <CampoScore servico={s} aoSalvar={aoSalvar} aoErrar={aoErrar} />
        )}
      </td>
    </>
  )
}

/**
 * Relatório da semana, adesivador por adesivador, serviço por serviço.
 *
 * É a leitura que a hora não dá: como todos cumprem a mesma carga horária, o que mostra
 * o trabalho de cada um é a lista do que passou pela agenda dele — com a OS, o status e
 * o score, que é a ponderação de tamanho de serviço que o cronograma já usava.
 *
 * **É aqui que o score é lançado.** Na planilha ele era digitado na própria grade porque
 * não havia outro lugar para escrever; a agenda é o plano do dia, e o score é o peso do
 * que foi entregue — só se sabe olhando a lista pronta.
 */
export default function RelatorioSemanal() {
  useLegenda()
  // A semana cortada no mês, como a agenda; "Mês inteiro" junta o mês todo (pontos do mês).
  const [periodo, setPeriodo] = useState<Periodo>(() => semanaNoMes(hojeIso()))
  const noPeriodo = `inicio=${periodo.inicio}&fim=${periodo.fim}`
  const [dados, setDados] = useState<Relatorio | null>(null)
  const [erro, setErro] = useState<string | null>(null)
  const [carregando, setCarregando] = useState(true)
  /** Erro de um lançamento, separado do erro de carregar: o relatório continua na tela. */
  const [erroScore, setErroScore] = useState<string | null>(null)

  const carregar = useCallback(() => {
    setCarregando(true)
    setErro(null)
    api
      .get<Relatorio>(`/produtividade/semanal?${noPeriodo}`)
      .then(setDados)
      .catch((e) => setErro(e instanceof Error ? e.message : 'Falha ao carregar o relatório.'))
      .finally(() => setCarregando(false))
  }, [noPeriodo])

  useEffect(carregar, [carregar])

  /**
   * Grava o score. A resposta traz o período que está na tela, recalculado: o total
   * acompanha. (O período entra nas dependências: sem isso, lançar depois de trocar de
   * semana voltava a tela para a semana em que ela abriu.)
   */
  const salvarScore = useCallback(
    (agendamentoId: number, valor: number | null) =>
      api
        .patch<Relatorio>(`/produtividade/semanal/servicos/${agendamentoId}/score?${noPeriodo}`, { score: valor })
        .then(setDados),
    [noPeriodo],
  )

  /**
   * Busca em **todos os meses**, pelo número da OS ou pelo carro/serviço. Com 2 letras ou
   * mais, os resultados tomam o lugar do período até a busca ser limpa.
   */
  const [texto, setTexto] = useState('')
  const [busca, setBusca] = useState<BuscaRelatorio | null>(null)
  const [buscando, setBuscando] = useState(false)
  const [erroBusca, setErroBusca] = useState<string | null>(null)
  const termo = texto.trim()
  const buscandoAgora = termo.length >= 2
  /** Só vale a resposta da última busca: digitando rápido, as antigas chegam depois. */
  const ultimaBusca = useRef(0)
  const buscar = useCallback(() => {
    if (termo.length < 2) {
      setBusca(null)
      return
    }
    const esta = ++ultimaBusca.current
    setBuscando(true)
    setErroBusca(null)
    api
      .get<BuscaRelatorio>(`/produtividade/semanal/busca?termo=${encodeURIComponent(termo)}`)
      .then((r) => {
        if (esta === ultimaBusca.current) setBusca(r)
      })
      .catch((e) => setErroBusca(e instanceof Error ? e.message : 'Não foi possível buscar.'))
      .finally(() => {
        if (esta === ultimaBusca.current) setBuscando(false)
      })
  }, [termo])
  // Busca enquanto a pessoa digita, depois de uma pausa curta.
  useEffect(() => {
    const espera = window.setTimeout(buscar, 300)
    return () => window.clearTimeout(espera)
  }, [buscar])

  if (erro) return <Aviso tipo="erro">{erro}</Aviso>
  if (!dados && carregando) return <Carregando texto="Montando o relatório..." />
  if (!dados) return null

  const mes = mesDe(periodo.inicio)
  const mesInteiro = periodo.inicio === mes.inicio && periodo.fim === mes.fim

  const comServico = dados.adesivadores.filter((a) => a.servicos.length > 0)

  return (
    <>
      <div className="cabecalho-pagina sem-impressao">
        <div>
          <h1>{mesInteiro ? `Relatório de ${nomeDoMes(periodo.inicio)}` : 'Relatório semanal'}</h1>
          <p>
            {formatarDia(dados.inicio)} a {formatarDia(dados.fim)} · o que passou pela agenda de
            cada adesivador. O <strong>score</strong> de cada serviço se lança aqui.
          </p>
        </div>
        <div className="acoes-linha">
          <button className="botao" onClick={() => window.print()}>
            Imprimir
          </button>
        </div>
      </div>

      <div className="sem-impressao">
        <NavegacaoMes periodo={periodo} aoMudar={setPeriodo} comMesInteiro />
      </div>

      <div className="busca-relatorio sem-impressao">
        <input
          type="search"
          placeholder="Buscar em todos os meses: nº da OS ou carro/serviço"
          value={texto}
          onChange={(e) => setTexto(e.target.value)}
          aria-label="Buscar no relatório, em todos os meses, pelo número da OS ou pelo carro/serviço"
        />
        {texto && (
          <button className="botao botao-secundario" onClick={() => setTexto('')}>
            Limpar
          </button>
        )}
      </div>

      {erroScore && (
        <div className="sem-impressao">
          <Aviso tipo="erro">{erroScore}</Aviso>
        </div>
      )}

      {buscandoAgora ? (
        <div className="cartao relatorio-busca">
          <div className="relatorio-cabecalho">
            <h3>Busca: “{termo}”</h3>
            <div className="relatorio-resumo">
              {buscando && !busca ? (
                <span>buscando…</span>
              ) : busca ? (
                <span>
                  <b>{busca.servicos.length}</b> serviço{busca.servicos.length === 1 ? '' : 's'} em todos os meses
                  {busca.limitado && ' · mostrando os mais recentes'}
                </span>
              ) : null}
            </div>
          </div>
          {erroBusca && <Aviso tipo="erro">{erroBusca}</Aviso>}
          {busca && busca.servicos.length === 0 && !buscando && (
            <Vazio>Nenhum serviço com “{termo}” no número da OS ou no carro/serviço.</Vazio>
          )}
          {busca && busca.servicos.length > 0 && (
            <div className="tabela-rolagem">
              <table className="tabela-relatorio tabela-servicos tabela-cartoes">
                <ColunasDoServico busca />
                <thead>
                  <tr>
                    <th>Dia</th>
                    <th>Adesivador</th>
                    <th>Início</th>
                    <th>Conclusão</th>
                    <th>Carro / serviço</th>
                    <th>OS</th>
                    <th>Status</th>
                    <th>Score</th>
                  </tr>
                </thead>
                <tbody>
                  {busca.servicos.map(({ adesivador, servico: s }) => (
                    <tr key={s.agendamentoId}>
                      <td className="mono" data-rotulo="Dia">
                        {s.diaSemana} {new Date(`${s.data}T12:00:00`).toLocaleDateString('pt-BR')}
                      </td>
                      <td data-rotulo="Adesivador">{adesivador}</td>
                      <CelulasDoServico
                        s={s}
                        // Depois de lançar, a busca é refeita para mostrar o score gravado.
                        aoSalvar={(valor) => salvarScore(s.agendamentoId, valor).then(buscar)}
                        aoErrar={setErroScore}
                      />
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      ) : (
        <>

      <h2 className="titulo-impressao">
        Relatório {mesInteiro ? 'do mês' : 'semanal'} dos adesivadores · {formatarDia(dados.inicio)} a{' '}
        {formatarDia(dados.fim)}
      </h2>

      {comServico.length > 0 && (
        <div className="cartao relatorio-pontos">
          <h3>Pontos da semana</h3>
          <div className="tabela-rolagem">
            <table className="tabela-relatorio tabela-pontos tabela-cartoes">
              <colgroup>
                <col />
                <col className="rel-col-numero" />
                <col className="rel-col-numero" />
                <col className="rel-col-numero" />
                <col className="rel-col-numero" />
              </colgroup>
              <thead>
                <tr>
                  <th>Adesivador</th>
                  <th className="num">Serviços</th>
                  <th className="num">Concluídos</th>
                  <th className="num" title="Soma do score dos serviços concluídos">Pontos</th>
                  <th className="num" title="Soma do score de todos os serviços que já têm score">
                    Pontos lançados
                  </th>
                </tr>
              </thead>
              <tbody>
                {[...comServico]
                  .sort((a, b) => (b.score ?? 0) - (a.score ?? 0) || (b.scoreLancado ?? 0) - (a.scoreLancado ?? 0))
                  .map((a) => (
                    <tr key={a.adesivadorId}>
                      <td className="celula-titulo">
                        <strong>{a.adesivador}</strong>
                      </td>
                      <td className="num" data-rotulo="Serviços">{a.totalServicos}</td>
                      <td className="num" data-rotulo="Concluídos">{a.concluidos}</td>
                      <td className="num" data-rotulo="Pontos">
                        <b>{umDecimal(a.score ?? 0)}</b>
                      </td>
                      <td className="num" data-rotulo="Pontos lançados">{umDecimal(a.scoreLancado ?? 0)}</td>
                    </tr>
                  ))}
              </tbody>
              <tfoot>
                <tr>
                  <td className="celula-titulo">Total</td>
                  <td className="num" data-rotulo="Serviços">{comServico.reduce((t, a) => t + a.totalServicos, 0)}</td>
                  <td className="num" data-rotulo="Concluídos">{comServico.reduce((t, a) => t + a.concluidos, 0)}</td>
                  <td className="num" data-rotulo="Pontos">
                    <b>{umDecimal(comServico.reduce((t, a) => t + (a.score ?? 0), 0))}</b>
                  </td>
                  <td className="num" data-rotulo="Pontos lançados">
                    {umDecimal(comServico.reduce((t, a) => t + (a.scoreLancado ?? 0), 0))}
                  </td>
                </tr>
              </tfoot>
            </table>
          </div>
        </div>
      )}

      {comServico.length === 0 ? (
        <div className="cartao">
          <Vazio>Nenhum serviço agendado nesta semana.</Vazio>
        </div>
      ) : (
        <div className="relatorio-lista">
          {comServico.map((a) => (
            <div className="cartao relatorio-pessoa" key={a.adesivadorId}>
              <div className="relatorio-cabecalho">
                <h3>{a.adesivador}</h3>
                <div className="relatorio-resumo">
                  <span>
                    <b>{a.concluidos}</b> de {a.totalServicos} concluídos
                  </span>
                  <span title="Soma do score dos serviços concluídos">
                    pontos <b>{umDecimal(a.score ?? 0)}</b>
                  </span>
                  {a.scoreLancado != null && a.scoreLancado !== a.score && (
                    <span title="Soma do score de todos os serviços que já têm score, concluídos ou não">
                      lançados <b>{umDecimal(a.scoreLancado)}</b>
                    </span>
                  )}
                  {a.naoCompareceu > 0 && (
                    <span className="alerta-resumo">{a.naoCompareceu} não veio</span>
                  )}
                  {a.horasIndisponiveis > 0 && (
                    <span className="alerta-resumo">
                      {umDecimal(a.horasIndisponiveis)} h indisponível
                    </span>
                  )}
                </div>
              </div>

              <div className="tabela-rolagem">
                <table className="tabela-relatorio tabela-servicos tabela-cartoes">
                  <ColunasDoServico />
                  <thead>
                    <tr>
                      <th>Dia</th>
                      <th>Início</th>
                      <th>Conclusão</th>
                      <th>Carro / serviço</th>
                      <th>OS</th>
                      <th>Status</th>
                      <th>Score</th>
                    </tr>
                  </thead>
                  <tbody>
                    {a.servicos.map((s) => (
                      <tr
                        key={s.agendamentoId}
                        className={s.tipo === 'INDISPONIVEL' ? 'linha-bloqueio' : undefined}
                      >
                        <td className="mono" data-rotulo="Dia">
                          {s.diaSemana} {formatarDia(s.data)}
                        </td>
                        <CelulasDoServico
                          s={s}
                          aoSalvar={(valor) => salvarScore(s.agendamentoId, valor)}
                          aoErrar={setErroScore}
                        />
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          ))}
        </div>
      )}
        </>
      )}
    </>
  )
}
