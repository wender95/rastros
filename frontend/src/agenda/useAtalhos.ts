import { useEffect, useRef } from 'react'
import type { Agendamento } from '../api/tipos'
import type { AlvoSolte } from './useArrasteToque'

export type Selecao = { tipo: 'card'; item: Agendamento } | ({ tipo: 'celula' } & AlvoSolte) | null

interface Opcoes {
  habilitado: boolean
  selecao: Selecao
  copiado: Agendamento | null
  aoCopiar: (item: Agendamento) => void
  aoRecortar: (item: Agendamento) => void
  aoColar: (alvo: AlvoSolte) => void
  aoDesfazer: () => void
  aoExcluir: (item: Agendamento) => void
  aoLimpar: () => void
  aoAvisar: (mensagem: string) => void
}

/**
 * Ctrl+C copia o carro selecionado, Ctrl+X recorta (o Ctrl+V então o move para a faixa
 * escolhida), Ctrl+V cola na faixa selecionada, Delete exclui o carro
 * selecionado, Ctrl+Z desfaz e Esc limpa a seleção. Nada disso age enquanto o foco está
 * num campo de texto.
 */
export function useAtalhos(opcoes: Opcoes) {
  const atuais = useRef(opcoes)
  atuais.current = opcoes

  useEffect(() => {
    function aoTeclar(e: KeyboardEvent) {
      const o = atuais.current
      if (!o.habilitado) return
      const alvo = e.target as HTMLElement
      if (['INPUT', 'TEXTAREA', 'SELECT'].includes(alvo.tagName)) return

      if (e.key === 'Escape') {
        o.aoLimpar()
        return
      }
      // Delete (ou a tecla "delete" do Mac, que chega como Backspace) exclui o carro
      // selecionado. Sem confirmação: o Ctrl+Z traz de volta.
      if ((e.key === 'Delete' || e.key === 'Backspace') && !e.ctrlKey && !e.metaKey && !e.altKey) {
        if (o.selecao?.tipo !== 'card') {
          if (e.key === 'Delete') o.aoAvisar('Clique num carro para selecioná-lo antes de excluir.')
          return
        }
        e.preventDefault()
        o.aoExcluir(o.selecao.item)
        return
      }
      if (!(e.ctrlKey || e.metaKey)) return
      const tecla = e.key.toLowerCase()

      if (tecla === 'c' || tecla === 'x') {
        if (o.selecao?.tipo !== 'card') {
          o.aoAvisar(`Clique num carro para selecioná-lo antes de ${tecla === 'c' ? 'copiar' : 'recortar'}.`)
          return
        }
        e.preventDefault()
        if (tecla === 'c') o.aoCopiar(o.selecao.item)
        else o.aoRecortar(o.selecao.item)
      } else if (tecla === 'v') {
        if (!o.copiado) return
        if (o.selecao?.tipo !== 'celula') {
          o.aoAvisar('Clique numa faixa livre para escolher onde colar.')
          return
        }
        e.preventDefault()
        o.aoColar(o.selecao)
      } else if (tecla === 'z') {
        e.preventDefault()
        o.aoDesfazer()
      }
    }
    window.addEventListener('keydown', aoTeclar)
    return () => window.removeEventListener('keydown', aoTeclar)
  }, [])
}

/**
 * Dois cliques (ou dois toques) no mesmo lugar em menos de 400 ms. Feito à mão porque
 * no tablet o duplo toque do navegador vira zoom e não chega como duplo clique.
 */
export function useSegundoToque() {
  const ultimo = useRef<{ chave: string; quando: number } | null>(null)
  return (chave: string) => {
    const agora = Date.now()
    const anterior = ultimo.current
    const segundo = anterior !== null && anterior.chave === chave && agora - anterior.quando < 400
    ultimo.current = segundo ? null : { chave, quando: agora }
    return segundo
  }
}
