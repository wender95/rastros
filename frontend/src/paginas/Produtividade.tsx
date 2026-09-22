import { useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api/client'
import { mesDe, paraIso } from '../api/periodos'
import type { Periodo } from '../api/periodos'
import NavegacaoMes from '../componentes/NavegacaoMes'
import type { Produtividade, ProdutividadePessoa, ProdutividadeSetor } from '../api/tipos'
import { Aviso, Carregando, HORAS_DIA_UTIL, Vazio, formatarHoras, rotuloSetor } from '../componentes/Ui'


/** 1 processada, 2 processadas. */
const plural = (n: number, palavra: string) => (n === 1 ? palavra : `${palavra}s`)

const umDecimal = (n: number) => n.toLocaleString('pt-BR', { maximumFractionDigits: 1 })

/** Tempo legível. As horas já vêm só em horário comercial, então "dia" é dia útil de 9h. */
function tempo(horas: number | null): string {
  if (horas == null) return '—'
  if (horas < 1 / 60) return 'menos de 1 min'
  if (horas >= HORAS_DIA_UTIL) {
    const dias = horas / HORAS_DIA_UTIL
    return `${umDecimal(dias)} dia${dias >= 2 ? 's' : ''} út${dias >= 2 ? 'eis' : 'il'}`
  }
  return formatarHoras(horas)
}

/** Prateleira e Pátio são lugares de espera: a OS conta, mas não há quem a receba. */
const LUGAR = new Set(['PRATELEIRA', 'PATIO'])

interface Barra {
  chave: string | number
  rotulo: string
  valor: number
  texto: ReactNode
  dica: string
}

/**
 * Barras horizontais, uma escala só, com o valor escrito na ponta de cada barra — o
 * número nunca depende de passar o mouse. Categoria nominal: todas da mesma cor.
 */
function Barras({ itens }: { itens: Barra[] }) {
  const maximo = Math.max(1, ...itens.map((i) => i.valor))
  return (
    <div className="grafico">
      {itens.map((i) => (
        <div className="viz-linha" key={i.chave} title={i.dica}>
          <span className="viz-rotulo">{i.rotulo}</span>
          <span className="viz-trilha">
            <span className="viz-barra viz-setor" style={{ width: `${(i.valor / maximo) * 100}%` }} />
          </span>
          <span className="viz-valor">{i.texto}</span>
        </div>
      ))}
    </div>
  )
}

function Numero({ valor, rotulo }: { valor: ReactNode; rotulo: string }) {
  return (
    <div className="prod-numero">
      <strong>{valor}</strong>
      <span>{rotulo}</span>
    </div>
  )
}

/** As pessoas de um setor: quantas OS cada uma processou e o tempo médio delas no setor. */
function barrasDePessoas(pessoas: ProdutividadePessoa[], unidade: string, comTempo: boolean): Barra[] {
  return pessoas.map((p) => ({
    chave: p.usuarioId,
    rotulo: p.nome,
    valor: p.quantidade,
    texto: (
      <>
        {p.quantidade}
        {comTempo && <span className="viz-valor-total"> · {tempo(p.horasMediaNoSetor)}</span>}
      </>
    ),
    dica: `${p.nome}: ${p.quantidade} ${unidade}${comTempo ? `, ${tempo(p.horasMediaNoSetor)} em média no setor` : ''}`,
  }))
}

function TabelaPessoas({ pessoas, unidade, comTempo }: { pessoas: ProdutividadePessoa[]; unidade: string; comTempo: boolean }) {
  return (
    <div className="tabela-rolagem">
      <table>
        <thead>
          <tr>
            <th>Pessoa</th>
            <th>{unidade}</th>
            {comTempo && <th>Tempo médio no setor</th>}
          </tr>
        </thead>
        <tbody>
          {pessoas.map((p) => (
            <tr key={p.usuarioId}>
              <td>
                <strong>{p.nome}</strong>
              </td>
              <td className="mono">{p.quantidade}</td>
              {comTempo && <td className="mono">{tempo(p.horasMediaNoSetor)}</td>}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

/**
 * Produtividade por setor e, dentro de cada setor, por pessoa.
 *
 * - Comercial: OS abertas, por quem abriu.
 * - Setores (e o Financeiro): OS processadas e o tempo que elas ficam no setor, da chegada
 *   à saída. A OS conta para quem a recebeu — o responsável pela etapa.
 * - Adesivadores: serviços feitos e a pontuação.
 */
export default function Produtividade() {
  // O mês todo por padrão; dentro dele, as semanas cortadas na virada do mês.
  const [periodo, setPeriodo] = useState<Periodo>(() => mesDe(paraIso(new Date())))
  const [dados, setDados] = useState<Produtividade | null>(null)
  const [erro, setErro] = useState<string | null>(null)
  const [carregando, setCarregando] = useState(true)
  const [tabela, setTabela] = useState(false)

  useEffect(() => {
    setCarregando(true)
    setErro(null)
    api
      .get<Produtividade>(`/produtividade?inicio=${periodo.inicio}&fim=${periodo.fim}`)
      .then(setDados)
      .catch((e) => setErro(e instanceof Error ? e.message : 'Falha ao carregar os indicadores.'))
      .finally(() => setCarregando(false))
  }, [periodo])

  const setores = useMemo(() => (dados?.setores ?? []).filter((s) => s.processadas > 0), [dados])
  const adesivadores = useMemo(
    () => (dados?.adesivadores ?? []).filter((a) => a.servicos > 0),
    [dados],
  )

  if (erro) return <Aviso tipo="erro">{erro}</Aviso>
  if (!dados && carregando) return <Carregando texto="Calculando indicadores..." />
  if (!dados) return null

  const comercial = dados.comercial

  return (
    <>
      <div className="cabecalho-pagina">
        <div>
          <h1>Produtividade</h1>
          <p>
            Por setor e, dentro de cada setor, por pessoa. Calculado na hora a partir das movimentações e da agenda.
            Os tempos contam só o <strong>horário comercial</strong> (seg a sex, 07:30–12:00 e 13:30–18:00, sexta
            até 17:00, fora feriados).
          </p>
        </div>
        {/* Uma faixa de filtro acima de tudo o que ela afeta. */}
        <div className="acoes-linha">
          <button className="botao botao-secundario" onClick={() => setTabela((v) => !v)}>
            {tabela ? 'Ver gráficos' : 'Ver tabelas'}
          </button>
        </div>
      </div>

      <NavegacaoMes periodo={periodo} aoMudar={setPeriodo} comMesInteiro />

      <div className={`viz-conteudo${carregando ? ' recarregando' : ''}`}>
        {/* ---------------------------------------------------------- visão geral */}
        <div className="cartao grafico-cartao">
          <div className="grafico-cabecalho">
            <h2>OS processadas por setor</h2>
            <p>Saíram do setor no período · ao lado, o tempo médio que ficaram lá (da chegada à saída)</p>
          </div>
          {setores.length === 0 ? (
            <Vazio>Nenhuma OS saiu de setor nenhum neste período.</Vazio>
          ) : tabela ? (
            <TabelaSetores setores={setores} />
          ) : (
            <Barras
              itens={setores.map((s) => ({
                chave: s.setor,
                rotulo: rotuloSetor(s.setor),
                valor: s.processadas,
                texto: (
                  <>
                    {s.processadas}
                    <span className="viz-valor-total"> · {tempo(s.horasMediaNoSetor)}</span>
                  </>
                ),
                dica: `${rotuloSetor(s.setor)}: ${s.processadas} processada(s), ${tempo(s.horasMediaNoSetor)} em média no setor`,
              }))}
            />
          )}
        </div>

        <div className="prod-grade">
          {/* ------------------------------------------------------------ comercial */}
          <div className="cartao grafico-cartao">
            <div className="grafico-cabecalho">
              <h2>Comercial</h2>
              <p>OS abertas por vendedor</p>
            </div>
            <div className="prod-numeros">
              <Numero valor={comercial.abertas} rotulo={plural(comercial.abertas, 'OS aberta')} />
            </div>
            {comercial.pessoas.length === 0 ? (
              <Vazio>Nenhuma OS aberta neste período.</Vazio>
            ) : tabela ? (
              <TabelaPessoas pessoas={comercial.pessoas} unidade="OS abertas" comTempo={false} />
            ) : (
              <Barras itens={barrasDePessoas(comercial.pessoas, 'OS aberta(s)', false)} />
            )}
          </div>

          {/* -------------------------------------------------- um cartão por setor */}
          {setores.map((s) => {
            const lugar = LUGAR.has(s.setor)
            const financeiro = s.setor === 'FINANCEIRO'
            return (
              <div className="cartao grafico-cartao" key={s.setor}>
                <div className="grafico-cabecalho">
                  <h2>{rotuloSetor(s.setor)}</h2>
                  <p>
                    {lugar
                      ? 'Lugar de espera: o material aguarda retirada, sem responsável'
                      : financeiro
                        ? 'OS concluídas por pessoa e o tempo até concluir'
                        : 'OS processadas por pessoa (quem recebeu) e o tempo delas no setor'}
                  </p>
                </div>
                <div className="prod-numeros">
                  <Numero valor={s.processadas} rotulo={plural(s.processadas, financeiro ? 'concluída' : 'processada')} />
                  <Numero valor={tempo(s.horasMediaNoSetor)} rotulo={lugar ? 'esperando, em média' : 'no setor, em média'} />
                  {s.retornos > 0 && <Numero valor={s.retornos} rotulo={plural(s.retornos, 'devolvida')} />}
                </div>
                {!lugar &&
                  (s.pessoas.length === 0 ? (
                    <Vazio>Sem recebimento registrado neste período.</Vazio>
                  ) : tabela ? (
                    <TabelaPessoas
                      pessoas={s.pessoas}
                      unidade={financeiro ? 'Concluídas' : 'Processadas'}
                      comTempo
                    />
                  ) : (
                    <Barras
                      itens={barrasDePessoas(s.pessoas, financeiro ? 'concluída(s)' : 'processada(s)', true)}
                    />
                  ))}
              </div>
            )
          })}

          {/* --------------------------------------------------------- adesivadores */}
          <div className="cartao grafico-cartao prod-largo">
            <div className="grafico-cabecalho">
              <h2>Adesivadores</h2>
              <p>Serviços feitos e a pontuação de cada um</p>
            </div>
            {adesivadores.length === 0 ? (
              <Vazio>Nenhum serviço na agenda neste período.</Vazio>
            ) : tabela ? (
              <div className="tabela-rolagem">
                <table>
                  <thead>
                    <tr>
                      <th>Adesivador</th>
                      <th>Serviços feitos</th>
                      <th>Pontuação</th>
                      <th>Agendados</th>
                      <th>Não veio</th>
                    </tr>
                  </thead>
                  <tbody>
                    {adesivadores.map((a) => (
                      <tr key={a.adesivadorId}>
                        <td>
                          <strong>{a.adesivador}</strong>
                        </td>
                        <td className="mono">{a.concluidos}</td>
                        <td className="mono">{a.scoreConcluido != null ? umDecimal(a.scoreConcluido) : '—'}</td>
                        <td className="mono">{a.servicos}</td>
                        <td className="mono">{a.naoCompareceu}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ) : (
              // Duas medidas de escalas diferentes: dois gráficos lado a lado, cada um com a sua.
              <div className="prod-lado-a-lado">
                <div>
                  <h3 className="prod-subtitulo">Serviços feitos</h3>
                  <Barras
                    itens={adesivadores.map((a) => ({
                      chave: a.adesivadorId,
                      rotulo: a.adesivador,
                      valor: a.concluidos,
                      texto: a.concluidos,
                      dica: `${a.adesivador}: ${a.concluidos} de ${a.servicos} serviços feitos`,
                    }))}
                  />
                </div>
                <div>
                  <h3 className="prod-subtitulo">Pontuação</h3>
                  <Barras
                    itens={adesivadores.map((a) => ({
                      chave: a.adesivadorId,
                      rotulo: a.adesivador,
                      valor: a.scoreConcluido ?? 0,
                      texto: a.scoreConcluido != null ? umDecimal(a.scoreConcluido) : '—',
                      dica: `${a.adesivador}: pontuação ${a.scoreConcluido != null ? umDecimal(a.scoreConcluido) : 'não lançada'}`,
                    }))}
                  />
                </div>
              </div>
            )}
            <div className="grafico-rodape">
              <span className="viz-nota">
                Serviço feito = concluído na agenda. A pontuação soma o score dos serviços feitos, lançado
                serviço por serviço no{' '}
                <Link to="/relatorio-semanal" className="rel-link">
                  relatório semanal
                </Link>
                .
              </span>
            </div>
          </div>
        </div>
      </div>
    </>
  )
}

function TabelaSetores({ setores }: { setores: ProdutividadeSetor[] }) {
  return (
    <div className="tabela-rolagem">
      <table>
        <thead>
          <tr>
            <th>Setor</th>
            <th>Processadas</th>
            <th>Tempo no setor</th>
            <th>Esperando receber</th>
            <th>Em trabalho</th>
            <th>Devolvidas</th>
          </tr>
        </thead>
        <tbody>
          {setores.map((s) => (
            <tr key={s.setor}>
              <td>
                <strong>{rotuloSetor(s.setor)}</strong>
              </td>
              <td className="mono">{s.processadas}</td>
              <td className="mono">{tempo(s.horasMediaNoSetor)}</td>
              <td className="mono">{tempo(s.horasMediaEspera)}</td>
              <td className="mono">{tempo(s.horasMediaProcessamento)}</td>
              <td className="mono">{s.retornos}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
