import { useCallback, useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { formatarDia } from '../agenda/regua'
import { api } from '../api/client'
import { hojeIso, mesDe, nomeDoMes, semanaNoMes } from '../api/periodos'
import type { Periodo } from '../api/periodos'
import NavegacaoMes from '../componentes/NavegacaoMes'
import type { RelatorioSemanal as Relatorio } from '../api/tipos'
import { Aviso, Carregando, Vazio, rotuloStatusAgenda } from '../componentes/Ui'

/** Texto do campo -> numero. Aceita vírgula, e vazio significa "sem score". */
function lerScore(texto: string): number | null | undefined {
  const limpo = texto.trim().replace(',', '.')
  if (!limpo) return null
  const n = Number(limpo)
  return Number.isFinite(n) && n >= 0 ? n : undefined
}

const umDecimal = (n: number) => n.toLocaleString('pt-BR', { maximumFractionDigits: 1 })

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
  // A semana cortada no mês, como a agenda; "Mês inteiro" junta o mês todo (pontos do mês).
  const [periodo, setPeriodo] = useState<Periodo>(() => semanaNoMes(hojeIso()))
  const noPeriodo = `inicio=${periodo.inicio}&fim=${periodo.fim}`
  const [dados, setDados] = useState<Relatorio | null>(null)
  const [erro, setErro] = useState<string | null>(null)
  const [carregando, setCarregando] = useState(true)
  /** Score sendo digitado, por serviço — só existe entre o primeiro toque e o salvamento. */
  const [rascunhos, setRascunhos] = useState<Record<number, string>>({})
  const [salvando, setSalvando] = useState<number | null>(null)
  /** Erro de um lançamento, separado do erro de carregar: o relatório continua na tela. */
  const [erroScore, setErroScore] = useState<string | null>(null)
  const desistindo = useRef(false)

  const carregar = useCallback(() => {
    setCarregando(true)
    setErro(null)
    api
      .get<Relatorio>(`/produtividade/semanal?${noPeriodo}`)
      .then((r) => {
        setDados(r)
        setRascunhos({})
      })
      .catch((e) => setErro(e instanceof Error ? e.message : 'Falha ao carregar o relatório.'))
      .finally(() => setCarregando(false))
  }, [noPeriodo])

  useEffect(carregar, [carregar])

  /** Grava o score digitado. A resposta traz a semana recalculada: o total acompanha. */
  const salvarScore = useCallback(
    (agendamentoId: number, atual: number | null, texto: string) => {
      const valor = lerScore(texto)
      if (valor === undefined) {
        setErroScore('Score inválido: escreva um número, como 3 ou 3,5.')
        return
      }
      if (valor === atual) {
        setRascunhos(({ [agendamentoId]: _, ...resto }) => resto)
        return
      }
      setErroScore(null)
      setSalvando(agendamentoId)
      api
        .patch<Relatorio>(`/produtividade/semanal/servicos/${agendamentoId}/score?${noPeriodo}`, {
          score: valor,
        })
        .then((r) => {
          setDados(r)
          setRascunhos(({ [agendamentoId]: _, ...resto }) => resto)
        })
        .catch((e) =>
          setErroScore(e instanceof Error ? e.message : 'Não foi possível salvar o score.'),
        )
        .finally(() => setSalvando(null))
    },
    [],
  )

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

      <h2 className="titulo-impressao">
        Relatório {mesInteiro ? 'do mês' : 'semanal'} dos adesivadores · {formatarDia(dados.inicio)} a{' '}
        {formatarDia(dados.fim)}
      </h2>

      {comServico.length > 0 && (
        <div className="cartao relatorio-pontos">
          <h3>Pontos da semana</h3>
          <div className="tabela-rolagem">
            <table className="tabela-relatorio">
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
                      <td>
                        <strong>{a.adesivador}</strong>
                      </td>
                      <td className="num">{a.totalServicos}</td>
                      <td className="num">{a.concluidos}</td>
                      <td className="num">
                        <b>{umDecimal(a.score ?? 0)}</b>
                      </td>
                      <td className="num">{umDecimal(a.scoreLancado ?? 0)}</td>
                    </tr>
                  ))}
              </tbody>
              <tfoot>
                <tr>
                  <td>Total</td>
                  <td className="num">{comServico.reduce((t, a) => t + a.totalServicos, 0)}</td>
                  <td className="num">{comServico.reduce((t, a) => t + a.concluidos, 0)}</td>
                  <td className="num">
                    <b>{umDecimal(comServico.reduce((t, a) => t + (a.score ?? 0), 0))}</b>
                  </td>
                  <td className="num">{umDecimal(comServico.reduce((t, a) => t + (a.scoreLancado ?? 0), 0))}</td>
                </tr>
              </tfoot>
            </table>
          </div>
        </div>
      )}

      {erroScore && (
        <div className="sem-impressao">
          <Aviso tipo="erro">{erroScore}</Aviso>
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
                <table className="tabela-relatorio">
                  <thead>
                    <tr>
                      <th>Dia</th>
                      <th>Horário</th>
                      <th>Carro / serviço</th>
                      <th>OS</th>
                      <th>Material</th>
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
                        <td className="mono">
                          {s.diaSemana} {formatarDia(s.data)}
                        </td>
                        <td className="mono">
                          {s.dias > 1 ? (
                            <>
                              {s.horarioInicio} → {s.diaSemanaFim} {formatarDia(s.dataFim)} {s.horarioFim}
                              <span className="rel-dias">{s.dias} dias</span>
                            </>
                          ) : (
                            <>
                              {s.horarioInicio}–{s.horarioFim}
                            </>
                          )}
                        </td>
                        <td>
                          <strong>{s.descricao}</strong>
                          {s.vendedor && <span className="rel-vendedor"> · {s.vendedor}</span>}
                        </td>
                        <td className="mono">
                          {s.osId != null ? (
                            <Link to={`/ordens/${s.osId}`} className="rel-link">
                              {s.numeroOsErp}
                            </Link>
                          ) : (
                            <span className="rel-vazio">—</span>
                          )}
                        </td>
                        <td>
                          {s.materialPronto == null ? (
                            <span className="rel-vazio">—</span>
                          ) : (
                            <span className={s.materialPronto ? 'rel-pronto' : 'rel-producao'}>
                              {s.materialPronto ? 'pronto' : 'em produção'}
                            </span>
                          )}
                        </td>
                        <td>
                          <span className={`chip ag-${s.status.toLowerCase()}`}>
                            {rotuloStatusAgenda(s.status)}
                          </span>
                        </td>
                        {/* O score se digita aqui, na linha do serviço: um campo por
                            serviço, salvo ao sair dele ou ao apertar Enter. */}
                        <td className="celula-score">
                          {s.tipo === 'INDISPONIVEL' ? (
                            <span className="rel-vazio">—</span>
                          ) : (
                            <input
                              className="campo-score"
                              inputMode="decimal"
                              aria-label={`Score de ${s.descricao}`}
                              placeholder="—"
                              disabled={salvando === s.agendamentoId}
                              value={
                                rascunhos[s.agendamentoId] ??
                                (s.score != null ? umDecimal(s.score) : '')
                              }
                              onChange={(e) =>
                                setRascunhos((r) => ({
                                  ...r,
                                  [s.agendamentoId]: e.target.value,
                                }))
                              }
                              onFocus={(e) => e.currentTarget.select()}
                              onBlur={(e) => {
                                // Esc desiste do que foi digitado; o blur que ele provoca
                                // não pode salvar.
                                if (desistindo.current) {
                                  desistindo.current = false
                                  return
                                }
                                // Compara o que está no campo com o que está gravado — e
                                // não com o rascunho: digitar e sair na mesma batida não
                                // pode perder o lançamento.
                                salvarScore(s.agendamentoId, s.score, e.target.value)
                              }}
                              onKeyDown={(e) => {
                                if (e.key === 'Enter') e.currentTarget.blur()
                                if (e.key === 'Escape') {
                                  desistindo.current = true
                                  setRascunhos(({ [s.agendamentoId]: _, ...resto }) => resto)
                                  e.currentTarget.blur()
                                }
                              }}
                            />
                          )}
                        </td>
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
  )
}
