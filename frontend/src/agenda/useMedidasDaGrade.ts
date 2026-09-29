import { useEffect, useLayoutEffect, useState } from 'react'
import type { RefObject } from 'react'

/**
 * Topo de cada linha da grade, semana por semana, medido na tela.
 *
 * As linhas têm altura fixa (os cards ficam por cima das células e não as esticam), mas
 * a borda grossa do fim do dia soma pixels: a divisa entre um dia e outro, desenhada por
 * dentro de um card que atravessa a virada, continua medida e não calculada.
 */
export function useToposDasLinhas(quadroRef: RefObject<HTMLDivElement | null>) {
  const [topos, setTopos] = useState<Record<string, number[]>>({})

  useLayoutEffect(() => {
    function medir() {
      const medidas: Record<string, number[]> = {}
      quadroRef.current?.querySelectorAll<HTMLTableSectionElement>('tbody[data-semana]').forEach((corpo) => {
        const base = corpo.getBoundingClientRect().top
        medidas[corpo.dataset.semana!] = [...corpo.rows].map((tr) => tr.getBoundingClientRect().top - base)
      })
      setTopos((atuais) => {
        const igual =
          Object.keys(medidas).length === Object.keys(atuais).length &&
          Object.entries(medidas).every(([k, v]) =>
            atuais[k]?.length === v.length && v.every((y, i) => Math.abs(atuais[k][i] - y) < 0.5),
          )
        return igual ? atuais : medidas
      })
    }
    medir()
    window.addEventListener('resize', medir)
    return () => window.removeEventListener('resize', medir)
  })

  return topos
}

/**
 * O nome do carro encolhe até caber na coluna, em vez de ser cortado: parte do tamanho
 * normal e desce até 9 px. Só um nome que nem assim cabe perde o fim (reticências).
 *
 * Mede todos de uma vez e depois escreve todos de uma vez: com o mês inteiro na tela são
 * centenas de cards, e medir um por um travaria a página. Refaz quando `conteudo` muda e
 * quando a janela muda de tamanho — inclusive com o zoom do navegador.
 */
export function useNomesQueCabem(quadroRef: RefObject<HTMLDivElement | null>, ...conteudo: unknown[]) {
  const [larguraDaJanela, setLarguraDaJanela] = useState(() => window.innerWidth)
  useEffect(() => {
    const aoRedimensionar = () => setLarguraDaJanela(window.innerWidth)
    window.addEventListener('resize', aoRedimensionar)
    return () => window.removeEventListener('resize', aoRedimensionar)
  }, [])

  useLayoutEffect(() => {
    const quadro = quadroRef.current
    if (!quadro) return
    const nomes = [...quadro.querySelectorAll<HTMLElement>('.card-simples .nome')]
    nomes.forEach((nome) => (nome.style.fontSize = ''))
    // A linha tem altura fixa: a letra também não pode passar da altura do card.
    const grade = quadro.querySelector('.grade-simples')
    const alturaDaLinha = grade ? parseFloat(getComputedStyle(grade).getPropertyValue('--altura-linha')) || 24 : 24
    const medidas = nomes.map((nome) => {
      const card = nome.closest<HTMLElement>('.card-simples')
      return {
        nome,
        normal: parseFloat(getComputedStyle(nome).fontSize),
        cabe: nome.clientWidth,
        precisa: nome.scrollWidth,
        altura: Math.min(card?.clientHeight ?? alturaDaLinha, alturaDaLinha - 2),
      }
    })
    medidas.forEach(({ nome, normal, cabe, precisa, altura }) => {
      // A largura do texto acompanha o tamanho da letra: a conta sai numa passada só.
      const pelaLargura = cabe > 0 && precisa > cabe ? normal * (cabe / precisa) : normal
      const pelaAltura = altura > 0 ? altura / 1.15 : normal
      const tamanho = Math.floor(Math.min(normal, pelaLargura, pelaAltura) * 2) / 2
      if (tamanho < normal) nome.style.fontSize = `${Math.max(8, tamanho)}px`
    })
  }, [...conteudo, larguraDaJanela])
}

/**
 * Pedido de descer até o dia de hoje (o botão Hoje). Fica guardado até o mês de hoje
 * estar desenhado: trocando de mês, a grade só aparece depois que a semana chega.
 * `desenhado` muda a cada vez que a grade é redesenhada.
 */
export function useIrParaHoje(quadroRef: RefObject<HTMLDivElement | null>, desenhado: unknown) {
  const [irParaHoje, setIrParaHoje] = useState(0)
  useEffect(() => {
    if (!irParaHoje) return
    const dia = quadroRef.current?.querySelector<HTMLElement>('[data-hoje]')
    if (!dia) return // o mês de hoje ainda está carregando: tenta de novo quando chegar
    // O dia fica logo abaixo da barra do topo, que acompanha a rolagem.
    const barra = document.querySelector('.topo')?.getBoundingClientRect().height ?? 0
    // Salto direto: a rolagem suave nem sempre anda (aba em segundo plano, economia de energia).
    window.scrollTo({ top: dia.getBoundingClientRect().top + window.scrollY - barra - 8 })
    setIrParaHoje(0)
  }, [irParaHoje, desenhado, quadroRef])

  return () => setIrParaHoje((n) => n + 1)
}
