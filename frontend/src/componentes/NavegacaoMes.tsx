import type { Periodo } from '../api/periodos'
import { hojeIso, mesDe, nomeDoMes, outroMes, rotuloPeriodo, semanaNoMes, semanasDoMes } from '../api/periodos'

/**
 * Navegação por mês, com a semana dentro dele — como as abas da planilha. As semanas são
 * cortadas na virada do mês; com `comMesInteiro`, dá para ver o mês todo de uma vez.
 */
export default function NavegacaoMes({
  periodo,
  aoMudar,
  comMesInteiro = false,
}: {
  periodo: Periodo
  aoMudar: (p: Periodo) => void
  comMesInteiro?: boolean
}) {
  const inicio = new Date(`${periodo.inicio}T12:00:00`)
  const semanas = semanasDoMes(inicio.getFullYear(), inicio.getMonth())
  const mes = mesDe(periodo.inicio)
  const mesInteiro = periodo.inicio === mes.inicio && periodo.fim === mes.fim

  const irParaMes = (passo: number) => {
    const dia = outroMes(periodo.inicio, passo)
    aoMudar(mesInteiro ? mesDe(dia) : semanaNoMes(dia))
  }

  return (
    <div className="nav-mes">
      <div className="nav-mes-topo">
        <button className="botao botao-secundario" onClick={() => irParaMes(-1)} aria-label="Mês anterior">
          ‹
        </button>
        <strong className="nav-mes-nome">{nomeDoMes(periodo.inicio)}</strong>
        <button className="botao botao-secundario" onClick={() => irParaMes(1)} aria-label="Próximo mês">
          ›
        </button>
        <button
          className="botao botao-secundario"
          onClick={() => aoMudar(mesInteiro ? mesDe(hojeIso()) : semanaNoMes(hojeIso()))}
        >
          Hoje
        </button>
      </div>
      <div className="nav-mes-semanas" role="tablist">
        {comMesInteiro && (
          <button
            role="tab"
            aria-selected={mesInteiro}
            className={`chip-periodo${mesInteiro ? ' ativo' : ''}`}
            onClick={() => aoMudar(mes)}
          >
            Mês inteiro
          </button>
        )}
        {semanas.map((s, i) => {
          const ativa = !mesInteiro && s.inicio === periodo.inicio
          return (
            <button
              key={s.inicio}
              role="tab"
              aria-selected={ativa}
              className={`chip-periodo${ativa ? ' ativo' : ''}`}
              onClick={() => aoMudar(s)}
              title={`Semana ${i + 1}`}
            >
              <span className="chip-periodo-num">Sem {i + 1}</span> {rotuloPeriodo(s)}
            </button>
          )
        })}
      </div>
    </div>
  )
}
