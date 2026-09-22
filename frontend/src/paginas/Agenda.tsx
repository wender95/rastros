import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import CardAgenda from '../agenda/CardAgenda'
import {
  formatarDia,
  linhaFinalDe,
  montarLayout,
  montarLinhas,
  primeiraLivreAcima,
} from '../agenda/regua'
import type { LayoutColuna, Previa } from '../agenda/regua'
import { chaveDoAlvo, useArrasteToque } from '../agenda/useArrasteToque'
import type { AlvoSolte } from '../agenda/useArrasteToque'
import { useAtalhos, useSegundoToque } from '../agenda/useAtalhos'
import type { Selecao } from '../agenda/useAtalhos'
import { useRedimensionar } from '../agenda/useRedimensionar'
import { api } from '../api/client'
import { bloqueioDesde } from '../api/faixas'
import { hojeIso, outraSemana, semanaNoMes } from '../api/periodos'
import type { Periodo } from '../api/periodos'
import type { Agendamento, SemanaAgenda, StatusAgendamento } from '../api/tipos'
import { useAuth } from '../auth/AuthContext'
import DetalheAgendamento from '../componentes/DetalheAgendamento'
import EditarAdesivadores from '../componentes/EditarAdesivadores'
import NavegacaoMes from '../componentes/NavegacaoMes'
import { pode } from '../auth/acessos'
import { Aviso, Carregando, formatarHoras, rotuloStatusAgenda, useVendedores } from '../componentes/Ui'

/**
 * Agenda dos adesivadores em horários reais: cada dia tem as faixas de 07:30 às 18:00
 * (a sexta vai até 17:00), incluindo o almoço. Navega por mês, com as semanas cortadas na
 * virada do mês, como as abas da planilha. As faixas da semana formam uma régua contínua, então um serviço
 * longo passa do fim do dia e segue no dia seguinte.
 *
 * Tudo que é cálculo da grade está em agenda/regua.ts (testado); aqui ficam o estado e
 * os gestos: clique, arrastar (mouse e dedo), puxar a borda e os atalhos de teclado.
 */
export default function Agenda() {
  const { usuario } = useAuth()
  useVendedores() // os nomes dos vendedores nos cards chegam do servidor
  const [periodo, setPeriodo] = useState<Periodo>(() => semanaNoMes(hojeIso()))
  const referencia = periodo.inicio
  const [semana, setSemana] = useState<SemanaAgenda | null>(null)
  const [erro, setErro] = useState<string | null>(null)
  const [carregando, setCarregando] = useState(true)
  const [selecionado, setSelecionado] = useState<Agendamento | null>(null)
  const [novaCelula, setNovaCelula] = useState<AlvoSolte | null>(null)
  const [somenteSemOs, setSomenteSemOs] = useState(false)
  const [arrastando, setArrastando] = useState<Agendamento | null>(null)
  const [alvo, setAlvo] = useState<string | null>(null)
  const [erroAcao, setErroAcao] = useState<string | null>(null)
  const [previa, setPrevia] = useState<Previa | null>(null)
  const [copiado, setCopiado] = useState<Agendamento | null>(null)
  /** O copiado veio de um Ctrl+X: colar move o carro em vez de criar outro. */
  const [recortado, setRecortado] = useState(false)
  const [aviso, setAviso] = useState<string | null>(null)
  const [editandoColunas, setEditandoColunas] = useState(false)
  /** Um clique seleciona, dois abrem. A seleção é o alvo do Ctrl+C (card) e do Ctrl+V (faixa livre). */
  const [selecao, setSelecao] = useState<Selecao>(null)
  /**
   * Ate quando ignorar clique: soltar um arraste ou a borda pode gerar um "clique" logo em
   * seguida (ou nao, no toque). Uma janela curta evita engolir o proximo clique de verdade.
   */
  const ignorarCliqueAte = useRef(0)
  const ignorarProximoClique = () => {
    ignorarCliqueAte.current = Date.now() + 400
  }
  const ehSegundoToque = useSegundoToque()

  /**
   * Geometria real das linhas da grade (topo e altura, em px). As linhas não têm todas a
   * mesma altura — o separador de dia e as faixas com dois serviços mudam isso — então a
   * tarja do almoço dentro de um card longo precisa de medida, não de porcentagem.
   */
  const corpoRef = useRef<HTMLTableSectionElement | null>(null)
  const [geometria, setGeometria] = useState<{ topo: number; altura: number }[]>([])

  const carregar = useCallback(() => {
    setCarregando(true)
    setErro(null)
    api
      .get<SemanaAgenda>(`/agenda/semana?data=${referencia}`)
      .then(setSemana)
      .catch((e) => setErro(e instanceof Error ? e.message : 'Falha ao carregar a agenda.'))
      .finally(() => setCarregando(false))
  }, [referencia])

  /** Resposta de uma edição: se é a semana que está na tela, usa; senão recarrega a da tela. */
  const aplicarSemana = useCallback(
    (s: SemanaAgenda) => (s.inicio === referencia ? setSemana(s) : carregar()),
    [referencia, carregar],
  )

  useEffect(carregar, [carregar])

  useLayoutEffect(() => {
    function medir() {
      const corpo = corpoRef.current
      if (!corpo) return
      const base = corpo.getBoundingClientRect().top
      const medidas = [...corpo.querySelectorAll('tr')].map((tr) => {
        const r = tr.getBoundingClientRect()
        return { topo: r.top - base, altura: r.height }
      })
      setGeometria((atual) => {
        const igual =
          atual.length === medidas.length && atual.every((a, i) => Math.abs(a.topo - medidas[i].topo) < 0.5)
        return igual ? atual : medidas
      })
    }
    medir()
    window.addEventListener('resize', medir)
    return () => window.removeEventListener('resize', medir)
  }, [semana, previa])

  const podeEditar =
    usuario?.perfil === 'VENDEDOR' || usuario?.perfil === 'DIRETORIA' || usuario?.perfil === 'ADMIN'

  const falhou = (padrao: string) => (e: unknown) => setErroAcao(e instanceof Error ? e.message : padrao)

  /** Soltar um carro numa faixa: move, ou troca de lugar com quem estiver lá. */
  const soltar = useCallback(
    ({ data, adesivadorId, slot }: AlvoSolte) => {
      const item = arrastando
      setArrastando(null)
      setAlvo(null)
      if (!item) return
      if (item.data === data && item.adesivadorId === adesivadorId && item.slotInicio === slot) return
      setErroAcao(null)
      api
        .patch<SemanaAgenda>(`/agenda/${item.id}/mover`, { data, adesivadorId, slotInicio: slot })
        .then(aplicarSemana)
        .catch(falhou('Não foi possível mover.'))
    },
    [arrastando],
  )

  /** Cola a cópia numa faixa livre, com tudo do original. */
  const colarEm = useCallback(
    ({ data, adesivadorId, slot }: AlvoSolte) => {
      const origem = copiado
      if (!origem) return
      setErroAcao(null)
      if (recortado) {
        // Recortar e colar é mover: o carro é o mesmo, com OS, status e score.
        api
          .patch<SemanaAgenda>(`/agenda/${origem.id}/mover`, { data, adesivadorId, slotInicio: slot })
          .then((s) => {
            aplicarSemana(s)
            setCopiado(null)
            setRecortado(false)
            setSelecao(null)
            setAviso(`"${origem.descricao}" movido. Ctrl+Z desfaz.`)
          })
          .catch(falhou('Não foi possível mover.'))
        return
      }
      api
        .post('/agenda', {
          data,
          adesivadorId,
          slotInicio: slot,
          tipo: origem.tipo,
          horasEstimadas: origem.horasEstimadas,
          descricao: origem.descricao,
          vendedorCodigo: origem.vendedorCodigo,
          status: origem.tipo === 'INDISPONIVEL' ? origem.status : 'PROGRAMADO',
          osId: origem.material?.osId ?? null,
          observacao: origem.observacao,
        })
        .then(() => {
          setAviso(`"${origem.descricao}" colado.`)
          carregar()
        })
        .catch(falhou('Não foi possível colar.'))
    },
    [copiado, recortado, carregar],
  )

  const desfazer = useCallback(() => {
    setErroAcao(null)
    api
      .post<{ desfeito: string }>('/agenda/desfazer')
      .then((r) => {
        setAviso(`Desfeito: ${r.desfeito}.`)
        carregar()
      })
      .catch(falhou('Não foi possível desfazer.'))
  }, [carregar])

  useAtalhos({
    // Com uma janela aberta por cima, as teclas sao dela e nao da grade.
    habilitado: podeEditar && !selecionado && !novaCelula && !editandoColunas,
    selecao,
    copiado,
    aoCopiar: (item) => {
      setCopiado(item)
      setRecortado(false)
      setAviso(`"${item.descricao}" copiado. Selecione uma faixa livre e cole.`)
    },
    aoRecortar: (item) => {
      setCopiado(item)
      setRecortado(true)
      setAviso(`"${item.descricao}" recortado. Selecione uma faixa livre e cole com Ctrl+V: ele sai de onde está.`)
    },
    aoColar: colarEm,
    aoDesfazer: desfazer,
    aoExcluir: (item) => {
      setErroAcao(null)
      setSelecao(null)
      api
        .delete(`/agenda/${item.id}`)
        .then(() => {
          setAviso(`"${item.descricao}" excluído. Ctrl+Z traz de volta.`)
          carregar()
        })
        .catch(falhou('Não foi possível excluir.'))
    },
    aoLimpar: () => {
      setSelecao(null)
      setCopiado(null)
      setRecortado(false)
      setAviso(null)
    },
    aoAvisar: setAviso,
  })

  const iniciarRedimensionar = useRedimensionar({
    faixas: semana?.faixas,
    bloqueada: semana ? bloqueioDesde(semana.faixas, semana.inicio) : () => false,
    aoMudarPrevia: setPrevia,
    aoTerminar: ignorarProximoClique,
    aoConfirmar: (id, horas) => {
      setErroAcao(null)
      api
        .patch<SemanaAgenda>(`/agenda/${id}/horas`, { horasEstimadas: horas })
        .then(aplicarSemana)
        .catch(falhou('Não foi possível redimensionar.'))
    },
    aoConfirmarInicio: (id, linha) => {
      const destino = linhas[linha]
      if (!destino) return
      setErroAcao(null)
      api
        .patch<SemanaAgenda>(`/agenda/${id}/inicio`, { data: destino.data, slotInicio: destino.faixa })
        .then(aplicarSemana)
        .catch(falhou('Não foi possível mudar o início.'))
    },
  })

  const aoTocarCard = useArrasteToque({
    habilitado: podeEditar,
    aoIniciar: (item) => {
      setErroAcao(null)
      setArrastando(item)
    },
    aoPassarPor: setAlvo,
    aoSoltar: (destino) => {
      ignorarProximoClique()
      if (destino) soltar(destino)
      else {
        setArrastando(null)
        setAlvo(null)
      }
    },
  })

  const cargaPorColuna = useMemo(
    () => new Map(semana?.horasOcupadas.map((t) => [t.adesivadorId, t.total]) ?? []),
    [semana],
  )
  /** Horas bloqueadas por falta/férias: saem da capacidade da semana. */
  const bloqueioPorColuna = useMemo(
    () => new Map(semana?.horasIndisponiveis.map((t) => [t.adesivadorId, t.total]) ?? []),
    [semana],
  )
  const linhas = useMemo(() => (semana ? montarLinhas(semana) : []), [semana])
  const layout = useMemo(
    () => (semana ? montarLayout(semana, linhas.length, previa) : new Map<number, LayoutColuna>()),
    [semana, linhas.length, previa],
  )
  const pendentes = useMemo(
    () => semana?.dias.reduce((soma, dia) => soma + dia.agendamentos.filter((a) => !a.material).length, 0) ?? 0,
    [semana],
  )

  if (carregando && !semana) return <Carregando texto="Carregando agenda..." />
  if (erro) return <Aviso tipo="erro">{erro}</Aviso>
  if (!semana) return null

  const faixasPorDia = semana.faixas.length
  const capacidadeSemanal = semana.horasUteisDaSemana

  /** Duplo clique/toque abre, clique simples seleciona. */
  function clicarCard(item: Agendamento) {
    if (Date.now() < ignorarCliqueAte.current) return
    if (ehSegundoToque(`card:${item.id}`)) setSelecionado(item)
    else setSelecao({ tipo: 'card', item })
  }

  function clicarFaixaLivre(celula: AlvoSolte) {
    if (ehSegundoToque(`celula:${chaveDoAlvo(celula.data, celula.adesivadorId, celula.slot)}`)) {
      setNovaCelula(celula)
    } else {
      setSelecao({ tipo: 'celula', ...celula })
    }
  }

  return (
    <>
      <div className="cabecalho-pagina">
        <div>
          <h1>Agenda dos adesivadores</h1>
          <p>
            {formatarDia(semana.inicio)} a {formatarDia(semana.fim)} · {formatarHoras(semana.horasUteisDaSemana)} úteis
            por adesivador (07:30–18:00, sexta até 17:00, sem o almoço) · {pendentes} carro(s) sem OS
          </p>
        </div>
        <div className="acoes-linha">
          {podeEditar && (
            <button
              className="botao botao-secundario"
              disabled={!semana.ultimaAcao}
              title={
                semana.ultimaAcao ? `Desfazer: ${semana.ultimaAcao} (Ctrl+Z)` : 'Nada para desfazer nas últimas 24 horas'
              }
              onClick={desfazer}
            >
              ↶ Desfazer
            </button>
          )}
          {pode(usuario, 'colunasAgenda') && (
            <button className="botao botao-secundario" onClick={() => setEditandoColunas(true)}>
              ✎ Editar adesivadores
            </button>
          )}
          <label className="alternador">
            <input type="checkbox" checked={somenteSemOs} onChange={(e) => setSomenteSemOs(e.target.checked)} />
            Destacar sem OS
          </label>
          <button className="botao botao-secundario" onClick={() => setPeriodo(outraSemana(periodo, -1))}>
            ← Semana
          </button>
          <button className="botao botao-secundario" onClick={() => setPeriodo(outraSemana(periodo, 1))}>
            Semana →
          </button>
        </div>
      </div>

      <NavegacaoMes periodo={periodo} aoMudar={setPeriodo} />

      {podeEditar && (
        <div className="instrucao-arrastar">
          <span className="icone-arrastar">✥</span>
          <span>
            <strong>Um clique seleciona, dois cliques abrem</strong> o carro para editar.{' '}
            <strong>Arraste</strong> para outro horário — soltando em cima de outro, os dois{' '}
            <strong>trocam de lugar</strong>. No tablet, <strong>segure o dedo</strong> no carro até ele
            &quot;pegar&quot; e arraste. Puxe a barrinha da borda de baixo até a linha onde o serviço deve{' '}
            <strong>terminar</strong>, mesmo em outro dia. Com um carro selecionado, <strong>Ctrl+C</strong>{' '}
            copia e <strong>Ctrl+X</strong> recorta; selecione uma faixa livre e <strong>Ctrl+V</strong> cola. <strong>Delete</strong> exclui o carro selecionado,{' '}
            <strong>Ctrl+Z</strong> desfaz e{' '}
            <strong>Esc</strong> limpa a seleção.
          </span>
        </div>
      )}

      {copiado && (
        <div className="barra-copia">
          <span className="icone-copia">📋</span>
          <span>
            {recortado ? (
              <>
                <strong>{copiado.descricao}</strong> recortado — clique numa faixa livre e cole: o carro é movido
                para lá, com OS, status e score.
              </>
            ) : (
              <>
                <strong>{copiado.descricao}</strong> copiado — clique numa faixa livre e cole. O vínculo com a OS
                vem junto.
              </>
            )}
          </span>
          <button
            className="botao"
            disabled={selecao?.tipo !== 'celula'}
            onClick={() => selecao?.tipo === 'celula' && colarEm(selecao)}
          >
            {selecao?.tipo === 'celula' ? 'Colar aqui' : 'Selecione a faixa'}
          </button>
          <button
            className="botao botao-secundario"
            onClick={() => {
              setCopiado(null)
              setRecortado(false)
            }}
          >
            Cancelar
          </button>
        </div>
      )}

      {aviso && <Aviso tipo="info">{aviso}</Aviso>}
      {erroAcao && <Aviso tipo="erro">{erroAcao}</Aviso>}

      <div className="cartao">
        <div className="tabela-rolagem">
          <table className={`grade-agenda${arrastando ? ' arrastando' : ''}`}>
            <thead>
              <tr>
                <th className="col-dia">Dia</th>
                <th className="col-horario">Horário</th>
                {semana.colunas.map((coluna) => {
                  const horas = cargaPorColuna.get(coluna.id) ?? 0
                  const bloqueadas = bloqueioPorColuna.get(coluna.id) ?? 0
                  const disponivel = Math.max(0, capacidadeSemanal - bloqueadas)
                  const proporcao = disponivel === 0 ? 100 : Math.min(100, (horas / disponivel) * 100)
                  return (
                    <th key={coluna.id}>
                      {coluna.nome}
                      <span className="carga-cabecalho">
                        {formatarHoras(horas)} / {formatarHoras(disponivel)}
                      </span>
                      {bloqueadas > 0 && <span className="carga-bloqueada">−{formatarHoras(bloqueadas)} indisp.</span>}
                      <span className="barra-carga">
                        <span
                          className={`barra-carga-preenchida${proporcao >= 95 ? ' cheia' : ''}`}
                          style={{ width: `${proporcao}%` }}
                        />
                      </span>
                    </th>
                  )
                })}
              </tr>
            </thead>
            <tbody ref={corpoRef}>
              {linhas.map((linha, indiceLinha) => (
                <tr
                  key={indiceLinha}
                  className={`${linha.primeiraDoDia ? 'inicio-do-dia' : ''}${linha.almoco || linha.fechada ? ' linha-almoco' : ''}`}
                >
                  {linha.primeiraDoDia && (
                    <th className="col-dia" rowSpan={faixasPorDia}>
                      <div className="dia-nome">{linha.diaSemana}</div>
                      <div className="dia-data">{formatarDia(linha.data)}</div>
                    </th>
                  )}
                  <th className="col-horario">
                    {linha.rotulo}
                    {linha.almoco && <span className="marca-almoco">almoço</span>}
                    {linha.fechada && <span className="marca-almoco">fechado</span>}
                  </th>
                  {semana.colunas.map((coluna) => {
                    const daColuna = layout.get(coluna.id)
                    const inicio = daColuna?.inicios.get(indiceLinha)
                    if (!inicio && daColuna?.cobertas.has(indiceLinha)) return null

                    const celula: AlvoSolte = { data: linha.data, adesivadorId: coluna.id, slot: linha.faixa }
                    const chave = chaveDoAlvo(linha.data, coluna.id, linha.faixa)

                    if (!inicio) {
                      const selecionada =
                        selecao?.tipo === 'celula' &&
                        selecao.data === linha.data &&
                        selecao.adesivadorId === coluna.id &&
                        selecao.slot === linha.faixa
                      return (
                        <td
                          key={coluna.id}
                          // O almoço nunca recebe serviço: não é alvo de soltar.
                          data-alvo={linha.almoco || linha.fechada ? undefined : chave}
                          className={`celula-vazia${alvo === chave ? ' alvo-solte' : ''}${
                            linha.almoco || linha.fechada ? ' celula-almoco' : ''
                          }${selecionada ? ' celula-selecionada' : ''}`}
                          onDragOver={(e) => {
                            if (!arrastando || linha.almoco || linha.fechada) return
                            e.preventDefault()
                            setAlvo(chave)
                          }}
                          onDragLeave={() => setAlvo((a) => (a === chave ? null : a))}
                          onDrop={(e) => {
                            e.preventDefault()
                            if (!linha.almoco && !linha.fechada) soltar(celula)
                          }}
                          onClick={podeEditar && !linha.almoco && !linha.fechada ? () => clicarFaixaLivre(celula) : undefined}
                        >
                          {podeEditar && !linha.almoco && !linha.fechada && <span className="mais">{copiado ? '📋' : '+'}</span>}
                        </td>
                      )
                    }

                    const { itens, span } = inicio
                    return (
                      <td
                        key={coluna.id}
                        rowSpan={span}
                        data-alvo={chave}
                        className={`celula-faixa${alvo === chave ? ' alvo-troca' : ''}${
                          itens.length > 1 ? ' faixa-dividida' : ''
                        }`}
                        onDragOver={(e) => {
                          if (!arrastando || itens.some((i) => i.id === arrastando.id)) return
                          e.preventDefault()
                          setAlvo(chave)
                        }}
                        onDragLeave={() => setAlvo((a) => (a === chave ? null : a))}
                        onDrop={(e) => {
                          e.preventDefault()
                          soltar(celula)
                        }}
                      >
                        {itens.map((item) => (
                          <CardAgenda
                            key={item.id}
                            item={item}
                            faixas={semana.faixas}
                            indiceLinha={indiceLinha}
                            span={span}
                            geometria={geometria}
                            pausaNaLinha={(l) => !!linhas[l] && (linhas[l].almoco || linhas[l].fechada)}
                            podeEditar={podeEditar}
                            selecionado={selecao?.tipo === 'card' && selecao.item.id === item.id}
                            recortado={recortado && copiado?.id === item.id}
                            emArrasto={arrastando?.id === item.id}
                            horasEmPrevia={previa && previa.id === item.id ? previa.horas : null}
                            destacarSemOs={somenteSemOs}
                            arrastavel={podeEditar && previa === null}
                            comecouEm={item.data < semana.inicio ? item.data : null}
                            aoComecarArraste={(e) => {
                              setErroAcao(null)
                              setArrastando(item)
                              e.dataTransfer.effectAllowed = 'move'
                              e.dataTransfer.setData('text/plain', String(item.id))
                            }}
                            aoTerminarArraste={() => {
                              setArrastando(null)
                              setAlvo(null)
                            }}
                            aoClicar={() => clicarCard(item)}
                            aoTocar={(e) => aoTocarCard(e, item)}
                            aoPuxarBorda={(e) => iniciarRedimensionar(e, item, indiceLinha)}
                            aoPuxarTopo={(e) =>
                              iniciarRedimensionar(e, item, indiceLinha, {
                                linhaFinal: linhaFinalDe(semana.faixas, item, indiceLinha),
                                limite: primeiraLivreAcima(daColuna, indiceLinha),
                              })
                            }
                          />
                        ))}
                      </td>
                    )
                  })}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      <div className="legenda-agenda">
        {(['PROGRAMADO', 'EM_PATIO', 'EXECUTANDO', 'CONCLUIDO', 'NAO_VEIO', 'EXTERNO'] as StatusAgendamento[]).map(
          (s) => (
            <span key={s} className={`chip ag-${s.toLowerCase()}`}>
              {rotuloStatusAgenda(s)}
            </span>
          ),
        )}
      </div>

      {editandoColunas && (
        <EditarAdesivadores
          aoFechar={() => {
            setEditandoColunas(false)
            carregar()
          }}
        />
      )}

      {(selecionado || novaCelula) && (
        <DetalheAgendamento
          agendamento={selecionado}
          novaCelula={novaCelula && { data: novaCelula.data, adesivadorId: novaCelula.adesivadorId, slot: novaCelula.slot }}
          faixas={semana.faixas}
          podeEditar={podeEditar}
          podeMudarStatus={podeEditar}
          aoFechar={() => {
            setSelecionado(null)
            setNovaCelula(null)
          }}
          aoSalvar={() => {
            setSelecionado(null)
            setNovaCelula(null)
            carregar()
          }}
        />
      )}
    </>
  )
}
