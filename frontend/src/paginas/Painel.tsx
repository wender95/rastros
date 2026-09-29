import { useEffect, useState } from 'react'
import { api } from '../api/client'
import { hojeIso, mesDe, nomeDoMes, outraSemana, outroMes, paraIso, rotuloPeriodo, semanaNoMes } from '../api/periodos'
import type { Periodo } from '../api/periodos'
import { useMudancas } from '../api/mudancas'
import type { Painel as PainelDados } from '../api/tipos'
import { AgendasDoDia, LinhaDoDia, OsNoAcabamento } from '../componentes/PainelAoVivo'
import { Aviso, Carregando, Vazio, rotuloSetor } from '../componentes/Ui'

type Escala = 'dia' | 'semana' | 'mes' | 'ano'

const ESCALAS: { chave: Escala; rotulo: string }[] = [
  { chave: 'dia', rotulo: 'Dia' },
  { chave: 'semana', rotulo: 'Semana' },
  { chave: 'mes', rotulo: 'Mês' },
  { chave: 'ano', rotulo: 'Ano' },
]

const DIAS = ['domingo', 'segunda', 'terça', 'quarta', 'quinta', 'sexta', 'sábado']
const comoData = (iso: string) => new Date(`${iso}T12:00:00`)

/** O período da escala que contém a data. A semana é cortada no mês, como no resto do sistema. */
function periodoDe(escala: Escala, iso: string): Periodo {
  const d = comoData(iso)
  if (escala === 'dia') return { inicio: iso, fim: iso }
  if (escala === 'semana') return semanaNoMes(iso)
  if (escala === 'mes') return mesDe(iso)
  return { inicio: `${d.getFullYear()}-01-01`, fim: `${d.getFullYear()}-12-31` }
}

/** O período anterior ou seguinte na mesma escala. */
function andar(escala: Escala, atual: Periodo, passo: 1 | -1): Periodo {
  if (escala === 'dia') {
    const d = comoData(atual.inicio)
    d.setDate(d.getDate() + passo)
    return periodoDe('dia', paraIso(d))
  }
  if (escala === 'semana') return outraSemana(atual, passo)
  if (escala === 'mes') return mesDe(outroMes(atual.inicio, passo))
  const ano = comoData(atual.inicio).getFullYear() + passo
  return periodoDe('ano', `${ano}-01-01`)
}

function rotulo(escala: Escala, p: Periodo) {
  const d = comoData(p.inicio)
  if (escala === 'dia') return `${p.inicio === hojeIso() ? 'Hoje' : DIAS[d.getDay()]}, ${d.toLocaleDateString('pt-BR')}`
  if (escala === 'semana') return `${rotuloPeriodo(p)} · ${nomeDoMes(p.inicio)}`
  if (escala === 'mes') return nomeDoMes(p.inicio)
  return String(d.getFullYear())
}

/** O dia útil anterior ou seguinte: a agenda não tem sábado nem domingo. */
function outroDiaUtil(iso: string, passo: 1 | -1): string {
  const d = comoData(iso)
  do d.setDate(d.getDate() + passo)
  while (d.getDay() === 0 || d.getDay() === 6)
  return paraIso(d)
}

/** "Hoje", "Ontem", "Amanhã" ou "segunda, 29/09". */
function nomeDoDia(iso: string): string {
  const hoje = hojeIso()
  if (iso === hoje) return 'Hoje'
  if (iso === outroDia(hoje, -1)) return 'Ontem'
  if (iso === outroDia(hoje, 1)) return 'Amanhã'
  const d = comoData(iso)
  return `${DIAS[d.getDay()]}, ${d.toLocaleDateString('pt-BR', { day: '2-digit', month: '2-digit' })}`
}

function outroDia(iso: string, passo: number): string {
  const d = comoData(iso)
  d.setDate(d.getDate() + passo)
  return paraIso(d)
}

/**
 * Anda de um dia para o outro: setas (pulando o fim de semana), calendário para escolher a
 * data e o botão Hoje.
 */
function NavegadorDeDia({ dia, aoMudar }: { dia: string; aoMudar: (dia: string) => void }) {
  return (
    <div className="navegador-de-dia">
      <button className="botao botao-secundario" onClick={() => aoMudar(outroDiaUtil(dia, -1))} aria-label="Dia anterior">
        ‹
      </button>
      <input
        type="date"
        className="campo-data"
        value={dia}
        onChange={(e) => e.target.value && aoMudar(e.target.value)}
        aria-label="Escolher o dia"
      />
      <button className="botao botao-secundario" onClick={() => aoMudar(outroDiaUtil(dia, 1))} aria-label="Próximo dia">
        ›
      </button>
      {dia !== hojeIso() && (
        <button className="botao botao-secundario" onClick={() => aoMudar(hojeIso())}>
          Hoje
        </button>
      )}
    </div>
  )
}

const NO_PERIODO: Record<Escala, string> = { dia: 'no dia', semana: 'na semana', mes: 'no mês', ano: 'no ano' }

/**
 * O painel: a fotografia de agora — OS ativas, a agenda de hoje de cada adesivador (com o
 * projeto em que cada um está) e as OS esperando no Acabamento — e o que aconteceu no
 * período escolhido: dia, semana, mês ou ano, com OS abertas, concluídas, canceladas e
 * quantas saíram de cada setor.
 */
export default function Painel() {
  const [escala, setEscala] = useState<Escala>('dia')
  const [periodo, setPeriodo] = useState<Periodo>(() => periodoDe('dia', hojeIso()))
  const [dados, setDados] = useState<PainelDados | null>(null)
  /** O dia da agenda dos adesivadores no painel (hoje, ou o escolhido no navegador). */
  const [diaDaAgenda, setDiaDaAgenda] = useState(hojeIso)
  const [erro, setErro] = useState<string | null>(null)
  /** Muda a cada aviso do servidor: os números do painel se refazem sozinhos. */
  const [versao, setVersao] = useState(0)
  useMudancas(() => setVersao((v) => v + 1))

  useEffect(() => {
    api
      .get<PainelDados>(`/painel?inicio=${periodo.inicio}&fim=${periodo.fim}`)
      .then((d) => {
        setDados(d)
        setErro(null)
      })
      .catch((e) => setErro(e instanceof Error ? e.message : 'Falha ao carregar o painel.'))
  }, [periodo, versao])

  function trocarEscala(nova: Escala) {
    setEscala(nova)
    // Mantém a referência: do dia 15/09 em "Mês" vai para setembro, e assim por diante.
    const referencia = periodo.inicio <= hojeIso() && hojeIso() <= periodo.fim ? hojeIso() : periodo.inicio
    setPeriodo(periodoDe(nova, referencia))
  }

  if (erro) return <Aviso tipo="erro">{erro}</Aviso>
  if (!dados) return <Carregando />

  const agora = [
    { rotulo: 'OS ativas', valor: dados.fluxosAtivos },
    { rotulo: 'Aguardando recebimento', valor: dados.aguardandoRecebimento },
    { rotulo: 'Em processamento', valor: dados.emProcessamento },
  ]
  const doPeriodo = [
    { rotulo: `Abertas ${NO_PERIODO[escala]}`, valor: dados.osAbertas },
    { rotulo: `Concluídas ${NO_PERIODO[escala]}`, valor: dados.concluidas },
    { rotulo: `Canceladas ${NO_PERIODO[escala]}`, valor: dados.canceladas },
  ]

  return (
    <>
      <div className="cabecalho-pagina">
        <div>
          <h1>Painel operacional</h1>
          <LinhaDoDia />
        </div>
      </div>

      <div className="titulo-secao painel-grupo">Agora</div>
      <div className="grade-kpis">
        {agora.map((kpi) => (
          <div className="kpi" key={kpi.rotulo}>
            <div className="valor mono">{kpi.valor}</div>
            <div className="rotulo">{kpi.rotulo}</div>
          </div>
        ))}
      </div>

      <div className="cartao painel-bloco">
        <div className="titulo-secao titulo-com-acoes">
          <span>Agenda dos adesivadores · {nomeDoDia(diaDaAgenda)}</span>
          <NavegadorDeDia dia={diaDaAgenda} aoMudar={setDiaDaAgenda} />
        </div>
        <AgendasDoDia dia={diaDaAgenda} />
      </div>

      <div className="cartao painel-bloco">
        <div className="titulo-secao">Disponíveis para o Acabamento</div>
        <OsNoAcabamento />
      </div>

      <div className="painel-periodo">
        <div className="nav-mes-semanas" role="tablist">
          {ESCALAS.map((e) => (
            <button
              key={e.chave}
              role="tab"
              aria-selected={escala === e.chave}
              className={`chip-periodo${escala === e.chave ? ' ativo' : ''}`}
              onClick={() => trocarEscala(e.chave)}
            >
              {e.rotulo}
            </button>
          ))}
        </div>
        <div className="nav-mes-topo">
          <button className="botao botao-secundario" onClick={() => setPeriodo(andar(escala, periodo, -1))} aria-label="Anterior">
            ‹
          </button>
          <strong className="nav-mes-nome">{rotulo(escala, periodo)}</strong>
          {/* Calendário: qualquer data leva ao período dela na escala escolhida. */}
          <input
            type="date"
            className="campo-data"
            value={periodo.inicio}
            onChange={(e) => e.target.value && setPeriodo(periodoDe(escala, e.target.value))}
            aria-label="Escolher a data do período"
          />
          <button className="botao botao-secundario" onClick={() => setPeriodo(andar(escala, periodo, 1))} aria-label="Próximo">
            ›
          </button>
          <button className="botao botao-secundario" onClick={() => setPeriodo(periodoDe(escala, hojeIso()))}>
            Hoje
          </button>
        </div>
      </div>

      <div className="titulo-secao painel-grupo">{rotulo(escala, periodo)}</div>
      <div className="grade-kpis">
        {doPeriodo.map((kpi) => (
          <div className="kpi" key={kpi.rotulo}>
            <div className="valor mono">{kpi.valor}</div>
            <div className="rotulo">{kpi.rotulo}</div>
          </div>
        ))}
      </div>

      <div className="cartao">
        <div className="titulo-secao">Por setor</div>
        {dados.porSetor.length === 0 ? (
          <Vazio>Nenhuma OS ativa e nenhuma saída no período.</Vazio>
        ) : (
          <div className="grade-setores">
            {dados.porSetor.map((setor) => (
              <div className="setor-card" key={setor.setor}>
                <div className="nome">{rotuloSetor(setor.setor)}</div>
                <div className="numeros">
                  <div title="Agora">
                    <b className="mono">{setor.aguardando}</b>
                    na fila
                  </div>
                  <div title="Agora">
                    <b className="mono">{setor.emProcessamento}</b>
                    em curso
                  </div>
                  <div title={`Saíram do setor ${NO_PERIODO[escala]}`}>
                    <b className="mono">{setor.saidasNoPeriodo}</b>
                    saíram {NO_PERIODO[escala]}
                  </div>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </>
  )
}
