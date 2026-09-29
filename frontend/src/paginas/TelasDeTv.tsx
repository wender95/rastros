import { useEffect, useState, type ReactNode } from 'react'
import { AgendasDoDia, LinhaDoDia, OsNoAcabamento } from '../componentes/PainelAoVivo'

/**
 * Uma tela para deixar numa TV da fábrica: título grande, data, hora e tempo, e o conteúdo
 * se atualizando sozinho. Em tela cheia (F11 ou o botão), o topo do sistema some e sobra só
 * o painel.
 */
function TelaDeTv({ titulo, children }: { titulo: string; children: ReactNode }) {
  const [cheia, setCheia] = useState(() => !!document.fullscreenElement)

  useEffect(() => {
    const aoMudar = () => setCheia(!!document.fullscreenElement)
    document.addEventListener('fullscreenchange', aoMudar)
    return () => document.removeEventListener('fullscreenchange', aoMudar)
  }, [])

  const alternar = () =>
    (document.fullscreenElement ? document.exitFullscreen() : document.documentElement.requestFullscreen()).catch(
      () => undefined,
    )

  return (
    <div className="tela-tv">
      <header className="tela-tv-topo">
        <div>
          <h1>{titulo}</h1>
          <LinhaDoDia />
        </div>
        <button className="botao botao-secundario" onClick={alternar} title="A TV mostra só o painel">
          {cheia ? '✕ Sair da tela cheia' : '⛶ Tela cheia'}
        </button>
      </header>
      <div className="tela-tv-conteudo">{children}</div>
    </div>
  )
}

/** A agenda de hoje de cada adesivador (e do Noturno, quando tem serviço). */
export function TvAdesivadores() {
  return (
    <TelaDeTv titulo="Agenda dos adesivadores">
      <AgendasDoDia />
    </TelaDeTv>
  )
}

/** As OS que estão no Acabamento, da que chegou primeiro para a mais nova. */
export function TvAcabamento() {
  return (
    <TelaDeTv titulo="Disponíveis para o Acabamento">
      <OsNoAcabamento />
    </TelaDeTv>
  )
}
