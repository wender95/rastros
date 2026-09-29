import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { api } from '../api/client'
import { useMudancas } from '../api/mudancas'
import { semanasDoMes } from '../api/periodos'
import type { Periodo } from '../api/periodos'
import type { SemanaAgenda } from '../api/tipos'

/**
 * As semanas do mês na tela, sempre em dia.
 *
 * A agenda se atualiza sozinha **na hora** em que algo muda no servidor (o adesivador
 * concluiu no celular, outra pessoa mexeu na agenda) e, por garantia, a cada minuto e ao
 * voltar para a aba.
 *
 * Ela espera a vez quando alguém está mexendo (`mexendo`) — arrastando, escolhendo espaços,
 * com o menu, a janela ou o campo de nome abertos —, senão o trabalho sumiria da mão.
 */
export function useSemanasDoMes(mes: Periodo, mexendo: boolean) {
  const [semanas, setSemanas] = useState<SemanaAgenda[] | null>(null)
  const [erro, setErro] = useState<string | null>(null)
  const [carregando, setCarregando] = useState(true)

  const periodos = useMemo(() => {
    const d = new Date(`${mes.inicio}T12:00:00`)
    return semanasDoMes(d.getFullYear(), d.getMonth())
  }, [mes.inicio])

  const carregar = useCallback(
    (silencioso = false) => {
      if (!silencioso) setCarregando(true)
      setErro(null)
      Promise.all(periodos.map((p) => api.get<SemanaAgenda>(`/agenda/semana?data=${p.inicio}`)))
        .then(setSemanas)
        .catch((e) => {
          // Na atualização automática, um tropeço de rede não pode apagar a tela.
          if (!silencioso) setErro(e instanceof Error ? e.message : 'Falha ao carregar a agenda.')
        })
        .finally(() => {
          if (!silencioso) setCarregando(false)
        })
    },
    [periodos],
  )

  useEffect(() => carregar(), [carregar])

  const mexendoRef = useRef(mexendo)
  mexendoRef.current = mexendo

  // Aviso que chega com alguém mexendo fica guardado e é atendido quando a pessoa termina.
  const mudouEnquantoMexia = useRef(false)
  useMudancas(() => {
    if (mexendoRef.current) mudouEnquantoMexia.current = true
    else carregar(true)
  })
  useEffect(() => {
    if (!mexendo && mudouEnquantoMexia.current) {
      mudouEnquantoMexia.current = false
      carregar(true)
    }
  }, [mexendo, carregar])

  useEffect(() => {
    // `agora`: quando a pessoa volta para a aba, busca na hora. O relógio, em segundo
    // plano, espera a aba estar à vista — não adianta gastar rede numa tela que ninguém vê.
    const atualizar = (agora = false) => {
      if (mexendoRef.current) return
      if (!agora && document.hidden) return
      carregar(true)
    }
    const relogio = window.setInterval(() => atualizar(), 60_000)
    const aoVoltar = () => atualizar(true)
    const aoMudarVisibilidade = () => {
      if (!document.hidden) atualizar(true)
    }
    window.addEventListener('focus', aoVoltar)
    document.addEventListener('visibilitychange', aoMudarVisibilidade)
    return () => {
      window.clearInterval(relogio)
      window.removeEventListener('focus', aoVoltar)
      document.removeEventListener('visibilitychange', aoMudarVisibilidade)
    }
  }, [carregar])

  return { semanas, erro, carregando, carregar }
}
