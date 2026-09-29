import { useEffect, useRef } from 'react'
import { tokenStorage } from './client'

/**
 * Avisos de mudança em tempo real. O servidor manda um "mudou" a cada gravação na agenda ou
 * nas OS, e a tela busca de novo na hora — o painel vê o adesivador concluir sem F5.
 *
 * Uma conexão só por aba, dividida por todas as telas abertas nela. Não é o EventSource do
 * navegador porque ele não manda o cabeçalho de login: a leitura é feita pelo fetch, que
 * manda. Se a conexão cair (servidor reiniciado, rede), ela volta sozinha e a tela busca de
 * novo, porque pode ter perdido algum aviso enquanto estava fora.
 */

/** Recebe os assuntos do aviso ("agenda,os"), ou '' quando pode ter perdido algum. */
type Ouvinte = (assuntos: string) => void

const ouvintes = new Set<Ouvinte>()
let conexao: AbortController | null = null
let tentativas = 0
let jaConectou = false
let religar: number | undefined

const avisarTodos = (assuntos = '') => ouvintes.forEach((o) => o(assuntos))

function tratar(bloco: string) {
  const linhas = bloco.split('\n')
  const evento = linhas.find((l) => l.startsWith('event:'))?.slice(6).trim()
  const dados = linhas.find((l) => l.startsWith('data:'))?.slice(5).trim() ?? ''
  if (evento === 'mudou') avisarTodos(dados)
}

function conectar() {
  if (conexao || ouvintes.size === 0) return
  const token = tokenStorage.ler()
  if (!token) return
  const esta = new AbortController()
  conexao = esta
  let sessaoRecusada = false

  fetch('/api/mudancas', {
    headers: { Authorization: `Bearer ${token}`, Accept: 'text/event-stream' },
    signal: esta.signal,
    cache: 'no-store',
  })
    .then(async (resposta) => {
      if (resposta.status === 401 || resposta.status === 403) {
        sessaoRecusada = true // sem sessão, não adianta insistir; o login novo reabre
        return
      }
      if (!resposta.ok || !resposta.body) throw new Error(`HTTP ${resposta.status}`)
      tentativas = 0
      if (jaConectou) avisarTodos() // voltou depois de uma queda: pode ter perdido algo
      jaConectou = true

      const leitor = resposta.body.pipeThrough(new TextDecoderStream()).getReader()
      let resto = ''
      for (;;) {
        const { value, done } = await leitor.read()
        if (done) break
        resto += value.replace(/\r\n/g, '\n')
        let fim: number
        while ((fim = resto.indexOf('\n\n')) >= 0) {
          tratar(resto.slice(0, fim))
          resto = resto.slice(fim + 2)
        }
      }
    })
    .catch(() => undefined)
    .finally(() => {
      if (conexao === esta) conexao = null
      if (sessaoRecusada || esta.signal.aborted || ouvintes.size === 0) return
      // Religa com espera crescente: 1 s, 2 s, 4 s… até 30 s.
      window.clearTimeout(religar)
      religar = window.setTimeout(conectar, Math.min(30_000, 1000 * 2 ** tentativas++))
    })
}

/** Ouve os avisos fora de um componente (ex.: a legenda da agenda, guardada no módulo). */
export function ouvirMudancas(ouvinte: Ouvinte) {
  return ouvir(ouvinte)
}

function ouvir(ouvinte: Ouvinte) {
  ouvintes.add(ouvinte)
  conectar()
  return () => {
    ouvintes.delete(ouvinte)
    if (ouvintes.size === 0) {
      window.clearTimeout(religar)
      conexao?.abort()
      conexao = null
    }
  }
}

/**
 * Chama `aoMudar` quando algo muda no servidor. Vários avisos seguidos viram uma chamada só
 * (espera `esperaMs` sem aviso novo), para uma ação grande não disparar várias buscas.
 */
export function useMudancas(aoMudar: () => void, esperaMs = 300) {
  const atual = useRef(aoMudar)
  atual.current = aoMudar
  useEffect(() => {
    let espera: number | undefined
    const parar = ouvir(() => {
      window.clearTimeout(espera)
      espera = window.setTimeout(() => atual.current(), esperaMs)
    })
    return () => {
      window.clearTimeout(espera)
      parar()
    }
  }, [esperaMs])
}
