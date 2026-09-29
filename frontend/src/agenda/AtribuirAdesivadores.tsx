import { useState } from 'react'
import type { Adesivador, Agendamento } from '../api/tipos'

interface Props {
  item: Agendamento
  /** Os adesivadores da agenda (sem Encaixe e Noturno). */
  adesivadores: Adesivador[]
  aoFechar: () => void
  aoSalvar: (adesivadorIds: number[]) => void
}

/**
 * Coluna Noturno: quem vai fazer o serviço à noite. Os nomes aparecem no card do Noturno
 * no painel dos adesivadores (e na TV). Vale para todas as cópias do serviço.
 */
export default function AtribuirAdesivadores({ item, adesivadores, aoFechar, aoSalvar }: Props) {
  const [escolhidos, setEscolhidos] = useState<number[]>(() => (item.atribuidos ?? []).map((a) => a.id))
  const alternar = (id: number, marcado: boolean) =>
    setEscolhidos((atual) => (marcado ? [...atual, id] : atual.filter((x) => x !== id)))

  return (
    <div className="modal-fundo" onClick={aoFechar}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <h2>🌙 Atribuir adesivadores</h2>
        <p className="subtitulo">
          <strong>{item.descricao}</strong> — quem vai fazer este serviço no Noturno. Aparece no card do Noturno no
          painel dos adesivadores.
        </p>
        <div className="lista-secoes">
          {adesivadores.map((a) => (
            <label key={a.id} className="secao-opcao">
              <input
                type="checkbox"
                checked={escolhidos.includes(a.id)}
                onChange={(e) => alternar(a.id, e.target.checked)}
              />
              <span>{a.nome}</span>
            </label>
          ))}
        </div>
        <div className="rodape-modal">
          <button className="botao botao-secundario" onClick={aoFechar}>
            Cancelar
          </button>
          <button className="botao" onClick={() => aoSalvar(escolhidos)}>
            Salvar
          </button>
        </div>
      </div>
    </div>
  )
}
